# Android Hardware Acceleration — Assessment (2026-07-31)

Question: can marmalade-tts-android get meaningful speedups from GPU/NPU acceleration,
and what runtime strategy supports all devices? Baseline for every comparison is the
**current shipping stack: onnxruntime-android CPU with our optimizations** (6-perf-core
thread pinning, no-spin, selective int8 flow_lm for Pocket, streaming mimi state,
P-AL overlap-discard).

Compiled from a 3-agent research sweep (2026-07-30) + a focused Opus research pass +
local model inspection + the ExecuTorch project memory. Sources at bottom.

## TL;DR

- **NNAPI is deprecated (Android 15) but not removed** — it still works; the real story
  is vendor drivers rotting into CPU fallback. Its deprecation costs us nothing we had.
- **No acceleration path offers a credible speedup for our current engines.** Estimates
  below are mostly ≈1× or *worse* than our tuned CPU baseline. The structural blockers:
  LSTMs (Kokoro/Kitten), batch-1 autoregressive decode (Pocket), small-graph dispatch
  overhead, and fp16-by-default GPU paths vs our fp32 quality requirement.
- **One runtime already covers all devices: ORT CPU.** If we ever add acceleration, it
  can stay one runtime (ORT + QNN plugin EP for Snapdragon NPUs, or ExecuTorch with
  multiple backends). LiteRT would mean a second runtime AND a blocked model conversion.
- **The highest-value lever isn't acceleration at all — it's selective quantization on
  the CPU path we already ship** (per-node sensitivity sweep, keep vocoder/output layers
  fp32). Two of our models already do this upstream.

## Model inventory (measured 2026-07-31, local ONNX inspection)

| Model | Size | Weights | Recurrent ops | Notes |
|---|---|---|---|---|
| Kokoro v1.0 | 310 MB | pure fp32 | **6 LSTM** | StyleTTS2-derived; iSTFT vocoder |
| Kitten nano v0.8 | 54 MB | fp32 | **5 LSTM** | |
| Kitten mini v0.8 | 74 MB | fp32 + fp16 + int8/uint8 | none | **already selectively quantized upstream** |
| Pocket flow_lm_main | 72 MB | fp32 + int8 (52 tensors) | none | **already selectively quantized** (upstream recipe: attention/FFN linears only) |
| Pocket mimi_decoder | 39 MB | fp32 | none | transposed-conv heavy |
| Pocket flow_lm_flow / encoder / text_cond | 15–37 MB | fp32 | none | |

Everything quality-critical runs fp32 today; where int8 exists it was applied
*selectively* upstream, which is exactly the technique that preserves audio quality.

## Estimated speedups vs our ORT-CPU baseline

These are **estimates**, not measurements — marked ✗ where the path is blocked outright.
"Modern Snapdragon" = 8 Gen 3 / 8 Elite class (Adreno GPU + Hexagon NPU).

| Path | Pixel 8a (Tensor G3, Mali-G715) | Modern Snapdragon | Why |
|---|---|---|---|
| ORT NNAPI EP | ✗ ~0.5–1× | ~0.5–1.5×, fragile | drivers rot to CPU fallback; sherpa users hit hard op errors on Kokoro/Piper (`GATHER failed`) |
| ORT QNN EP (Hexagon NPU) | ✗ N/A (Snapdragon-only) | ~1–3× on delegable subgraphs *only* | LSTM graphs fragment; batch-1 AR loops dispatch-bound; needs quant + mostly static shapes |
| ExecuTorch XNNPACK (CPU) | ~0.9–1.1× | ~0.9–1.1× | parity-class; and ET 1.2.0 AAR can't pin threads (our ORT pins 6 perf cores, a ~20% lever ET loses) |
| ExecuTorch Vulkan (GPU) | ~0.1–1× — likely a big LOSS | similar | **measured in the wild: NekoSpeak ran Pocket on ET Vulkan at 47s vs 5s CPU and reverted** (ADR-004); LSTM ops not delegable at all |
| LiteRT GPU delegate / ML Drift | ✗ for Kokoro/Kitten; ~1–2× for mimi decoder *if converted* | same | GPU delegate has **no LSTM support** (hard errors, not fallback, in some cases); small-graph dispatch overhead unchanged in kind (ML Drift ≈1.4× over old delegate) |
| LiteRT NPU — Google Tensor | ✗ **unavailable: Tensor SDK supports G5 only**, Pixel 8a is G3 | — | also beta, AOT-only, sign-up-gated, currently has an open "AOT path unusable" bug |
| LiteRT NPU — Qualcomm | ✗ N/A | ~2–5× *if* a model fully compiles | marketing claims up to 100×; realistic only for static conv/transformer graphs; our LSTM + custom-iSTFT + AR-loop graphs are unlikely to compile cleanly |
| Selective int8 on CPU (ORT QDQ) | size 3–4× smaller; speed **~0.5–1.3×, per-model** | same, slightly better (stronger i8mm/SME) | naive int8 Kokoro measured **2× slower on iPhone, 1.7× slower on Pi** — but Pocket's int8 flow_lm *wins* (bandwidth-bound M=1 AR). Never assume; measure per model |

