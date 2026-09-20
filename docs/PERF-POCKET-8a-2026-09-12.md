# Pocket TTS — Pixel 8a profiling pass, 2026-09-12

Measurement-first review of the Pocket engine's speed on the Pixel 8a
(Tensor G3). Quantization is explicitly **out of scope** (Max, 2026-09-12:
dynamic int8 `flow_lm_main` stays; no QDQ; no quality-degrading tricks).

**Read the honesty line first:** the warm steady-state numbers this pass was
meant to capture were **NOT measured tonight** — the device was unavailable
for the whole window (details in §1.3). Everything below is either (a) a cold
measurement taken earlier this evening, (b) a device-state reading I took
tonight, (c) a prior warm measurement already on record for this exact device,
or (d) static analysis. Each number says which.

**2026-09-19 UPDATE — warm numbers captured; see §1.3a.** The §1.2 two-point
fit's "fixed per-chunk prefill ~2.3–2.9 s" is now measured DIRECTLY at
~0.35–0.42 s (P-AM.1 instrumentation, `e90e823`); the 09-12 cold numbers were
taken on a phone at ≤3% battery and overstate everything. Phase 1 is
9.4–12.8% of a warm chunk's `ar=` — **below the 15% gate, so P-AM.2 (voice-KV
snapshot) is NOT justified** on current evidence.

---

## 1. Measured

### 1.1 Device state (read by me, 2026-09-12 19:00–19:53 local)

| property | value |
|---|---|
| device | Pixel 8a, Android 16, Tensor G3 |
| CPU | **9 cores** — `policy0` cpu0-3 @ 1.704 GHz (A510), `policy4` cpu4-7 @ 2.367 GHz (A715), `policy8` cpu8 @ 2.914 GHz (X3) |
| `CpuClusterDetector.detectPerfCoreCount()` on this topology | **5** (policy4's 4 + policy8's 1; policy0 is the min-freq tier and is excluded) |
| ORT intra-op threads actually used | **5** for text_cond / mimi_enc / mimi_dec / flow_lm_main; **2** for flow_lm_flow (`max(5/2, 2)`) |
| thermal status | **1 (LIGHT)** at 19:00 during a Signal call; **0 (NONE)** 19:44–19:50 |
| cached temps at 19:00 | BIG 60 °C, MID 64 °C, LITTLE 64 °C, TPU 47 °C, VIRTUAL-SKIN 39 °C (status 1) |
| live HAL temps at 19:00 | BIG 46 °C, LITTLE 46 °C, G3D 45 °C |
| battery saver | **OFF** (`settings get global low_power` = `0`) for the entire window |
| battery level | 16% at 18:44 → 12% 19:00 → 5% 19:29 → **2% 19:53** (heavy Signal call draining it) |
| memory | MemTotal 7.74 GB, **MemAvailable 0.70 GB** — memory-tight, the same condition flagged in the 2026-08-04 quant bench |

Installed Pocket bundle (`files/engines/pocket-tts-en-v2026_04/`, pushed
18:45 today), `quantization_variant = "mixed-fp32-mimi"`:

| graph | file | size |
|---|---|---|
| flow_lm_main | `flow_lm_main_int8.onnx` | 76.3 MB (dynamic int8) |
| mimi_decoder | `mimi_decoder.onnx` | 41.5 MB (fp32) |
| flow_lm_flow | `flow_lm_flow.onnx` | 39.1 MB (fp32) |
| mimi_encoder | `mimi_encoder.onnx` | 39.8 MB (fp32) |
| text_conditioner | `text_conditioner.onnx` | 16.4 MB (fp32) |
| | **total** | **213 MB** |

Bundle geometry: `frame_rate` 12.5 (80 ms/frame budget), `latent_dim` 32,
`conditioning_dim` 1024, `samples_per_frame` 1920, `sample_rate` 24000,
`max_token_per_chunk` 50, `insert_bos_before_voice` true.

### 1.2 COLD numbers (captured ~18:44 by the session that briefed this pass)

