"""Export upstream Pocket TTS PyTorch graphs to ExecuTorch `.pte` files.

This is the production export pipeline for marmalade-tts-android's
ExecuTorch migration (see ../../docs/REVIEW-2026-05.md for rationale).

Targets: english_2026-04 to start; the language switcher reads
DEFAULT_LANGUAGE below. Outputs all `.pte` files into
`out/pocket-tts-en-v2026_04/`.

Run:
    uv run python export_pocket.py
    uv run python export_pocket.py --graphs text_conditioner    # one graph
    uv run python export_pocket.py --output-dir /tmp/pocket-export
    uv run python export_pocket.py --precision fp32 fp16 int8    # quant variants

Each graph is wrapped in a thin nn.Module that exposes a clean
forward signature (no NamedTuples, no dicts) so torch.export's
pytree handling stays simple.

Status (as of 2026-06-04):
  All 5 graphs export + verify bit-exact (max abs diff vs eager PyTorch):
    - text_conditioner   16.4 MB  diff 0
    - mimi_encoder       19.3 MB  diff 0
    - flow_lm_flow       39.1 MB  diff 5e-7
    - mimi_decoder       41.3 MB  diff 3e-7   (stateless single-shot rewrite)
    - flow_lm_main      302.4 MB  diff 9e-6   (export-friendly KV-cache rewrite)
  The two stateful graphs needed rewrites to dodge a data-dependent
  `int(offset.item())` slice in the upstream KV cache; see their wrapper
  docstrings and `patch_kv_cache_for_export`.

Quantized variants (--precision fp16/int8, 2026-06-05): see README
"Quantized variants". TL;DR — int8 PT2E (XNNPACKQuantizer) on flow_lm_main
is the usable win: 76 MB (4x smaller), rel-err 0.17, no NaN, the int8 target
for KleidiAI. fp16 EAGER is accurate but the ExecuTorch 1.2.0 fp16 .pte
LOWERING is broken for the transformer/conv graphs (rel-err 3+), so fp16 .pte
are not usable as-is. mimi_decoder int8 fails to load (XNNPACK squeeze node).
"""

from __future__ import annotations

import argparse
import logging
import os
import shutil
import sys
from pathlib import Path

import torch
from torch import nn

# ExecuTorch's flatbuffer serializer shells out to a `flatc` binary. It first
# tries a copy packaged inside its own `_serialize` package (not present in
# this wheel layout), then falls back to $FLATC_EXECUTABLE / bare `flatc` on
# PATH. The binary ships in the venv at .venv/bin/flatc but that dir isn't
# always on PATH when the interpreter is invoked directly, so point
# FLATC_EXECUTABLE at it here if the caller hasn't already.
if not os.environ.get("FLATC_EXECUTABLE") and shutil.which("flatc") is None:
    _venv_flatc = Path(sys.executable).parent / "flatc"
    if _venv_flatc.exists():
        os.environ["FLATC_EXECUTABLE"] = str(_venv_flatc)

# Neuter beartype BEFORE importing pocket_tts. Upstream's `pocket_tts/__init__.py`
# calls `beartype_this_package()` which installs an import hook that wraps EVERY
# function in the package. That hook's runtime int-check fails when torch.export
# substitutes SymInt for dynamic shapes (e.g. in conv padding helpers).
# Replacing `beartype_this_package` with a no-op disables the hook before it
# can install itself. Production inference runs the exported `.pte` and never
# touches beartype.
import beartype.claw as _beartype_claw  # noqa: E402
_beartype_claw.beartype_this_package = lambda *args, **kwargs: None

from pocket_tts.models.tts_model import TTSModel  # noqa: E402

from executorch.exir import (
    EdgeCompileConfig,
    to_edge_transform_and_lower,
)
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner


logger = logging.getLogger(__name__)

DEFAULT_LANGUAGE = "english_2026-04"

# Precision variants the export pipeline can emit. fp32 is the bit-exact
# baseline; fp16 / int8 are the perf variants this script grew to support
# (see README "Quantized variants"). Output filenames get a `_fp16` / `_int8`
# suffix; fp32 keeps the bare `<graph>.pte` name for backward compat.
PRECISIONS = ("fp32", "fp16", "int8")

# Map from language string → engineName the Android app uses for the
# bundle directory. Keep aligned with PocketEngine.ENGINE_NAME values.
LANGUAGE_TO_BUNDLE_NAME = {
    "english_2026-04": "pocket-tts-en-v2026_04",
}


# ---------------------------------------------------------------------------
# Graph wrappers
# ---------------------------------------------------------------------------
# Each wrapper takes a piece of the upstream TTSModel and exposes a
# torch.export-friendly forward(): plain tensors in, plain tensors out,
# no NamedTuples or dicts on the boundary. Internal nn.Module state
# (weights) is left intact — only the call surface is reshaped.


class TextConditionerWrapper(nn.Module):
    """Wraps `flow_lm.conditioner` (a `LUTConditioner`).

    Upstream: `forward(inputs: TokenizedText) -> Tensor` where
    `TokenizedText` is a NamedTuple holding the tokens. We strip the
    NamedTuple and accept the long tensor directly.

    Forward:
        tokens: int64[1, T]  — SentencePiece IDs from PocketTokenizer
        returns: float32[1, T, conditioning_dim]
    """

    def __init__(self, lut_conditioner: nn.Module):
        super().__init__()
        # Just need the embedding layer; the upstream module's tokenizer
        # is a Python object (sentencepiece) which can't be exported and
        # which we already replicate in Kotlin (PocketTokenizer.kt).
        self.embed = lut_conditioner.embed

    def forward(self, tokens: torch.Tensor) -> torch.Tensor:
        return self.embed(tokens)


class MimiEncoderWrapper(nn.Module):
    """Wraps `mimi.encode_to_latent` + the `flow_lm.speaker_proj_weight`
    linear projection, matching the composition the current ORT bundle
    bakes into a single `mimi_encoder` ONNX graph.

    Upstream `_encode_audio` (tts_model.py):
        encoded = self.mimi.encode_to_latent(audio)     # [B, latent_dim, T_emb]
        latents = encoded.transpose(-1, -2).float()      # [B, T_emb, latent_dim]
        conditioning = F.linear(latents, speaker_proj_weight)  # [B, T_emb, 1024]

    Forward:
        audio: float32[1, 1, T_audio]  — mono PCM at model.sample_rate
        returns: float32[1, T_emb, 1024]  — voice conditioning embeddings
    """

    def __init__(self, model: TTSModel):
        super().__init__()
        self.mimi = model.mimi
        # Register speaker_proj_weight as a Parameter on this wrapper so
        # state_dict includes it. Could equivalently leave it on flow_lm
        # and reference via attribute; we register here for export clarity.
        self.speaker_proj_weight = model.flow_lm.speaker_proj_weight

    def forward(self, audio: torch.Tensor) -> torch.Tensor:
        encoded = self.mimi.encode_to_latent(audio)
        latents = encoded.transpose(-1, -2).to(torch.float32)
        return torch.nn.functional.linear(latents, self.speaker_proj_weight)