Reality check on the baseline: our engines are already real-time on CPU on the Pixel 8a
except Pocket's AR loop — and batch-1 autoregressive decode is the single worst shape
for GPU/NPU offload (thousands of tiny dispatches; per-call overhead dominates). Kyutai,
NekoSpeak, the Unity port, and the Core ML port all converged on CPU for Pocket.

On a modern Snapdragon the CPU itself is ~1.5–2× faster than Tensor G3, which delivers
more real-world speedup than any of the acceleration rows above — for zero engineering.

## TFLite/LiteRT conversion feasibility (Kokoro / Kitten / Pocket)

**Does one already exist? Effectively no.**

- **Kokoro:** one experimental LiteRT preview exists
  (`wdga/kokoro-82m-litert-runtime-preview` on HF): fp32, split into two graphs, needs a
  **compiled custom op** (`KokoroSourceStft`) with prebuilt binaries for Linux/Jetson
  only — no Android build — and is locked to a static 48-token bucket. Proof of
  possibility, not a shippable artifact.
- **Kitten:** nothing. Ecosystem is ONNX-only.
- **Pocket/mimi:** nothing in TFLite. Ports are ONNX (what we ship), Candle/WASM, MLX.

**Could we convert ourselves? Kokoro/Kitten: this is a rewrite-the-model project, not a
conversion project.**