Cold process per run, fdroidDebug build (commit `0454d95`), Kokoro warmup
possibly contending, alias speed 0.5 (post-engine Tempo — does not affect
engine timings).

| metric | value | derived |
|---|---|---|
| `loadWait` | 4.0–4.2 s | — |
| `TTFA` | ~16 s | — |
| chunk 0 `ar=` ("The quick brown fox jumps over the lazy dog.") | **5 500 ms** for 2 000 ms audio | 25 frames → **220 ms/frame**, RTF 2.75 |
| chunk 1 `ar=` | **7 300–7 700 ms** for 3 360 ms audio | 42 frames → **174–183 ms/frame**, RTF 2.17–2.29 |
| first-run `decode=` | **557 ms** for 400 ms audio | 5 frames — all on the per-frame path (5 < `MIMI_OVERLAP_FRAMES`=8) → **~111 ms per single-frame mimi run** |

**Derived decomposition (inference, not a direct measurement).** Both chunks
pay the same fixed per-chunk prefill *P* (phase 1 voice-cond + phase 2
text-cond, both re-run on every chunk) plus *F* per AR frame. Two-point fit:

```
P + 25F = 5500
P + 42F = 7300 … 7700
  ⇒ F ≈ 106 … 129 ms/frame,  P ≈ 2.3 … 2.9 s per chunk
```

Caveat that matters: chunk 0 also carries ORT's first-run-per-shape cost, so
this fit **overstates P and understates F**. Treat it as an upper bound on
prefill and a lower bound on per-frame — but even the pessimistic reading puts
fixed per-chunk prefill in the seconds, which is the finding that drives §5.

### 1.3 WARM numbers — NOT CAPTURED. Why, and the exact recipe to get them.

The device was unavailable for the whole session:

- 18:5x–19:44 — continuous Signal calls (`MODE_IN_COMMUNICATION`), foreground
  `WebRtcCallActivity` / Chrome. The briefing's hard rule is to wait, so I
  polled at 45–60 s intervals from 19:01 to 19:53 and did static analysis
  in between. Nothing was launched, nothing played, the screen was never
  touched.
- 19:44 — call ended (`mAudioModeOwner: mMode=MODE_NORMAL`), but Chrome was
  foreground with the screen on (Max using the phone), and battery had fallen
  to 3%.
- 19:53 — battery 2%. Stopped. Running a ~20 s CPU-saturating AR benchmark on
  a phone at 2% that its owner is holding is not a measurement I'm willing to
  take, and the result would need so many caveats it wouldn't settle anything.

Nothing was left running; the debug app was force-stopped at 19:49 and no
synthesis was ever triggered.

**Recipe for the next session (phone charged, idle, `MODE_NORMAL`):**

```bash
export ANDROID_SERIAL=<device-ip>:<adb-port>
adb shell dumpsys audio | grep "mAudioModeOwner"      # must say MODE_NORMAL
adb shell dumpsys battery | grep "  level"            # want >40% and charging
adb shell settings get global low_power               # must be 0
adb shell dumpsys thermalservice | grep "Thermal Status"   # want 0

adb logcat -c
adb shell am force-stop app.marmalade.tts.debug
# RUN 1 = COLD
adb shell am start -W -n app.marmalade.tts.debug/app.marmalade.tts.ui.intent.ShareIntentActivity \
  -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "'The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs. How razorback jumping frogs can level six piqued gymnasts.'"
sleep 60
# RUN 2 + RUN 3 = WARM (same process — do NOT force-stop between)
#   repeat the am start twice more, sleep 60 between
adb logcat -d -v time -s StreamPerf:* PocketEngine:* > warm.log
adb shell dumpsys thermalservice | grep "Thermal Status"   # check for drift
adb shell am force-stop app.marmalade.tts.debug
```

Read from `warm.log`: `ar=`/`decode=`/`emit`/`TTFA` on the `StreamPerf` tag,
and `Pocket chunk N: frames=X/Y` on `PocketEngine` for the frame count that
turns `ar=` into ms/frame. Compare run 1 chunk 0 against run 3 chunk 0.