class FlowNetWrapper(nn.Module):
    """Wraps `flow_lm.flow_net` (a `SimpleMLPAdaLN`) — the per-step Euler
    flow direction predictor. Stateless. The autoregressive Euler integration
    loop lives in Kotlin (PocketEngine.runFlowEuler), this graph just
    computes one direction.

    Upstream signature:
        flow_net(c, s, t, x) -> flow_dir

    Forward:
        c: float32[1, 1024]  — conditioning from flow_lm_main
        s: float32[1, 1]     — current Euler time
        t: float32[1, 1]     — target Euler time
        x: float32[1, 32]    — current latent
        returns: float32[1, 32]  — flow direction
    """

    def __init__(self, flow_net: nn.Module):
        super().__init__()
        self.flow_net = flow_net

    def forward(
        self,
        c: torch.Tensor,
        s: torch.Tensor,
        t: torch.Tensor,
        x: torch.Tensor,
    ) -> torch.Tensor:
        return self.flow_net(c, s, t, x)


# ---------------------------------------------------------------------------
# STATEFUL graph wrappers (mimi_decoder, flow_lm_main)
# ---------------------------------------------------------------------------
# These were the hard cases. Upstream threads streaming state as a dict-of-dict
# (`{module_path: {state_key: tensor}}`); the original drafts tried to pass that
# pytree through the graph verbatim. That does NOT export: the KV-cache backend
# reads its write offset via `int(offset.item())` (transformer.complete_kv),
# which torch.export rejects as a data-dependent symbolic int (even with fully
# static shapes — the value comes from a state tensor the tracer treats as
# opaque). Both graphs are now solved with targeted rewrites:
#
#   * mimi_decoder — exported STATELESS / single-shot: the decoder transformer
#     runs with model_state=None (non-streaming, no KV cache, no .item()), and
#     the SEANet conv state is built fresh inside the graph. One COMPLETE chunk
#     decoded per call. This also makes the decoder window-size-invariant (see
#     MimiDecoderWrapper docstring), fixing the chunk-start artifact class that
#     the window-DEPENDENT ONNX decoder forced a Kotlin workaround for.
#
#   * flow_lm_main — the AR KV cache is essential, so it can't go stateless.
#     `patch_kv_cache_for_export` swaps in an `.item()`-free cache backend that
#     writes via index_copy and threads the caches as explicit graph I/O.


import contextlib  # noqa: E402

from pocket_tts.modules.stateful_module import init_states  # noqa: E402
from pocket_tts.modules.transformer import (  # noqa: E402
    StreamingMultiheadAttention,
    _LinearKVCacheBackend,
)

# PT2E int8 quantization (XNNPACK quantizer + torchao's prepare/convert flow).
# This is the official ExecuTorch recipe — see
# .venv/.../executorch/examples/xnnpack/quantization/utils.py, which we mirror.
# In torch 2.12 the pt2e helpers live in torchao, not torch.ao.quantization.
from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (  # noqa: E402
    XNNPACKQuantizer,
    get_symmetric_quantization_config,
)
from torchao.quantization.pt2e.quantize_pt2e import (  # noqa: E402
    convert_pt2e,
    prepare_pt2e,
)


@contextlib.contextmanager
def patch_kv_cache_for_export():
    """Temporarily replace the KV-cache backend's `append_and_get` with an
    export-friendly variant that avoids the `int(offset.item())` data-dependent
    slice in `transformer.complete_kv`.

    Differences from upstream (semantically equivalent — verified host-side):
      * Writes K/V at `offset + arange(T)` via `index_copy` (index-tensor
        scatter) instead of `cache[:, off:off+T] = k` (Python-int slice).
      * Returns the FULL fixed-capacity cache for attention (length TC) rather
        than the `:off+T` prefix; unwritten slots are flagged `pos_k = -1`,
        which the existing `_build_attention_mask` (`pos_k >= 0`) masks out.
      * Rebinds `state["cache"]` to the new (immutable) cache tensor instead of
        in-place slice assignment, so torch.export can thread it as graph I/O
        without a constant-mutation error.

    Installed around the whole flow_lm_main export+verify (the `_verify_pte`
    call runs inside this `with` block), so the eager reference compared against
    the .pte uses the SAME patched path — otherwise the full-cache vs `:off+T`
    prefix shapes wouldn't line up for the diff. Restored on exit so nothing
    else in the process sees the monkeypatch.
    """
    original = _LinearKVCacheBackend.append_and_get

    def export_append_and_get(self, k, v, state):
        if state is None:
            # Non-streaming path is already export-safe; leave it alone.
            return original(self, k, v, state)
        cache = state["cache"]                       # [2, B, TC, H, D]
        offset = state["offset"].view(-1)[0]         # 0-d long tensor
        tc = cache.shape[2]
        t = k.shape[1]
        idx = offset + torch.arange(t, device=k.device)
        new_k = cache[0].index_copy(1, idx, k)       # [B, TC, H, D]
        new_v = cache[1].index_copy(1, idx, v)
        state["cache"] = torch.stack([new_k, new_v], dim=0)

        k_attn = new_k.permute(0, 2, 1, 3)           # [B, H, TC, D]
        v_attn = new_v.permute(0, 2, 1, 3)
        slots = torch.arange(tc, device=k.device, dtype=torch.long)
        written = slots < (offset + t)
        pos_k = torch.where(written, slots, torch.full_like(slots, -1))
        pos_k = pos_k.view(1, -1).expand(k_attn.shape[0], -1)
        return k_attn, v_attn, pos_k, state["offset"]

    _LinearKVCacheBackend.append_and_get = export_append_and_get
    try:
        yield
    finally:
        _LinearKVCacheBackend.append_and_get = original


