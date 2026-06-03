package app.marmalade.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketClonedVoice
import app.marmalade.tts.engine.pocket.PocketClonedVoiceStore
import app.marmalade.tts.engine.pocket.PocketClonedVoiceSummary
import app.marmalade.tts.engine.pocket.PocketStates
import app.marmalade.tts.engine.pocket.PocketTokenizer
import app.marmalade.tts.engine.pocket.bindStateInputs
import app.marmalade.tts.engine.pocket.enableStatePinning
import app.marmalade.tts.engine.pocket.initStates
import app.marmalade.tts.engine.pocket.resetStatesToInit
import app.marmalade.tts.engine.pocket.updateStatesFromResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pocket TTS engine — Kyutai Labs' Latent Space Diffusion TTS running
 * directly on `com.microsoft.onnxruntime:onnxruntime-android`.
 *
 * v0.3.0-alpha.2 lights up real synthesis with the full 5-graph LSD
 * pipeline:
 *
 *   text → tokenize → text_conditioner → text_embeddings [1,T,1024]
 *   voice WAV → resample 24kHz → mimi_encoder → voice_emb [1,V,1024]
 *               (cached to disk after first encode)
 *
 *   flow_lm phase 1 (voice cond):  sequence=[1,0,32]  +  text_embeds=BOS++voice_emb
 *   flow_lm phase 2 (text cond):   sequence=[1,0,32]  +  text_embeds=text_embeds
 *   flow_lm phase 3 (autoregressive, per latent frame):
 *      sequence=[1,1,32] (NaN→BOS first step, then previous latent)
 *      → conditioning [1,1024] + eos_logit [1,1]
 *      → Euler-integrated flow_lm_flow: x_{t+1} = x_t + flow(c,s,t,x)·(1/steps)
 *      → emit latent, check EOS
 *   mimi_decoder(all latents) → float PCM @ 24 kHz → PCM16
 *
 * Voice cloning (v0.3.0): users supply WAV; same `mimi_encoder` path,
 * with the resulting embedding written to `cloned_voices/<id>.bin`.
 *
 * Why a separate class (not a SherpaEngine subclass): different runtime
 * (ORT direct), different model topology (5 graphs vs 1 OfflineTts),
 * stateful streaming codec, voice cloning. The shared parent surface is
 * [TtsEngine] — same contract Synthesizer + the two TTS services route
 * through.
 *
 * Thread-safety: model loading is gated by a coroutine [Mutex]; synthesis
 * itself is gated by [Mutex.withLock] so concurrent `synthesize` calls
 * serialise (the model's KV cache state isn't reentrant).
 */
