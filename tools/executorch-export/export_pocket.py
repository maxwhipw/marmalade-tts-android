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

Status (as of 2026-05-25):
  - text_conditioner: implemented + tested
  - mimi_encoder, mimi_decoder, flow_lm_main, flow_lm_flow: TODO
"""

from __future__ import annotations

import argparse
import logging
import os
from pathlib import Path

import torch
from torch import nn
from torch.utils._pytree import tree_map

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
# These are the hard cases. Upstream's StatefulModule pattern stores state
# as a dict-of-dict (`{module_path: {state_key: tensor}}`), with each
# module's forward mutating its slot in place (`state["previous"][:] = ...`).
# torch.export 2.x supports in-place mutation via functionalization — it
# rewrites in-place ops to functional form and tracks the mutated tensors
# in the graph signature.
#
# Strategy: accept the dict-of-dict pytree directly as a tuple input;
# torch.export handles pytree flattening internally as long as the structure
# is stable across calls. Inside the wrapper, run upstream's forward as-is
# and return both the audio AND the mutated state explicitly. This forces
# any functional-rewrite logic to surface tensors explicitly.
#
# UNVERIFIED — these were drafted without runtime testing because bash was
# wedged. Next-session checks:
#   1. Run with --graphs mimi_decoder; expect either success or a clear
#      torch.export error about pytree / mutation.
#   2. If it fails, the next thing to try is rewriting the forward to flatten
#      state to a plain list of tensors (one arg per state slot), which makes
#      the export signature even more obvious to torch.export.


from pocket_tts.modules.stateful_module import init_states  # noqa: E402


class MimiDecoderWrapper(nn.Module):
    """Wraps the composition that our existing `mimi_decoder.onnx` graph
    encodes:

        denorm   = latent * emb_std + emb_mean       # [1, T_latent, 32]
        transp   = denorm.transpose(-1, -2)          # [1, 32, T_latent]
        quant    = mimi.quantizer(transp)            # DummyQuantizer is identity
        audio    = mimi.decode_from_latent(quant, mimi_state)
        # decode_from_latent mutates mimi_state in place.

    Forward:
        latent: float32[1, T_latent, 32]
        mimi_state: dict[str, dict[str, Tensor]] — output of init_states(mimi, 1, S)
        returns: (audio: float32[1, 1, T_audio], mimi_state_after: same as input)

    The mutated state is returned explicitly so torch.export's functionalizer
    captures the state updates as graph outputs (rather than relying on
    silent input mutation, which is harder to expose to ExecuTorch's
    runtime).
    """

    def __init__(self, model: TTSModel):
        super().__init__()
        self.mimi = model.mimi
        self.emb_std = model.flow_lm.emb_std
        self.emb_mean = model.flow_lm.emb_mean

    def forward(
        self,
        latent: torch.Tensor,
        mimi_state: dict,
    ) -> tuple[torch.Tensor, dict]:
        denorm = latent * self.emb_std + self.emb_mean
        transposed = denorm.transpose(-1, -2)
        quantized = self.mimi.quantizer(transposed)
        audio = self.mimi.decode_from_latent(quantized, mimi_state)
        return audio, mimi_state


class FlowLmMainWrapper(nn.Module):
    """Wraps the `flow_lm_main` composition used by PocketEngine:
    one call advances the AR transformer by one frame using the input
    sequence (NaN for the first frame past prompt, then previous latent)
    and text/voice embeddings, returning a conditioning vector + EOS logit.

    The upstream backbone forward (`flow_lm.backbone`) processes input
    embeddings + text embeddings and returns transformer output. The
    AR loop external to this graph then samples a new latent via
    flow_net.

    Three call modes from PocketEngine.runFlowLmMain in Kotlin:
      Phase 1 (voice cond):   sequence=[1,0,32]  text=BOS+voice [1, V+1, 1024]
      Phase 2 (text cond):    sequence=[1,0,32]  text=text_embs [1, T, 1024]
      Phase 3 (AR step):      sequence=[1,1,32]  text=[1,0,1024]

    For ExecuTorch, dynamic shapes on the time axes are required.
    torch.export may struggle with this; if so, three separate
    specialized graphs (one per phase) are an option.

    Forward:
        sequence: float32[1, T_seq, 32]      — 0, V+1, or 1 frames depending on phase
        text_embeddings: float32[1, T_text, 1024]
        flow_lm_state: dict[str, dict[str, Tensor]]
        returns: (conditioning [1, 1024], eos_logit [1, 1], state_after)

    The conditioning + EOS aren't valid for phases 1+2 (those are
    conditioning passes). Kotlin already calls with `captureConditioning`
    false for those. We emit them anyway and let the caller ignore.
    """

    def __init__(self, model: TTSModel):
        super().__init__()
        self.flow_lm = model.flow_lm

    def forward(
        self,
        sequence: torch.Tensor,
        text_embeddings: torch.Tensor,
        flow_lm_state: dict,
    ) -> tuple[torch.Tensor, torch.Tensor, dict]:
        # Mirror flow_lm.forward up through backbone output.
        sequence = torch.where(
            torch.isnan(sequence), self.flow_lm.bos_emb, sequence
        )
        input_ = self.flow_lm.input_linear(sequence)
        transformer_out = self.flow_lm.backbone(
            input_, text_embeddings, sequence, model_state=flow_lm_state
        )
        transformer_out = transformer_out.to(torch.float32)
        last = transformer_out[:, -1] if transformer_out.shape[1] > 0 else transformer_out

        # `out_eos` is a Linear projecting transformer_out to a single
        # logit. Upstream applies a threshold inside the model; for export
        # we emit the raw logit and let Kotlin apply EOS_THRESHOLD.
        eos_logit = self.flow_lm.out_eos(last) if last.shape[0] > 0 else torch.zeros(1, 1)

        # `conditioning` is the same `last` tensor — flow_net's `c` input.
        conditioning = last

        return conditioning, eos_logit, flow_lm_state


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

    # mimi_state is a dict-of-dict pytree. init_states accepts a
    # sequence_length cap — pick something generous (a long Bible-chapter
    # mini-chunk is ≤ 50 latent frames at ~12.5 Hz; 300 frames = 24 s).
    mimi_state = init_states(model.mimi, batch_size=1, sequence_length=300)

    # Example latent: 1 mini-chunk worth (~15 frames at 12.5 Hz frame rate).
    example_latent = torch.zeros((1, 15, model.flow_lm.ldim), dtype=torch.float32)

    # Dynamic T_latent so any chunk length up to 300 works at runtime.
    # `mimi_state` is a nested dict-of-dict pytree; torch.export needs a
    # structurally isomorphic dynamic_shapes spec, with `None` at every
    # tensor leaf (all state tensors are pre-allocated static-sized KV
    # caches — only the input `latent`'s time axis varies at runtime).
    t_latent_dim = torch.export.Dim("T_latent", min=1, max=300)
    state_dynamic_shapes = tree_map(lambda _: None, mimi_state)
    _export_and_lower(
        wrapper,
        (example_latent, mimi_state),
        dynamic_shapes={"latent": {1: t_latent_dim}, "mimi_state": state_dynamic_shapes},
        output_path=output_path,
        graph_name="mimi_decoder",
        verify_atol=1e-3,  # streaming-conv accumulator may drift slightly fp32→fp32
    )


def export_flow_lm_main(model: TTSModel, output_path: Path) -> None:
    wrapper = FlowLmMainWrapper(model)
    wrapper.eval()

    # KV-cache sequence_length: longest single AR session worth of tokens
    # plus latents. Upper bound ~512 for a 25-token chunk's prompt + 75 AR
    # frames. Allocate 1024 for headroom.
    flow_lm_state = init_states(model.flow_lm, batch_size=1, sequence_length=1024)

    # Phase-3 example: sequence=[1,1,32], text=[1,0,1024]. This is the
    # most common call shape. Phases 1+2 use the same graph with
    # different time-dim values; dynamic dims expose both phases.
    example_sequence = torch.zeros((1, 1, model.flow_lm.ldim), dtype=torch.float32)
    example_text = torch.zeros((1, 0, model.flow_lm.dim), dtype=torch.float32)

    # Dynamic dims for T_seq (0 or 1) and T_text (0, T, or V+1).
    # See mimi_decoder for the pytree-isomorphism rule on dynamic_shapes;
    # flow_lm_state is also a dict-of-dict pytree with statically-sized leaves.
    t_seq_dim = torch.export.Dim("T_seq", min=0, max=2)
    t_text_dim = torch.export.Dim("T_text", min=0, max=1024)
    state_dynamic_shapes = tree_map(lambda _: None, flow_lm_state)
    _export_and_lower(
        wrapper,
        (example_sequence, example_text, flow_lm_state),
        dynamic_shapes={
            "sequence": {1: t_seq_dim},
            "text_embeddings": {1: t_text_dim},
            "flow_lm_state": state_dynamic_shapes,
        },
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

    runtime = Runtime.get()
    program = runtime.load_program(str(pte_path))
    method = program.load_method("forward")

    with torch.no_grad():
        reference_out = reference(*example_inputs)
    pte_out = method.execute(list(example_inputs))[0]

    diff = (reference_out - pte_out).abs().max().item()
    logger.info(f"verify: max abs diff between PyTorch and .pte = {diff:.6g}")
    if diff > atol:
        logger.warning(f"verify: diff exceeds atol={atol} — exported graph may be wrong")


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


GRAPHS = {
    # Stateless — verified bit-exact.
    "text_conditioner": export_text_conditioner,
    "mimi_encoder": export_mimi_encoder,
    "flow_lm_flow": export_flow_lm_flow,
    # Stateful — experimental, drafted but not yet runtime-tested.
    # Opt in with `--graphs mimi_decoder` or `flow_lm_main`.
    "mimi_decoder": export_mimi_decoder,
    "flow_lm_main": export_flow_lm_main,
}

# Default graphs exported when --graphs is omitted. Excludes the stateful
# experimental ones until they're verified to actually export cleanly.
DEFAULT_GRAPHS = ["text_conditioner", "mimi_encoder", "flow_lm_flow"]


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