class MimiDecoderWrapper(nn.Module):
    """STATELESS single-shot decode of a complete latent chunk to audio.

    The original draft tried to thread the upstream streaming `mimi_state`
    (dict-of-dict KV-cache + conv overlap) through the graph as pytree I/O.
    That is NOT exportable: the KV-cache backend reads its write offset via
    `int(offset.item())` (transformer.py `complete_kv`), which torch.export
    rejects as a data-dependent symbolic int (`GuardOnDataDependentSymNode`).
    See README "Known gotchas".

    Instead we export the decode the way ExecuTorch's own Mimi reference does
    (examples/models/moshi/mimi/test_mimi.py::test_exported_decoder_xnnpack):
    run the decoder transformer in NON-streaming mode (`model_state=None`,
    which takes the `torch.arange` path in the KV backend — no `.item()`),
    and decode the whole chunk in a single shot. The SEANet conv / transposed-
    conv layers DO need a state object (the transposed-conv reads it
    unconditionally), so we build a FRESH zeroed state inside the graph each
    call. Fresh state means the cross-call `previous`/`partial` overlap is all
    zeros, i.e. each chunk is decoded from a clean start.

    Equivalences verified host-side (scratch probes, fp32):
      - stateless single-shot  ==  upstream stateful single-call      (diff 0.0)
      - decode(first 6 frames)  ==  decode(12 frames)[:, :, :len6]     (diff 5e-7)
        => the leading edge is length-independent, so the chunk-start
           "bitcrush" artifact (a window-size-DEPENDENT ONNX decoder) cannot
           recur: every chunk is decoded fresh and the leading edge is stable.

    Caller contract (Kotlin): decode one COMPLETE mini-chunk per call. Do NOT
    try to thread state across calls or decode frame-by-frame — single frames
    lack the transposed-conv receptive field (per-frame vs whole-chunk differ
    by ~0.4). One call == one full chunk.

    Forward:
        latent: float32[1, T_latent, 32]
        returns: audio float32[1, 1, T_audio]   (T_audio = T_latent * frame_size)
    """

    def __init__(self, model: TTSModel):
        super().__init__()
        self.mimi = model.mimi
        self.emb_std = model.flow_lm.emb_std
        self.emb_mean = model.flow_lm.emb_mean

    def forward(self, latent: torch.Tensor) -> torch.Tensor:
        # Build the conv/upsample state FRESH inside forward (all zeros). It
        # must be created here, not stored as an attribute: the transposed-
        # conv mutates `partial` in place, and torch.export rejects in-place
        # mutation of a captured-constant attribute ("Pls register it as
        # buffer"). A locally-allocated state is a true graph intermediate, so
        # the mutation is functionalized away and nothing escapes the call.
        fresh_state = init_states(self.mimi, batch_size=1, sequence_length=1)
        # Dtype the float intermediates must match the conv WEIGHTS (which .half()
        # casts under the fp16 export). emb_std/emb_mean are plain Python attrs,
        # NOT buffers, so .half() leaves them fp32 — derive the target dtype from
        # an actual parameter instead. init_states also allocates fp32 state
        # regardless of module dtype, so cast its float slots too (no-op for
        # fp32). Without this the SEANet conv hits "expected Float but found Half".
        param_dtype = self.mimi.decoder.model[0].conv.weight.dtype
        for module_state in fresh_state.values():
            for key, tensor in module_state.items():
                if tensor.is_floating_point():
                    module_state[key] = tensor.to(param_dtype)
        denorm = (latent * self.emb_std + self.emb_mean).to(param_dtype)
        quantized = self.mimi.quantizer(denorm.transpose(-1, -2))
        # Replicate mimi.decode_from_latent, but route the transformer through
        # the stateless (non-streaming) path and the convs through fresh state.
        emb = self.mimi._to_encoder_framerate(quantized, fresh_state)
        (emb,) = self.mimi.decoder_transformer(emb, None)
        # The decoder transformer (LayerNorm/attention) may upcast to fp32
        # internally; the SEANet decoder convs are param_dtype (fp16 under the
        # fp16 export), so re-cast to avoid "Input type (float) and bias type
        # (Half) should be the same". No-op for fp32.
        emb = emb.to(param_dtype)
        return self.mimi.decoder(emb, fresh_state)


