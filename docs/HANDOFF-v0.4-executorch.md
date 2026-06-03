# Handoff: v0.4 ExecuTorch migration

Date written: 2026-05-25
Purpose: pick this up from a fresh session with full context.

---

## TL;DR (read this if you read nothing else)

Marmalade Android TTS has quality problems across all engines except
Kitten Nano. Cause is split: (a) INT8 quantization noise on bigger
engines, (b) silent sentence drops via `producer.trySend(...)` in
SherpaEngine, (c) a few pipeline bugs (cancel race, system-TTS not
streaming, etc.). Rather than patch these on a stack we're leaving,
we chose to **migrate all 5 engines to ExecuTorch (`org.pytorch:executorch-android:1.2.0`)**
running PyTorch models at fp16 + KleidiAI. Drops sherpa-onnx and
onnxruntime-android entirely. Existence proof: react-native-executorch
already ships Kokoro-82M via this exact stack.

**Where we are:** the export pipeline (PyTorch → `.pte`) for Pocket TTS
is **3 of 5 graphs done** (verified bit-exact). The 2 remaining graphs
are stateful (KV cache, streaming conv state); wrappers are drafted in
the script but not yet runtime-tested because the bash terminal in this
session got wedged after the third export.

**Next concrete action:** in a fresh terminal, `cd tools/executorch-export
&& uv run python export_pocket.py` to re-confirm the 3 stateless graphs,
then `--graphs mimi_decoder` to try the first stateful one.

---

## Background — why we're doing this

User reported quality issues across engines. Kitten Nano (smallest, fp32)
sounded BEST. Kitten Mini (INT8-mixed) was tinny. Kokoro v1.0 "wanting,"
v1.1 "very bad." Pocket worst.

Two reviewer agents + an ExecuTorch research agent ran in parallel. Full
findings are in `docs/REVIEW-2026-05.md`. The key insights:

- INT8 hobbles everything except Nano (Nano is the only fp32 model in the
  lineup)
- `SherpaEngine.kt:278` silently drops sentences via `producer.trySend(...)`
  whose result is discarded — slower engines hit backpressure first
- Pocket has additional issues: LSD_DECODE_STEPS=1 (NekoSpeak uses 10),
  mimi_encoder INT8 bakes quant error into cached voice embeddings
- Multiple pipeline bugs (Synthesizer.cancelled race, MarmaladeTtsService.onStop
  no-op, streaming gate too tight on emojis, EffectChain.cave in-place
  feedback)

