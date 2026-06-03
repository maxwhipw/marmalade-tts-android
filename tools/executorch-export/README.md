# marmalade-executorch-export

Python tooling to convert TTS PyTorch models into ExecuTorch `.pte` bundles
for `marmalade-tts-android`. See `docs/REVIEW-2026-05.md` in the repo root
for the strategic rationale (we're migrating off sherpa-onnx + onnxruntime
to a single ExecuTorch stack).

## Setup

This project uses [`uv`](https://docs.astral.sh/uv/) and consumes the upstream
Kyutai `pocket-tts` package as an editable install from a local clone at
`/tmp/pocket-tts-upstream`. Ensure that clone exists before syncing.

```bash
git clone https://github.com/kyutai-labs/pocket-tts.git /tmp/pocket-tts-upstream  # if not already

cd tools/executorch-export
uv sync
```

## Scripts

- `inspect_pocket.py` — load the upstream Pocket TTS model and print its
  module structure. Sanity check that the export environment can load the
  PyTorch model end-to-end. Run BEFORE attempting any export.
- `export_pocket.py` — `torch.export` each graph, lower to ExecuTorch
  `.pte` files. Outputs to `out/pocket-tts-en-v2026_04/`.

## Status (2026-05-25)

**Verified stateless graphs** (default `--graphs` set):

- ✅ `text_conditioner.pte` (16.4 MB) — bit-exact vs PyTorch (diff = 0)
- ✅ `mimi_encoder.pte` — exported, fixed T_audio = 30 s (caller pads zeros)
- ✅ `flow_lm_flow.pte` — exported, static shapes

**Experimental stateful graphs** (opt-in via `--graphs`):

- 🚧 `mimi_decoder` — wrapper drafted; expects dict-of-dict mimi_state pytree
  input + same dict back. Uses `torch.export`'s in-place mutation functional-
  ization. Not yet runtime-tested.
- 🚧 `flow_lm_main` — wrapper drafted; same pattern but with dynamic T_seq
  and T_text dims. Three phases (voice cond, text cond, AR step) share the
  graph via dynamic dims.

## Known gotchas

1. **Beartype import hook.** `pocket_tts/__init__.py` calls
   `beartype.claw.beartype_this_package(...)` which auto-wraps every
   function with runtime type checks. These reject `SymInt` from
   torch.export's symbolic shape analysis. `export_pocket.py` neuters
   the hook before importing `pocket_tts`. Don't remove that or every
   conv-padding helper will fail.

2. **Dynamic shapes + symbolic int constraints.** `pad_for_conv1d` does
   `(length / stride) * stride - length` math that creates symbolic
   constraints torch.export's dynamic-shape solver can't satisfy. The
   workaround for `mimi_encoder` is to fix T_audio = 30 s and pad in
   Kotlin. Same problem may bite mimi_decoder; watch for it.

3. **In-place state mutation.** Upstream's StatefulModules mutate state
   via slice assignment (`state["previous"][:] = ...`). torch.export 2.x
   functionalizes this automatically, but if a graph fails to export
   with a "mutation" error, the fallback is to rewrite the wrapper to
   accept a flat tensor list (one arg per state slot) and explicitly
   return all of them.

## Output layout

The exported bundle mirrors the current ORT bundle layout so the Android
side can swap engines without restructuring its install path. Per-language
subdirectories under `out/`:

```
out/pocket-tts-en-v2026_04/
  flow_lm_main.pte
  flow_lm_flow.pte
  mimi_encoder.pte
  mimi_decoder.pte
  text_conditioner.pte
  tokenizer.model           # SentencePiece, unchanged
  bos_before_voice.npy      # learned BOS, unchanged
  bundle.json               # state manifest, updated for ExecuTorch
  voices/                   # WAV files for predefined voices
    *.wav
```

## Notes

The upstream Pocket TTS model has 5 distinct graphs that we run as separate
ONNX sessions today. Each needs its own `torch.export` wrapper that pins the
inputs/outputs cleanly — most of the engineering effort here is writing those
wrappers (the stateful KV-cache modules pass state in/out via dicts, which
ExecuTorch's externalized-state docs explicitly support).