class FlowLmMainWrapper(nn.Module):
    """Wraps the `flow_lm_main` composition used by PocketEngine.

    PocketEngine calls flow_lm_main in three modes, but they differ ONLY in
    the time-axis lengths of `sequence` / `text_embeddings`:

      Phase 1 (voice cond):   sequence=[1,0,32]  text=BOS+voice [1, V+1, 1024]
      Phase 2 (text cond):    sequence=[1,0,32]  text=text_embs [1, T,   1024]
      Phase 3 (AR step):      sequence=[1,1,32]  text=[1,0,1024]

    The autoregressive KV-cache is the hard part. Upstream's cache backend
    (`transformer.complete_kv`) reads its write offset via `int(offset.item())`
    and does a Python-int dynamic slice `cache[:, off:off+T] = k`. torch.export
    rejects the `.item()` as a data-dependent symbolic int — fully static
    export fails too, because the value comes from a *state* tensor the tracer
    treats as opaque (`GuardOnDataDependentSymNode`). See README gotchas.

    Fix (applied via `patch_kv_cache_for_export`, a context manager installed
    around the export of THIS graph only): swap the cache backend for an
    `.item()`-free static-cache variant. It writes K/V into the fixed-capacity
    cache at `offset + arange(T)` using `index_copy` (index-tensor scatter, no
    Python int), reads the FULL fixed cache, and marks unwritten slots with
    `pos_k = -1` so the existing attention mask (`pos_k >= 0`) ignores them.
    Numerically identical to upstream (verified host-side, diff ~1e-6).

    `offset` is supplied by the caller (Kotlin already tracks the AR step via
    `increment_steps`) as a 0-d int input shared across layers. The updated
    caches are returned so the host can thread them into the next call.

    Forward:
        sequence:        float32[1, T_seq,  32]
        text_embeddings: float32[1, T_text, 1024]
        offset:          int64[]  — absolute AR position (0 for the first call)
        caches:          tuple[Tensor, ...] — one [2,1,TC,H,D] KV cache per layer
        returns: (conditioning [1, T_seq, 1024],
                  eos_logit    [1, T_seq, 1],
                  caches_after: tuple[Tensor, ...])

    NOTE: this is the phase-specialized STATIC fallback the README anticipates.
    Time axes are dynamic dims so all three phases share one graph; the cache
    capacity TC is fixed at export time (default 1024).
    """

    def __init__(self, model: TTSModel):
        super().__init__()
        self.flow_lm = model.flow_lm
        # Ordered list of self-attn modules whose caches we thread as I/O.
        # Order MUST match the cache tuple the caller passes / receives.
        self._attn_modules = [
            m for m in self.flow_lm.transformer.modules()
            if isinstance(m, StreamingMultiheadAttention)
        ]

    def forward(
        self,
        sequence: torch.Tensor,
        text_embeddings: torch.Tensor,
        offset: torch.Tensor,
        caches: tuple,
    ) -> tuple[torch.Tensor, torch.Tensor, tuple]:
        # Rebuild the dict-of-dict model_state the upstream backbone expects,
        # from the flat (offset, caches) graph inputs. The patched cache
        # backend (see patch_kv_cache_for_export) reads `offset`/`cache` and
        # rebinds `cache` to the updated tensor in place of in-place mutation.
        model_state = {}
        for mod, cache in zip(self._attn_modules, caches):
            model_state[mod._module_absolute_name] = {"offset": offset, "cache": cache}

        sequence = torch.where(torch.isnan(sequence), self.flow_lm.bos_emb, sequence)
        input_ = self.flow_lm.input_linear(sequence)
        transformer_out = self.flow_lm.backbone(
            input_, text_embeddings, sequence, model_state=model_state
        )
        # Coerce to the OUTPUT-HEAD weight dtype (fp32 normally; fp16 under the
        # fp16 export) so the following out_eos linear's addmm doesn't hit a
        # mixed {Half, Float} dtype mismatch. Was hardcoded float32, which broke
        # the fp16 variant. The backbone may compute in fp16 internally; this
        # single cast re-aligns the output head.
        out_dtype = self.flow_lm.out_eos.weight.dtype
        transformer_out = transformer_out.to(out_dtype)

        # Raw EOS logit; Kotlin applies EOS_THRESHOLD. Conditioning is the same
        # transformer output (flow_net's `c` input). Both keep the time axis so
        # there are no data-dependent `[:, -1]` branches across phases.
        eos_logit = self.flow_lm.out_eos(transformer_out)
        conditioning = transformer_out

        caches_after = tuple(
            model_state[mod._module_absolute_name]["cache"] for mod in self._attn_modules
        )
        return conditioning, eos_logit, caches_after


# ---------------------------------------------------------------------------
# Export pipeline
# ---------------------------------------------------------------------------


def _to_half_for_export(wrapper: nn.Module) -> nn.Module:
    """Return an fp16 DEEP COPY of `wrapper` for export.

    Plain `wrapper.half()` casts every float parameter/buffer to fp16, which is
    what we want for the perf-relevant linear/conv/attention weights. BUT the
    wrappers hold direct references to the shared `model`'s submodules (e.g.
    `self.flow_net = model.flow_lm.flow_net`), so an in-place `.half()` would
    corrupt the model for any subsequent fp32/int8 export in the same process
    (the driver can emit several precisions in one run). Deep-copy first so the
    cast is isolated to this export.
    """
    import copy

    return copy.deepcopy(wrapper).half().eval()


def _restore_float_inputs(
    half_inputs: tuple[torch.Tensor, ...],
) -> tuple[torch.Tensor, ...]:
    """Inverse of `_cast_inputs_to_half`: float16 → float32, ints untouched.
    Used to feed the fp32 eager reference the same logical inputs the fp16
    .pte received, so the verify delta is purely the fp16 numerical error.
    """
    from torch.utils._pytree import tree_map

    def _cast(x):
        if isinstance(x, torch.Tensor) and x.dtype == torch.float16:
            return x.to(torch.float32)
        return x

    return tree_map(_cast, half_inputs)


def _cast_inputs_to_half(
    example_inputs: tuple[torch.Tensor, ...],
) -> tuple[torch.Tensor, ...]:
    """Cast every floating-point tensor in a (possibly nested) input pytree to
    float16, leaving integer tensors (token ids, the AR `offset`) untouched.
    Used by the fp16 export path so the call boundary matches the halved model.
    """
    from torch.utils._pytree import tree_map

    def _cast(x):
        if isinstance(x, torch.Tensor) and x.is_floating_point():
            return x.to(torch.float16)
        return x

    return tree_map(_cast, example_inputs)


def _quantize_int8(
    wrapper: nn.Module,
    example_inputs: tuple[torch.Tensor, ...],
    *,
    dynamic_shapes: dict | None,
    graph_name: str,
    quant_filter=None,
    calibration_inputs=None,
):
    """Run the official PT2E + XNNPACKQuantizer int8 flow and return the
    re-exported (quantized) ExportedProgram ready for lowering.

    Mirrors executorch/examples/xnnpack/quantization/utils.py: capture a
    pre-autograd graph, annotate with the XNNPACK quantizer (per-channel
    symmetric weights, DYNAMIC per-token activations — the KleidiAI int8
    target), calibrate, then `convert_pt2e`.

    `is_dynamic=True` is load-bearing for speed: it maps the matmuls to
    XNNPACK's `qd8-f32-qc8w` GEMM, the path KleidiAI's i8mm microkernels
    accelerate on ARM (Tensor G3). Static quant (`is_dynamic=False`) instead
    hits the `qs8` kernels — NOT i8mm — and a KV-cache transformer with fp32
    cache I/O then dequant→fp32→requants at every seam each step, so it
    benched ~88 ms/step, SLOWER than the fp32 78 ms. Dynamic is the recipe
    ExecuTorch's own llama examples use for KV-cache transformers.

    `quant_filter`, if given, is `set_filter_function`'d onto the quantizer to
    EXCLUDE sensitive submodules (e.g. the Mimi decoder transformer + output
    projections, per the T-Mimi mixed-precision finding) — those nodes stay
    fp32. `calibration_inputs` is an iterable of input tuples; if None we
    calibrate on `example_inputs` alone (enough to fix activation ranges for
    a smoke-quality export, though more diverse data would tighten them).
    """
    logger.info(f"Quantizing {graph_name} (PT2E int8, XNNPACK quantizer)…")
    # Pre-autograd capture. torch.export(...).module() is the pt2e entry point
    # in this torch version (export_for_training isn't exposed on torch.export).
    pre = torch.export.export(
        wrapper, example_inputs, dynamic_shapes=dynamic_shapes, strict=False
    ).module()

    quantizer = XNNPACKQuantizer()
    quantizer.set_global(
        get_symmetric_quantization_config(is_per_channel=True, is_dynamic=True)
    )
    if quant_filter is not None:
        quantizer.set_filter_function(quant_filter)

    prepared = prepare_pt2e(pre, quantizer)
    # Calibration: run representative inputs so observers record activation
    # ranges. example_inputs are zeros for several graphs, which gives
    # degenerate ranges — callers that care pass richer calibration_inputs.
    for sample in (calibration_inputs or [example_inputs]):
        prepared(*sample)
    converted = convert_pt2e(prepared)

    # Re-export the converted graph for lowering (the q/dq ops become real
    # nodes the XNNPACK partitioner folds into int8 kernels).
    return torch.export.export(
        converted, example_inputs, dynamic_shapes=dynamic_shapes, strict=False
    )