- **LSTM is the structural blocker on every route:** `torch.export` (the basis of
  Google's `litert-torch`, ex ai-edge-torch) does not support LSTM export; `onnx2tf`
  handles only constrained LSTM patterns; and even a successfully converted
  `UNIDIRECTIONAL_SEQUENCE_LSTM` is unsupported by the GPU delegate anyway.
- **iSTFT vocoder:** no clean path — either a hand-written complex-free STFT layer
  (Lyjak's ONNX approach) or a custom TFLite op you compile per-ABI (the preview
  repo's approach). Either way we own a custom op forever.
- **Dynamic sequence lengths:** chronic onnx2tf weakness (dynamic dims silently become
  static); the existing Kokoro preview's fixed token bucket is the visible symptom.
- **Pocket** is the most convertible architecture (transformer AR — exactly what
  litert-torch's Generative API targets), but conversion wouldn't fix its actual
  bottleneck: T-Mimi (ICASSP 2026) showed mimi's transposed-conv decoder is the mobile
  latency problem and fixed it by *replacing the decoder architecture* (42→4 ms/frame,
  on CPU). A format change doesn't touch that.

**Does the NPU path amortize the dispatch overhead?** Partially, and not for us. LiteRT
Next has real mechanisms — zero-copy AHardwareBuffer tensors, async events, compilation
caching (7.4s→198ms *init*, not per-inference) — but there are **no published
per-inference dispatch numbers for <100M-param models**, Google's own guidance doesn't
claim AOT helps small-model latency, and none of it is reachable on our G3 hardware.

## Runtime strategy: can one runtime cover all devices?

**Yes — and it already does.** ORT CPU (XNNPACK-class kernels + our tuning) runs
real-time on 100% of Android devices with one artifact set. That is also what the
open-source LLM apps (ChatterUI, PocketPal) ship, on all hardware including flagships.

If we ever want acceleration, the options in order of added complexity:

1. **ORT + QNN plugin EP** (`onnxruntime-android-qnn`, Maven) — still ONE runtime; adds
   Hexagon NPU on Snapdragon only; Pixel and others keep the identical CPU path.
   Smallest possible delta, but per-device delegation behavior must be measured.
2. **ExecuTorch** — one runtime, multiple backends (XNNPACK + Vulkan + QNN). See below.
3. **LiteRT** — the only stack with a first-party Mali GPU engine and (on G5+ Pixels) a
   Tensor-NPU door. But it's a *second* runtime, requires model conversions that are
   blocked for Kokoro/Kitten, and duplicates every engine artifact. Not justified.

**Recommendation: stay single-runtime ORT CPU.** Revisit only if a future engine is
genuinely RTF-bound on flagship CPUs, and then reach for QNN EP first.

## The actually-promising lever: selective ("intelligent") quantization on CPU

Uniform int8 is what produced the tinny sherpa Kokoro and the muffled ET-Pocket —
*selective* quantization is a different animal, and it's already how Kitten mini and
Pocket's flow_lm are built upstream. The transferable recipe (Lyjak, on Kokoro
specifically; independently corroborated by T-Mimi):

1. Per-node sensitivity sweep: quantize one node at a time, score with a
   **mel-spectrogram loss** vs fp32 output, rank.
2. Exclude sensitive nodes via ORT's `nodes_to_exclude` (regex-capable). Vocoder /
   output-adjacent layers and final transformer blocks reliably must stay fp32.
3. Use `QuantFormat.QDQ` + ARM64 targeting (ORT's CPU kernels are optimized for QDQ;
   wrong format is the classic "int8 slower than fp32" cause).
4. Measure speed AND quality per model on-device. Expected outcome per model class:
   - **Kokoro/Kitten nano:** size win near-certain (310→~100 MB class); speed win
     uncertain (naive Kokoro int8 measured *slower* on ARM; QDQ+i8mm may flip that).
   - **Pocket:** already done upstream; verify our recipe matches upstream's
     (memory notes flagged this as an open check).

The i8mm caveat: the "int8 up to 20–30% faster" results come from XNNPACK/KleidiAI
kernels; ORT's default CPU int8 path doesn't automatically get them, and ORT's XNNPACK
EP is float-oriented. Speed claims must be verified on our actual stack.

### Measured on-device (Pixel 8a, 2026-08-01 — debug Benchmark screen "Kokoro quant bench")

onnx-community pre-quantized Kokoro variants vs our shipping fp32 export, identical
token inputs, engine's own ORT config (XNNPACK EP, perf-core pinning). Max quality-
approved ALL variants by ear on desktop first (zero audible distinction). Mean RTF
across 4 texts, single cool-phone run (thermal variance across runs is large — fp32
baseline measured 1.24 hot vs 0.71 cool; only within-run comparisons are valid):

| Variant | Size | Mean RTF | vs baseline |
|---|---|---|---|
| fp32 (our export, baseline) | 310 MB | **0.705** | 1.00× — fastest |
| uint8f16 | 109 MB | 0.831 | 1.18× slower, 2.9× smaller |
| fp16 | 163 MB | ~1.06× slower (prior hot run, normalized) | |
| fp32 onnx-community export | 326 MB | ~1.28× slower (prior hot run, normalized) | our export is genuinely faster |
| int8 uniform ("quantized") | 88 MB | 1.098 | 1.56× slower |
| q8f16 | 86 MB | DNF — ~10× slower class, first text alone took 7+ min | disqualified |

**UPDATE (later 2026-08-01): our OWN quantization beat this table.** Selective
static QDQ int8 on the fast graph (per-channel, 118-node mel-loss-sweep
exclusion list, `scratch/kokoro-quant-experiments/REPORT.md`) measured **mean
RTF 0.77 vs fp32 1.01 same-run on the Pixel 8a (24–36% faster) at 150 MB**,
ear-verified lossless — ships as the v23 bundle. Two constraints discovered:
XNNPACK EP + QDQ graph = native SIGSEGV on ORT-Android 1.26 (engine now skips
XNNPACK for marked quantized bundles), and desktop pre-fusing the QDQ graph
*hurts* on ARM (0.80) — ship the raw QDQ graph. The pre-made-variant
conclusions below still stand for the onnx-community artifacts:

Conclusions on the pre-made variants: **no PRE-MADE quantized variant is faster on ARM** — the research prediction held
(ORT's ConvInteger/MatMulInteger path doesn't hit i8mm kernels), so "smaller = faster
from bandwidth" is refuted for this stack. fp32 stays the speed champion and remains
the shipping default. **uint8f16 is the one interesting trade**: 2.9× smaller download
at an 18% speed cost while still under realtime — a candidate for a low-storage option,
not a replacement. The desktop q8f16 ORT-optimizer segfault did not reproduce on
ORT-Android 1.26 (it ran — just absurdly slowly).

## ExecuTorch: parked, revisit on a development branch

Status per the 2026-06 investigation (full detail in project memory): Pocket-on-ET
reached end-to-end audio on the Pixel 8a but lost to shipping ORT on memory (3.9 GB
arenas vs ~266 MB), quality (PT2E int8 muffled where ORT int8 is fine), and tooling
(fp16 lowering broken in ET 1.2.0; prebuilt AAR can't pin threads). Ecosystem consensus
was ORT-CPU; NekoSpeak tried and reverted ET.

**Decision (Max, 2026-07-31): revisit in the future, on a development branch only.**
What would make a revisit worthwhile: ET fixes fp16 lowering; AAR exposes thread
control; or targeting the *stateless small* models (Kokoro/Kitten) rather than Pocket,
where the 2026-06 assessment said the bet may still fit. The export pipeline
(`tools/executorch-export/`) and bench harness (`PocketExecuTorchBenchTest.kt`) exist
and de-risk the restart.

## Future watch list

- **LiteRT NPU on Google Tensor** maturing past beta *and* past G5-only — the first
  real third-party door to Pixel TPUs. Re-check yearly.
- **Unified-memory zero-copy** (AHardwareBuffer tensors in LiteRT Next; Vulkan
  device-local host-visible memory on SoCs) — removes the transfer half of the
  small-model GPU penalty, but not the dispatch half. Not actionable today.
- **T-Mimi-style decoder replacement** for Pocket — an architecture fix, not a runtime
  fix; would need upstream (Kyutai) adoption or a retrain.
- **ET fp16 lowering fix** — the original reason ET was chosen; broken in 1.2.0.

## Sources

NNAPI migration guide: <https://developer.android.com/ndk/guides/neuralnetworks/migration-guide> ·
LiteRT NPU: <https://developers.google.com/edge/litert/next/npu> ·
Tensor SDK (G5-only): <https://developers.google.com/edge/litert/next/tensor-sdk> ·
ExecuTorch Vulkan: <https://docs.pytorch.org/executorch/stable/backends-vulkan.html> ·
ORT quantization: <https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html> ·
ORT QNN EP: <https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html> ·
Kokoro LiteRT preview: <https://huggingface.co/wdga/kokoro-82m-litert-runtime-preview> ·
Kokoro quant sensitivity method: <https://www.adrianlyjak.com/p/onnx/> ·
Kokoro int8 2× slower on iPhone: <https://github.com/k2-fsa/sherpa-onnx/issues/2374> ·
T-Mimi: <https://arxiv.org/abs/2601.20094> ·
TuneQn (auto layer-exclusion): <https://arxiv.org/html/2507.12196v1> ·
KleidiAI i8mm in XNNPACK: <https://developer.arm.com/community/arm-community-blogs/b/ai-blog/posts/arm-kleidiai-in-xnnpack> ·
GPU delegate LSTM unsupported: <https://github.com/tensorflow/tensorflow/issues/56482> ·
NekoSpeak (ET tried + reverted): <https://github.com/siva-sub/NekoSpeak> ·
llama.cpp Vulkan-on-Mali: <https://github.com/ggml-org/llama.cpp/discussions/9464> ·
MLC Mali gap: <https://arxiv.org/html/2410.03613v3>