### 1.3a WARM numbers — CAPTURED 2026-09-19 (recipe above, P-AM.1 build `e90e823`)

Preconditions held for the whole session: MODE_NORMAL, battery 72–74% (not
charging), thermal status 0 before AND after, low_power 0, 5 ORT threads,
XNNPACK main+flow. Note the device had been wiped since 09-12 (both marmalade
TTS apps uninstalled); the debug app was reinstalled from `e90e823` and the
v21 Pocket bundle side-loaded byte-identical (sha256 verified). Full log:
`~/coding/scratch/mtts-device-backups/warm-2026-09-19.log`.

3-sentence §1.3 text, RUN 1 cold, RUNs 2–3 same process, 60 s apart:

| run | chunk | frames | prefill p1 (voice) | prefill p2 (text) | ar= | ms/frame (ar−prefill)/frames | decode | TTFA |
|---|---|---|---|---|---|---|---|---|
| 1 cold | 0 | 25 | (log evicted) | — | 2572 | — | 1165 | 7882 (load 1847) |
| 1 cold | 1 | 26 | 292 | 95 | 2536 | 82.7 | 1354 | |
| 1 cold | 2 | 38 | 312 | 94 | 3251 | 74.9 | 1296 | |
| 2 warm | 0 | 26 | 249 | 71 | 2489 | 83.4 | 1129 | 4138 |
| 2 warm | 1 | 24 | 313 | 75 | 2449 | 85.9 | 1097 | |
| 2 warm | 2 | 35 | 308 | 97 | 3022 | 74.8 | 1290 | |
| 3 warm | 0 | 32 | 272 | 72 | 2884 | 78.1 | 1195 | 4582 |
| 3 warm | 1 | 25 | 325 | 86 | 2543 | 85.3 | 1115 | |
| 3 warm | 2 | 37 | 321 | 99 | 3210 | 75.4 | 1383 | |

Readings:

- **Warm full AR step ≈ 75–86 ms/frame at 5 threads** — right at the 80 ms
  frame budget, consistent with K=1 holding (trimmed-max of early frames
  52–57 ms; early frames are faster than late ones as the KV grows).
- **Cold ≈ warm.** Run 1's `ar=` matches runs 2–3 within noise, and warmup
  synth absorbs the first-chunk penalty. The §1.2 "cold penalty dominates"
  inference came from a throttled 2%-battery phone and is retired.
- **Per-chunk prefill measured directly: phase 1 (voice cond) 249–325 ms,
  phase 2 (text cond) 71–99 ms** (text_conditioner itself ≤1 ms). Phase 1 =
  9.4–12.8% of the chunk's `ar=` → under the §5 15% gate. P-AM.2 would save
  ~0.3 s/chunk at medium risk; not worth it while the AR loop costs 2.4–3.2 s.
- Warm TTFA 4.1–4.6 s (K=1) — the honest "preparing…" number for E-proposal 4.
- eosFired=true on every chunk; frames well under maxFrames; peakLatentAbs
  5.7–6.5, no non-finite values.

### 1.4 The one WARM per-graph number already on record for this device

From `docs/HARDWARE-ACCELERATION-2026-07.md` (PocketQuantBench, Pixel 8a,
2026-08-04, 80 AR steps after 5 warmup steps, `flow_lm_main` in isolation —
no flow_lm_flow, no mimi, no audio plumbing; device was memory-tight):

| variant | run 1 median | run 2 median |
|---|---|---|
| int8dyn CPU EP (shipping) | **39.9 ms** | 53.4 ms |
| int8dyn + XNNPACK | 42.6 ms | 43.3 ms |
| fp32 CPU EP | 80.7 ms | 60.7 ms |

