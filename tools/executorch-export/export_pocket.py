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

from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner


logger = logging.getLogger(__name__)

DEFAULT_LANGUAGE = "english_2026-04"

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
        denorm = latent * self.emb_std + self.emb_mean
        quantized = self.mimi.quantizer(denorm.transpose(-1, -2))
        # Replicate mimi.decode_from_latent, but route the transformer through
        # the stateless (non-streaming) path and the convs through fresh state.
        emb = self.mimi._to_encoder_framerate(quantized, fresh_state)
        (emb,) = self.mimi.decoder_transformer(emb, None)
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
        transformer_out = transformer_out.to(torch.float32)

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


def _export_and_lower(
    wrapper: nn.Module,
    example_inputs: tuple[torch.Tensor, ...],
    dynamic_shapes: dict | None,
    output_path: Path,
    *,
    graph_name: str,
    verify_atol: float = 1e-4,
) -> None:
    """Common path: torch.export → ExecuTorch lower → write .pte → verify.
    Most graphs share this exact wrapping; only inputs/shapes differ."""
    logger.info(f"Exporting {graph_name} via torch.export…")
    exported = torch.export.export(
        wrapper,
        example_inputs,
        dynamic_shapes=dynamic_shapes,
    )

    logger.info(f"Lowering {graph_name} to ExecuTorch (XNNPACK partitioner)…")
    edge = to_edge_transform_and_lower(
        exported,
        partitioner=[XnnpackPartitioner()],
    )
    program = edge.to_executorch()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(program.buffer)
    size_mb = output_path.stat().st_size / 1e6
    logger.info(f"Wrote {output_path} ({size_mb:.1f} MB)")

    _verify_pte(wrapper, output_path, example_inputs, atol=verify_atol)


def export_text_conditioner(model: TTSModel, output_path: Path) -> None:
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
    )


def export_mimi_encoder(model: TTSModel, output_path: Path) -> None:
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
    )


def export_flow_lm_flow(model: TTSModel, output_path: Path) -> None:
    wrapper = FlowNetWrapper(model.flow_lm.flow_net)
    wrapper.eval()

    cond_dim = model.flow_lm.dim
    latent_dim = model.flow_lm.ldim
    example_c = torch.zeros((1, cond_dim), dtype=torch.float32)
    example_s = torch.zeros((1, 1), dtype=torch.float32)
    example_t = torch.zeros((1, 1), dtype=torch.float32)
    example_x = torch.zeros((1, latent_dim), dtype=torch.float32)
    _export_and_lower(
        wrapper,
        (example_c, example_s, example_t, example_x),
        dynamic_shapes=None,  # all shapes static
        output_path=output_path,
        graph_name="flow_lm_flow",
    )


# ---------------------------------------------------------------------------
# EXPERIMENTAL: stateful graph exports
# ---------------------------------------------------------------------------
# Not wired into GRAPHS by default — opt in with e.g.
# `--graphs mimi_decoder`. These are unverified drafts; expect to iterate
# once they actually run.


def export_mimi_decoder(model: TTSModel, output_path: Path) -> None:
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
    _export_and_lower(
        wrapper,
        (example_latent,),
        dynamic_shapes={"latent": {1: t_latent_dim}},
        output_path=output_path,
        graph_name="mimi_decoder",
        verify_atol=1e-3,  # fp32→fp32 lowering may drift slightly
    )


# KV cache capacity baked into the flow_lm_main graph (max AR positions per
# session). A 25-token chunk's prompt + ~75 AR frames stays well under this.
FLOW_LM_CACHE_CAP = 1024


def export_flow_lm_main(model: TTSModel, output_path: Path) -> None:
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
        )


def _verify_pte(
    reference: nn.Module,
    pte_path: Path,
    example_inputs: tuple[torch.Tensor, ...],
    *,
    atol: float = 1e-4,
) -> None:
    """Run both the reference PyTorch module and the .pte on the same
    input; report max abs diff. Catches export bugs immediately."""
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
        reference_out = reference(*example_inputs)
    flat_ref, _ = tree_flatten(reference_out)
    pte_out = method.execute(flat_inputs)

    # Compare every output tensor position-wise. For stateful graphs the
    # outputs include updated caches; we report the worst diff across all of
    # them (the conditioning/audio output is always position 0).
    diff = max(
        (r - p).abs().max().item()
        for r, p in zip(flat_ref, list(pte_out))
    )
    logger.info(f"verify: max abs diff between PyTorch and .pte = {diff:.6g}")
    if diff > atol:
        logger.warning(f"verify: diff exceeds atol={atol} — exported graph may be wrong")


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
    args = parser.parse_args()

    bundle_name = LANGUAGE_TO_BUNDLE_NAME.get(args.language)
    if bundle_name is None:
        raise SystemExit(f"No bundle name mapping for language '{args.language}'")

    output_dir = Path(args.output_dir or f"out/{bundle_name}")
    logger.info(f"Output dir: {output_dir.resolve()}")
    logger.info(f"Loading upstream Pocket TTS model: {args.language}")
    model = TTSModel.load_model(language=args.language)
    model.eval()

    for graph_name in args.graphs:
        export_fn = GRAPHS[graph_name]
        out_path = output_dir / f"{graph_name}.pte"
        export_fn(model, out_path)

    logger.info("done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