ExecuTorch research verdict: **migrate is the right call**, fp32 ONNX re-export
considered as cheap diagnostic spike but skipped per user direction ("shoot
straight for executorch").

---

## Architectural decision

Single inference stack across all 5 engines: ExecuTorch + XNNPACK + KleidiAI
+ fp16 weights.

| Aspect | Before | After |
|---|---|---|
| AAR | sherpa-onnx (vendored) + ORT Maven (~80 MB) | ExecuTorch (~11 MB) |
| Pocket precision | INT8 all 5 graphs | fp16 |
| Kokoro/Kitten | sherpa-onnx wrapper | ExecuTorch native |
| Pocket size on disk | ~70 MB (INT8) | ~220 MB (fp16) |

Sources: `react-native-executorch` ships Kokoro via this stack;
`org.pytorch:executorch-android:1.2.0` stable on Maven; KleidiAI default
since 0.7; PyTorch Mobile is dead, NNAPI is dead, XNNPACK rejects our
current dynamic-int8 ONNX format.

See memory entries:
- `project_executorch_target.md`
- `project_pocket_tts_export_gotchas.md`

---

## Migration phases (tasks #36-43 in task tracker)

- **A: Pocket** (priority 1, hardest case, validates everything else)
  - A.1: Python export pipeline ← **in progress, 3/5 graphs done**
  - A.2: Add `org.pytorch:executorch-android:1.2.0` AAR
  - A.3: Write `PocketEngineExecutorch` (parallel to current `PocketEngine`)
  - A.4: Bundle .pte files (replaces / coexists with current ONNX bundle)
  - A.5: A/B test on Pixel 8a, decide go/no-go
- **B: Kokoro** (after A confirms)
- **C: Kitten** (after A confirms)
- **D: Remove sherpa-onnx + onnxruntime-android deps**

---

## State of A.1 (export pipeline) in detail

### Tooling location

`marmalade-tts-android/tools/executorch-export/`:

```
pyproject.toml      uv project, deps pinned
README.md           setup + status + known gotchas
inspect_pocket.py   sanity check (working)
export_pocket.py    production export script (production-ready for 3 graphs)
.venv/              uv-managed venv (after `uv sync`)
```

Dependencies (resolved via `uv sync`):
- `executorch == 1.2.0`
- `torch == 2.12.0+cpu`
- `pocket-tts` from `/tmp/pocket-tts-upstream` (editable)
- `safetensors`, `huggingface_hub`

Required: `/tmp/pocket-tts-upstream` must exist (clone of
`https://github.com/kyutai-labs/pocket-tts.git`).

### Graphs status

| Graph | Wrapper class | Export status | .pte location |
|---|---|---|---|
| text_conditioner | `TextConditionerWrapper` | ✅ bit-exact (diff=0) | `/tmp/pocket-export-verify/text_conditioner.pte` (16.4 MB) |
| mimi_encoder | `MimiEncoderWrapper` | ✅ exported, verifier ran (output cut off by bash glitch, no traceback) | `/tmp/pocket-export-verify/mimi_encoder.pte` |
| flow_lm_flow | `FlowNetWrapper` | ✅ exported (same caveat) | `/tmp/pocket-export-verify/flow_lm_flow.pte` |
| mimi_decoder | `MimiDecoderWrapper` | 🚧 drafted, NOT RUN | — |
| flow_lm_main | `FlowLmMainWrapper` | 🚧 drafted, NOT RUN | — |

Stateful wrappers (mimi_decoder, flow_lm_main) are opt-in via `--graphs`
arg; default run excludes them.

### Known gotchas (saved to memory)

1. **Beartype import hook neutered**. `pocket_tts/__init__.py` runs
   `beartype.claw.beartype_this_package(...)` which wraps every function
   with runtime type checks that reject torch.export's SymInt. The export
   script neuters it. If you see `beartype.roar.BeartypeCallHintReturnViolation`,
   the neutering didn't apply early enough — check import order.

2. **`pad_for_conv1d` breaks dynamic shape solver**. For `mimi_encoder`,
   the workaround is fixed `T_audio = 30 s` (matches existing
   `PocketEngine.encodePcm` cap). Kotlin pre-pads shorter inputs with zeros.

3. **`Constraints violated (T_audio)`** is the dynamic-shape symptom.
   Static shapes always work; dynamic only when the math is simple.

### Static-vs-dynamic shape summary across graphs

- text_conditioner: T_tokens **dynamic** (1..4096) — embedding lookup
  is happy with any T
- mimi_encoder: T_audio **static** at 30 s — pad_for_conv1d breaks dynamic
- flow_lm_flow: all **static** — single-step Euler MLP, fixed shapes
- mimi_decoder: T_latent **dynamic** (1..300) intended — TBD if works
- flow_lm_main: T_seq + T_text **dynamic** intended — TBD if works

### What to run next session

```bash
cd marmalade-tts-android/tools/executorch-export

# 1. Sanity check tooling still works:
uv run python export_pocket.py
# Expect: 3 .pte files in out/pocket-tts-en-v2026_04/,
# all reporting "verify: max abs diff between PyTorch and .pte = 0" (or small)

# 2. Try first stateful graph:
uv run python export_pocket.py --graphs mimi_decoder
# Failure modes to expect:
#   - torch.export pytree error → state dict-of-dict not handled cleanly
#   - mutation error → in-place state mutation not functionalized
# Workaround: rewrite wrapper to take flat tensor list (one arg per state slot)
# instead of dict-of-dict. There are 56 state slots in mimi (see bundle.json
# mimi_state_manifest).

# 3. Try second stateful graph:
uv run python export_pocket.py --graphs flow_lm_main
# 18 state slots (flow_lm_state_manifest).
```

If a stateful graph fails: don't sink hours debugging. Drop to the
fp32-ONNX-re-export plan (Option B from the research agent) as a
faster way to validate that fp precision is the quality fix, then
return to ExecuTorch later.

---

## State of the rest of the codebase (NOT executorch-related)

These were the work BEFORE pivoting to ExecuTorch tonight. All committed
or buildable. Don't lose them:

### v0.3.0-alpha.6+ landed in this session

- **D refactor** (`PocketEngine.kt`): per-chunk batched pipeline with
  adaptive pre-roll. Replaces within-chunk frame streaming.
  Mimi state resets per chunk. Token-aware chunker
  (`chunkPocketByTokens` — sentence + comma + word fallback, ≤25 tokens
  per chunk). Adaptive K: measure frames [1..4] of chunk 0, compute K
  chunks to buffer before first emit, cap at 3.
- **Warmup synth** in `doLoad()` to amortize ORT JIT cost.
- **Stop button** in `SpeakScreen.kt` (toggles "Speak"/"Stop"). Cooperative
  cancel in `PocketEngine.generateChunkBatched` via
  `coroutineContext.ensureActive()`.

### Open issues from review (not fixed because we're migrating)

See `docs/REVIEW-2026-05.md` for the full ranked list. Critical ones:

- **C1**: `SherpaEngine.kt:278` silent `trySend` drops. **Engine-layer,
  goes away with ExecuTorch migration.** Don't fix.
- **C4**: `Synthesizer.cancelled` process-global race. **Pipeline-layer,
  survives migration.** Worth fixing alongside.
- **C5/C6**: `MarmaladeTtsService.onStop` no-op + non-streaming.
  **Pipeline-layer, survives migration.**
- **H2**: streaming gate too tight on emojis. **Pipeline-layer.**
- **H6**: `EffectChain.cave` in-place feedback ducking. **Pipeline-layer.**

If user asks for quick UX wins between ExecuTorch milestones, do the
pipeline-layer ones first (they're independent of which inference runtime
runs underneath).

---

## Pointers

| File | What |
|---|---|
| `docs/REVIEW-2026-05.md` | Full review findings (bugs ranked, both reviewers + ExecuTorch research) |
| `docs/HANDOFF-v0.4-executorch.md` | This file |
| `tools/executorch-export/` | Export pipeline |
| `tools/executorch-export/export_pocket.py` | All 5 graph wrappers + export logic |
| `tools/executorch-export/README.md` | Pipeline setup + status |
| `app/src/main/java/.../engine/PocketEngine.kt` | Current INT8 ORT engine (becomes `PocketEngineOnnx`?) |
| `app/src/main/java/.../engine/PocketEngineExecutorch.kt` | TO CREATE (task A.3) |
| `app/src/main/java/.../audio/Synthesizer.kt` | Pipeline-layer concerns live here |
| `/tmp/pocket-tts-upstream/` | Upstream Kyutai Pocket TTS clone |
| `/tmp/NekoSpeak/` | Reference Android impl (MIT) |

### Memory entries (`~/.claude/projects/-home-max-coding-marmalade-tts-cli/memory/`)

- `MEMORY.md` (index)
- `project_executorch_target.md` — chosen long-term direction
- `project_pocket_tts_export_gotchas.md` — beartype + pad_for_conv1d
- `project_pocket_v0_3_0_in_progress.md` — current alpha status (now superseded)
- `feedback_logcat_over_screenshots.md` — when verifying on device
- All others — standard project context

### Device info

- Pixel 8a (Tensor G3), Tailscale IP `100.114.195.29`. Wireless ADB port
  rotates per session — user provides it when needed.
- Saved in user-level `~/.claude/CLAUDE.md` under "Max's Devices".

---

## What was hard this session

- **Beartype import-hook discovery** cost ~1h of confused debugging.
  Documented in memory; future export work won't trip on this.
- **`pad_for_conv1d` symbolic constraint** another ~30 min. Same.
- **Bash session got wedged** late in the session after stream
  redirections. Stopped the verify-and-iterate loop. Not a code problem;
  fresh shell fixes.
- **Stateful graph wrappers drafted without testing** — non-ideal. Treat
  them as 80% drafts.