So: **warm `flow_lm_main` alone is ~40–53 ms** against an 80 ms frame budget,
while the cold full-loop fit above implies 106–129 ms per whole AR step. The
gap (≈ 55–90 ms) is `flow_lm_flow` + the Java state plumbing + cold effects,
and **which of those three dominates is exactly the unanswered question.**
The 80–99 ms/frame figures quoted in `PocketEngine.doLoad`'s comment block
are a different (older) calibration again — do not treat them as current.

---

## 2. Where the time goes, per graph

Structural, from the code and the bundle manifest. Sizes are exact.

### flow_lm_main — the AR step (dominant)

18 state slots. **6 × `[2,1,1000,16,64]` fp32 KV caches = 49.15 MB**, plus 12
small trackers. Every one of those 6 caches is a full model **output on every
AR frame**, and `updateStatesFromResult` memcpys each one back into its
persistent input buffer (`PocketStateManager.kt`). Per AR frame, before a
single matmul:

- ORT reads 49 MB of cache as input
- ORT materialises 49 MB of cache as output
- Java copies 49 MB output → input buffers

**≥ 147 MB of memory traffic per AR frame.** At a realistic phone memcpy
bandwidth of 10–15 GB/s that is a **~10–15 ms/frame floor** of pure state
plumbing — 12–19% of the 80 ms budget spent moving bytes.

And most of it is moving nothing: the cache is exported at **1000 positions**,
but a chunk can only ever use `1 BOS + 51 voice frames + ≤50 text tokens +
AR frames`. With `estimateMaxFrames = ceil((tokens/3 + 2) × 12.5)` and typical
40–60 generated frames, **under 15% of those 1000 positions ever hold real
data.** The other 85%+ is NaN padding being copied 12.5 times a second.

### flow_lm_flow — the Euler step

One call per AR frame (`LSD_DECODE_STEPS = 1`, upstream default, correct).
39 MB fp32 graph, `latent_dim` 32, running on **2 threads** (P-Q's
`max(intraOp/2, 2)`). Never measured in isolation on device. It is on the
serial critical path of every frame, so even its dispatch/wake cost is
multiplied by 12.5/s.

### mimi_decoder

56 state slots, **16.49 MB** (4 × `[2,1,8,1000,64]` fp32 + 52 small). The
P-AL schedule per 64-frame batch: snapshot 16.49 MB → 8 single-frame runs →
restore 16.49 MB → 1 batch-64 run. So **9 ORT runs and 33 MB of
snapshot/restore per 64 frames**, plus each single-frame run pays the full
16.49 MB state in/out. Cold single-frame run measured **~111 ms** (§1.2) —
which, if it holds warm, makes the 8-frame lead-in alone ~0.9 s per 64 frames.
This is the number most worth re-measuring warm; it may be almost entirely
cold-page-in.

### text_conditioner / mimi_encoder

`text_conditioner` is one embedding-lookup call per chunk — negligible.
`mimi_encoder` only runs on a voice-embedding cache miss (in-memory +
`voice_cache/` on disk), so it is off the steady-state path entirely.

### Warm RTF on the AR loop — best available estimate

Cannot be stated as measured. Bracketing: warm `flow_lm_main` 40–53 ms
(measured, isolated) + state plumbing floor ~10–15 ms (computed) +
`flow_lm_flow` (unmeasured) ⇒ **warm AR is plausibly 60–90 ms/frame,
RTF ≈ 0.75–1.1**, i.e. right on the 80 ms budget, consistent with the
backlog's "borderline 1.0× RTF" framing — *not* the 2.2–2.75 the cold run
showed. **The cold penalty looks like the dominant term in tonight's cold
numbers, but that is an inference and §1.3 must be run to confirm it.**

---

## 3. Session-options review

Against `buildPocketSessionOptions` (`PocketEngine.kt:401-449`), the ORT
footgun notes, and ORT's own Android guidance. **improvable** = a config-level
A/B; **architectural** = model shape or runtime choice, not a knob.