def _export_and_lower(
    wrapper: nn.Module,
    example_inputs: tuple[torch.Tensor, ...],
    dynamic_shapes: dict | None,
    output_path: Path,
    *,
    graph_name: str,
    verify_atol: float = 1e-4,
    precision: str = "fp32",
    quant_filter=None,
    calibration_inputs=None,
    verify_inputs: tuple[torch.Tensor, ...] | None = None,
) -> None:
    """Common path: torch.export → ExecuTorch lower → write .pte → verify.
    Most graphs share this exact wrapping; only inputs/shapes differ.

    `precision` selects the variant:
      * "fp32" — bit-exact baseline (unchanged behaviour).
      * "fp16" — model + float inputs cast to half; XNNPACK uses ARMv8.2 FP16.
      * "int8" — PT2E XNNPACK quantization (per-channel weights). Pass
        `quant_filter` / `calibration_inputs` to steer selective quant.

    Verification always compares the .pte against the FP32 eager reference, so
    the reported "max abs diff" is the true quant error vs full precision (NOT
    a within-precision round-trip), confirming the variant isn't garbage.

    `verify_inputs` (fp32) overrides the inputs used for the verify diff. Most
    `export_*` fns use zero example_inputs, which is a DEGENERATE operating
    point for quantized graphs (AdaLN modulation collapses, fp16/int8 round-off
    gets amplified) and gives a pessimistic, unrepresentative delta. Pass a
    realistic draw here so the reported number reflects real inference error.
    """
    # FP32 eager reference, captured before any cast/quant, for the verify delta.
    fp32_reference = wrapper

    if precision == "fp16":
        wrapper = _to_half_for_export(wrapper)
        example_inputs = _cast_inputs_to_half(example_inputs)

    logger.info(f"Exporting {graph_name} ({precision}) via torch.export…")
    if precision == "int8":
        exported = _quantize_int8(
            wrapper,
            example_inputs,
            dynamic_shapes=dynamic_shapes,
            graph_name=graph_name,
            quant_filter=quant_filter,
            calibration_inputs=calibration_inputs,
        )
    else:
        exported = torch.export.export(
            wrapper,
            example_inputs,
            dynamic_shapes=dynamic_shapes,
        )

    logger.info(f"Lowering {graph_name} ({precision}) to ExecuTorch (XNNPACK)…")
    # fp16 and int8 graphs trip the edge-dialect IR validity check (half dtypes /
    # q-dq ops), so relax it for the non-fp32 variants — matching the official
    # xnnpack aot_compiler recipe. _skip_dim_order keeps XNNPACK happy.
    compile_config = (
        EdgeCompileConfig(_check_ir_validity=False, _skip_dim_order=True)
        if precision != "fp32"
        else None
    )
    edge = to_edge_transform_and_lower(
        exported,
        partitioner=[XnnpackPartitioner()],
        compile_config=compile_config,
    )
    program = edge.to_executorch()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(program.buffer)
    size_mb = output_path.stat().st_size / 1e6
    logger.info(f"Wrote {output_path} ({size_mb:.1f} MB)")

    # Verify against the fp32 reference. The reference always sees fp32 inputs;
    # the .pte sees fp16 inputs for the fp16 variant. Prefer realistic
    # verify_inputs (fp32) over the often-zero example_inputs — see docstring.
    ref_inputs_fp32 = verify_inputs if verify_inputs is not None else (
        _restore_float_inputs(example_inputs)
        if precision == "fp16"
        else example_inputs
    )
    pte_inputs = (
        _cast_inputs_to_half(ref_inputs_fp32) if precision == "fp16" else ref_inputs_fp32
    )
    _verify_pte(
        fp32_reference,
        output_path,
        pte_inputs,
        atol=verify_atol,
        reference_inputs=ref_inputs_fp32,
        precision=precision,
    )


def export_text_conditioner(
    model: TTSModel, output_path: Path, precision: str = "fp32"
) -> None:
    wrapper = TextConditionerWrapper(model.flow_lm.conditioner)
    wrapper.eval()

    example_tokens = torch.zeros((1, 32), dtype=torch.int64)
    seq_dim = torch.export.Dim("T", min=1, max=4096)
    _export_and_lower(
        wrapper,
        (example_tokens,),
        {"tokens": {1: seq_dim}},
        output_path,
        graph_name="text_conditioner",
        precision=precision,
    )


def export_mimi_encoder(
    model: TTSModel, output_path: Path, precision: str = "fp32"
) -> None:
    wrapper = MimiEncoderWrapper(model)
    wrapper.eval()

    # Static T_audio = 30 s of input. Voice encoding happens once per voice
    # at engine load (cached to disk thereafter), so the encoder graph
    # always processes a fixed-size buffer. The Kotlin side pads input
    # PCM to 30 s with zeros (matching the existing 30 s truncation cap
    # in PocketEngine.encodePcm). Dynamic T_audio dim is rejected by
    # torch.export because pad_for_conv1d's symbolic math creates a
    # constraint the dynamic analysis can't satisfy.
    sample_rate = model.config.mimi.sample_rate
    static_audio_len = 30 * sample_rate
    example_audio = torch.zeros((1, 1, static_audio_len), dtype=torch.float32)
    _export_and_lower(
        wrapper,
        (example_audio,),
        dynamic_shapes=None,
        output_path=output_path,
        graph_name="mimi_encoder",
        precision=precision,
    )


