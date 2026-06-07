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
  `.pte` files. Outputs to `out/pocket-tts-en-v2026_04/`. Add
  `--precision fp16 int8` to also emit quantized variants (see below).

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

## Quantized variants (fp16 / int8) — 2026-06-05

`--precision {fp32,fp16,int8}` (repeatable) emits perf variants. fp32 keeps the
bare `<graph>.pte` name; fp16/int8 get a `_fp16` / `_int8` suffix. The goal is
the AR-step hot loop: fp32 `flow_lm_main` benches ~78 ms/step on a Pixel 8a,
roughly the whole 80 ms/frame realtime budget for one op, so int8 (the current
ORT path, + KleidiAI micro-kernels) is the decisive lever.

**Recipes**
- **fp16** — deep-copy the wrapper, `.half()` it, cast float inputs to half.
  XNNPACK then uses ARMv8.2 FP16. `EdgeCompileConfig(_check_ir_validity=False,
  _skip_dim_order=True)`.
- **int8** — official PT2E flow, mirroring
  `executorch/examples/xnnpack/quantization/utils.py`: pre-autograd capture
  (`torch.export.export(...).module()`), `XNNPACKQuantizer` with
  `get_symmetric_quantization_config(is_per_channel=True)` (per-channel
  symmetric weights, per-tensor activations — the KleidiAI int8 target),
  `prepare_pt2e` → calibrate → `convert_pt2e`, re-export, lower. In torch 2.12
  the pt2e helpers live in **`torchao.quantization.pt2e`**, not `torch.ao`
  (torchao is already in the venv). `mimi_decoder` int8 uses a SELECTIVE filter
  (`_mimi_decoder_quant_filter`) that leaves the `decoder_transformer` (its 2
  transformer layers + output projections — the T-Mimi documented-sensitive
  tail) in fp32 and quantizes only the heavy SEANet convs.

**Results** (verify = max abs diff / relative error of primary output vs the
fp32 eager reference on a REALISTIC input; quant variants are expected to
differ — the check is "not NaN/garbage", not bit-exactness)

| graph | fp32 | fp16 | int8 | verdict |
|-------|------|------|------|---------|
| `flow_lm_main` (AR step) | 302.4 MB | 151.3 MB | **76.1 MB** | **int8 usable** — rel-err 0.17, no NaN. fp16 .pte rel-err 3.1 (BROKEN lowering, see below) |
| `mimi_decoder` | 41.3 MB | 28.7 MB | — | fp16 .pte rel-err 7.5 (BROKEN lowering); int8 fails to LOAD (XNNPACK squeeze node) — both unusable |
| `flow_lm_flow` | 39.1 MB | 19.6 MB | 10.1 MB | fp16 rel-err 0.005 (good); int8 rel-err 0.46 (high for this tiny AdaLN MLP) |
| `text_conditioner` | 16.4 MB | 8.2 MB | 16.4 MB | embedding; fp16 rel 0.009. int8 NOT smaller (table unquantized + q/dq overhead) — prefer fp16 |
| `mimi_encoder` | 19.3 MB | fails | 4.9 MB* | run-once (not perf-critical). fp16 export fails (dtype clash); *int8 verify ran on zeros → rel 0 is not meaningful |

**The fp16 lowering is broken in ExecuTorch 1.2.0 for the transformer/conv
graphs.** Eager fp16 is ACCURATE (`flow_lm_main` cond rel-err 0.001,
`mimi_decoder` 0.008), but the lowered `.pte` diverges badly (3.1 / 7.5). The
divergence is identical with the XNNPACK partitioner and with portable ops, so
it's the `to_edge`/`to_executorch` fp16 decomposition — almost certainly an
attention softmax / conv reduction computed in pure fp16 where eager keeps fp32
accumulation. This XNNPACK version exposes no "fp16 weights, fp32 accumulate"
partitioner flag, so pre-casting is the only fp16 route and it loses the
accumulation. **Net: fp16 .pte are NOT usable for the heavy graphs as-is** —
they load and run (benchable for raw timing) but produce wrong audio. int8 is
the viable quant path.

`mimi_decoder` int8 writes a `.pte` but FAILS TO LOAD: `XNNCompiler.cpp Failed
to create squeeze node ... xnn_status_invalid_parameter`. Reproduces with a
static shape too, so it's not the dynamic `T_latent` dim — a squeeze in the
quantized SEANet/quantizer graph that XNNPACK's int8 path can't build. Left
unsolved (mimi is meant to stay high-precision for quality anyway, and the
decoder isn't the AR hot loop). The non-loadable file is deleted; the
degraded-but-runnable fp16 one is kept for timing only.

**Cleanest recipe that worked end-to-end:** int8 PT2E on `flow_lm_main` —
76 MB (4x smaller than fp32), loads + runs host-side, rel-err 0.17, no NaN.
That is the variant to bench on device; it's the same int8 precision class as
the current ORT path, so quality is an apples-to-apples A/B.

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