| # | item | state | verdict |
|---|---|---|---|
| 1 | **intra-op thread count** | autodetect gives **5** on this device. The in-code calibration table says intra-op=6 → 80 ms/frame, 4 → 99, 1 → 155. The shipping default is therefore **not the value the comment calls optimal**, and 5 was never on the calibration table. | **improvable.** Expected gain: unknown, bounded by the 80↔99 ms spread ≈ up to 19%. Risk: none (Settings → Performance already exposes it). Verify: 3-sentence warm run at 4/5/6/7, compare `ar=`÷`frames=`. Cheapest experiment in this table. |
| 2 | **flow_lm_flow thread count = 2** | `max(intraOp/2, 2)` — on this device that's exactly 2. P-Q's reasoning (don't wake the whole pool for a small matmul) is sound but the value was never swept. | **improvable.** Expected gain: small but it is on every frame's critical path. Risk: low. Verify: same A/B, vary the divisor. |
| 3 | **`session.intra_op.allow_spinning = 0`** | Correct per ORT's own diagnostic warning when XNNPACK's second pthread pool exists. But it means every one of the ~25 `session.run` calls per second pays a thread wake instead of finding a hot spinner. | **improvable — worth exactly one A/B.** The original finding was made under different conditions (before P-Q split the thread budget). Expected gain: could go either way, ±5–10%. Risk: low, it is a one-line const. Verify: warm `ar=` A/B, and watch logcat for ORT's contention warning returning. |
| 4 | **`allow_spinning` is set inside the XNNPACK `try` block** | If `addXnnpack` ever threw, the config entry would never be applied — the CPU-EP fallback session would silently get spinning *enabled*. Cosmetic today (XNNPACK is present on this build), but it couples two unrelated settings. | **improvable (hygiene).** Move `addConfigEntry` outside the try. Zero perf risk. |
| 5 | **`setMemoryPatternOptimization(true)`** | Load-bearing. The bisect record says disabling it "tanks RTF significantly", and its interaction with pinned outputs is the documented reason P-V is off. | **Leave alone.** Not a candidate. |
| 6 | **`OptLevel.ALL_OPT`** | Correct, standard. | **Leave alone.** |
| 7 | **XNNPACK on `flow_lm_main`** | Measured a wash on 2026-08-04 (42.6/43.3 vs 39.9/53.4). `MatMulInteger` falls back to the CPU EP anyway, so XNNPACK contributes a thread pool and no kernels for the dominant graph. | **improvable.** Drop XNNPACK for `flow_lm_main` only, keep it on the fp32 graphs (`mimi_decoder`, `flow_lm_flow`, `text_conditioner`) where it has real kernels. P-Q already factored per-session options, so this is a small, contained change. Expected gain: removes one pool's wake cost per frame; small. Risk: low. Verify: PocketQuantBench (already benches both) + warm `ar=`. |
| 8 | **No arena / allocator config anywhere** | ORT's default CPU arena is used. With a 49 MB output tensor allocated every frame, arena extend strategy and `kOrtSessionOptionsConfigUseDeviceAllocatorForInitializers` are unexplored. | **improvable but speculative.** Only worth trying after §5 lands, since §5 may remove most of the per-frame allocation pressure. Verify: `ar=` plus app RSS via `dumpsys meminfo`. |
| 9 | **`FLOW_MAIN_PINNED_OUTPUTS = false`** | Would remove the 49 MB/frame Java memcpy quantified in §2. Reverted because pinning + memory-pattern optimization + per-chunk-fresh flow_lm buffers = stale memory plan at chunk boundaries. | **architectural until PERF-IDEAS item 1 lands** — exactly as the backlog's item 5 says. Stable engine-level buffers are the precondition. |
| 10 | **KV cache exported at 1000 positions** | §2: >85% of the 49 MB/frame is padding. | **architectural** — needs an ONNX re-export, not a session option. But see §4(c): this is the highest-value re-export on the table. |
| 11 | **12.5 Hz serial autoregressive loop, 24 kHz non-window-invariant mimi decode** | The frame loop cannot be batched (each frame feeds the next), and the mimi graph's lack of window invariance is what forces P-AL's 8-frame per-frame lead-in. | **architectural.** Accept. |

### Footgun compliance — clean

Checked against the two documented ORT-Android constraints:

- **Input tensor invalidation on `Result.close()`** — `runFlowLmMainWithTensors`
  and `decodeMimiChunk` both create input tensors per call and close them in
  `finally` *after* the result; state is read out of the result *before*
  `result.close()`. No tensor is carried across a `run()`. Compliant.
- **`pinnedOutputs` must make `requested.size + pinned.size == model.outputs`
  with the two sets disjoint** — `introspectFlowMainOutputs` reads
  `session.outputNames`, filters the manifest to outputs that both exist and
  have fully-fixed shapes, and precomputes the disjoint complement. Correctly
  implemented; currently dormant behind item 9.

No violations found.

### Instrumentation gap (blocks §5 step 1)

`startArSession` takes a `phases: MutableList<PhaseSpan>?` and populates
`"flow-lm phase 1 (voice cond)"` and `"flow-lm phase 2 (text cond)"`
(`PocketEngine.kt:1436, 1455`) — but **both call sites pass `phases = null`**
(`:475` batch, `:850` streaming). The per-chunk prefill timings the whole of
§1.2's derivation hangs on are therefore never recorded. One line each fixes it.

---

## 4. PERF-IDEAS backlog triage

### (a) Already done — do not re-suggest

XNNPACK EP, `ALL_OPT`, spinning disabled, `CpuClusterDetector` autodetect +
Settings override, voice-embedding in-memory + disk caches, async warmup
synth, mmap'd models, P-AL segmented overlap-discard mimi decode,
`LSD_DECODE_STEPS = 1`, sentence-boundary chunking, engine-level mimi state
(the mimi half of P-Y), P-Q per-session thread split.

**Item 4 (int8 dynamic quant) is CLOSED** — benched on this device
2026-08-04, static QDQ never faster, decision is keep dynamic int8. Out of
scope tonight and permanently settled.

### (b) Still open, and now measurably worth it

- **Item 1 — voice-conditioning KV snapshot cache. CONFIRMED NOT
  IMPLEMENTED.** `startArSession` re-runs phase 1 (voice conditioning, a
  52-frame prefill through `flow_lm_main`) and phase 2 (text conditioning) on
  **every chunk** (`PocketEngine.kt:1410-1460`); there is no snapshot, no
  cache, no reuse. The §1.2 fit puts the combined per-chunk prefill at
  2.3–2.9 s cold. Crucially, **the machinery already exists**:
  `snapshotStates` / `restoreStates` / `PocketStatesSnapshot` are implemented
  in `PocketStateManager.kt` and used every batch by P-AL for the mimi state.
  This is reuse, not new infrastructure. Still the best effort:payoff item.
- **Item 5 — re-attempt P-V/P-Y/P-Z as one unit.** Still open, still correctly
  gated on item 1 (stable engine-level flow_lm buffers are the precondition
  for a valid memory plan under pinning). The prize is now quantified: the
  49 MB/frame Java memcpy from §2.
- **Item 2 — pipeline mimi decode during the AR loop.** Still open. The
  backlog's own caution stands and is reinforced by §1.1: with only 5 perf
  cores and `flow_lm_main` already at 5 threads, there is no spare cluster to
  hand a decoder thread. Lower priority than 1 and 5.

### (c) Not worth it / superseded

- **Item 3 — fp16 KV caches.** Needs an ONNX re-export. If a re-export ever
  happens, **shrinking the cache from 1000 positions to ~192–256 is strictly
  the better first move**: it cuts the same memory traffic by ~4–5× (vs fp16's
  2×), costs no numerical precision, and the two stack. Item 3 should be
  re-ranked below a cache-length re-export.
- **Item 4 — closed** (see above).
- **Item 6 — ExecuTorch.** Parked per project memory; ORT-CPU is the shipping
  stack.

### (d) New, not in the backlog

- **KV cache length re-export (1000 → 256).** Quantified in §2. Architectural
  (bundle rev + re-export + voice_cache invalidation), but it is the single
  largest identified waste in the AR loop and it is a pure-win change — no
  quantization, no precision loss, no quality risk. Worth a line in the
  backlog even though it is not this pass's recommendation.
- **Instrumentation:** `PhaseSpan` plumbing is dead at both call sites (§3).

---

## 5. Recommended next experiment — ONE lettered unit

### P-AM — measure, then eliminate, the per-chunk voice-conditioning prefill

**2026-09-19 OUTCOME: P-AM.1 shipped (`e90e823`); P-AM.2 gate FAILED — do not
build it.** Measured phase-1 share of warm `ar=` is 9.4–12.8% (§1.3a), under
the 15% threshold below. The AR loop itself (75–86 ms/frame × 24–37 frames)
is where the time goes; the next-best levers are §4(b) item 5 / §4(d) KV
cache-length re-export, not the voice snapshot.

Two steps; **step 1 gates step 2** and step 1 is worth landing on its own.

**P-AM.1 — instrument (no behaviour change).**
Pass a real `MutableList<PhaseSpan>` at `PocketEngine.kt:850` (the streaming
`generateChunkLatents` path; optionally `:475` too) and `Log.d(PERF_TAG, …)`
each span after `startArSession` returns.

*Acceptance:* a warm 3-sentence run logs, for every chunk, `phase 1 (voice
cond)=… ms` and `phase 2 (text cond)=… ms (incl. text_conditioner … ms)`
alongside the existing `ar=` and `Pocket chunk N: frames=X/Y`. This alone
converts §1.2's two-point inference into a direct measurement and tells us
whether the AR loop or the prefill is the real problem.

**P-AM.2 — cache (only if P-AM.1 shows phase 1 ≥ 15% of a warm chunk's `ar=`).**
After phase 1 in `startArSession`, `snapshotStates(state)` into a snapshot
keyed by `voiceId`. On every later chunk with the same voice, `initStates` +
`restoreStates(snapshot)` instead of re-running phase 1. Reuse the existing
P-AL snapshot/restore path verbatim. **In-memory only — no disk cache in this
unit.** Gate the whole thing behind a `const val FLOW_MAIN_VOICE_SNAPSHOT` so
it can be A/B'd and killed without a revert.

*Acceptance measurement:* warm 3-sentence utterance, identical text / voice /
thread count, before vs after, run 3 of 3 in the same process —
1. chunk ≥ 1 `ar=` drops by the phase-1 time measured in P-AM.1, ±20%;
2. `frames=X/Y` and `eosFired@N` unchanged for every chunk;
3. bit-equivalence check: with the noise source temporarily seeded, the first
   AR frame's latent from the restored path must match the recompute path;
4. Max ear-checks chunk ≥ 1 audio for the chunk-start artefact class that
   P-AL owns.

*Risk: medium.* The P-Y bisect showed flow_lm state reuse across chunks
producing audio glitches — but that was reusing **live buffers** across
`session.run` calls. A restore **copies into fresh buffers**, which is
precisely why the backlog calls this out as sidestepping the P-Y failure mode.
Acceptance criteria 3 and 4 exist to prove that distinction holds on device.

*Non-negotiable prerequisite:* **the warm baseline from §1.3 must be captured
first**, on a charged idle phone. Without it there is nothing to measure the
delta against, and tonight's cold numbers are not a valid baseline.

---

## Appendix — what was NOT done tonight

- No code changed in the app. No gradle run. No `connectedAndroidTest`.
- No synthesis triggered, no audio played, no screen touched on the device.
- Read-only device access only: `dumpsys`, `/proc`, `/sys`, and
  `run-as app.marmalade.tts.debug cat` of `bundle.json` + a directory listing.
- The debug app was force-stopped at 19:49 so nothing is left queued.
- `app.marmalade.tts` (Max's daily app) was never touched.