def export_flow_lm_flow(
    model: TTSModel, output_path: Path, precision: str = "fp32"
) -> None:
    wrapper = FlowNetWrapper(model.flow_lm.flow_net)
    wrapper.eval()

    cond_dim = model.flow_lm.dim
    latent_dim = model.flow_lm.ldim
    example_c = torch.zeros((1, cond_dim), dtype=torch.float32)
    example_s = torch.zeros((1, 1), dtype=torch.float32)
    example_t = torch.zeros((1, 1), dtype=torch.float32)
    example_x = torch.zeros((1, latent_dim), dtype=torch.float32)

    # int8 calibration: zeros give degenerate activation ranges, so feed a
    # handful of randn draws (conditioning `c` and latent `x` are ~unit-normal
    # in practice; s/t are Euler times in [0,1]). Cheap and bounds the ranges.
    torch.manual_seed(0)
    realistic = (
        torch.randn((1, cond_dim)),
        torch.rand((1, 1)),
        torch.rand((1, 1)),
        torch.randn((1, latent_dim)),
    )
    calibration_inputs = None
    if precision == "int8":
        calibration_inputs = [
            (
                torch.randn((1, cond_dim)),
                torch.rand((1, 1)),
                torch.rand((1, 1)),
                torch.randn((1, latent_dim)),
            )
            for _ in range(16)
        ]

    _export_and_lower(
        wrapper,
        (example_c, example_s, example_t, example_x),
        dynamic_shapes=None,  # all shapes static
        output_path=output_path,
        graph_name="flow_lm_flow",
        precision=precision,
        calibration_inputs=calibration_inputs,
        # Realistic verify point — zeros are a degenerate operating point for
        # this AdaLN flow net (see _export_and_lower verify_inputs note).
        verify_inputs=realistic if precision != "fp32" else None,
    )


# ---------------------------------------------------------------------------
# EXPERIMENTAL: stateful graph exports
# ---------------------------------------------------------------------------
# Not wired into GRAPHS by default — opt in with e.g.
# `--graphs mimi_decoder`. These are unverified drafts; expect to iterate
# once they actually run.


# Substrings of an nn_module_stack path that mark a Mimi-decoder submodule as
# precision-SENSITIVE: it must stay high precision under int8 (the T-Mimi
# finding — the decoder transformer's final layers + the output projections
# that feed the SEANet are where int8 audibly degrades). The int8 quant filter
# (see _mimi_decoder_quant_filter) refuses to quantize any node sourced from a
# module whose path contains one of these.
_MIMI_DECODER_INT8_SKIP = ("decoder_transformer",)


def _mimi_decoder_quant_filter(node) -> bool:
    """XNNPACK-quantizer filter for mimi_decoder int8: return True to quantize,
    False to leave a node fp32. We exclude the decoder transformer + its output
    projections (`decoder_transformer.*`) — the documented-sensitive tail — so
    only the heavy SEANet convs get int8'd. Identification is via the node's
    nn_module_stack (the chain of source modules torch.export records)."""
    stack = node.meta.get("nn_module_stack")
    if not stack:
        return True  # not attributable to a module (e.g. plain aten op) → quantize
    paths = " ".join(entry[0] for entry in stack.values())
    return not any(marker in paths for marker in _MIMI_DECODER_INT8_SKIP)


def export_mimi_decoder(
    model: TTSModel, output_path: Path, precision: str = "fp32"
) -> None:
    wrapper = MimiDecoderWrapper(model)
    wrapper.eval()

    # Single-shot stateless decode (see MimiDecoderWrapper docstring). Only
    # input is the latent chunk; the conv state is built fresh inside the
    # graph and the transformer runs non-streaming, so there is no state I/O.

    # Example latent: 1 mini-chunk worth (~15 frames at 12.5 Hz frame rate).
    example_latent = torch.zeros((1, 15, model.flow_lm.ldim), dtype=torch.float32)

    # Dynamic T_latent so any chunk length works at runtime (one COMPLETE
    # chunk per call — see wrapper docstring). min=1 to keep export from
    # 0/1-specializing the time axis.
    t_latent_dim = torch.export.Dim("T_latent", min=1, max=300)

    torch.manual_seed(0)
    # Latents are ~unit-normal (they're the flow_lm output, normalised by
    # emb_mean/std inside the wrapper). Realistic verify point at the example
    # length (15 frames) so the diff reflects real decode error, not zeros.
    realistic = (torch.randn((1, 15, model.flow_lm.ldim)),)
    quant_filter = None
    calibration_inputs = None
    if precision == "int8":
        quant_filter = _mimi_decoder_quant_filter  # selective: skip sensitive tail
        # A few varied-length draws calibrate the conv activation ranges.
        calibration_inputs = [
            (torch.randn((1, n, model.flow_lm.ldim)),) for n in (8, 15, 24)
        ]

    _export_and_lower(
        wrapper,
        (example_latent,),
        dynamic_shapes={"latent": {1: t_latent_dim}},
        output_path=output_path,
        graph_name="mimi_decoder",
        verify_atol=1e-3,  # fp32→fp32 lowering may drift slightly
        precision=precision,
        quant_filter=quant_filter,
        calibration_inputs=calibration_inputs,
        verify_inputs=realistic if precision != "fp32" else None,
    )


# KV cache capacity baked into the flow_lm_main graph (max AR positions per
# session). A 25-token chunk's prompt + ~75 AR frames stays well under this.
FLOW_LM_CACHE_CAP = 1024


