# marmalade-executorch-export

Python tooling to convert TTS PyTorch models into ExecuTorch `.pte` bundles
for `marmalade-tts-android`. See `docs/REVIEW-2026-05.md` in the repo root
for the strategic rationale (we're migrating off sherpa-onnx + onnxruntime
to a single ExecuTorch stack).

## Setup

This project uses [`uv`](https://docs.astral.sh/uv/) and consumes the upstream
Kyutai `pocket-tts` package (pinned to v2.1.0) as an editable install from a
local clone. The clone path is configured in `pyproject.toml` under
`[tool.uv.sources]`. Use a STABLE path — NOT `/tmp`, which gets wiped (that is
exactly what stranded the editable install before).

```bash
git clone https://github.com/kyutai-labs/pocket-tts.git ~/coding/scratch/pocket-tts-upstream
git -C ~/coding/scratch/pocket-tts-upstream checkout v2.1.0

cd tools/executorch-export
uv sync
```

If the existing `.venv` already has `pocket_tts` installed editable but its
source path went missing, you can just re-point the one-line `.pth` finder
(`.venv/lib/python*/site-packages/_editable_impl_pocket_tts.pth`) at the new
clone instead of re-syncing.

`torch.export` lowering shells out to a `flatc` binary. It ships in the venv at
`.venv/bin/flatc`; the script auto-sets `FLATC_EXECUTABLE` to it when neither
that env var nor a PATH `flatc` is found.

## Scripts

- `inspect_pocket.py` — load the upstream Pocket TTS model and print its
  module structure. Sanity check that the export environment can load the
  PyTorch model end-to-end. Run BEFORE attempting any export.
- `export_pocket.py` — `torch.export` each graph, lower to ExecuTorch
  `.pte` files. Outputs to `out/pocket-tts-en-v2026_04/`.

## Status (2026-06-04)

All 5 graphs export and verify bit-exact (max abs diff vs eager PyTorch on
representative inputs). `--graphs` defaults to the full set.

| graph | size | max abs diff | notes |
|-------|------|--------------|-------|
| `text_conditioner` | 16.4 MB | 0 | embedding lookup |
| `mimi_encoder` | 19.3 MB | 0 | fixed T_audio = 30 s (caller pads zeros) |
| `flow_lm_flow` | 39.1 MB | 5e-7 | static shapes |
| `mimi_decoder` | 41.3 MB | 3e-7 | **stateless single-shot** (see below) |
| `flow_lm_main` | 302.4 MB | 9e-6 | **export-friendly KV cache** (see below) |

### The two stateful graphs

Both originally tried to thread upstream's streaming state (dict-of-dict KV
cache + conv overlap) through the graph as pytree I/O. That is unexportable:
the KV-cache backend reads its write offset via `int(offset.item())`
(`transformer.complete_kv`), which `torch.export` rejects as a data-dependent
symbolic int — even with fully static shapes, because the value comes from a
*state* tensor. Fixes:

- **`mimi_decoder`** — exported STATELESS / single-shot, the way ExecuTorch's
  own Mimi reference does (`examples/models/moshi/mimi/test_mimi.py`): the
  decoder transformer runs non-streaming (`model_state=None`, no KV cache), and
  the SEANet conv state is built fresh inside the graph. Caller decodes one
  COMPLETE mini-chunk per call. **This makes the decoder window-size-invariant**
  (decode(6 frames) == decode(12 frames)[:len6], diff 5e-7), which the
  window-DEPENDENT ONNX decoder was not — removing the reason for the Kotlin
  graduated-window workaround. (Per-frame decode is NOT supported — a lone
  frame lacks the transposed-conv receptive field; feed whole chunks.)

- **`flow_lm_main`** — the autoregressive KV cache is essential, so it can't go
  stateless. `patch_kv_cache_for_export` (applied only around this export)
  swaps in an `.item()`-free cache backend: it writes K/V at `offset+arange(T)`
  via `index_copy`, reads the full fixed-capacity cache, and masks unwritten
  slots with `pos_k = -1`. `offset` (0-d int) and the per-layer caches are
  explicit graph I/O; the host increments the offset and threads the caches
  across AR steps. Exported as a static phase-3 (AR-step) graph; the
  conditioning phases (seq=0, text=T) have a different shape signature and would
  be separate specialized graphs if needed.

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