@Singleton
open class PocketEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settingsRepository: app.marmalade.tts.data.SettingsRepository,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME

    override val sampleRate: Int
        get() = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE

    /**
     * Pocket handles its own chunking internally via [chunkPocketByTokens],
     * which is sentence-aware and token-aware (vs the Synthesizer's outer
     * char-based [app.marmalade.tts.audio.TextChunker], which would have
     * to guess at tokenizer behavior). We advertise no limit so the
     * Synthesizer hands us the full input string in one call.
     */
    override val maxInputChars: Int = Int.MAX_VALUE

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")
    private val voicesDir: File get() = File(engineDir, "voices")
    private val voiceCacheDir: File get() = File(engineDir, "voice_cache")
    private val clonedVoicesDir: File get() = File(engineDir, "cloned_voices")

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    // -- live state (null while not loaded) ----------------------------------

    private var env: OrtEnvironment? = null
    private var bundle: PocketBundle? = null
    private var tokenizer: PocketTokenizer? = null

    private var textCondSession: OrtSession? = null
    private var mimiEncoderSession: OrtSession? = null
    private var mimiDecoderSession: OrtSession? = null
    private var flowLmMainSession: OrtSession? = null
    private var flowLmFlowSession: OrtSession? = null

    /**
     * P-V step 1 — flow_lm_main output introspection. Set when [doLoad]
     * creates the session. The previous P-R attempt failed because the
     * state manifest enumerates more entries than the model actually
     * emits as outputs, and `run(inputs, pinnedOutputs)`/3-arg `run`
     * both validate against the model's real output count.
     *
     * [flowMainRealOutputs] is the model's actual output-name set
     * (`session.outputNames`). [flowMainPinnableSpecs] is the subset of
     * the state manifest where the output exists AND the shape is
     * fully fixed (no `0` or growing dims) — only those slots can be
     * safely pinned to a pre-allocated buffer.
     */
    private var flowMainRealOutputs: Set<String> = emptySet()
    private var flowMainPinnableSpecs: List<PocketBundle.StateSpec> = emptyList()

    /**
     * P-V — outputs requested through the Result (NOT pinned). The
     * 3-arg `session.run(inputs, requestedOutputs, pinnedOutputs)`
     * validates that `requested.size + pinned.size == model.outputs`,
     * and the two sets MUST be disjoint. So this is the set of real
     * outputs minus the pinned output names — i.e. `conditioning`,
     * `eos_logit`, and the growing state outputs that we still need
     * to memcpy.
     */
    private var flowMainRequestedNonPinned: Set<String> = emptySet()

    /**
     * P-Y — engine-level state maps for flow_lm_main and mimi_decoder.
     * Previously these were allocated fresh per chunk (flow_lm: 18 slots
     * × ~MB each, plus a second pinned-output buffer per pinnable slot)
     * and per stream (mimi: 56 slots). The freshly-discarded direct
     * ByteBuffers from the previous synth piled up waiting on Cleaner
     * because direct buffers don't pressure the Java heap — three
     * adversarial reviewers converged on this as the root cause of the
     * "Pocket gets way slower after just 1 run" report.
     *
     * Allocated once at engine load. `resetStatesToInit(...)` re-fills
     * values in-place at the start of each synth — zero per-synth
     * allocations.
     */
    private var flowLmState: PocketStates? = null
    private var mimiState: PocketStates? = null

    /**
     * P-Z — engine-level scratch buffers for the AR-step `sequence` and
     * `text_embeddings` inputs. Previously allocated fresh per AR frame
     * (~12.5 fps × multi-second utterance = hundreds of small direct
     * buffers per synth, all waiting on Cleaner). Allocated once at
     * engine load + reused across every AR step of every synth.
     *
     * Refilled per frame in [stepAr] (sequence with [ArSession.previousLatent];
     * the text-dummy buffer is shape `[1, 0, conditioningDim]` so its
     * contents are never read).
     */
    private var mainSeqBuf: ByteBuffer? = null
    private var mainSeqFloatBuf: java.nio.FloatBuffer? = null
    private var mainTextDummyBuf: ByteBuffer? = null
    private var mainTextDummyFloatBuf: java.nio.FloatBuffer? = null

    /** `[1, 1, 1024]` learned embedding prepended to the voice prompt. */
    private var bosBeforeVoice: FloatArray? = null

    /** In-memory cache of voice embeddings (built-in + user-cloned). */
    private val voiceEmbeddings: ConcurrentHashMap<String, FloatArray> = ConcurrentHashMap()

    /**
     * P-D: async warmup. See KittenDirectEngine for the rationale —
     * Pocket's warmup is heavier (~1-2 s, runs a real 2-frame AR step
     * + mimi decode), so hiding it behind the Speak-screen mount → tap
     * gap is the highest-impact engine of the three.
     */
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var warmupJob: Job? = null

    /**
     * Detailed phase timings from the most recent [synthesize] call.
     * Read by [synthesizeWithTimings] to attach to the returned
     * [EnginePhaseTimings]. Protected by [synthLock] — never read while
     * a synth is in flight.
     *
     * Bench-only. Plain [synthesize] callers ignore this; the overhead
     * is a handful of `System.currentTimeMillis()` calls per synth so
     * we always populate it.
     */
    @Volatile
    private var lastDetailedPhases: List<PhaseSpan> = emptyList()

    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        // Always-present, variant-independent files.
        for (name in REQUIRED_NON_ONNX_FILES) {
            if (!File(engineDir, name).isFile) return false
        }
        // ONNX filenames vary per bundle variant — read them from bundle.json
        // and verify each one exists. Parse failures fall through to false.
        val bundleSpec = runCatching {
            PocketBundle.load(File(engineDir, "bundle.json"))
        }.getOrNull() ?: return false
        val onnxFiles = bundleSpec.onnxFiles
        val requiredOnnx = listOf(
            onnxFiles.textConditioner,
            onnxFiles.mimiEncoder,
            onnxFiles.mimiDecoder,
            onnxFiles.flowLmMain,
            onnxFiles.flowLmFlow,
        )
        for (name in requiredOnnx) {
            if (!File(engineDir, name).isFile) return false
        }
        if (!voicesDir.isDirectory) return false
        for (voice in PocketVoiceCatalog.voices) {
            if (!File(voicesDir, "${voice.displayName}.wav").isFile) return false
        }
        return true
    }

    override fun ensureModelLoaded() {
        // ensureModelLoaded is declared non-suspend by TtsEngine to keep
        // the system-TTS callback path simple. We bridge to the suspend
        // loadLock by running blocking on Dispatchers.IO inside synth's
        // own coroutine. The volatile field check makes the hot path
        // zero-cost after first load.
        if (env != null) return
        kotlinx.coroutines.runBlocking { ensureLoadedSuspending() }
    }

    private suspend fun ensureLoadedSuspending() {
        if (env != null) return
        loadLock.withLock {
            if (env != null) return
            if (!isInstalled()) throw EngineNotInstalledException(ENGINE_NAME)
            Log.i(TAG, "Loading Pocket TTS bundle from $engineDir …")
            val t0 = System.currentTimeMillis()
            // Resolve intra-op thread count: manual override takes priority,
            // else the CPU-cluster autodetect runs. Done here (suspend
            // context) so doLoad stays non-suspend.
            val manualThreads: Int? = settingsRepository.intraOpThreads.firstOrNull()
            val threadCount = manualThreads
                ?: app.marmalade.tts.perf.CpuClusterDetector.detectPerfCoreCount()
            try {
                doLoad(threadCount)
                val elapsed = System.currentTimeMillis() - t0
                Log.i(TAG, "Pocket TTS loaded in ${elapsed} ms")
            } catch (t: Throwable) {
                // Best-effort cleanup so a half-loaded engine doesn't
                // leak across retries.
                releaseInternal()
                throw IllegalStateException("Failed to load Pocket TTS: ${t.message}", t)
            }
        }
    }

    private fun doLoad(intraOpThreads: Int) {
        val ort = OrtEnvironment.getEnvironment().also { env = it }
        bundle = PocketBundle.load(File(engineDir, "bundle.json"))
        tokenizer = PocketTokenizer.load(File(engineDir, "tokenizer.model"))

        // bos_before_voice.npy: shape [1,1,1024] float32. Stash the flat
        // 1024-d vector — we'll concat along the time axis when needed.
        val npy = NpyReader.readFloat32(File(engineDir, "bos_before_voice.npy"))
        check(npy.data.size == bundle!!.conditioningDim) {
            "bos_before_voice.npy has ${npy.data.size} floats; expected ${bundle!!.conditioningDim}"
        }
        bosBeforeVoice = npy.data

        // ORT SessionOptions. Thread count is per-device — see
        // CpuClusterDetector for the autodetect heuristic. Empirical
        // calibration on Pixel 8a (Tensor G3):
        //   intra-op=1:  ~155 ms/frame
        //   intra-op=4:  ~99 ms/frame
        //   intra-op=6:  ~80 ms/frame  (NekoSpeak default for Tensor G3)
        // Tensor G3 = 1×X3 + 4×A715 + 4×A510. Filling X3 + 4×A715 (= 5)
        // is the autodetect default. Snapdragon Gen 2/3 and Exynos 2400
        // have different topologies — the detector handles each.
        // Users can override via Settings → Performance → Thread count.
        Log.i(TAG, "ORT intraOpNumThreads=$intraOpThreads (manual setting or autodetect)")
        // P-Q: flow_lm_flow is a 1-frame Euler net — over-parallelising it
        // wakes the whole XNNPACK pool just to do a small matmul. Per the
        // adversarial review, give it a smaller thread budget (2) so the
        // bigger sessions (text_cond / mimi / flow_lm_main) don't contend
        // for cores when the Euler call wakes up.
        val flowFlowThreads = (intraOpThreads / 2).coerceAtLeast(2)
        val opts = buildPocketSessionOptions(intraOpThreads, label = "main")
        val flowFlowOpts = if (flowFlowThreads != intraOpThreads) {
            buildPocketSessionOptions(flowFlowThreads, label = "flow_lm_flow")
        } else {
            opts
        }

        val files = bundle!!.onnxFiles
        Log.i(TAG, "Loading Pocket bundle (quantization=${bundle!!.quantizationVariant}): " +
            "text_cond=${files.textConditioner}, mimi_enc=${files.mimiEncoder}, " +
            "mimi_dec=${files.mimiDecoder}, flow_main=${files.flowLmMain}, " +
            "flow_flow=${files.flowLmFlow}")
        textCondSession = createSession(ort, opts, files.textConditioner)
        mimiEncoderSession = createSession(ort, opts, files.mimiEncoder)
        mimiDecoderSession = createSession(ort, opts, files.mimiDecoder)
        flowLmMainSession = createSession(ort, opts, files.flowLmMain)
        introspectFlowMainOutputs(bundle!!, flowLmMainSession!!)
        flowLmFlowSession = createSession(ort, flowFlowOpts, files.flowLmFlow)

        // P-Y — engine-level state maps. Allocated once, reset per synth.
        // Pinning is enabled here too (was previously per-AR-session).
        flowLmState = initStates(bundle!!.flowLmStateManifest).also { state ->
            if (FLOW_MAIN_PINNED_OUTPUTS && flowMainPinnableSpecs.isNotEmpty()) {
                enableStatePinning(state, flowMainPinnableSpecs)
            }
        }
        mimiState = initStates(bundle!!.mimiStateManifest)

        // P-Z — engine-level direct scratch for AR-step inputs.
        // `sequence` is [1, 1, latentDim]; `text_embeddings` is
        // [1, 0, conditioningDim] (empty time dim — the dummy buffer is
        // never read by the model, but ORT requires non-null backing).
        val latentBytes = bundle!!.latentDim * 4
        mainSeqBuf = ByteBuffer.allocateDirect(latentBytes).order(ByteOrder.nativeOrder())
        mainSeqFloatBuf = mainSeqBuf!!.asFloatBuffer()
        mainTextDummyBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        mainTextDummyFloatBuf = mainTextDummyBuf!!.asFloatBuffer()

        // P-D: warmup runs async — see KittenDirectEngine for details.
        warmupJob = warmupScope.launch {
            synthLock.withLock { warmupSynth() }
        }
    }

    /**
     * P-V step 1 — read the model's real output names from
     * `session.outputNames`, filter the state manifest to the subset
     * where the output exists AND the shape is fully fixed. Logs the
     * three relevant sets so we can confirm the pin candidates before
     * step 2 actually rewires `runFlowLmMain` to the 3-arg `run` path.
     *
     * Why this matters: agent round-3 review found `OrtSession.outputNames`
     * is exposed on ORT-Android 1.26 — the previous P-R failures
     * (`Session did not return X` and `Unexpected number of requested
     * outputs`) were both consequences of NOT calling this. The manifest
     * is built from the upstream Python that knew about input-only state
     * slots; the exported model has fewer outputs.
     */
    private fun introspectFlowMainOutputs(bundle: PocketBundle, session: OrtSession) {
        val real = session.outputNames.toSet()
        val manifestNames = bundle.flowLmStateManifest.map { it.outputName }.toSet()
        val pinnable = bundle.flowLmStateManifest.filter {
            it.outputName in real && it.shape.all { d -> d > 0 }
        }
        val notInRealOutputs = manifestNames - real
        val growingButReal = bundle.flowLmStateManifest
            .filter { it.outputName in real && !it.shape.all { d -> d > 0 } }
            .map { "${it.outputName}${it.shape.toList()}" }

        flowMainRealOutputs = real
        flowMainPinnableSpecs = pinnable
        // Pre-compute the disjoint complement so [runFlowLmMain] doesn't
        // do set algebra in the hot loop.
        val pinnedNames = pinnable.map { it.outputName }.toSet()
        flowMainRequestedNonPinned = real - pinnedNames

        Log.i(TAG, "flow_lm_main outputs: real=${real.size} manifest=${manifestNames.size} " +
            "pinnable=${pinnable.size} skipped(growing)=${growingButReal.size} " +
            "skipped(not_in_real)=${notInRealOutputs.size}")
        Log.d(TAG, "flow_lm_main real outputs: $real")
        if (notInRealOutputs.isNotEmpty()) {
            Log.d(TAG, "flow_lm_main manifest entries with no model output: $notInRealOutputs")
        }
        if (growingButReal.isNotEmpty()) {
            Log.d(TAG, "flow_lm_main manifest entries growing (not pinnable): $growingButReal")
        }
    }

    /**
     * Per-session OrtSession.SessionOptions builder. Factored out by P-Q so
     * flow_lm_flow can use a smaller thread count than the other sessions.
     * Heavy ops (text_cond / mimi / flow_lm_main) want all perf cores;
     * flow_lm_flow is a 1-frame Euler net that doesn't benefit from full
     * parallelism and just wakes the whole pool for no gain.
     */
    private fun buildPocketSessionOptions(intraOpThreads: Int, label: String): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Memory-pattern optimization restored — bisect confirmed it
            // doesn't fix the intermittent middle-chunk glitch in
            // isolation, and disabling it tanks RTF significantly. The
            // chunk-boundary glitch was its interaction with
            // [FLOW_MAIN_PINNED_OUTPUTS], which is now off.
            setMemoryPatternOptimization(true)
            // P-T: flip [VERBOSE_ORT_DIAGNOSTIC] to true and run one synth to
            // capture node placement in logcat (filter `adb logcat -s onnxruntime`).
            // Confirms which ops fall back to CPU EP (looking for
            // `DynamicQuantizeLinear`, fused `Attention`, anything tagged
            // "fallback to CPU EP" / "Node placed on"). Flip back to false after
            // capture — VERBOSE per-run logging swamps logcat.
            if (VERBOSE_ORT_DIAGNOSTIC) {
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
                setSessionLogVerbosityLevel(0)
                Log.w(TAG, "ORT verbose diagnostic ENABLED for $label — disable after capture")
            }
            // XNNPACK execution provider. Ships ARM kernels (sdot, fp16
            // SIMD, optional i8mm) tuned for the Cortex-X3/A715 cores in
            // Tensor G3 and the equivalent clusters in Snapdragon 8 Gen
            // 2/3 and Exynos 2400 — same EP that react-native-executorch
            // uses for Kokoro on the same hardware. ORT falls back to
            // CPU EP for any ops XNNPACK doesn't support, so the worst
            // case is "no speedup on flow_lm_main's int8 KV-cache loop"
            // rather than an outright failure.
            //
            // The Map is wrapped in a try/catch because XNNPACK is
            // optionally bundled in some onnxruntime-android builds;
            // if the runtime was compiled without it, addXnnpack throws
            // and we fall back to a pure CPU-EP session.
            try {
                addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                // XNNPACK has its own pthread pool; ORT's intra-op pool with
                // spinning enabled fights it for cores. Per ORT's own diagnostic
                // warning, disable spinning so ORT threads park instead of
                // busy-waiting when XNNPACK is busy. Slightly higher per-call
                // wake-up cost but no contention.
                addConfigEntry("session.intra_op.allow_spinning", "0")
                Log.i(TAG, "XNNPACK EP enabled for $label (intra_op_num_threads=$intraOpThreads, allow_spinning=0)")
            } catch (t: Throwable) {
                Log.w(TAG, "XNNPACK EP unavailable for $label; using CPU EP only", t)
            }
        }
    }

    /**
     * Run a tiny throwaway synth at the end of [doLoad] to amortize
     * ORT's first-inference JIT/kernel-compile cost. Without this,
     * the first user-triggered synth pays an extra ~500 ms before any
     * audio comes back. With this, that cost is folded into engine
     * load (which the user already waits for once per process).
     *
     * What we warm up:
     *   - mimi_encoder (via [embeddingForVoice] on first voice)
     *   - text_conditioner + flow_lm_main + flow_lm_flow (via 2 AR steps)
     *   - mimi_decoder (via [runMimiDecoder])
     *
     * Failures here are non-fatal — the engine still loads, just
     * without warm caches. Logged so we know if a build silently
     * regressed warmup.
     */
    private fun warmupSynth() {
        val tStart = System.currentTimeMillis()
        try {
            val bundle = bundle ?: return
            val tokenizer = tokenizer ?: return
            val voiceName = bundle.predefinedVoices.firstOrNull() ?: return
            val voiceEmb = embeddingForVoice(voiceName)
            val preprocessed = preprocessForPocket("Hi.", bundle)
            val tokens = tokenizer.encode(preprocessed)
            val ar = startArSession(bundle, voiceEmb, tokens, preprocessed, phases = null)
            val latents = ArrayList<FloatArray>(3)
            repeat(2) {
                val step = stepAr(ar) ?: return@repeat
                latents.add(step.latent)
            }
            if (latents.isNotEmpty()) {
                val flat = FloatArray(latents.size * bundle.latentDim)
                var pos = 0
                for (l in latents) {
                    System.arraycopy(l, 0, flat, pos, bundle.latentDim)
                    pos += bundle.latentDim
                }
                runMimiDecoder(bundle, flat)
            }
            Log.i(TAG, "Pocket warmup synth completed in ${System.currentTimeMillis() - tStart} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "Pocket warmup failed (non-fatal): ${t.message}")
        }
    }

    /**
     * Load an ONNX session, applying the ARMv7 byte-array workaround on
     * 32-bit devices. ARMv7 traps SIGBUS (BUS_ADRALN) on misaligned
     * 32-bit loads, which fires for INT8 ONNX tensors whose data offsets
     * end up at 1-byte alignment when ORT mmaps the file. Loading the
     * model into a heap-allocated `ByteArray` produces 4-byte alignment
     * via the JVM heap allocator and dodges the trap. arm64-v8a handles
     * misaligned access in hardware so mmap is fine there.
     *
     * Cost: ~50 MB transient heap during 5-session load on armeabi-v7a.
     * Pixel-class arm64 devices pay nothing.
     */
    private fun createSession(
        ort: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        fileName: String,
    ): OrtSession {
        val file = File(engineDir, fileName)
        return if (is32BitArm()) {
            ort.createSession(file.readBytes(), opts)
        } else {
            ort.createSession(file.absolutePath, opts)
        }
    }

    private fun is32BitArm(): Boolean {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        return primary == "armeabi-v7a" || primary == "armeabi"
    }

    /**
     * Synthesize the full input as one merged PCM buffer. Delegates to
     * [synthesizeStream] + concat — the streaming path chunks input
     * internally via [TextChunker] to respect Pocket's 50-token-per-
     * AR-session limit. Going through it from `synthesize` too means
     * every caller (the TTS services, the bench's old batched mode,
     * anyone passing full text in one call) gets correct behaviour
     * without needing to know the chunking rules.
     *
     * The lock-protected single-chunk inference body (which used to be
     * inlined here) is preserved in [synthesizeSingleChunk] for the
     * detailed-phase variant in [synthesizeWithTimings].
     */
    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        // Pocket does its own phonemization upstream of ORT; the espeak
        // language knob doesn't apply.
        val parts = ArrayList<ShortArray>()
        var sampleRate = 0
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect { chunk ->
            parts.add(chunk.pcm)
            sampleRate = chunk.sampleRate
        }
        if (parts.isEmpty()) {
            return@withContext SynthAudio(
                pcm = ShortArray(0),
                sampleRate = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE,
            )
        }
        val total = parts.sumOf { it.size }
        val merged = ShortArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, merged, pos, p.size)
            pos += p.size
        }
        SynthAudio(pcm = merged, sampleRate = sampleRate)
    }

    /**
     * Single-chunk inference with the detailed phase-timing
     * instrumentation. Bypasses [TextChunker] — caller must ensure the
     * input is already ≤ [PocketBundle.maxTokenPerChunk] tokens. Only
     * used by [synthesizeWithTimings] for the bench's phase-breakdown
     * mode (kept for diagnostic value even though the bench's UI
     * default no longer exposes it).
     */
    private suspend fun synthesizeSingleChunk(
        text: String,
        voiceId: String,
        speed: Float,
    ): SynthAudio = withContext(Dispatchers.Default) {
        ensureLoadedSuspending()
        val bundle = bundle ?: error("bundle missing after load")
        val tokenizer = tokenizer ?: error("tokenizer missing after load")

        synthLock.withLock {
            val phases = ArrayList<PhaseSpan>(8)
            val voiceName = voiceId.substringAfter(':', voiceId)

            val voiceEncStart = System.currentTimeMillis()
            val voiceWasCached = voiceEmbeddings.containsKey(voiceName)
            val voiceEmb = embeddingForVoice(voiceName)
            val voiceEncMs = System.currentTimeMillis() - voiceEncStart
            if (!voiceWasCached) {
                phases.add(PhaseSpan("voice-encode (cold)", voiceEncMs))
            } else if (voiceEncMs > 0) {
                // Sub-ms warm hit; only record if it actually showed up.
                phases.add(PhaseSpan("voice-encode (warm)", voiceEncMs))
            }

            // Tokenize. Apply the preprocessing flags the bundle exposes
            // (semicolons, short-input padding); these are no-ops for the
            // common case but matter for short / punctuation-heavy inputs.
            val tokStart = System.currentTimeMillis()
            val preprocessed = preprocessForPocket(text, bundle)
            val tokens = tokenizer.encode(preprocessed)
            val tokMs = System.currentTimeMillis() - tokStart
            phases.add(PhaseSpan("tokenize", tokMs, detail = "${tokens.size} tokens"))
            if (tokens.size > bundle.maxTokenPerChunk) {
                Log.w(
                    TAG,
                    "Input is ${tokens.size} tokens; bundle's max_token_per_chunk is " +
                        "${bundle.maxTokenPerChunk}. Output may skip words. " +
                        "(Sentence chunker lands in a future alpha.)",
                )
            }

            // Run the full inference pipeline. `speed` is currently
            // ignored — Pocket's LSD pipeline doesn't expose a
            // length-scale parameter natively. A sox-style post-resample
            // is the right home for speed; that's a Synthesizer-layer
            // concern, not engine-layer.
            if (speed != 1.0f) {
                Log.d(TAG, "Pocket TTS ignores speed=$speed (not exposed natively in this build)")
            }

            val flowStart = System.currentTimeMillis()
            val flowResult = runFlowLm(bundle, voiceEmb, tokens, preprocessed, phases)
            val flowMs = System.currentTimeMillis() - flowStart
            // The phase spans for phase-1, phase-2, AR loop are added by runFlowLm.
            // This "flow-lm total" is just a sanity sum for the bench UI.
            phases.add(
                PhaseSpan(
                    "flow-lm total",
                    flowMs,
                    detail = "${flowResult.numFrames} latent frames" +
                        if (flowResult.eosFiredAt >= 0) ", EOS at #${flowResult.eosFiredAt}"
                        else ", no EOS (capped)",
                ),
            )

            val decStart = System.currentTimeMillis()
            val pcmFloat = runMimiDecoder(bundle, flowResult.latents)
            val decMs = System.currentTimeMillis() - decStart
            val audioSeconds = pcmFloat.size.toDouble() / bundle.sampleRate.toDouble()
            phases.add(
                PhaseSpan(
                    "mimi-decode",
                    decMs,
                    detail = "→ %.2f s of audio".format(audioSeconds),
                ),
            )

            val pcm16Start = System.currentTimeMillis()
            val pcm16 = floatToPcm16(pcmFloat)
            val pcm16Ms = System.currentTimeMillis() - pcm16Start
            if (pcm16Ms > 0) phases.add(PhaseSpan("pcm16 convert", pcm16Ms))

            lastDetailedPhases = phases
            SynthAudio(pcm = pcm16, sampleRate = bundle.sampleRate)
        }
    }

    /**
     * Streaming variant — per-chunk batched pipeline with adaptive
     * pre-roll. Architecture (alpha.7, replaces the within-chunk
     * frame-streaming pattern that stuttered under sub-realtime
     * generation):
     *
     *   text ─► chunkPocketByTokens ─► mini-chunks (≤ MAX_TOKENS each)
     *           (sentence-aware,            │
     *            token-counted)             ▼
     *                              ┌────────────────────────┐
     *                              │ For each mini-chunk:   │
     *                              │   - fresh flow_lm state│
     *                              │   - fresh mimi state   │  ← matches upstream
     *                              │   - run AR to EOS      │     _decode_audio_worker
     *                              │   - mimi_decode whole  │
     *                              │   - emit 1 SynthAudio  │
     *                              └────────────────────────┘
     *
     * Each emitted SynthAudio is a fully-formed PCM buffer; the
     * AudioTrack consumer plays it without underrun. Inter-chunk
     * gaps only appear if the next chunk isn't ready when the
     * current chunk's audio drains.
     *
     * Adaptive pre-roll: while generating chunk 0, we measure the
     * wall-time of frames 2..5 (skipping frame 0 — which carries
     * ORT JIT/warmup cost — and frame 1 conservatively). The
     * MAX of those samples is our per-frame steady-state estimate.
     * From that we compute K = how many chunks to buffer before
     * the first emit:
     *
     *   deficitMs = max(0, perFrameMaxMs − frameBudgetMs)
     *   K = ceil(totalChunks × deficitMs / frameBudgetMs), capped at 3
     *
     * If gen is at or above realtime (deficitMs == 0), K = 1 and
     * chunks emit as they're ready. If gen is sub-realtime, K grows
     * so the buffer never fully drains before all chunks complete.
     * TTFA pays the K×gen cost; we cap K at 3 so TTFA stays bounded
     * (≈ 3 × 3 s gen on slow hardware).
     */
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic — see KittenDirectEngine for the rationale.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        val bundle = bundle ?: error("bundle missing after load")
        val tokenizer = tokenizer ?: error("tokenizer missing after load")

        if (speed != 1.0f) {
            Log.d(TAG, "Pocket TTS ignores speed=$speed in streaming path")
        }

        val voiceName = voiceId.substringAfter(':', voiceId)
        val voiceEmb = embeddingForVoice(voiceName)

        // Chunking parity with KokoroDirect: sentence-only splits, never
        // mid-word, pack tiny adjacent sentences via `minChars` so a 5-char
        // "Yes." doesn't waste an AR session as its own chunk. Pocket used
        // to chunk by tokens (cap 25), which produced orphan fragments
        // ("Outside,") when a long sentence got comma-split — every
        // chunk boundary breaks Pocket's prosody seed, so it sounded
        // choppy. Post-process pass below enforces the model's hard
        // token limit on the rare 255-char chunk that exceeds it.
        val preprocessed = preprocessForPocket(text.trim(), bundle)
        val outerChunks = TextChunker.chunk(
            text = preprocessed,
            maxChars = POCKET_MAX_CHARS_PER_CHUNK,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = POCKET_MIN_CHARS_PER_CHUNK,
        )
        val miniChunks = ArrayList<String>(outerChunks.size)
        for (chunk in outerChunks) {
            val tokenCount = tokenizer.encode(chunk).size
            if (tokenCount <= bundle.maxTokenPerChunk) {
                miniChunks.add(chunk)
            } else {
                // Safety net: a single sentence packs more tokens than the
                // model can handle. Fall back to the old token-aware
                // sub-splitter (commas → words) for this chunk only.
                Log.d(TAG, "outer chunk has $tokenCount tokens > ${bundle.maxTokenPerChunk}; sub-splitting")
                miniChunks.addAll(chunkPocketByTokens(chunk, tokenizer, bundle, bundle.maxTokenPerChunk))
            }
        }
        if (miniChunks.isEmpty()) return@channelFlow
        Log.d(TAG, "synthesizeStream: ${miniChunks.size} mini-chunk(s) from ${outerChunks.size} outer chunk(s)")
        for ((i, c) in miniChunks.withIndex()) {
            Log.d(TAG, "  chunk[$i] (${c.length} chars): \"${c.take(80)}${if (c.length > 80) "…" else ""}\"")
        }

        synthLock.withLock {
            var prerollChunks = 1
            val buffered = ArrayList<SynthAudio>(MAX_PREROLL_CHUNKS)
            // Pipelining: when sub-realtime (K > 1), kick off chunk N's mimi
            // decode on a worker thread while chunk N+1's AR loop runs.
            // [pendingDecode] holds the previous chunk's in-flight decode
            // (chunkIdx, deferred audio) — awaited before the next emit.
            // Disjoint ORT sessions (flow_lm_* on the main coroutine,
            // mimi_decoder on the worker) make this safe; the win is total
            // synth time, not TTFA, so we gate to K > 1 to avoid delaying
            // chunk-0 emit on realtime workloads.
            var pendingDecode: Pair<Int, Deferred<SynthAudio>>? = null

            // P-B: persist mimi state across all chunks of THIS stream. Was
            // freshly initStates'd inside runMimiDecoder every call → ~56
            // direct-buffer allocations per chunk + transients at every
            // chunk boundary (mimi is a streaming codec; its conv state
            // represents the waveform "in motion" — resetting between
            // chunks causes a brief click at each seam). Single state per
            // stream means continuous decode trajectory + no per-chunk
            // alloc overhead. Decodes are strictly serialised through
            // pendingDecode's await, so concurrent mutation is impossible.
            // P-Y — reuse the engine-level mimi state. Reset values
            // in-place; underlying buffers persist across synths.
            // Previously this was a fresh `initStates(...)` per stream,
            // allocating 56 direct ByteBuffers each time and leaving
            // them to Cleaner's backlog.
            val mimiState = this@PocketEngine.mimiState
                ?: error("mimi state missing — engine not loaded?")
            resetStatesToInit(mimiState, bundle.mimiStateManifest)

            // Local helper: emit a finished chunk's audio per the K-buffer
            // policy. Lambda (not local fun) so we can suspend on `send`.
            // P-A diagnostic: track inter-send producer gap; counts mini-
            // chunks emitted to downstream (incl. buffered flushes).
            var prevSendNs = 0L
            var firstEmitted = false
            val emitOrBuffer: suspend (SynthAudio, Int) -> Unit = { audio, chunkIdx ->
                if (chunkIdx < prerollChunks - 1 && chunkIdx < miniChunks.size - 1) {
                    buffered.add(audio)
                } else {
                    // Flush previously-buffered chunks (in order), then this one.
                    for (b in buffered) {
                        val gMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
                        Log.d(PERF_TAG, "pocket emit(buf) audio=${b.pcm.size * 1000L / b.sampleRate}ms gap=${gMs}ms")
                        if (!firstEmitted) {
                            val ttfa = (System.nanoTime() - streamStartNs) / 1_000_000
                            Log.d(PERF_TAG, "pocket TTFA=${ttfa}ms (loadWait=${loadWaitMs}ms + producePath=${ttfa - loadWaitMs}ms) K=$prerollChunks")
                            firstEmitted = true
                        }
                        send(b)
                        prevSendNs = System.nanoTime()
                    }
                    buffered.clear()
                    val gapMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
                    val audioMs = audio.pcm.size * 1000L / audio.sampleRate
                    Log.d(PERF_TAG, "pocket emit chunkIdx=$chunkIdx audio=${audioMs}ms gap=${gapMs}ms K=$prerollChunks")
                    if (!firstEmitted) {
                        val ttfa = (System.nanoTime() - streamStartNs) / 1_000_000
                        Log.d(PERF_TAG, "pocket TTFA=${ttfa}ms (loadWait=${loadWaitMs}ms + producePath=${ttfa - loadWaitMs}ms) K=$prerollChunks")
                        firstEmitted = true
                    }
                    send(audio)
                    prevSendNs = System.nanoTime()
                }
            }

            for ((idx, miniChunk) in miniChunks.withIndex()) {
                val isFirst = idx == 0
                val frameTimes = if (isFirst) ArrayList<Long>(8) else null

                // 1. AR loop for this chunk (synchronous on current coroutine).
                val arStartNs = System.nanoTime()
                val latents = generateChunkLatents(
                    bundle = bundle,
                    voiceEmb = voiceEmb,
                    miniChunk = miniChunk,
                    frameTimes = frameTimes,
                    chunkIdx = idx,
                )
                val arMs = (System.nanoTime() - arStartNs) / 1_000_000
                Log.d(PERF_TAG, "pocket chunk=$idx ar=${arMs}ms (text='${miniChunk.take(40)}${if (miniChunk.length > 40) "…" else ""}')")

                // 2. After chunk 0, calculate K from observed frame times.
                //
                // Originally used the raw max of frames[1..4]. That triggered
                // K=2 on any single-frame hiccup (e.g. 93ms outlier when the
                // sustained average was 60ms — well under the 80ms budget),
                // doubling TTFA for no real reason. Switched to a
                // trimmed-max: sort, drop the single worst frame, take the
                // max of the remaining 3. Still conservative — a SUSTAINED
                // sub-realtime workload trips K>1 — but a one-off cold-cache
                // spike doesn't.
                if (isFirst && frameTimes != null && frameTimes.size >= 5) {
                    val window = frameTimes.subList(1, 5)
                    val trimmedMaxMs = window.sortedDescending().drop(1).first()
                    val frameBudgetMs = 1000.0 / bundle.frameRate
                    val deficitMs = (trimmedMaxMs - frameBudgetMs).coerceAtLeast(0.0)
                    prerollChunks = if (deficitMs == 0.0) {
                        1
                    } else {
                        Math.ceil(miniChunks.size * deficitMs / frameBudgetMs)
                            .toInt()
                            .coerceIn(1, MAX_PREROLL_CHUNKS)
                    }
                    Log.d(
                        TAG,
                        "Adaptive pre-roll: chunk0 frames[1..4]=$window (raw frames=$frameTimes), " +
                            "trimmedMax=${trimmedMaxMs}ms, deficit=${deficitMs}ms, " +
                            "K=$prerollChunks chunk(s) of ${miniChunks.size} total",
                    )
                }

                // 3. Drain any previously-pending decode (await + emit/buffer).
                // Done BEFORE starting this chunk's decode so the await happens
                // in the gap between iterations (typically the deferred is
                // already complete by now thanks to the AR loop overlap).
                pendingDecode?.let { (prevIdx, prevJob) ->
                    val prevAudio = prevJob.await()
                    emitOrBuffer(prevAudio, prevIdx)
                }
                pendingDecode = null

                // 4. Decide whether to pipeline THIS chunk's decode or run
                // it synchronously. Gate on K > 1 (sub-realtime regime where
                // total-time savings matter more than first-emit latency)
                // AND not the last chunk (no pipelining benefit on the tail).
                val canPipeline = prerollChunks > 1 && idx < miniChunks.size - 1
                // P-AE REVERTED (carry-state was confounded by silent chunk
                // boundaries → carried history ≈ zeros, inconclusive). Back
                // to per-chunk reset; P-AF (warm-prime inside decodeChunkAudio)
                // now fills the conv history with the chunk's own onset.
                // Per-chunk reset is safe: chunk N-1's decode was awaited above.
                resetStatesToInit(mimiState, bundle.mimiStateManifest)
                if (canPipeline) {
                    pendingDecode = idx to async(Dispatchers.Default) {
                        val decodeStartNs = System.nanoTime()
                        val out = decodeChunkAudio(bundle, latents, mimiState)
                        val decodeMs = (System.nanoTime() - decodeStartNs) / 1_000_000
                        Log.d(PERF_TAG, "pocket chunk=$idx decode=${decodeMs}ms (pipelined)")
                        out
                    }
                } else {
                    val decodeStartNs = System.nanoTime()
                    val audio = decodeChunkAudio(bundle, latents, mimiState)
                    val decodeMs = (System.nanoTime() - decodeStartNs) / 1_000_000
                    Log.d(PERF_TAG, "pocket chunk=$idx decode=${decodeMs}ms (sync)")
                    emitOrBuffer(audio, idx)
                }
            }

            // Drain any remaining pending decode after the last iteration.
            pendingDecode?.let { (prevIdx, prevJob) ->
                val prevAudio = prevJob.await()
                emitOrBuffer(prevAudio, prevIdx)
            }
            // Defensive — buffered should be empty after the loop's flush logic.
            for (b in buffered) send(b)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * AR loop for one mini-chunk. Returns the flat latent buffer (frames
     * concatenated along time) or `null` if the chunk produced no frames.
     *
     * Was a single `generateChunkBatched` before v0.3.0-alpha.7; the
     * streaming path can pipeline this chunk's AR with the *previous*
     * chunk's mimi decode (see [synthesizeStream]). The two phases use
     * disjoint ORT sessions (`flow_lm_main` + `flow_lm_flow` here;
     * `mimi_decoder` in [decodeChunkAudio]) so they can safely run
     * concurrently.
     *
     * If [frameTimes] is non-null, the per-frame wall-clock (ms) of
     * each AR step is appended (caller uses these for adaptive pre-roll
     * on the first chunk).
     */
    private suspend fun generateChunkLatents(
        bundle: PocketBundle,
        voiceEmb: FloatArray,
        miniChunk: String,
        frameTimes: MutableList<Long>?,
        chunkIdx: Int = -1,
    ): FloatArray? {
        val tokenizer = tokenizer ?: error("tokenizer missing")
        val preprocessed = preprocessForPocket(miniChunk, bundle)
        val tokens = tokenizer.encode(preprocessed)
        if (tokens.size > bundle.maxTokenPerChunk) {
            Log.w(
                TAG,
                "Mini-chunk has ${tokens.size} tokens (cap ${bundle.maxTokenPerChunk}); " +
                    "chunker should have prevented this — output may skip words.",
            )
        }

        val ar = startArSession(bundle, voiceEmb, tokens, preprocessed, phases = null)
        val latents = ArrayList<FloatArray>(ar.maxFrames)
        // P-AD diagnostics: track latent magnitude + EOS so a glitchy chunk's
        // logcat distinguishes the two leading theories (per adversarial
        // review): (1) EOS-failure → over-generation (eosFired=false,
        // frames==maxFrames); (2) bad noise draw → AR divergence (peakLatentAbs
        // blows up / nonFinite=true). The corruption persists across a whole
        // chunk because the AR loop feeds previousLatent=nextLatent forward.
        var peakLatentAbs = 0f
        var sawNonFinite = false
        while (true) {
            // Cooperative cancellation. The AR step is a non-suspend ORT
            // native call (~50–100 ms on Pixel 8a depending on EP/thread
            // count); without this check, a Stop tap couldn't take effect
            // until the current mini-chunk finished its full AR loop.
            coroutineContext.ensureActive()
            val tStart = System.currentTimeMillis()
            val step = stepAr(ar) ?: break
            if (frameTimes != null && frameTimes.size < 8) {
                frameTimes.add(System.currentTimeMillis() - tStart)
            }
            for (v in step.latent) {
                if (!v.isFinite()) { sawNonFinite = true; continue }
                val a = if (v < 0f) -v else v
                if (a > peakLatentAbs) peakLatentAbs = a
            }
            latents.add(step.latent)
        }

        // Per-chunk summary. WARN on the two failure signatures; INFO otherwise.
        val summary = "Pocket chunk $chunkIdx: frames=${latents.size}/${ar.maxFrames} " +
            "eosFired=${ar.eosFired}@${ar.eosFiredAtFrame} " +
            "peakLatentAbs=${"%.2f".format(peakLatentAbs)} nonFinite=$sawNonFinite " +
            "text=\"${miniChunk.take(48)}\""
        if (!ar.eosFired || sawNonFinite) {
            Log.w(TAG, "SUSPECT $summary")
        } else {
            Log.i(TAG, summary)
        }

        if (latents.isEmpty()) return null
        val flat = FloatArray(latents.size * bundle.latentDim)
        var pos = 0
        for (l in latents) {
            System.arraycopy(l, 0, flat, pos, bundle.latentDim)
            pos += bundle.latentDim
        }
        return flat
    }

    /**
     * Run the mimi decoder on a flat latent buffer and convert to PCM16.
     * `null` input → empty audio (caller filters empty chunks).
     *
     * Safe to invoke from a background coroutine — the mimi_decoder
     * `OrtSession` is thread-safe for concurrent `run()` calls, and
     * `runMimiDecoder` initialises a fresh mimi state internally per
     * call so there's no cross-chunk dependency. This enables the
     * streaming-path pipeline that overlaps chunk N's mimi decode with
     * chunk N+1's AR loop.
     */
    private fun decodeChunkAudio(
        bundle: PocketBundle,
        latents: FloatArray?,
        mimiState: PocketStates,
    ): SynthAudio {
        if (latents == null || latents.isEmpty()) {
            return SynthAudio(pcm = ShortArray(0), sampleRate = bundle.sampleRate)
        }
        // The chunk-start "distorted first word" artifact is handled inside
        // [runMimiDecoder] by the P-AI graduated decode window (decode the
        // first few latent frames one at a time, the rest batched). No
        // warm-prime / trim / fade is needed here — the root cause was the
        // batched mimi graph corrupting a fresh decode's leading edge.
        val pcmFloat = runMimiDecoder(bundle, latents, mimiState)
        return SynthAudio(pcm = floatToPcm16(pcmFloat), sampleRate = bundle.sampleRate)
    }

    /**
     * Token-aware sentence chunker for Pocket. Mirrors upstream
     * `split_into_best_sentences` (tts_model.py:978):
     *
     *  1. Apply [preprocessForPocket] (uppercase, trailing period,
     *     whitespace norm) to the whole input.
     *  2. Split on sentence boundaries `[.!?]` followed by whitespace.
     *  3. Any sentence exceeding [maxTokens] sub-splits on `,;:`.
     *  4. Any sub-segment still exceeding [maxTokens] falls through to
     *     greedy word packing.
     *  5. Greedy bin-pack consecutive segments into chunks ≤ maxTokens.
     *
     * Returns a list of textual chunks (each safe to feed to
     * [generateChunkLatents] without exceeding [PocketBundle.maxTokenPerChunk]).
     */
    private fun chunkPocketByTokens(
        text: String,
        tokenizer: PocketTokenizer,
        bundle: PocketBundle,
        maxTokens: Int,
    ): List<String> {
        val preprocessed = preprocessForPocket(text.trim(), bundle)
        if (preprocessed.isEmpty()) return emptyList()

        // Step 1: split into sentences.
        val sentences = SENTENCE_SPLIT_REGEX.split(preprocessed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return emptyList()

        // Step 2: refine — sub-split oversized sentences.
        val segments = ArrayList<String>(sentences.size * 2)
        for (s in sentences) {
            val n = tokenizer.encode(s).size
            if (n <= maxTokens) {
                segments.add(s)
                continue
            }
            val parts = COMMA_SPLIT_REGEX.split(s)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size > 1) {
                for (p in parts) {
                    if (tokenizer.encode(p).size <= maxTokens) {
                        segments.add(p)
                    } else {
                        segments.addAll(splitByWordsToTokenLimit(p, tokenizer, maxTokens))
                    }
                }
            } else {
                segments.addAll(splitByWordsToTokenLimit(s, tokenizer, maxTokens))
            }
        }

        // Step 3: greedy bin-pack into chunks.
        val chunks = ArrayList<String>(segments.size)
        var cur = StringBuilder()
        var curTokens = 0
        for (seg in segments) {
            val segTokens = tokenizer.encode(seg).size
            if (cur.isEmpty()) {
                cur.append(seg)
                curTokens = segTokens
                continue
            }
            if (curTokens + segTokens <= maxTokens) {
                cur.append(' ').append(seg)
                curTokens += segTokens
            } else {
                chunks.add(cur.toString())
                cur = StringBuilder(seg)
                curTokens = segTokens
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    /**
     * Last-resort word splitter for sentences/clauses that exceed
     * [maxTokens] even after `,;:` splitting. Greedy: walks words,
     * re-encodes the running buffer each step; emits a chunk and resets
     * whenever the buffer would exceed [maxTokens].
     *
     * A single word exceeding [maxTokens] (rare — URLs, hash strings)
     * still emits as its own chunk; the engine warns and proceeds.
     */
    private fun splitByWordsToTokenLimit(
        text: String,
        tokenizer: PocketTokenizer,
        maxTokens: Int,
    ): List<String> {
        val words = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>(words.size / 5 + 1)
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (tokenizer.encode(candidate).size <= maxTokens) {
                cur = StringBuilder(candidate)
            } else {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur = StringBuilder(w)
                } else {
                    // single overlong word — emit alone, reset.
                    out.add(w)
                    cur = StringBuilder()
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * Override the default timed-synth wrapper so the bench UI gets our
     * detailed phase breakdown. Production callers stay on plain
     * [synthesize] and never trip this path.
     */
    override suspend fun synthesizeWithTimings(
        text: String,
        voiceId: String,
        speed: Float,
    ): TimedSynthAudio = withContext(Dispatchers.Default) {
        val loadStart = System.currentTimeMillis()
        ensureLoadedSuspending()
        val loadMs = System.currentTimeMillis() - loadStart
        val t0 = System.currentTimeMillis()
        val audio = synthesize(text, voiceId, speed)
        val totalMs = System.currentTimeMillis() - t0
        TimedSynthAudio(
            audio = audio,
            timings = EnginePhaseTimings(
                engineName = engineName,
                totalMs = totalMs,
                loadMs = loadMs,
                phases = lastDetailedPhases,
            ),
        )
    }

    override fun release() {
        try {
            releaseInternal()
        } catch (t: Throwable) {
            Log.w(TAG, "release() ignored failure: ${t.message}")
        }
    }

    private fun releaseInternal() {
        // Cancel any mid-flight warmup so it doesn't blow up trying to
        // touch sessions we're about to null out.
        warmupJob?.cancel()
        warmupJob = null
        listOf(
            textCondSession,
            mimiEncoderSession,
            mimiDecoderSession,
            flowLmMainSession,
            flowLmFlowSession,
        ).forEach { s ->
            try { s?.close() } catch (_: Throwable) {}
        }
        textCondSession = null
        mimiEncoderSession = null
        mimiDecoderSession = null
        flowLmMainSession = null
        flowLmFlowSession = null
        bundle = null
        tokenizer = null
        bosBeforeVoice = null
        voiceEmbeddings.clear()
        // OrtEnvironment is process-scoped; don't close it.
        env = null
    }

    // -- voice embedding cache -----------------------------------------------

    /**
     * Look up [voiceName]'s 1024-d voice embedding sequence (shape
     * `[V, 1024]` returned as a flat FloatArray of length `V * 1024`).
     *
     * Order of resolution:
     *   1. In-memory cache.
     *   2. If `voiceName` starts with `"cloned-"`: read from
     *      `cloned_voices/<voiceName>.bin` (PVS1 format).
     *   3. On-disk built-in `.emb` cache.
     *   4. Encode from `voices/<name>.wav` via `mimi_encoder`, write the
     *      .emb cache, then return.
     */
    private fun embeddingForVoice(voiceName: String): FloatArray {
        voiceEmbeddings[voiceName]?.let { return it }

        if (voiceName.startsWith(CLONED_VOICE_PREFIX)) {
            val cacheFile = File(clonedVoicesDir, "$voiceName.bin")
            check(cacheFile.isFile) { "Cloned voice missing: $cacheFile" }
            val cloned = PocketClonedVoiceStore.read(cacheFile)
            voiceEmbeddings[voiceName] = cloned.embedding
            return cloned.embedding
        }

        if (!voiceCacheDir.exists()) voiceCacheDir.mkdirs()
        val cacheFile = File(voiceCacheDir, "$voiceName.emb")
        if (cacheFile.isFile) {
            try {
                val cached = readEmbCache(cacheFile)
                voiceEmbeddings[voiceName] = cached
                return cached
            } catch (t: Throwable) {
                Log.w(TAG, "Voice cache $cacheFile unreadable, re-encoding: ${t.message}")
                cacheFile.delete()
            }
        }

        val wavFile = File(voicesDir, "$voiceName.wav")
        check(wavFile.isFile) { "Voice WAV missing: $wavFile" }
        val wav = PocketAudio.readWav(wavFile)
        val embedding = encodePcm(wav.samples, wav.sampleRate)
        try {
            writeEmbCache(cacheFile, embedding)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write voice cache $cacheFile: ${t.message}")
        }
        voiceEmbeddings[voiceName] = embedding
        return embedding
    }

    /**
     * Run [pcm] (at [srcSampleRate], in [-1, 1] float range) through the
     * `mimi_encoder` and return the flat `[numFrames, 1024]` embedding.
     *
     * Pre-processing matches the Python ground truth:
     *   - resample to the bundle's sample rate (24 kHz for english_2026-04),
     *   - divide by peak if any sample exceeds 1.0,
     *   - truncate to 30 s (the encoder's effective receptive field).
     *
     * Shared between the built-in voice encoder (called lazily on first
     * use of each Kyutai-supplied WAV) and the [cloneVoice] path
     * (called when the user supplies their own audio).
     */
    private fun encodePcm(pcm: FloatArray, srcSampleRate: Int): FloatArray {
        val bundle = bundle ?: error("bundle missing")
        val ort = env ?: error("ORT env missing")
        val session = mimiEncoderSession ?: error("mimi encoder session missing")

        var processed = PocketAudio.resample(pcm, srcSampleRate, bundle.sampleRate)
        processed = PocketAudio.normalizeIfClipping(processed)
        val cap = 30 * bundle.sampleRate
        if (processed.size > cap) processed = processed.copyOf(cap)

        // mimi_encoder input: `audio` float32 [1, 1, T]
        val buf = ByteBuffer.allocateDirect(processed.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(processed)
        buf.rewind()
        OnnxTensor.createTensor(ort, buf, longArrayOf(1, 1, processed.size.toLong())).use { audioTensor ->
            session.run(mapOf("audio" to audioTensor)).use { result ->
                val out = result.get("latents").orElseThrow {
                    IllegalStateException("mimi_encoder did not return 'latents'")
                } as OnnxTensor
                val shape = out.info.shape
                // Expected: [1, V, 1024]
                check(shape.size == 3 && shape[0] == 1L && shape[2].toInt() == bundle.conditioningDim) {
                    "mimi_encoder unexpected output shape: ${shape.toList()}"
                }
                val numFrames = shape[1].toInt()
                val flat = FloatArray(numFrames * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        }
    }

    // -- voice cloning (backend) --------------------------------------------
    //
    // Surface area only. There is NO user-facing affordance yet — the
    // cloning UX (recorder, file picker, consent dialog, alias editor
    // entry) is deliberately deferred pending the ethical/UX call on
    // whether and how to expose this capability. These methods exist so
    // the eventual UI can wire up without further engine changes, and
    // so we can exercise the encode path end-to-end in isolation.
    //
    // The cloned voice's mimi embedding is computed once at clone time
    // and persisted to disk (PVS1 binary format) — synthesis just
    // reads the file. No live audio leaves the device.

    /**
     * Clone a voice from arbitrary PCM input. The audio is resampled +
     * normalised + capped to 30 s before being fed through
     * `mimi_encoder`. The resulting embedding is written to
     * `cloned_voices/cloned-<uuid>.bin` and returned via its full voice
     * ID (`pocket-tts-en-v2026_04:cloned-<uuid>`).
     *
     * Idempotent w.r.t. failure: if the encode succeeds but the file
     * write fails, no state survives the throw. The cloned voice is
     * also added to the in-memory cache so the first synth call after
     * cloning doesn't pay the file-read cost.
     *
     * @param displayName user-facing label (≤ 256 UTF-8 bytes)
     * @param pcm mono float32 PCM in [-1, 1]
     * @param srcSampleRate sample rate of [pcm]
     * @return full voice ID, e.g. `"pocket-tts-en-v2026_04:cloned-abc..."`
     */
    suspend fun cloneVoice(
        displayName: String,
        pcm: FloatArray,
        srcSampleRate: Int,
    ): String = withContext(Dispatchers.Default) {
        require(displayName.isNotBlank()) { "Cloned voice display name must not be blank" }
        require(pcm.isNotEmpty()) { "Cloned voice PCM is empty" }
        ensureLoadedSuspending()
        val bundle = bundle ?: error("bundle missing after load")

        synthLock.withLock {
            val embedding = encodePcm(pcm, srcSampleRate)
            val numFrames = embedding.size / bundle.conditioningDim
            val localId = CLONED_VOICE_PREFIX + java.util.UUID.randomUUID().toString()
            val voice = PocketClonedVoice(
                id = localId,
                displayName = displayName,
                createdAtMillis = System.currentTimeMillis(),
                numFrames = numFrames,
                embedding = embedding,
            )
            PocketClonedVoiceStore.write(clonedVoicesDir, voice)
            voiceEmbeddings[localId] = embedding
            Log.i(TAG, "Cloned voice '$displayName' as $localId ($numFrames frames)")
            "${PocketVoiceCatalog.ENGINE}:$localId"
        }
    }

    /**
     * List every cloned voice on disk. Reads only the headers — does
     * NOT load embeddings into memory. Cheap to call from a UI flow.
     */
    suspend fun listClonedVoices(): List<PocketClonedVoiceSummary> = withContext(Dispatchers.IO) {
        PocketClonedVoiceStore.list(clonedVoicesDir)
    }

    /**
     * Delete the cloned voice with [voiceId] (the full
     * `pocket-tts-en-v2026_04:cloned-<uuid>` form, or just the local
     * `cloned-<uuid>` part). Returns true if a file was removed.
     * Also drops the in-memory cache entry.
     */
    suspend fun deleteClonedVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        val localId = voiceId.substringAfter(':', voiceId)
        voiceEmbeddings.remove(localId)
        PocketClonedVoiceStore.delete(clonedVoicesDir, localId)
    }

    private fun readEmbCache(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 4) { "voice cache too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numFrames = buf.getInt()
        val condDim = bundle!!.conditioningDim
        val expectedFloats = numFrames * condDim
        require(bytes.size == 4 + expectedFloats * 4) {
            "voice cache size mismatch: header says $numFrames frames " +
                "but file holds ${(bytes.size - 4) / 4} floats"
        }
        val out = FloatArray(expectedFloats)
        buf.asFloatBuffer().get(out)
        return out
    }

    private fun writeEmbCache(file: File, embedding: FloatArray) {
        val condDim = bundle!!.conditioningDim
        require(embedding.size % condDim == 0) { "embedding length not divisible by conditioningDim" }
        val numFrames = embedding.size / condDim
        val out = ByteBuffer.allocate(4 + embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(numFrames)
        out.asFloatBuffer().put(embedding)
        file.writeBytes(out.array())
    }

    // -- flow LM pipeline ----------------------------------------------------

    /** Return-shape from [runFlowLm]: flattened latents + telemetry for the bench. */
    private data class FlowLmResult(
        val latents: FloatArray,
        val numFrames: Int,
        /** Frame index at which the EOS logit first crossed the threshold, or -1 if it never did. */
        val eosFiredAt: Int,
    )

    /**
     * Live state of an in-flight autoregressive synthesis. Held across
     * many [stepAr] calls; not safe to share between synths.
     *
     * Carved out of the old monolithic `runFlowLm` so the AR loop can
     * be driven frame-by-frame from either the batch path (non-streaming
     * `synthesize`) or the streaming path (`synthesizeStream`'s flow
     * builder).
     */
    private class ArSession(
        val bundle: PocketBundle,
        val ort: OrtEnvironment,
        val main: OrtSession,
        val flow: OrtSession,
        val flowLmState: PocketStates,
        val maxFrames: Int,
        val framesAfterEos: Int,
        var previousLatent: FloatArray,
        var frameIdx: Int = 0,
        var eosFired: Boolean = false,
        var eosFiredAtFrame: Int = -1,
        var framesPostEos: Int = 0,
        var done: Boolean = false,
    ) {
        /**
         * Reusable buffers for the inner flow_lm_flow Euler loop. Pre-
         * allocated once at session start so [runFlowEuler] doesn't
         * `allocateDirect` 4 buffers per Euler step per frame. Layout:
         *   - cBuf    : conditioning vector (latentDim ⇒ conditioningDim,
         *               written once per frame, read by every Euler step)
         *   - sBuf, tBuf : scalar timestep boundaries (1 float each)
         *   - xBuf    : current x vector (latentDim floats, updated each step)
         *   - flowOut : output read scratch (latentDim floats)
         *
         * Native order direct buffers ⇒ zero-copy at ORT's JNI layer.
         */
        val flowScratch: FlowEulerScratch = FlowEulerScratch(
            latentDim = bundle.latentDim,
            conditioningDim = bundle.conditioningDim,
        )

    }

    /**
     * Per-Euler-loop scratch buffers (see [ArSession.flowScratch]).
     * Lives for the AR session lifetime so the inner loop never allocates.
     */
    private class FlowEulerScratch(latentDim: Int, conditioningDim: Int) {
        val cBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(conditioningDim * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val cFloatBuf: java.nio.FloatBuffer = cBuf.asFloatBuffer()

        val sBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        val sFloatBuf: java.nio.FloatBuffer = sBuf.asFloatBuffer()

        val tBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        val tFloatBuf: java.nio.FloatBuffer = tBuf.asFloatBuffer()

        val xBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(latentDim * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val xFloatBuf: java.nio.FloatBuffer = xBuf.asFloatBuffer()

        /** Reusable read-out array — flow_lm_flow output bytes copied here per step. */
        val flowOut: FloatArray = FloatArray(latentDim)
    }

    private data class ArStepResult(val latent: FloatArray, val eosLogit: Float)

    /**
     * Run phase 1 (voice conditioning) and phase 2 (text conditioning),
     * leaving the flow_lm state primed for the autoregressive loop.
     * Returns the live AR session whose `flowLmState` is the post-
     * priming state ready for [stepAr] calls.
     *
     * [phases] is non-null only when the caller wants timing data
     * recorded (the bench path); streaming skips it for clarity.
     */
    private fun startArSession(
        bundle: PocketBundle,
        voiceEmbedding: FloatArray,
        tokens: IntArray,
        originalText: String,
        phases: MutableList<PhaseSpan>?,
    ): ArSession {
        val ort = env ?: error("ORT env missing")
        val main = flowLmMainSession ?: error("flow_lm_main session missing")
        val flow = flowLmFlowSession ?: error("flow_lm_flow session missing")
        val textCond = textCondSession ?: error("text_conditioner session missing")

        // P-Y BISECT: flow_lm path reverted to per-chunk fresh allocation.
        // The mimi P-Y stayed (it lives in `synthesizeStream`) because the
        // adversarial reviewer found no static bug in `reset()` but
        // pinpointed flow_lm-side reuse as the likely cause of the
        // per-chunk audio glitch — ORT-Android may behave subtly
        // differently when the same KV-cache direct buffers are passed
        // across many `session.run` calls (P-Z exhibited a related
        // symptom with persistent input wrappers). Eats some of the
        // accumulation win but mimi P-Y still saves 56 buffers per stream.
        val state = initStates(bundle.flowLmStateManifest)
        if (FLOW_MAIN_PINNED_OUTPUTS && flowMainPinnableSpecs.isNotEmpty()) {
            enableStatePinning(state, flowMainPinnableSpecs)
        }

        // PHASE 1: voice conditioning.
        // text_embeddings = bos_before_voice ++ voice_embedding, shape [1, V+1, conditioningDim]
        val phase1Start = System.currentTimeMillis()
        val bos = bosBeforeVoice ?: error("bos_before_voice missing")
        val voiceFrames = voiceEmbedding.size / bundle.conditioningDim
        val totalFrames = voiceFrames + if (bundle.insertBosBeforeVoice) 1 else 0
        val concat = FloatArray(totalFrames * bundle.conditioningDim)
        run {
            var pos = 0
            if (bundle.insertBosBeforeVoice) {
                System.arraycopy(bos, 0, concat, pos, bos.size)
                pos += bos.size
            }
            System.arraycopy(voiceEmbedding, 0, concat, pos, voiceEmbedding.size)
        }
        runFlowLmMain(
            session = main,
            bundle = bundle,
            ort = ort,
            state = state,
            sequenceData = FloatArray(0),
            sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
            textEmbedsData = concat,
            textEmbedsShape = longArrayOf(1, totalFrames.toLong(), bundle.conditioningDim.toLong()),
            captureConditioning = false,
        )
        phases?.add(PhaseSpan("flow-lm phase 1 (voice cond)", System.currentTimeMillis() - phase1Start))

        // PHASE 2: text conditioning.
        val phase2Start = System.currentTimeMillis()
        val tcStart = System.currentTimeMillis()
        val textEmbeds = runTextConditioner(textCond, tokens, bundle, ort)
        val tcMs = System.currentTimeMillis() - tcStart
        runFlowLmMain(
            session = main,
            bundle = bundle,
            ort = ort,
            state = state,
            sequenceData = FloatArray(0),
            sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
            textEmbedsData = textEmbeds,
            textEmbedsShape = longArrayOf(1, tokens.size.toLong(), bundle.conditioningDim.toLong()),
            captureConditioning = false,
        )
        phases?.add(
            PhaseSpan(
                "flow-lm phase 2 (text cond)",
                System.currentTimeMillis() - phase2Start,
                detail = "incl. text_conditioner $tcMs ms",
            ),
        )

        return ArSession(
            bundle = bundle,
            ort = ort,
            main = main,
            flow = flow,
            flowLmState = state,
            maxFrames = estimateMaxFrames(bundle, tokens.size),
            framesAfterEos = framesAfterEosFor(bundle, originalText),
            previousLatent = FloatArray(bundle.latentDim) { Float.NaN },
        )
    }

    /**
     * Advance the AR session by one frame. Returns the new latent +
     * the raw EOS logit (caller checks against `EOS_THRESHOLD`).
     * Returns `null` when the session is done (max frames hit, or EOS
     * fired and the post-EOS tail has elapsed).
     *
     * Updates [ar] in place: increments frameIdx, updates state +
     * previousLatent, sets eosFired / done flags as appropriate.
     */
    private fun stepAr(ar: ArSession): ArStepResult? {
        if (ar.done || ar.frameIdx >= ar.maxFrames) return null
        val frame = ar.frameIdx
        // P-Z REVERTED: persistent seq/text scratch buffers caused
        // chunk-boundary glitches (same symptom as the original P-X #2
        // attempt). Going back to fresh per-frame directFloatTensor —
        // the eliminated allocations are tiny (128 bytes each) and the
        // bigger accumulation problem is already solved by P-Y.
        val capture = runFlowLmMain(
            session = ar.main,
            bundle = ar.bundle,
            ort = ar.ort,
            state = ar.flowLmState,
            sequenceData = ar.previousLatent,
            sequenceShape = longArrayOf(1, 1, ar.bundle.latentDim.toLong()),
            textEmbedsData = FloatArray(0),
            textEmbedsShape = longArrayOf(1, 0, ar.bundle.conditioningDim.toLong()),
            captureConditioning = true,
        )!! // captureConditioning=true guarantees non-null
        val nextLatent = runFlowEuler(ar.flow, ar.ort, capture.conditioning, ar.flowScratch, ar.bundle.latentDim)
        ar.previousLatent = nextLatent
        ar.frameIdx++

        if (!ar.eosFired && capture.eosLogit > EOS_THRESHOLD) {
            ar.eosFired = true
            ar.eosFiredAtFrame = frame
            Log.d(TAG, "EOS at frame $frame (logit=${capture.eosLogit}); +${ar.framesAfterEos} more")
        }
        if (ar.eosFired) {
            ar.framesPostEos++
            if (ar.framesPostEos >= ar.framesAfterEos) ar.done = true
        }
        return ArStepResult(nextLatent, capture.eosLogit)
    }

    /**
     * Non-streaming AR loop: drive [stepAr] to completion, collect every
     * latent, return them as one flat buffer. Used by [synthesize]; the
     * streaming path drives [stepAr] itself with adaptive buffering.
     */
    private fun runFlowLm(
        bundle: PocketBundle,
        voiceEmbedding: FloatArray,
        tokens: IntArray,
        originalText: String,
        phases: MutableList<PhaseSpan>,
    ): FlowLmResult {
        val ar = startArSession(bundle, voiceEmbedding, tokens, originalText, phases)
        val latents = ArrayList<FloatArray>(ar.maxFrames)
        val arStart = System.currentTimeMillis()
        while (true) {
            val step = stepAr(ar) ?: break
            latents.add(step.latent)
        }
        val arMs = System.currentTimeMillis() - arStart
        val perFrameMs = if (latents.isNotEmpty()) (arMs.toDouble() / latents.size) else 0.0
        val msPerFrameRealtime = 1000.0 / bundle.frameRate
        val realtimeRatio = if (perFrameMs > 0) (msPerFrameRealtime / perFrameMs) else 0.0
        phases.add(
            PhaseSpan(
                "flow-lm phase 3 (AR loop)",
                arMs,
                detail = "${latents.size} frames @ %.1f ms/frame, %.2fx realtime".format(
                    perFrameMs, realtimeRatio,
                ),
            ),
        )
        if (!ar.eosFired) {
            Log.w(TAG, "Hit max frames (${ar.maxFrames}) without EOS — output may be truncated")
        }

        val flat = FloatArray(latents.size * bundle.latentDim)
        var pos = 0
        for (l in latents) {
            System.arraycopy(l, 0, flat, pos, bundle.latentDim)
            pos += bundle.latentDim
        }
        return FlowLmResult(latents = flat, numFrames = latents.size, eosFiredAt = ar.eosFiredAtFrame)
    }

    /** Result of a `flow_lm_main` call when phase 3 captures the value outputs. */
    private data class FlowLmCapture(val conditioning: FloatArray, val eosLogit: Float)

    /**
     * Run `text_conditioner(tokens)` and return the flat embeddings
     * (shape `[T, conditioningDim]`). Tokenizer output is an int64
     * tensor; the conditioner is a plain embedding lookup.
     */
    private fun runTextConditioner(
        session: OrtSession,
        tokens: IntArray,
        bundle: PocketBundle,
        ort: OrtEnvironment,
    ): FloatArray {
        val tokensLong = LongArray(tokens.size) { tokens[it].toLong() }
        val tokT = directLongTensor(
            ort, tokensLong, longArrayOf(1, tokens.size.toLong()),
        )
        try {
            session.run(mapOf("token_ids" to tokT)).use { result ->
                val out = result.get("embeddings").orElseThrow {
                    IllegalStateException("text_conditioner did not return 'embeddings'")
                } as OnnxTensor
                val flat = FloatArray(tokens.size * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        } finally {
            tokT.close()
        }
    }

    /**
     * Run flow_lm_main once. Persistent state lives in [state] as Kotlin
     * arrays and is updated in place from the session outputs (the only
     * correct lifecycle with the ORT Java API — see PocketStateManager
     * for why we can't carry OnnxTensor references across calls).
     *
     * @param captureConditioning when true, returns the `conditioning`
     *   and `eos_logit` outputs (phase 3 needs them). When false (phases
     *   1 + 2) returns null and skips the extra copies.
     */
    /**
     * Phases 1 + 2 entry point. Builds `sequence` and `text_embeddings`
     * tensors via [directFloatTensor] (per-call direct buffers) since
     * the text shape varies across these calls. Delegates to
     * [runFlowLmMainWithTensors] for the rest.
     */
    private fun runFlowLmMain(
        session: OrtSession,
        bundle: PocketBundle,
        ort: OrtEnvironment,
        state: PocketStates,
        sequenceData: FloatArray,
        sequenceShape: LongArray,
        textEmbedsData: FloatArray,
        textEmbedsShape: LongArray,
        captureConditioning: Boolean,
    ): FlowLmCapture? {
        val seqT = directFloatTensor(ort, sequenceData, sequenceShape)
        val textT = directFloatTensor(ort, textEmbedsData, textEmbedsShape)
        return runFlowLmMainWithTensors(
            session = session, bundle = bundle, ort = ort, state = state,
            seqT = seqT, textT = textT,
            captureConditioning = captureConditioning,
        )
    }

    /**
     * P-Z — AR-step entry point. Wraps the engine-level [mainSeqBuf] and
     * [mainTextDummyBuf] in fresh OnnxTensor handles (P-M-safe: wrappers
     * per-call, buffers persistent for the engine's lifetime) and
     * delegates. Caller must have written `previousLatent` into
     * [mainSeqFloatBuf] before calling.
     */
    private fun runFlowLmMainAr(
        ar: ArSession,
        captureConditioning: Boolean,
    ): FlowLmCapture? {
        val seqBuf = mainSeqFloatBuf ?: error("mainSeqFloatBuf missing")
        val textBuf = mainTextDummyFloatBuf ?: error("mainTextDummyFloatBuf missing")
        val seqT = OnnxTensor.createTensor(
            ar.ort, seqBuf,
            longArrayOf(1, 1, ar.bundle.latentDim.toLong()),
        )
        val textT = OnnxTensor.createTensor(
            ar.ort, textBuf,
            longArrayOf(1, 0, ar.bundle.conditioningDim.toLong()),
        )
        return runFlowLmMainWithTensors(
            session = ar.main, bundle = ar.bundle, ort = ar.ort,
            state = ar.flowLmState,
            seqT = seqT, textT = textT,
            captureConditioning = captureConditioning,
        )
    }

    /**
     * Shared implementation. Owns closing [seqT] and [textT] in finally
     * regardless of source — the underlying buffers' lifetimes belong
     * to whoever allocated them (per-call for phases, engine-level for
     * the AR step).
     */
    private fun runFlowLmMainWithTensors(
        session: OrtSession,
        bundle: PocketBundle,
        ort: OrtEnvironment,
        state: PocketStates,
        seqT: OnnxTensor,
        textT: OnnxTensor,
        captureConditioning: Boolean,
    ): FlowLmCapture? {
        val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 2)
        inputs["sequence"] = seqT
        inputs["text_embeddings"] = textT
        bindStateInputs(ort, bundle.flowLmStateManifest, state, inputs)

        // P-V — pinning path. For each fixed-shape state slot, pass a
        // pre-allocated output buffer as a pinned output. ORT writes
        // directly into it; we then swap the slot's in/out buffers so
        // the next call's input is what was just written. Saves the
        // per-frame memcpy (`floatBuffer.put` of every state output back
        // into the slot's input buffer) AND the `result.get(name)` +
        // OnnxTensor cast for each pinned slot.
        //
        // Falls back to the 1-arg `session.run(inputs)` path when
        // [FLOW_MAIN_PINNED_OUTPUTS] is false (e.g. introspection not
        // yet run, or A/B disable).
        val pinningActive = FLOW_MAIN_PINNED_OUTPUTS && flowMainPinnableSpecs.isNotEmpty()
        val pinnedOutputs: LinkedHashMap<String, OnnxTensor>? = if (pinningActive) {
            LinkedHashMap<String, OnnxTensor>(flowMainPinnableSpecs.size).also { map ->
                for (spec in flowMainPinnableSpecs) {
                    val slot = state[spec.inputName]
                        ?: error("State missing for ${spec.inputName}")
                    map[spec.outputName] = slot.bindAsPinnedOutput(ort)
                }
            }
        } else {
            null
        }

        try {
            val result = if (pinnedOutputs != null) {
                // 3-arg run: requested + pinned must be DISJOINT and
                // their sum must equal the model's actual output count.
                // [flowMainRequestedNonPinned] is precomputed to be
                // `realOutputs - pinned names` (conditioning, eos_logit,
                // and the 6 growing state outputs).
                session.run(inputs, flowMainRequestedNonPinned, pinnedOutputs)
            } else {
                session.run(inputs)
            }
            try {
                val capture = if (captureConditioning) {
                    val condTensor = result.get("conditioning").orElseThrow {
                        IllegalStateException("flow_lm_main did not return 'conditioning'")
                    } as OnnxTensor
                    val cond = FloatArray(bundle.conditioningDim)
                    condTensor.floatBuffer.get(cond)
                    val eosTensor = result.get("eos_logit").orElseThrow {
                        IllegalStateException("flow_lm_main did not return 'eos_logit'")
                    } as OnnxTensor
                    val eos = eosTensor.floatBuffer.get(0)
                    FlowLmCapture(conditioning = cond, eosLogit = eos)
                } else {
                    null
                }
                if (pinningActive) {
                    // Pinned slots: just swap in/out buffers — ORT already
                    // wrote the new state into the slot's outBuffer. Unpinned
                    // slots (growing length trackers): memcpy via the
                    // existing path. Iterate the manifest once; branch per
                    // spec on whether it's in the pinned set.
                    val pinnedNames = pinnedOutputs!!.keys
                    for (spec in bundle.flowLmStateManifest) {
                        // Defensive: a future bundle could have manifest
                        // entries whose output the exported model doesn't
                        // emit (input-only state slots). The current
                        // Pocket bundle has skipped(not_in_real)=0 but
                        // skipping here keeps the path safe if that
                        // changes. Without this, result.get below would
                        // throw on the first AR step.
                        if (spec.outputName !in flowMainRealOutputs) continue
                        if (spec.outputName in pinnedNames) {
                            state[spec.inputName]!!.swapBuffers()
                        } else {
                            val out = result.get(spec.outputName).orElseThrow {
                                IllegalStateException("Session did not return ${spec.outputName}")
                            } as OnnxTensor
                            state[spec.inputName]!!.updateFromOutput(out)
                        }
                    }
                } else {
                    updateStatesFromResult(bundle.flowLmStateManifest, result, state)
                }
                return capture
            } finally {
                result.close()
            }
        } finally {
            for (t in inputs.values) {
                try { t.close() } catch (_: Throwable) {}
            }
            // Close pinned-output wrappers AFTER the result (their
            // underlying ByteBuffers stay live; only the JNI wrapper is
            // released — same per-call lifetime we use for inputs, which
            // P-M proved is the safe pattern on ORT-Android 1.26).
            pinnedOutputs?.values?.forEach {
                try { it.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Euler-integrated flow matching. Walks `x` from random noise toward
     * the conditioning target across [LSD_DECODE_STEPS] flow-net calls.
     *
     * Python's default is `steps=1` (LSD training collapses the
     * trajectory into one step). NekoSpeak uses 20 by default. v0.3.0-
     * alpha.2 ships 1 to match the upstream contract and minimise the
     * per-frame ORT calls; we can bump it after listening tests if 1
     * step shows artefacts.
     */
    private fun runFlowEuler(
        session: OrtSession,
        ort: OrtEnvironment,
        conditioning: FloatArray,
        scratch: FlowEulerScratch,
        latentDim: Int,
    ): FloatArray {
        // x_0 = random normal scaled by sqrt(temperature), optionally
        // truncated to ±NOISE_CLAMP_ABS (matches upstream's `noise_clamp`
        // parameter — see constant docstring). `current` is the returned
        // latent — caller holds a reference, so it must stay a per-call
        // allocation (the next stepAr call will overwrite
        // ar.previousLatent and drop our reference).
        val noiseStd = sqrt(TEMPERATURE).toFloat()
        val clamp = NOISE_CLAMP_ABS
        val current = FloatArray(latentDim) {
            var x = random.nextGaussian().toFloat() * noiseStd
            // Rejection sample (trunc_normal_ semantics). Rate ~3e-4 at
            // std=0.837, clamp=3 — the inner loop almost never re-runs.
            while (x > clamp || x < -clamp) {
                x = random.nextGaussian().toFloat() * noiseStd
            }
            x
        }
        val steps = LSD_DECODE_STEPS
        val dt = 1f / steps

        // Write conditioning into the scratch's persistent direct buffer.
        // Rewind first so put() lands at offset 0.
        scratch.cFloatBuf.rewind()
        scratch.cFloatBuf.put(conditioning)
        scratch.cFloatBuf.rewind()
        val cTensor = OnnxTensor.createTensor(
            ort, scratch.cFloatBuf, longArrayOf(1, conditioning.size.toLong()),
        )

        try {
            for (j in 0 until steps) {
                val s = j.toFloat() / steps
                val t = (j + 1).toFloat() / steps

                // Refill the scratch buffers in place (no allocations).
                scratch.sFloatBuf.rewind()
                scratch.sFloatBuf.put(s)
                scratch.sFloatBuf.rewind()

                scratch.tFloatBuf.rewind()
                scratch.tFloatBuf.put(t)
                scratch.tFloatBuf.rewind()

                scratch.xFloatBuf.rewind()
                scratch.xFloatBuf.put(current)
                scratch.xFloatBuf.rewind()

                OnnxTensor.createTensor(ort, scratch.sFloatBuf, longArrayOf(1, 1)).use { sT ->
                    OnnxTensor.createTensor(ort, scratch.tFloatBuf, longArrayOf(1, 1)).use { tT ->
                        OnnxTensor.createTensor(ort, scratch.xFloatBuf, longArrayOf(1, latentDim.toLong())).use { xT ->
                            val inputs = mapOf("c" to cTensor, "s" to sT, "t" to tT, "x" to xT)
                            session.run(inputs).use { result ->
                                val flowOut = result.get("flow_dir").orElseThrow {
                                    IllegalStateException("flow_lm_flow did not return 'flow_dir'")
                                } as OnnxTensor
                                // Copy into scratch's persistent FloatArray; integrate into current.
                                flowOut.floatBuffer.get(scratch.flowOut)
                                for (k in 0 until latentDim) current[k] += scratch.flowOut[k] * dt
                            }
                        }
                    }
                }
            }
        } finally {
            cTensor.close()
        }
        return current
    }

    // -- mimi decoder --------------------------------------------------------

    /**
     * Decode a single chunk of [latents] through the mimi_decoder,
     * mutating [mimiState] in place. Caller is responsible for the
     * state's full lifecycle (init it once at the start of a synth,
     * carry it across chunks, let it fall out of scope when done).
     *
     * Used by both the batch [runMimiDecoder] (one mimi state, many
     * chunks) and the streaming path (one mimi state across all
     * emitted chunks).
     */
    private fun decodeMimiChunk(
        bundle: PocketBundle,
        latents: FloatArray,
        numFrames: Int,
        mimiState: PocketStates,
    ): FloatArray {
        val ort = env ?: error("ORT env missing")
        val session = mimiDecoderSession ?: error("mimi decoder session missing")
        val inputs = LinkedHashMap<String, OnnxTensor>(mimiState.size + 1)
        val latT = directFloatTensor(
            ort, latents,
            longArrayOf(1, numFrames.toLong(), bundle.latentDim.toLong()),
        )
        inputs["latent"] = latT
        bindStateInputs(ort, bundle.mimiStateManifest, mimiState, inputs)
        try {
            val out = FloatArray(numFrames * bundle.samplesPerFrame)
            session.run(inputs).use { result ->
                val audioTensor = result.get("audio_frame").orElseThrow {
                    IllegalStateException("mimi_decoder did not return 'audio_frame'")
                } as OnnxTensor
                audioTensor.floatBuffer.get(out)
                updateStatesFromResult(bundle.mimiStateManifest, result, mimiState)
            }
            return out
        } finally {
            for (t in inputs.values) {
                try { t.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Non-streaming mimi decoder: chunks [latents] internally to keep
     * memory bounded for long inputs, but produces all PCM up front.
     * Used by [synthesize]; the streaming path calls [decodeMimiChunk]
     * directly with its own chunking + state lifecycle.
     */
    private fun runMimiDecoder(
        bundle: PocketBundle,
        latents: FloatArray,
        mimiState: PocketStates = initStates(bundle.mimiStateManifest),
    ): FloatArray {
        if (latents.isEmpty()) return FloatArray(0)
        val numFrames = latents.size / bundle.latentDim
        val pcm = FloatArray(numFrames * bundle.samplesPerFrame)
        var pcmPos = 0
        var frame = 0
        while (frame < numFrames) {
            // P-AI graduated window: decode the first MIMI_RAMP_FRAMES frames
            // one at a time (clean cold-edge), then switch to big batches.
            val window = if (frame < MIMI_RAMP_FRAMES) 1 else MIMI_CHUNK_FRAMES
            val chunk = minOf(window, numFrames - frame)
            val chunkFloats = chunk * bundle.latentDim
            val chunkData = FloatArray(chunkFloats)
            System.arraycopy(latents, frame * bundle.latentDim, chunkData, 0, chunkFloats)
            val chunkPcm = decodeMimiChunk(bundle, chunkData, chunk, mimiState)
            System.arraycopy(chunkPcm, 0, pcm, pcmPos, chunkPcm.size)
            pcmPos += chunkPcm.size
            frame += chunk
        }
        return pcm
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Pocket's text preprocessing pre-tokenizer. Mirrors Python's
     * `prepare_text_prompt`: trim, collapse internal whitespace,
     * optionally swap `;`→`,`, capitalize the first letter, append `.`
     * if the input ends in an alphanumeric char, optionally prepend
     * 8 spaces for <5-word inputs.
     */
    private fun preprocessForPocket(raw: String, bundle: PocketBundle): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        // P-AG — fold smart punctuation to ASCII BEFORE tokenizing. The
        // bundled SentencePiece vocab has exactly one apostrophe piece: a
        // straight `'` (U+0027). Mobile keyboards autocorrect to a curly
        // apostrophe (U+2019), which NFKC does NOT fold, so a contraction
        // like "that's" byte-falls-back on the curly char → the model
        // renders the OOV bytes as a stumble/pause. Normalising here makes
        // contractions tokenize via the real `'` piece. Correctly-typed
        // ASCII text is unaffected.
        s = normalizeSmartPunctuation(s)
        // Collapse runs of whitespace (newlines/tabs/multi-spaces) to single spaces.
        s = s.replace(Regex("\\s+"), " ")
        if (bundle.removeSemicolons) s = s.replace(';', ',')
        // Capitalize first letter (matches Python).
        s = s[0].uppercaseChar() + s.substring(1)
        // Append `.` if last char is alphanumeric.
        if (s.last().isLetterOrDigit()) s += "."
        // Short-input padding (only fires if the bundle requests it).
        if (bundle.padWithSpacesForShortInputs) {
            val wordCount = s.split(' ').count { it.isNotEmpty() }
            if (wordCount < 5) s = "        $s"
        }
        return s
    }

    /**
     * Fold the common smart-punctuation characters (curly quotes, dashes,
     * ellipsis) to their ASCII equivalents. See [preprocessForPocket] for
     * why this matters — the bundle's SentencePiece vocab only has straight
     * ASCII punctuation pieces, and NFKC doesn't perform these mappings, so
     * un-normalised smart characters byte-fall-back and the model renders
     * them as stumbles/pauses. Shared by the production preprocessor and
     * [app.marmalade.tts.engine.PocketDevEngine].
     */
    private fun normalizeSmartPunctuation(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    '‘', '’', '‚', '‛', '′', 'ʼ' -> '\''
                    '“', '”', '„', '‟', '″' -> '"'
                    '–', '—', '‒', '―' -> '-'
                    ' ', ' ', ' ' -> ' '
                    else -> ch
                },
            )
        }
        // Ellipsis → single sentence-terminating dot.
        return sb.toString().replace('…', '.')
    }

    /**
     * Estimate a generous safety cap on the number of generated frames.
     * Mirrors Python's `_estimate_max_gen_len` heuristic: speech ≈ 1
     * frame per 3-character token, padded by 2 seconds.
     */
    private fun estimateMaxFrames(bundle: PocketBundle, numTokens: Int): Int {
        val seconds = numTokens / 3.0 + 2.0
        val frames = Math.ceil(seconds * bundle.frameRate).toInt()
        return frames.coerceAtMost(MAX_FRAMES_HARD_CAP)
    }

    /**
     * Per the Python ground truth (`prepare_text_prompt`,
     * `tts_model.py:913-926` + `:622`): use **word count** to decide
     * 3 (≤4 words) or 1 (longer), then add 2 for safety. We
     * previously used token count which gave 5/3 instead of 3/1 —
     * generated 2 extra surplus frames per utterance, which surfaces
     * as ~160 ms of trailing silence / low-confidence audio per
     * chunk. Matters audibly when streaming back-to-back chunks of a
     * long input.
     */
    private fun framesAfterEosFor(bundle: PocketBundle, originalText: String): Int {
        bundle.modelRecommendedFramesAfterEos?.let { return it + 2 }
        val wordCount = originalText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val base = if (wordCount <= 4) 3 else 1
        return base + 2
    }

    /**
     * Convert mimi_decoder's float output (already in [-1, 1] modulo
     * occasional excursions) to PCM16. Clamps defensively so a rogue
     * sample doesn't wrap-around into a loud click.
     */
    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            out[i] = (clamped * 32767f).toInt().toShort()
        }
        return out
    }

    /**
     * Create a float OnnxTensor backed by a fresh direct ByteBuffer (native
     * byte order) rather than `FloatBuffer.wrap(heapArr)`. Heap-wrapped
     * buffers force ORT's JNI layer to memcpy heap → native memory on every
     * call; direct buffers skip that copy. For per-AR-frame inputs the
     * difference adds up (~50 MB/s of memcpy traffic at ~12.5 fps).
     *
     * The buffer is owned by the returned tensor's lifecycle — callers
     * close the tensor as usual; the underlying direct memory is then
     * eligible for GC. We don't reuse the buffer across calls because the
     * shapes vary per call site (phase 1 has T_text = V+1; phase 2 has
     * T_text = tokens; AR has T_seq = 1, T_text = 0).
     */
    private fun directFloatTensor(
        ort: OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor {
        // ORT requires a non-null buffer even for 0-element shapes; allocate
        // a 1-float dummy in that case (the shape masks it out).
        val byteCount = (if (data.isEmpty()) 1 else data.size) * 4
        val buf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (data.isNotEmpty()) {
            buf.put(data)
            buf.rewind()
        }
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    /** Long-tensor sibling of [directFloatTensor] — used by `runTextConditioner` for token IDs. */
    private fun directLongTensor(
        ort: OrtEnvironment,
        data: LongArray,
        shape: LongArray,
    ): OnnxTensor {
        val byteCount = (if (data.isEmpty()) 1 else data.size) * 8
        val buf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).asLongBuffer()
        if (data.isNotEmpty()) {
            buf.put(data)
            buf.rewind()
        }
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    companion object {
        private const val TAG = "PocketEngine"
        /** Logcat tag for the P-A streaming perf diagnostic — `adb logcat -s StreamPerf`. */
        private const val PERF_TAG = "StreamPerf"
        const val ENGINE_NAME = "pocket-tts-en-v2026_04"

        /**
         * Files that exist in every Pocket bundle regardless of variant.
         * The 5 ONNX filenames are variant-specific (declared in bundle.json's
         * `onnx_files` block) and checked separately in [isInstalled].
         */
        private val REQUIRED_NON_ONNX_FILES = listOf(
            "tokenizer.model",
            "bos_before_voice.npy",
            "bundle.json",
        )

        /**
         * Mimi decoder chunk size (latent frames). Originally 15 (~1.2 s @
         * 12.5 fps) — kept per-call work small for memory safety when each
         * top-level chunk got a fresh mimi state. After P-B (mimi state
         * persists across stream chunks) the sub-chunking inside a single
         * top-level chunk strictly costs JNI overhead — we re-bind 56
         * state tensors and rebuild the input map per 15-frame batch when
         * one call could digest the whole 60-80-frame chunk in one shot.
         * Bumped to 64 to absorb most chunks in one call; the loop stays
         * as a safety net for the rare > 64-frame chunk.
         *
         * P-X (reverted): bumping to `Int.MAX_VALUE` caused a chunk-start
         * audio glitch — empirically the mimi decoder's output isn't
         * bit-identical for `decode([1..72])` vs `decode([1..64])
         * decode([65..72])` even though the model is causal+stateful.
         * Suspect some N-dependent normalisation or batch-internal
         * smoothing. Keep at 64; the 1-2 sub-iter case for 65-128 frame
         * chunks is acceptable JNI overhead.
         */
        // Batch size for the BULK of a chunk's mimi decode (after the
        // graduated ramp — see [MIMI_RAMP_FRAMES]).
        private const val MIMI_CHUNK_FRAMES = 64

        /**
         * P-AI — graduated decode window. P-AH proved per-frame decode
         * (window=1) is perfect but slow, while batched decode corrupts the
         * leading edge of a fresh mimi stream (the "distorted first word").
         * The bulk of a batch decodes fine once the conv state is warm.
         *
         * So decode the first [MIMI_RAMP_FRAMES] latent frames one at a time
         * (clean onset), then the remainder in [MIMI_CHUNK_FRAMES]-sized
         * batches (fast). Recovers most of the batched speed while keeping
         * the cold leading edge clean. Tune down toward the minimum that
         * stays clean. 0 = pure batched (the old buggy behaviour).
         */
        private const val MIMI_RAMP_FRAMES = 8

        /**
         * P-T diagnostic toggle. When true, every Pocket session gets
         * VERBOSE ORT logging (level 0). Captures kernel placement at
         * session-create so we can see which ops escape XNNPACK. Flip back
         * to false after capture — VERBOSE logging during inference is a
         * firehose. Inspect with `adb logcat -s onnxruntime`.
         */
        private const val VERBOSE_ORT_DIAGNOSTIC = false

        /**
         * P-V — when true, runFlowLmMain uses the 3-arg `session.run`
         * with output pinning for fixed-shape state slots (KV caches).
         * Saves the per-frame state-buffer memcpy (~tens of MB on Pocket)
         * AND the per-frame `result.get(name)` lookup for those slots.
         *
         * Set to false to A/B against the 1-arg fallback path. Both
         * paths produce identical state updates — the difference is
         * how state advances from output to next input.
         */
        // P-V REVERTED. Bisect confirmed the chunk-boundary glitch was
        // pinning's interaction with `setMemoryPatternOptimization(true)`
        // — ORT-Android's cached memory plan refers to pinned-output
        // buffer addresses that are fresh per chunk, producing a brief
        // "write through stale plan" artefact at every chunk start.
        //
        // We chose to revert pinning rather than turn off memory-pattern
        // optimization because the latter tanked RTF significantly while
        // the former's per-frame gain was modest (~5-10% AR loop). The
        // residual intermittent middle-chunk glitch matches the
        // separate GC-pause-AudioTrack-underrun pattern all three
        // reviewers predicted — addressed elsewhere.
        private const val FLOW_MAIN_PINNED_OUTPUTS = false

        // The chunk-start "distorted first word" artifact is fixed at its
        // root by the P-AI graduated decode window in [runMimiDecoder] (the
        // exported mimi graph corrupts the leading edge of a batched decode;
        // decoding the first few frames one at a time avoids it). The earlier
        // mask-the-symptom knobs — MIMI_COLD_START_TRIM_MS (P-AA), warm-prime
        // MIMI_WARMUP_FRAMES (P-AF), and a never-wired CHUNK_FADE_IN_MS (P-AB)
        // — were all removed once the real cause was found.

        /**
         * Euler integration steps for flow_lm_flow. Upstream default is
         * 1 — LSD training collapses the diffusion trajectory into a
         * single step. NekoSpeak ships 20.
         *
         * P-AC.D (2026-05): bumped 1 → 4. P-AC.B (trim=0) and P-AC.C
         * (noise_clamp=3.0) both failed to eliminate the intermittent
         * chunk-start artefact. Reviewer consensus pins frame 0's latent
         * quality: BOS-substituted conditioning + LSD_DECODE_STEPS=1
         * leaves a single Euler step to walk from random noise to a
         * usable latent, which is fragile when frame 0 conditioning is
         * itself atypical. More steps integrate flow direction more
         * times so frame 0 lands on-manifold. Cost: ~3× more flow_lm_flow
         * calls (small net — modest RTF hit).
         */
        private const val LSD_DECODE_STEPS = 4

        /** Sampling temperature for the initial noise. Python default. */
        private const val TEMPERATURE = 0.7

        /**
         * P-AC.C — absolute bound applied to each component of the
         * initial noise vector in [runFlowEuler]. Matches upstream's
         * `noise_clamp` parameter (kyutai-labs/pocket-tts flow_lm.py
         * lines 101–110), which routes to `torch.nn.init.trunc_normal_(
         * mean=0, std=sqrt(temp), a=-noise_clamp, b=noise_clamp)`.
         *
         * Upstream's default is `None` (no clamp). We apply 3.0 to
         * truncate fat-tail noise draws, the leading hypothesis for the
         * intermittent chunk-start artefact (every chunk starts a fresh
         * AR session whose frame 0 is BOS-substituted; with
         * LSD_DECODE_STEPS=1 the single Euler step preserves most of
         * the initial noise, so a tail draw projects to an off-manifold
         * latent that mimi decodes into ~1 word of degraded audio).
         *
         * With std≈0.837 and clamp=3.0 the rejection rate per scalar is
         * ~3e-4 — perceptually free on good draws, eliminates the bad
         * draws. Set to a large value (e.g. 100f) to effectively
         * disable the clamp without removing the code path.
         */
        private const val NOISE_CLAMP_ABS = 3.0f

        /** Raw eos_logit threshold from the export wrapper. */
        private const val EOS_THRESHOLD = -4.0f

        /** Belt-and-braces ceiling. Should never trip under normal use. */
        private const val MAX_FRAMES_HARD_CAP = 500

        /**
         * Voice-name prefix that marks a cloned voice. Built-in voices
         * use Kyutai's reference names (alba/azelma/...). Cloned voices
         * carry a UUID with this prefix so the lookup in
         * [embeddingForVoice] can route to the right on-disk format.
         */
        const val CLONED_VOICE_PREFIX = "cloned-"

        // -- chunking + pre-roll knobs (synthesizeStream) -----------------
        //
        // Pocket adopts KokoroDirect's chunking discipline (sentence-only,
        // char-bound, minChars-merge). [MAX_TOKENS_PER_MINI_CHUNK] used to
        // drive a token-based bin-pack — that produced orphan fragments
        // ("Outside,") whenever a sentence got comma-split, which broke
        // Pocket's per-chunk prosody seed and sounded choppy. The constant
        // is retained for the rare fallback path in [chunkPocketByTokens]
        // (oversized single sentence beyond the model's hard token cap),
        // but the main path now uses [TextChunker.chunk] with the same
        // params as KokoroDirect.

        /** Token cap for the fallback word-splitter — only fires on >maxTokenPerChunk single chunks. */
        private const val MAX_TOKENS_PER_MINI_CHUNK = 25

        /** Same as KokoroDirect.maxInputChars — sentence-boundary cap. */
        private const val POCKET_MAX_CHARS_PER_CHUNK = 255

        /**
         * Tiny-sentence merge threshold. Lower than KokoroDirect's 80
         * because Pocket runs sub-realtime: merging two ~70-char sentences
         * into a single ~150-char chunk doubles the AR loop's wall-clock
         * for that chunk, which exceeds the prior chunk's audio duration
         * and starves the consumer (audible gap). Set to 40 so sentences
         * 40+ chars emit alone — keeps per-chunk size variance low so the
         * adaptive pre-roll's K=1 holds without underrun. Truly tiny
         * sentences ("Yes.") still merge.
         */
        private const val POCKET_MIN_CHARS_PER_CHUNK = 40

        /**
         * Adaptive pre-roll ceiling. The K formula scales with the
         * per-frame deficit and the chunk count; on slow devices it
         * would otherwise demand 4+ chunks of buffering. We cap it
         * to keep TTFA bounded.
         *
         * Calibration: at the fp32-mimi precision shipped with v0.3.0-
         * alpha.7, a typical 15-frame chunk takes ~2.25 s wall-time on
         * Pixel 8a (Tensor G3) when the per-frame deficit is 80 ms+.
         * Cap K at 2 ⇒ worst-case TTFA ≈ 4.5 s, vs the older int8
         * timing where K=3 fit a 3 s budget. The int8 budget no longer
         * applies; the trade-off is now stutter risk (lower K) vs
         * startup delay (higher K).
         */
        private const val MAX_PREROLL_CHUNKS = 2

        /** Sentence boundary: `.`, `!`, or `?` followed by whitespace. */
        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")

        /** Sub-sentence boundary: `,`, `;`, or `:` followed by whitespace. */
        private val COMMA_SPLIT_REGEX = Regex("(?<=[,;:])\\s+")

        /** Generic whitespace splitter for word-level fallback. */
        private val WHITESPACE_REGEX = Regex("\\s+")

        // Reused across calls; ThreadLocal so two simultaneous engines
        // wouldn't share a Random (we serialise via synthLock anyway, but
        // the field is engine-scoped to make the no-share guarantee obvious).
        private val random: java.util.Random = java.util.Random()
    }
}