def export_flow_lm_main(
    model: TTSModel, output_path: Path, precision: str = "fp32"
) -> None:
    wrapper = FlowLmMainWrapper(model)
    wrapper.eval()

    ldim = model.flow_lm.ldim
    dim = model.flow_lm.dim

    # Phase-3 (AR step) example: sequence=[1,1,32], text=[1,0,1024]. This is
    # the call shape that exercises the incremental cache write. The cache
    # tuple holds one [2,1,TC,H,D] tensor per self-attn layer, zero-init.
    example_sequence = torch.zeros((1, 1, ldim), dtype=torch.float32)
    example_text = torch.zeros((1, 0, dim), dtype=torch.float32)
    example_offset = torch.zeros((), dtype=torch.int64)

    n_layers = len(wrapper._attn_modules)
    attn0 = wrapper._attn_modules[0]
    h = attn0.num_heads
    d = attn0.dim_per_head
    example_caches = tuple(
        torch.zeros((2, 1, FLOW_LM_CACHE_CAP, h, d), dtype=torch.float32)
        for _ in range(n_layers)
    )

    # int8 calibration: a few AR steps with non-zero sequence + advancing
    # offset, so the observers see realistic transformer activations (zeros
    # collapse to NaN→bos_emb via the isnan branch and give useless ranges).
    torch.manual_seed(0)
    realistic = (
        torch.randn((1, 1, ldim)),
        torch.zeros((1, 0, dim)),
        torch.zeros((), dtype=torch.int64),
        example_caches,
    )
    calibration_inputs = None
    if precision == "int8":
        calibration_inputs = [
            (
                torch.randn((1, 1, ldim)),
                torch.zeros((1, 0, dim)),
                torch.tensor(off, dtype=torch.int64),
                example_caches,
            )
            for off in (0, 1, 2)
        ]

    # Phase 3 (AR step) is fully static: seq=1, text=0. Phases 1/2 (seq=0,
    # text=T) have an incompatible shape signature (different which-axis-is-
    # nonzero), so per the README they get their own specialized graphs rather
    # than sharing dynamic dims with phase 3. This export targets the AR step.
    # The patched cache backend must be active for BOTH the export trace and
    # the eager reference inside _verify_pte (so cache shapes line up).
    with patch_kv_cache_for_export():
        _export_and_lower(
            wrapper,
            (example_sequence, example_text, example_offset, example_caches),
            dynamic_shapes=None,
            output_path=output_path,
            graph_name="flow_lm_main",
            verify_atol=1e-3,
            precision=precision,
            calibration_inputs=calibration_inputs,
            verify_inputs=realistic if precision != "fp32" else None,
        )


def export_flow_lm_cond(
    model: TTSModel, output_path: Path, precision: str = "fp32"
) -> None:
    """Export the flow_lm_main CONDITIONING phases (1 & 2) — the priming calls
    flow_lm_main's phase-3 export (`export_flow_lm_main`) deliberately omits.

    Same `FlowLmMainWrapper`, same `patch_kv_cache_for_export`, same
    `_export_and_lower` path as flow_lm_main — only the example inputs +
    dynamic_shapes differ. The wrapper's forward is phase-agnostic; phases 1/2
    just call it with an EMPTY sequence and a length-T text block:

      Phase 1 (voice cond):  sequence=[1,0,32]  text=[1, V+1, 1024]
      Phase 2 (text cond):   sequence=[1,0,32]  text=[1, T,   1024]

    With sequence empty, upstream `backbone` slices `transformer_out[:,
    -sequence.shape[1]:]` → `[:, -0:]` → `[:, 0:]`, i.e. the FULL text-length
    output (Python `-0 == 0`). So conditioning/eos come out at length T, not 1.
    Both phases share this one graph via the dynamic text-time dim.

    The data-dependent `int(offset.item())` guard the README warns about is
    already dodged by `patch_kv_cache_for_export`: it writes K/V at
    `offset + arange(T)` via `index_copy`, where `T = k.shape[1]` is the (now
    DYNAMIC) text length. `torch.arange(T)` + `index_copy` along dim 1 trace
    symbolically — no `.item()`, no Python-int slice — so the variable-length
    cache write exports cleanly. sequence stays empty/static (0); only the text
    time axis is a torch.export.Dim, mirroring `export_text_conditioner`'s `T`.

    Forward (priming):
        sequence:        float32[1, 0, 32]    (EMPTY, static 0)
        text_embeddings: float32[1, T, 1024]  (T DYNAMIC, 1..4096)
        offset:          int64[]              (absolute AR position; 0 on phase 1)
        caches:          6 × float32[2,1,TC,H,D]  (TC = FLOW_LM_CACHE_CAP)
        returns: (conditioning [1, T, 1024],
                  eos_logit    [1, T, 1],
                  caches_after: 6 × [2,1,TC,H,D])
    """
    wrapper = FlowLmMainWrapper(model)
    wrapper.eval()

    ldim = model.flow_lm.ldim
    dim = model.flow_lm.dim

    # Conditioning example: sequence EMPTY [1,0,32], text [1,T,1024] with T=64
    # as the representative trace length. offset=0 (phase 1 primes from the
    # start; phase 2 carries the running offset the host threads in).
    example_sequence = torch.zeros((1, 0, ldim), dtype=torch.float32)
    example_text = torch.zeros((1, 64, dim), dtype=torch.float32)
    example_offset = torch.zeros((), dtype=torch.int64)

    n_layers = len(wrapper._attn_modules)
    attn0 = wrapper._attn_modules[0]
    h = attn0.num_heads
    d = attn0.dim_per_head
    example_caches = tuple(
        torch.zeros((2, 1, FLOW_LM_CACHE_CAP, h, d), dtype=torch.float32)
        for _ in range(n_layers)
    )

    # Only the text time axis is dynamic; sequence stays empty/static, offset is
    # a scalar, and each cache is fixed-capacity (TC static). dynamic_shapes must
    # mirror the input pytree — the `caches` tuple gets a tuple of None (static).
    text_t_dim = torch.export.Dim("T", min=1, max=4096)
    dynamic_shapes = {
        "sequence": None,
        "text_embeddings": {1: text_t_dim},
        "offset": None,
        "caches": tuple(None for _ in range(n_layers)),
    }

    # int8 calibration: vary the text length + offset so observers see realistic
    # priming activations (zeros are a degenerate operating point for AdaLN).
    torch.manual_seed(0)
    realistic = (
        example_sequence,
        torch.randn((1, 64, dim)),
        torch.zeros((), dtype=torch.int64),
        example_caches,
    )
    calibration_inputs = None
    if precision == "int8":
        calibration_inputs = [
            (
                example_sequence,
                torch.randn((1, t, dim)),
                torch.tensor(off, dtype=torch.int64),
                example_caches,
            )
            for t, off in ((16, 0), (64, 0), (96, 16))
        ]

    # Patched cache backend must be active for BOTH the export trace and the
    # eager reference inside _verify_pte (so full-cache shapes line up) — same as
    # export_flow_lm_main.
    with patch_kv_cache_for_export():
        _export_and_lower(
            wrapper,
            (example_sequence, example_text, example_offset, example_caches),
            dynamic_shapes=dynamic_shapes,
            output_path=output_path,
            graph_name="flow_lm_cond",
            verify_atol=1e-3,
            precision=precision,
            calibration_inputs=calibration_inputs,
            verify_inputs=realistic if precision != "fp32" else None,
        )


