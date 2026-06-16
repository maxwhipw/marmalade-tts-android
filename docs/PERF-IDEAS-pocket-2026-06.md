# Engine speedup backlog — Pocket (and Kitten/Kokoro micro-wins)

**Status: parked — revisit after store beta launch.**

Captured 2026-06-11 from a two-track audit: (1) a read-only pass over the three
production engines in this repo, (2) a survey of community Pocket-TTS ports linked
from the Kyutai repo. Nothing here is implemented; this is a ranked backlog.

## TL;DR

Pocket is the only engine with a real speed problem. On the Pixel 8a (Tensor G3)
it runs ~80 ms/AR-frame against an 80 ms real-time budget (12.5 fps) — borderline
1.0× RTF, which is why chunk-gap underruns remain open. Kitten and Kokoro are
single-graph and already comfortably faster than real time; only micro-wins remain.

Already well-tuned (do NOT re-suggest): XNNPACK EP, `ALL_OPT`, intra-op spinning
disabled, perf-core thread autodetect (`CpuClusterDetector`), voice-embedding
in-memory + disk caches, async warmup synth, mmap'd models (arm64 + Kokoro
voices.bin), P-AL segmented overlap-discard mimi decode, `LSD_DECODE_STEPS=1`
(upstream default), sentence-boundary chunking.

## Reference implementation

**`github.com/VolgaGerm/PocketTTS.cpp`** is the gold-standard reference: same 5
ONNX graphs, same ORT runtime, CPU-first, claims 9.2× RTF + 30 ms TTFA on a
desktop Ryzen. Verified by reading its `pocket_tts.cpp` + `export_onnx.py`.
**Check its license before porting any code** — reading for understanding is fine,
copying needs Max's clearance (esp. anything GPL/LGPL).

Other ports surveyed: official `kyutai-labs/pocket-tts` (Python),
`k2-fsa/sherpa-onnx` (C++, no pipelining), `pocket-tts-mlx` (Apple only). The
`KevinAHM/pocket-tts-onnx` export the Android port drew from is now 404 on GitHub.

## Ranked candidates (Pocket)

### 1. Voice-conditioning KV-state snapshot cache — best effort:payoff
Today `startArSession` re-runs the voice-conditioning pass through `flow_lm_main`
for **every chunk**. PocketTTS.cpp runs it once, snapshots the resulting KV-cache
state, and memcpy-restores per utterance (~1 ms restore vs hundreds of ms
recompute; also disk-caches the blob across restarts). Restore copies into fresh
buffers, so it sidesteps the buffer-identity issue that forced the P-Y revert.
Directly attacks per-chunk overhead and chunk-gap underruns.
Evidence: `PocketEngine.kt` `startArSession`; PocketTTS.cpp 3-tier cache (verified).

### 2. Stream mimi decode during the AR loop (frame-queue pipeline)
Each chunk currently runs AR to completion, then decodes; decode only overlaps the
*next* chunk's AR when K>1. PocketTTS.cpp pipelines AR + decoder on separate
threads via a frame queue (`first_chunk_frames=1` → audio after frame 1 → 30 ms
TTFA). Two port-specific cautions:
- Preserve the P-AL overlap-discard discipline — the decoder must consume the
  queue in the same 8-per-frame + 64-batch pattern.
- Thread-splitting is riskier on Tensor G3 than on desktop: AR at 4 threads is
  already 99 ms/frame (over budget). Measure the split; don't copy their `total/2`.

### 3. fp16 KV caches in flow_lm_main — needs ONNX re-export
Store K/V caches as fp16, compute stays fp32. Halves KV memory traffic; the AR
loop at batch size 1 is likely bandwidth-bound. PocketTTS.cpp `export_onnx.py`
shows the recipe incl. a fixup for ORT's in-place-scatter limitation (relates to
our `ort_android_input_tensor_invalidation` constraint). License check applies.

### 4. Int8 dynamic quant (MatMul-only) of flow backbone — BENCHMARK, don't assume
Upstream PR #147's own ARM numbers contradict: **+23% RTF with torchao but −16%
with QNNPACK** at batch size 1. ORT-Android's int8 MatMul kernel is a third code
path — requires an on-device A/B. Quantize `flow_lm_main` + `flow_lm_flow` only;
keep `mimi_encoder`/`text_conditioner` fp32. NOTE: the local audit and
`REVIEW-2026-05.md` disagree on which graphs in the shipping v9 bundle are int8 vs
fp32 — pin down what's actually shipping before benchmarking against it.

### 5. Re-attempt reverted P-V / P-Y / P-Z as ONE unit
Pinned outputs (P-V) failed *because* flow_lm state reverted to per-chunk alloc
(P-Y) — ORT's cached memory plan held stale buffer addresses. If candidate 1 lands
(snapshot-restore into stable engine-level buffers), all three likely become safe
together: stable buffers → valid memory plan → pinning works → per-frame
`result.get` + memcpy for ~12 fixed-shape slots disappears.
Evidence: `PocketEngine.kt:2451` (`FLOW_MAIN_PINNED_OUTPUTS=false`), `:1534` (P-Y),
`:1617-1620` (P-Z), `PocketStateManager.kt:92-179`.

### 6. Strategic: ExecuTorch fp16 + KleidiAI (long-term, see REVIEW-2026-05)
Chosen long-term inference stack: fp16 SIMD on A715/X3, ~11 MB runtime vs ~80 MB
AAR. Candidates 1, 2, 5 are architectural and survive the runtime swap; 3 and 4
are ORT-specific and should be weighed against how soon ExecuTorch actually lands.

### Not applicable
- CFG elimination — Pocket has no unconditional pass.
- LSD steps — already 1 (upstream default). Open: confirm 1 step is perceptually
  clean now that P-AL owns the chunk-start glitch (`PocketEngine.kt:2467-2479`).

## Kitten / Kokoro micro-wins (low priority — not RTF-constrained)
- Kitten: per-chunk style-vector double-copy (`copyOfRange` + fresh direct buffer,
  `KittenDirectEngine.kt:458,516`). Kokoro already has the scratch pattern to copy.
- Kitten/Kokoro: per-chunk token-tensor `directLongTensor` alloc — cap+reuse a
  max-sized scratch (`KokoroDirectEngine.kt:474` already flags this as future work).
- Kokoro: only big lever is a smaller model variant, but known-good options are
  narrow (int8 multi-lang v1.0 banned/tinny; v1.1 drops English voices). fp16 via
  ExecuTorch is the realistic path — folds into candidate 6.

## Suggested order when resumed
Start with (1): biggest expected win, lowest quality risk, and it unblocks (5).
Then measure (2) on device. Treat (3)/(4) as ORT-specific bets contingent on the
ExecuTorch timeline.