def _verify_pte(
    reference: nn.Module,
    pte_path: Path,
    example_inputs: tuple[torch.Tensor, ...],
    *,
    atol: float = 1e-4,
    reference_inputs: tuple[torch.Tensor, ...] | None = None,
    precision: str = "fp32",
) -> None:
    """Run both the reference PyTorch module and the .pte on the same
    input; report max abs diff. Catches export bugs immediately.

    `example_inputs` are fed to the .pte (must match its expected dtypes, e.g.
    fp16 for an fp16 graph). `reference_inputs` (default: `example_inputs`) are
    fed to the fp32 eager `reference`, so for quantized variants the reported
    diff is the quant error against full precision rather than a same-precision
    round-trip.

    `precision` only changes the VERDICT: for fp32 we expect bit-exactness and
    treat `diff > atol` as a likely export bug; for fp16/int8 a sizeable diff is
    EXPECTED (that's the whole point), so we instead sanity-check the output
    isn't NaN/inf and report the relative error as informational."""
    if reference_inputs is None:
        reference_inputs = example_inputs
    try:
        from executorch.runtime import Runtime
    except ImportError:
        logger.warning("executorch.runtime not available — skipping verification")
        return

    from torch.utils._pytree import tree_flatten

    runtime = Runtime.get()
    program = runtime.load_program(str(pte_path))
    method = program.load_method("forward")

    # ExecuTorch's runtime takes a FLAT list of tensors; nested pytree inputs
    # (e.g. the per-layer cache tuple in flow_lm_main) must be flattened first.
    flat_inputs, _ = tree_flatten(example_inputs)

    with torch.no_grad():
        reference_out = reference(*reference_inputs)
    flat_ref, _ = tree_flatten(reference_out)
    pte_out = method.execute(flat_inputs)

    # Compare every output tensor position-wise. For stateful graphs the
    # outputs include updated caches; we report the worst diff across all of
    # them (the conditioning/audio output is always position 0). Position 0 is
    # the primary output (audio / conditioning) — report its relative error.
    diff = max(
        (r.float() - p.float()).abs().max().item()
        for r, p in zip(flat_ref, list(pte_out))
    )
    primary_ref = flat_ref[0].float()
    primary_pte = pte_out[0].float()
    primary_diff = (primary_ref - primary_pte).abs().max().item()
    ref_scale = primary_ref.abs().max().item() or 1.0
    rel = primary_diff / ref_scale
    has_nan = bool(primary_pte.isnan().any() or primary_pte.isinf().any())

    logger.info(
        f"verify: max abs diff (all outputs) = {diff:.6g}; "
        f"primary output rel-err = {rel:.4f} (abs {primary_diff:.6g}, "
        f"scale {ref_scale:.4g}); NaN/inf={has_nan}"
    )
    if precision == "fp32":
        if diff > atol:
            logger.warning(
                f"verify: fp32 diff exceeds atol={atol} — exported graph may be wrong"
            )
    else:
        # Quant variant: a real diff is expected; only flag garbage.
        if has_nan:
            logger.error("verify: quantized output has NaN/inf — export is BROKEN")
        elif rel > 0.5:
            logger.warning(
                f"verify: quantized rel-err {rel:.2f} is high (>0.5) — "
                "check calibration / selective-quant before trusting on-device"
            )
        else:
            logger.info("verify: quantized output looks sane (no NaN/inf, rel-err < 0.5)")


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


GRAPHS = {
    # Stateless.
    "text_conditioner": export_text_conditioner,
    "mimi_encoder": export_mimi_encoder,
    "flow_lm_flow": export_flow_lm_flow,
    # Stateful — verified bit-exact via the rewrites described in their
    # wrapper docstrings (single-shot decode / export-friendly KV cache).
    "mimi_decoder": export_mimi_decoder,
    "flow_lm_main": export_flow_lm_main,
    # Conditioning phases 1 & 2 (priming): empty sequence + dynamic-length text.
    # Same wrapper/patch/path as flow_lm_main; only inputs + dynamic_shapes differ.
    "flow_lm_cond": export_flow_lm_cond,
}

# Default graphs exported when --graphs is omitted: the full bundle.
DEFAULT_GRAPHS = list(GRAPHS.keys())


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
        force=True,  # some imported lib already called basicConfig
    )

    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--language",
        default=DEFAULT_LANGUAGE,
        help=f"Upstream language config (default: {DEFAULT_LANGUAGE})",
    )
    parser.add_argument(
        "--output-dir",
        default=None,
        help="Bundle output dir (default: ./out/<bundle-name>/)",
    )
    parser.add_argument(
        "--graphs",
        nargs="+",
        default=DEFAULT_GRAPHS,
        choices=list(GRAPHS.keys()),
        help=(
            "Which graphs to export. Default is the verified-stateless set; "
            "opt in to experimental stateful graphs explicitly (mimi_decoder, "
            "flow_lm_main)."
        ),
    )
    parser.add_argument(
        "--precision",
        nargs="+",
        default=["fp32"],
        choices=list(PRECISIONS),
        help=(
            "Numerical precision variant(s) to emit. fp32 = bit-exact baseline "
            "(bare <graph>.pte); fp16 / int8 add a suffixed file "
            "(<graph>_fp16.pte / <graph>_int8.pte). Pass multiple to emit "
            "several in one run, e.g. --precision fp32 fp16 int8."
        ),
    )
    args = parser.parse_args()

    bundle_name = LANGUAGE_TO_BUNDLE_NAME.get(args.language)
    if bundle_name is None:
        raise SystemExit(f"No bundle name mapping for language '{args.language}'")

    output_dir = Path(args.output_dir or f"out/{bundle_name}")
    logger.info(f"Output dir: {output_dir.resolve()}")
    logger.info(f"Loading upstream Pocket TTS model: {args.language}")
    model = TTSModel.load_model(language=args.language)
    model.eval()

    for precision in args.precision:
        suffix = "" if precision == "fp32" else f"_{precision}"
        for graph_name in args.graphs:
            export_fn = GRAPHS[graph_name]
            out_path = output_dir / f"{graph_name}{suffix}.pte"
            logger.info(f"=== {graph_name} [{precision}] -> {out_path.name} ===")
            try:
                export_fn(model, out_path, precision)
            except Exception:  # noqa: BLE001 — report + continue, don't abort the run
                logger.exception(
                    f"FAILED to export {graph_name} [{precision}] — continuing"
                )

    logger.info("done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
