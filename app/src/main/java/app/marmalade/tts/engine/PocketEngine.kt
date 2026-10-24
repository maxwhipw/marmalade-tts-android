package app.marmalade.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.Expressiveness
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketClonedVoice
import app.marmalade.tts.engine.pocket.PocketClonedVoiceStore
import app.marmalade.tts.engine.pocket.PocketClonedVoiceSummary
import app.marmalade.tts.engine.pocket.PocketLineBreaks
import app.marmalade.tts.engine.pocket.pocketNoiseStd
import app.marmalade.tts.engine.pocket.PocketStates
import app.marmalade.tts.engine.pocket.PocketTokenSplit
import app.marmalade.tts.engine.pocket.PocketTokenizer
import app.marmalade.tts.engine.pocket.initStates
import app.marmalade.tts.engine.pocket.PocketCacheShapes
import app.marmalade.tts.engine.pocket.PocketMimiBatch
import app.marmalade.tts.engine.pocket.PocketSessionPlan
import app.marmalade.tts.engine.pocket.PocketSliceBank
import app.marmalade.tts.engine.pocket.PocketSliceClock
import app.marmalade.tts.engine.pocket.PocketStreamPlan
import app.marmalade.tts.engine.pocket.PocketStateCarry
import app.marmalade.tts.engine.pocket.StateFrame
import app.marmalade.tts.engine.pocket.PocketTimes
import app.marmalade.tts.engine.pocket.PocketVoiceFallback
import app.marmalade.tts.engine.pocket.PocketVoiceFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
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
 *               (its state cached per voice, #15 "C")
 *   flow_lm phase 2 (text cond):   sequence=[1,0,32]  +  text_embeds=text_embeds
 *   flow_lm phase 3 (autoregressive, per latent frame):
 *      sequence=[1,1,32] (NaN→BOS first step, then previous latent)
 *      → conditioning [1,1024] + eos_logit [1,1]
 *      → Euler-integrated flow_lm_flow: x_{t+1} = x_t + flow(c,s,t,x)·(1/steps)
 *      → emit latent, check EOS
 *   mimi_decoder, a slice of latents at a time while the AR loop runs
 *      (#15 "E", [streamChunks]) → float PCM @ 24 kHz → PCM16
 *
 * Voice cloning (v0.3.0): users supply WAV; same `mimi_encoder` path,
 * with the resulting embedding written to `cloned_voices/<id>.bin`.
 *
 * Why a separate class from the other direct engines: different model
 * topology (5 graphs vs 1), a stateful streaming codec, and voice
 * cloning. The shared surface is [TtsEngine] — same contract Synthesizer
 * + the two TTS services route through.
 *
 * Thread-safety: model loading is gated by a coroutine [Mutex]; synthesis
 * itself is gated by [Mutex.withLock] so concurrent `synthesize` calls
 * serialise (the model's KV cache state isn't reentrant). Within one
 * request the decoder runs on a second coroutine, on its own state.
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

    /**
     * Pocket's five ONNX graphs are autoregressive and expose no speed
     * input — nothing in the pipeline can change the rate. The services
     * time-stretch the output instead; see [TtsEngine.supportsNativeSpeed].
     */
    override val supportsNativeSpeed: Boolean = false

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")
    private val voicesDir: File get() = File(engineDir, "voices")
    private val voiceCacheDir: File get() = File(engineDir, "voice_cache")
    private val clonedVoicesDir: File get() = File(engineDir, "cloned_voices")

    /**
     * A request for a voice the installed download lacks (v21 has five of
     * v27's ten) speaks with the default voice instead of failing (#44).
     */
    private val voiceFallback = PocketVoiceFallback(isPresent = { PocketVoiceFiles.has(engineDir, it) }) { requested, used ->
        Log.w(TAG, "voice '$requested' is not in the installed Pocket download; speaking with '$used' (updating Pocket adds it)")
    }

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    /**
     * Raised by [release] so an in-flight synth (which holds [synthLock]
     * for the whole utterance) aborts at its next per-frame check instead
     * of making release wait out the full AR loop.
     */
    private val releaseRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    // -- live state (null while not loaded) ----------------------------------

    @Volatile private var env: OrtEnvironment? = null
    private var bundle: PocketBundle? = null
    private var tokenizer: PocketTokenizer? = null

    private var textCondSession: OrtSession? = null
    private var mimiEncoderSession: OrtSession? = null
    private var mimiDecoderSession: OrtSession? = null
    private var flowLmMainSession: OrtSession? = null
    private var flowLmFlowSession: OrtSession? = null

    /**
     * mimi_decoder's init state (56 slots), allocated once at load. Every
     * chunk's decode starts from it through a [PocketStateCarry]; ORT only
     * reads graph inputs, so it is never refilled (#15 "A").
     */
    private var mimiState: PocketStates? = null

    /**
     * mimi state shapes to initialise from: the bundle's manifest, or ring
     * buffers resized for a decoder with a symbolic ring axis (#15 "F"; debug
     * knob `pocket.mimiCap`).
     */
    private var mimiManifest: List<PocketBundle.StateSpec> = emptyList()

    /**
     * The flow_lm init states chunks start from (read-only, like
     * [mimiState]), by KV-cache length: one entry for the shipped graph, one
     * per voice-sized length for a graph with a symbolic cache axis.
     */
    private val flowInitStates = HashMap<Int, PocketStates>()

    /**
     * #15 "F": flow_lm_main's cache axis is symbolic (a `*_dyncache`
     * export), so each voice gets a cache just long enough for its prompt
     * plus one chunk ([PocketCacheShapes.flowLenFor]) instead of 1000 rows
     * that every AR step copies. The shipped graph is fixed at 1000.
     */
    private var flowCacheDynamic = false
    private var flowCacheLenKnob: Int? = null

    /**
     * #15 "C": flow_lm's state right after the voice prompt (phase 1), for
     * the last voice used, so later chunks skip re-running the prompt. Phase
     * 1 has no randomness, so starting from it is exact. One voice only
     * (~49 MB native with the shipped 1000-row cache). Touched under [synthLock].
     */
    private var voicePrompt: VoicePrompt? = null

    private class VoicePrompt(val embedding: FloatArray, val cacheLen: Int?, val frame: StateFrame)

    /**
     * Latent frames per mimi_decoder run (#15 "D"): consecutive batches no
     * larger than [PocketMimiBatch.maxExactBatch], so every frame decodes
     * exactly as it would alone. Debug knob `pocket.decode` = a smaller
     * frame count.
     */
    private var decodeBatch = 0

    /**
     * #15 "E": audio is decoded and sent in slices while the AR loop runs
     * ([streamChunks]). [STREAM_OVERLAP] (the default) decodes on a second
     * thread, so the decoder's time hides behind the next frames' AR steps;
     * debug knob `pocket.stream=inline` decodes on the AR thread instead.
     */
    private var streamMode = STREAM_OVERLAP

    /** Debug knob `pocket.decodeSafety`: [PocketSliceClock]'s decode safety factor ("G"). */
    private var decodeSafety = PocketSliceClock.DECODE_SAFETY

    /** The decoder's measured cost per slice size, for the first-sound hold (decoder thread only). */
    private val decodeCost = SliceCost()

    /**
     * Chunk mode (#54, [RenderMode]): how much sent audio is still ahead of
     * the listener.
     */
    private val horizon = PlaybackHorizon()

    /** Audio per text token (EWMA), to estimate a request's length for the hold. */
    @Volatile private var streamMsPerToken = DEFAULT_MS_PER_TOKEN

    /** The AR loop's ms per frame in the last streamed request (#15 "E"), the next one's first guess. */
    @Volatile private var streamArMsPerFrame = DEFAULT_AR_MS_PER_FRAME

    /** Debug knob `pocket.ortProfile`: requests left before ORT's profiler is closed. */
    private var profileRequestsLeft = 0

    /** Debug knob `pocket.seed`: reseeds the noise at each request, for bit-exact A/B checks. */
    private var seed: Long? = null

    /**
     * The request's starting-noise std, from its alias's expressiveness
     * ([pocketNoiseStd]). Set under [synthLock] at the start of each
     * request and read by [runFlowEuler] under the same lock.
     */
    private var noiseStd: Float = pocketNoiseStd(null)

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
        // Only the default voice's WAV is required (the key is the lowercase
        // part of the id, not the displayName). Asking for every catalog voice
        // made a v21 install, which lacks v27's five new WAVs, report "not
        // installed" and break every Pocket alias until the update (#38). A
        // request for a voice whose files are missing speaks with this
        // default voice ([voiceToRender], #44); the installer's layout check
        // still asks for every offered voice of a current install.
        val defaultKey = PocketVoiceCatalog.DEFAULT_VOICE_ID.substringAfterLast(':')
        return File(voicesDir, "$defaultKey.wav").isFile
    }

    /**
     * The offered voices this install can speak, by key ([PocketVoiceFiles]).
     * System TTS lists only these, so no client is offered a voice that
     * would come out as the default one.
     */
    fun installedVoiceKeys(): Set<String> = PocketVoiceFiles.presentKeys(engineDir)

    /** The voice key [voiceId] renders with: itself, or the default voice when its files are absent. */
    internal fun voiceToRender(voiceId: String): String =
        voiceFallback.keyFor(voiceId.substringAfter(':', voiceId))

    /**
     * [env] is `doLoad`'s publish-last marker — the same field
     * [ensureModelLoaded]'s unlocked fast path treats as "fully loaded".
     */
    override fun isLoaded(): Boolean = env != null

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
            val threadCount = EngineDevKnobs.get(ctx.filesDir, "threads")?.toIntOrNull()
                ?: manualThreads
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
        // Build everything against the local [ort]; publish to [env] only
        // at the END. ensureModelLoaded's unlocked fast path treats
        // env != null as "fully loaded", so an early publish let a
        // concurrent first synth see a half-loaded engine ("bundle
        // missing after load"). Same rationale as KittenDirectEngine /
        // KokoroDirectEngine.doLoad.
        val ort = OrtEnvironment.getEnvironment()
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
        // Tensor G3 = 1×X3 + 4×A715 + 4×A510. The autodetect picks the 4
        // A715s: a screen-off reader can't use the X3 (see
        // CpuClusterDetector; #13 "C"). Pocket on the 8a, B1 set, screen
        // off: RTF 2.12 with 4 threads vs 2.22 with 5. Snapdragon Gen 2/3
        // and Exynos 2400 have different topologies — the detector
        // handles each.
        // Users can override via Settings → Performance → Thread count.
        Log.i(TAG, "ORT intraOpNumThreads=$intraOpThreads (manual setting or autodetect)")
        // flow_lm_flow (a small matmul, P-Q) and mimi_decoder (running
        // alongside the AR loop, #15) get half the threads, at least 2.
        // Per-graph settings and their debug A/B knobs: [PocketSessionPlan].
        val knob = { key: String -> EngineDevKnobs.get(ctx.filesDir, key) }
        val plan = PocketSessionPlan.resolve(intraOpThreads, knob)
        profileRequestsLeft = knob("pocket.ortProfile")?.toIntOrNull() ?: 0
        val profileDir = if (profileRequestsLeft > 0) File(ctx.filesDir, "bench/ortprof").apply { mkdirs() } else null
        seed = knob("pocket.seed")?.toLongOrNull()
        flowCacheLenKnob = knob("pocket.cacheLen")?.toIntOrNull()
        val mimiCapKnob = knob("pocket.mimiCap")?.toIntOrNull()
        streamMode = if (knob("pocket.stream") == "inline") STREAM_INLINE else STREAM_OVERLAP
        decodeSafety = knob("pocket.decodeSafety")?.toDoubleOrNull() ?: PocketSliceClock.DECODE_SAFETY
        val decodeKnob = knob("pocket.decode")

        val files = bundle!!.onnxFiles
        Log.i(TAG, "Loading Pocket bundle (quantization=${bundle!!.quantizationVariant}): " +
            "text_cond=${files.textConditioner}, mimi_enc=${files.mimiEncoder}, " +
            "mimi_dec=${files.mimiDecoder}, flow_main=${files.flowLmMain}, " +
            "flow_flow=${files.flowLmFlow}")
        fun logPlan() = Log.i(TAG, "session plan: " + plan.entries.joinToString(" ") { (g, c) ->
            "$g=${c.threads}t${if (c.xnnpack) "+xnn" else ""}${if (c.spin) "+spin" else ""}"
        } + (if (profileDir != null) " ortProfile=$profileRequestsLeft" else "") +
            (seed?.let { " seed=$it" } ?: "") + " decode=$decodeBatch stream=$streamMode safety=$decodeSafety" +
            " cacheLen=${flowCacheLenKnob ?: if (flowCacheDynamic) "per-voice" else "1000"}" +
            " mimiCap=${PocketMimiBatch.ringCapacity(mimiManifest)}")
        fun session(graph: String, fileName: String): OrtSession {
            // ORT copies the options into the native session at creation, so
            // they're closed right after — otherwise every keepalive
            // evict/reload leaked the native OrtSessionOptions.
            return buildPocketSessionOptions(plan.getValue(graph), graph, profileDir).use { opts ->
                createSession(ort, opts, fileName)
            }
        }
        // Debug knob `pocket.file.<graph>`: load another file from the engine
        // dir (#15 "F": model-file prototypes sideloaded beside the bundle).
        fun file(graph: String, default: String) = knob("pocket.file.$graph") ?: default
        textCondSession = session("text", file("text", files.textConditioner))
        mimiEncoderSession = session("enc", file("enc", files.mimiEncoder))
        mimiDecoderSession = session("dec", file("dec", files.mimiDecoder))
        flowLmMainSession = session("main", file("main", files.flowLmMain))
        flowLmFlowSession = session("flow", file("flow", files.flowLmFlow))

        // #15 "F": graphs exported with a symbolic cache axis take shorter
        // caches (a cache's declared shape has -1 there; the shipped ones say 1000).
        fun dynamic(session: OrtSession, manifest: List<PocketBundle.StateSpec>, axis: Int) =
            manifest.firstOrNull { it.shape.size == 5 && !it.path.startsWith("encoder") }?.let { spec ->
                ((session.inputInfo[spec.inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(axis) ?: 0L) < 0L
            } ?: false
        flowCacheDynamic = dynamic(flowLmMainSession!!, bundle!!.flowLmStateManifest, 2)
        val mimiCap = mimiCapKnob
            ?: if (dynamic(mimiDecoderSession!!, bundle!!.mimiStateManifest, 3)) PocketCacheShapes.DYNAMIC_MIMI_CAPACITY else null
        mimiManifest = mimiCap?.let { PocketCacheShapes.mimi(bundle!!.mimiStateManifest, it) } ?: bundle!!.mimiStateManifest
        val exact = PocketMimiBatch.maxExactBatch(PocketMimiBatch.ringCapacity(mimiManifest))
        decodeBatch = (decodeKnob?.toIntOrNull() ?: exact).coerceIn(1, exact)
        logPlan()

        mimiState = initStates(mimiManifest)

        // Publish only after every field a sibling caller's synth path
        // touches is non-null. Warmup launches after the publish because
        // warmupSynth reads [env] itself.
        env = ort

        // P-D: warmup runs async — see KittenDirectEngine for details.
        warmupJob = warmupScope.launch {
            synthLock.withLock { warmupSynth() }
        }
    }

    /**
     * One graph's OrtSession.SessionOptions ([PocketSessionPlan] decides
     * threads, XNNPACK and spinning per graph). [profileDir] non-null turns
     * on ORT's per-node profiler for the `pocket.ortProfile` debug knob.
     */
    private fun buildPocketSessionOptions(
        cfg: app.marmalade.tts.engine.pocket.PocketGraphConfig,
        label: String,
        profileDir: File?,
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(cfg.threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Memory-pattern optimization: disabling it tanks RTF. (Its
            // interaction with output pinning, P-V, caused a chunk-boundary
            // glitch; pinning was reverted and later removed, #15.)
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
            if (profileDir != null) enableProfiling(File(profileDir, label).absolutePath)
            // Spinning keeps the intra-op threads hot between the many
            // short calls of the AR loop instead of paying a wake-up each.
            // XNNPACK has its own pthread pool, and spinning ORT threads
            // fight it for cores (ORT warns about it).
            addConfigEntry("session.intra_op.allow_spinning", if (cfg.spin) "1" else "0")
            // XNNPACK execution provider. ORT falls back to the CPU EP for
            // any op XNNPACK doesn't support (flow_lm_main's MatMulInteger
            // among them). Wrapped because XNNPACK is optional in some
            // onnxruntime-android builds; without it the CPU EP runs alone.
            if (cfg.xnnpack) {
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to cfg.threads.toString()))
                } catch (t: Throwable) {
                    Log.w(TAG, "XNNPACK EP unavailable for $label; using CPU EP only", t)
                }
            }
        }
    }

    /** Debug knob `pocket.ortProfile`: close every session's profile and log where it went. */
    private fun endOrtProfiling() {
        for ((name, s) in listOf(
            "text" to textCondSession, "enc" to mimiEncoderSession, "dec" to mimiDecoderSession,
            "main" to flowLmMainSession, "flow" to flowLmFlowSession,
        )) {
            runCatching { s?.endProfiling() }
                .onSuccess { Log.i(PERF_TAG, "pocket ortProfile $name=$it") }
                .onFailure { Log.w(TAG, "endProfiling($name) failed: ${it.message}") }
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
            val phases = ArrayList<PhaseSpan>(2)
            val ar = startArSession(bundle, voiceEmb, tokens, preprocessed, phases = phases)
            for (p in phases) {
                Log.d(PERF_TAG, "pocket warmup prefill ${p.name}=${p.ms}ms${p.detail?.let { " ($it)" } ?: ""}")
            }
            val latents = ArrayList<FloatArray>(3)
            try {
                repeat(2) {
                    val step = stepAr(ar) ?: return@repeat
                    latents.add(step.latent)
                }
            } finally {
                ar.carry.close()
            }
            if (latents.isNotEmpty()) {
                val flat = FloatArray(latents.size * bundle.latentDim)
                var pos = 0
                for (l in latents) {
                    System.arraycopy(l, 0, flat, pos, bundle.latentDim)
                    pos += bundle.latentDim
                }
                runMimiDecoder(bundle, flat, mimiState ?: return)
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
     */
    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): SynthAudio = withContext(Dispatchers.Default) {
        // Pocket does its own phonemization upstream of ORT; the espeak
        // language knob doesn't apply.
        val parts = ArrayList<ShortArray>()
        var sampleRate = 0
        synthesizeStream(
            text, voiceId, speed, phonemizationLanguage,
            expressiveness = expressiveness, sentencePauseMs = sentencePauseMs,
        ).collect { chunk ->
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
     * Render [text] chunk by chunk (sentence-sized mini-chunks of at most
     * [PocketBundle.maxTokenPerChunk] tokens; each starts from fresh
     * flow_lm and mimi state, matching upstream's `_decode_audio_worker`),
     * sending audio in slices as it is decoded ([streamChunks], #15 "E").
     * The whole-chunk pipeline with its adaptive K-chunk pre-roll
     * (alpha.7) was removed in #15: streaming started sound 5× sooner on
     * the 6a and never ran dry.
     */
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        playbackRate: Float,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic — see KittenDirectEngine for the rationale.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        val bundle = bundle ?: error("bundle missing after load")
        val tokenizer = tokenizer ?: error("tokenizer missing after load")

        // `speed` is deliberately unused: the AR graphs have no speed input.
        // The services time-stretch instead — see [supportsNativeSpeed].

        val voiceName = voiceToRender(voiceId)

        // Chunking parity with KokoroDirect: sentence-only splits, never
        // mid-word, pack tiny adjacent sentences via `minChars` so a 5-char
        // "Yes." doesn't waste an AR session as its own chunk. Pocket used
        // to chunk by tokens (cap 25), which produced orphan fragments
        // ("Outside,") when a long sentence got comma-split — every
        // chunk boundary breaks Pocket's prosody seed, so it sounded
        // choppy. Post-process pass below enforces the model's hard
        // token limit on the rare 255-char chunk that exceeds it.
        // The alias's pause between sentences (#96): with one set, each
        // paragraph is prepared and chunked on its own (the preprocessing
        // collapses blank lines), and each outer chunk carries the pause that
        // follows it. At Natural this is the one plan it always was.
        val outerPlan = SentenceGaps.byParagraph(text.trim(), sentencePauseMs) { paragraph, _ ->
            pocketOuterChunks(preprocessForPocket(paragraph, bundle), sentencePauseMs)
        }
        val outerChunks = outerPlan.map { it.first }
        val miniChunks = ArrayList<String>(outerChunks.size)
        // The pause follows an outer chunk's last mini-chunk.
        val pauseAfter = ArrayList<Int>(outerChunks.size)
        for ((oi, chunk) in outerChunks.withIndex()) {
            val tokenCount = tokenizer.encode(chunk).size
            val before = miniChunks.size
            if (tokenCount <= bundle.maxTokenPerChunk) {
                miniChunks.add(chunk)
            } else {
                // Safety net: a single sentence packs more tokens than the
                // model can handle. Fall back to the old token-aware
                // sub-splitter (commas → words) for this chunk only.
                Log.d(TAG, "outer chunk has $tokenCount tokens > ${bundle.maxTokenPerChunk}; sub-splitting")
                miniChunks.addAll(chunkPocketByTokens(chunk, tokenizer, bundle, bundle.maxTokenPerChunk))
            }
            for (i in before until miniChunks.size) pauseAfter.add(if (i == miniChunks.size - 1) outerPlan[oi].second else 0)
        }
        if (miniChunks.isEmpty()) return@channelFlow
        Log.d(TAG, "synthesizeStream: ${miniChunks.size} mini-chunk(s) from ${outerChunks.size} outer chunk(s)")
        for ((i, c) in miniChunks.withIndex()) {
            Log.d(TAG, "  chunk[$i] (${c.length} chars): \"${c.take(80)}${if (c.length > 80) "…" else ""}\"")
        }
        val pace = RenderMode.paceFor(engineName, settingsRepository, ctx.filesDir, playbackRate)
        horizon.startRequest(pace.rate())

        // Everything that touches an ORT session runs under synthLock, and the
        // decoder coroutine ([streamChunks]) is a child of this inner
        // coroutineScope rather than of the channelFlow's producer scope. That way, when the
        // AR loop throws (Stop's ensureActive, the release check), the scope
        // cancels and then WAITS for an in-flight decode before withLock
        // releases — otherwise release() could close mimi_decoder mid-run()
        // (native SIGSEGV).
        synthLock.withLock { coroutineScope {
            // Under the lock: a first-use voice encodes through mimi_encoder.
            val voiceEmb = embeddingForVoice(voiceName)
            seed?.let { random.setSeed(it) }
            noiseStd = pocketNoiseStd(expressiveness)
            Log.d(TAG, "request temperature=${Expressiveness.pocketTemperature(expressiveness)}" +
                if (expressiveness == null) " (default)" else " (alias)")
            streamChunks(
                this@channelFlow, bundle, voiceEmb, miniChunks, playbackRate, pace, streamStartNs, loadWaitMs,
                pauseAfter,
            )
            if (profileRequestsLeft > 0 && --profileRequestsLeft == 0) endOrtProfiling()
        } }
    }.flowOn(Dispatchers.Default)

    /**
     * One slice of latent frames for the decoder. A chunk's [last] job may
     * have no frames (the slice before it took them all); it just ends the
     * chunk's decode.
     */
    private class DecodeJob(
        val chunk: Int,
        val slice: Int,
        /** The slice's index in the request's [PocketSliceClock], or -1 for an empty job. */
        val clockIndex: Int,
        val latents: FloatArray,
        val frames: Int,
        val last: Boolean,
        val chunkStartNs: Long,
        /** Frames in the whole chunk (set on its last job). */
        val chunkFrames: Int,
    )

    /**
     * The decoder side of [streamChunks]: one mimi state per chunk, decoded
     * a slice at a time (exact: every slice is within the ring limit,
     * [PocketMimiBatch]). Used by one thread at a time. [onDecoded] gets
     * each slice's audio (null for an empty last job) and decode time.
     */
    private inner class SliceDecoder(
        private val bundle: PocketBundle,
        private val onDecoded: suspend (SynthAudio?, DecodeJob, Double) -> Unit,
    ) {
        private val init = mimiState ?: error("mimi state missing — engine not loaded?")
        private var carry: PocketStateCarry? = null
        private var times = PocketTimes()
        private var chunkDecodeNs = 0L

        suspend fun decode(job: DecodeJob) {
            if (job.slice == 0) {
                close()
                times = PocketTimes()
                chunkDecodeNs = 0L
            }
            var audio: SynthAudio? = null
            var decodeNs = 0L
            if (job.frames > 0) {
                val c = carry ?: PocketStateCarry(
                    PocketStateCarry.bindSlots(env ?: error("ORT env missing"), bundle.mimiStateManifest, init),
                ).also { carry = it }
                val t0 = System.nanoTime()
                val pcm = decodeMimiChunk(bundle, job.latents, job.frames, c, times)
                decodeNs = System.nanoTime() - t0
                chunkDecodeNs += decodeNs
                times.decodedFrames += job.frames
                Log.d(PERF_TAG, "pocket slice chunk=${job.chunk} slice=${job.slice} frames=${job.frames} decode=${decodeNs / 1_000_000}ms")
                audio = SynthAudio(floatToPcm16(pcm), bundle.sampleRate, resumePoint = job.slice == 0)
            }
            if (job.last) {
                Log.d(PERF_TAG, times.decodeLine(job.chunk, chunkDecodeNs / 1_000_000))
                close()
            }
            onDecoded(audio, job, decodeNs / 1e6)
        }

        fun close() {
            carry?.close()
            carry = null
        }
    }

    /**
     * #15 "E" — streamed render. Each chunk's latent frames go to the
     * decoder in slices as the AR loop produces them, cut by deadline
     * ([PocketSliceClock]: the first after 6 frames, each later one before
     * playback could run dry), and each decoded slice is sent at once, or
     * held in a [PocketSliceBank] when a render slower than playback needs
     * a head start ([PocketStreamPlan.holdMs]). In chunk mode (#54, decided
     * per mini-chunk by [pace], at the speed it will be heard at) a
     * [PieceGate] in front of the bank holds the mini-chunk's slices until
     * the rest of it can't run dry ([PocketStreamPlan.chunkRest]), or until
     * it is fully decoded, so a wait falls between sentences. The cut rule
     * and the first-sound bank keep [playbackRate], the request's; only the
     * gate follows a speed change mid-request. With [STREAM_OVERLAP] the
     * decoder runs on its own coroutine, overlapping the AR steps; the
     * decoder's coroutine is a child of this scope, so a cancelled render
     * still waits for a decode in flight before [synthLock] is released
     * (same rule as the whole-chunk pipeline).
     */
    private suspend fun streamChunks(
        out: ProducerScope<SynthAudio>,
        bundle: PocketBundle,
        voiceEmb: FloatArray,
        miniChunks: List<String>,
        playbackRate: Float,
        pace: RequestPace,
        streamStartNs: Long,
        loadWaitMs: Long,
        /** Per mini-chunk: the ms of silence that follow it, an alias's pause (#96). */
        pauseAfter: List<Int>,
    ) = coroutineScope {
        val tokenizer = tokenizer ?: error("tokenizer missing")
        val chunkTokens = miniChunks.map { tokenizer.encode(preprocessForPocket(it, bundle)).size }
        val bank = PocketSliceBank<SynthAudio>()
        val bankLock = Mutex()
        val frameMs = 1000.0 / bundle.frameRate
        val overlap = streamMode == STREAM_OVERLAP
        val rate = playbackRate.coerceAtLeast(0.01f)
        val playedFrameMs = frameMs / rate
        // Chunk mode (#54): the gate sits in front of the bank. Each chunk's
        // AR progress is the AR thread's, read by the decoder: a job's channel
        // hand-off makes it at least as fresh as that job. chunkRest already
        // charges one slow decode per piece, so the gate adds none.
        var gateChunk = 0
        val gate = PieceGate<SynthAudio>(slowSlice = null, rate = pace::rate) { heldMs, slices, rest ->
            Log.d(PERF_TAG, "pocket chunk-mode release chunk=$gateChunk held=${heldMs}ms slices=$slices rest=$rest")
        }
        val generated = IntArray(miniChunks.size)
        val finished = BooleanArray(miniChunks.size)
        var decodedInChunk = 0
        var firstSent = false
        suspend fun send(audio: List<SynthAudio>) {
            for (a in audio) {
                if (!firstSent) {
                    firstSent = true
                    val ttfa = (System.nanoTime() - streamStartNs) / 1_000_000
                    Log.d(PERF_TAG, "pocket TTFA=${ttfa}ms (loadWait=${loadWaitMs}ms + producePath=${ttfa - loadWaitMs}ms) stream=$streamMode")
                }
                Log.d(PERF_TAG, "pocket send audio=${a.pcm.size * 1000L / a.sampleRate}ms")
                out.send(a)
                horizon.onEmitted((a.pcm.size * 1000.0 / a.sampleRate / pace.rate()).toLong())
            }
        }
        // The AR loop's pace (ms per step, not the prefill, not an inline
        // decode): last request's until this one has two frames.
        var arMs = streamArMsPerFrame
        var lastFrameNs = 0L
        var firstFrameNs = 0L
        var stepsTimed = 0
        // Chunk 0's voice prompt (phase 1) when it wasn't cached: later chunks
        // never pay it, so the hold's per-chunk prefill leaves it out.
        var chunk0PromptMs = 0L
        // Until a slice has been decoded, assume decoding costs what the AR step does.
        val decodeMs = { n: Int -> if (decodeCost.isEmpty) n * arMs else decodeCost.ms(n) }
        val clock = PocketSliceClock(decodeBatch, playedFrameMs, decodeMs, overlap, decodeSafety)
        var chunk0Frames = 0
        var holdTimer: Job? = null
        val decoder = SliceDecoder(bundle) { audio, job, ms ->
            val nowMs = System.nanoTime() / 1e6
            if (job.clockIndex >= 0) clock.decoded(job.clockIndex, nowMs)
            if (audio != null) decodeCost.add(job.frames, ms)
            if (job.slice == 0) {
                decodedInChunk = 0
            }
            decodedInChunk += job.frames
            if (audio != null && bank.releaseAtMs == null) {
                // First slice of the request: hold the first sound if the
                // render can't keep up with playback (never at 1x on the 6a).
                val framesPerToken = streamMsPerToken / frameMs
                val estFrames = chunkTokens.mapIndexed { i, tokens ->
                    val est = (tokens * framesPerToken).toInt()
                    if (i == 0) maxOf(est, chunk0Frames + 1) else est
                }
                // arMs, firstFrameNs, chunk0Frames and chunk0PromptMs are the AR
                // thread's, read here unsynchronised: all were written before
                // this slice was submitted, and a stale arMs only skews one
                // estimate (benign).
                val prefillMs = (firstFrameNs - job.chunkStartNs) / 1e6 - arMs - chunk0PromptMs
                val holdMs = PocketStreamPlan.holdMs(
                    estFrames, decodeBatch, arMs, prefillMs, decodeMs, overlap, playedFrameMs, decodeSafety,
                ).coerceAtMost(STREAM_HOLD_CAP_MS)
                clock.holdMs = holdMs
                bank.releaseAtMs = nowMs.toLong() + holdMs.toLong()
                Log.d(
                    PERF_TAG,
                    "pocket hold=${holdMs.toLong()}ms ar=${"%.1f".format(arMs)}ms/f " +
                        "dec(6)=${decodeMs(6).toLong()} dec(46)=${decodeMs(46).toLong()}ms " +
                        "estFrames=$estFrames rate=$playbackRate",
                )
                if (holdMs > 0) {
                    holdTimer = launch {
                        kotlinx.coroutines.delay(holdMs.toLong())
                        bankLock.withLock { send(bank.flush()) }
                    }
                }
            }
            if (job.last && job.chunkFrames > 0) {
                val audioMs = job.chunkFrames * frameMs
                val rtf = (System.nanoTime() - job.chunkStartNs) / 1e6 / audioMs
                streamMsPerToken = 0.5 * streamMsPerToken + 0.5 * (audioMs / chunkTokens[job.chunk].coerceAtLeast(1))
                Log.d(PERF_TAG, "pocket chunk=${job.chunk} rtf=${"%.2f".format(rtf)} audio=${audioMs.toLong()}ms")
            }
            bankLock.withLock {
                val sendMs = System.nanoTime() / 1_000_000
                if (job.slice == 0) {
                    val mode = pace.pieceMode()
                    horizon.follow(mode.playbackRate)
                    gate.startPiece(mode.chunk)
                    gateChunk = job.chunk
                }
                if (audio != null) {
                    val released = gate.offer(audio, job.frames * frameMs, sendMs, horizon.requestRemainingMs().toDouble()) {
                        if (job.last) {
                            emptyList()
                        } else {
                            PocketStreamPlan.chunkRest(
                                estFrames = PocketStreamPlan.chunkEstFrames(chunkTokens[job.chunk], streamMsPerToken, frameMs),
                                generatedFrames = generated[job.chunk],
                                decodedFrames = decodedInChunk,
                                finished = finished[job.chunk],
                                sliceFrames = minOf(maxOf(job.frames, PocketSliceClock.MIN_SLICE_FRAMES), maxOf(decodeBatch, 1)),
                                arMsPerFrame = arMs,
                                decodeMs = decodeMs,
                                overlap = overlap,
                                frameMs = frameMs,
                                decodeSafety = decodeSafety,
                            )
                        }
                    }
                    for (a in released) send(bank.offer(a, sendMs))
                }
                if (job.last) for (a in gate.endPiece(sendMs)) send(bank.offer(a, sendMs))
                // After the sentence's last slice, so a chunk-mode gate never holds it.
                if (job.last && pauseAfter[job.chunk] > 0) {
                    send(bank.offer(SentenceGaps.silence(pauseAfter[job.chunk], bundle.sampleRate), sendMs))
                }
            }
        }
        // Bounded, so the AR loop waits when the decoder does: when the
        // collector stops taking audio (the Narrator's read-ahead permit), the
        // decoder blocks in send() and, two slices later, so does the AR loop,
        // instead of rendering the rest of the request.
        val jobs = Channel<DecodeJob>(DECODE_QUEUE)
        val worker = if (overlap) {
            launch(Dispatchers.Default) {
                try {
                    for (job in jobs) {
                        // A queued job is received without suspending, so it
                        // would not see the AR side's failure on its own.
                        ensureActive()
                        decoder.decode(job)
                    }
                } finally {
                    decoder.close()
                }
            }
        } else {
            null
        }
        suspend fun submit(job: DecodeJob) = if (worker != null) jobs.send(job) else decoder.decode(job)
        try {
            for ((idx, miniChunk) in miniChunks.withIndex()) {
                val chunkStartNs = System.nanoTime()
                var slice = 0
                var chunkFrames = 0
                val pending = ArrayList<FloatArray>(decodeBatch)
                suspend fun cut(last: Boolean) {
                    val n = pending.size
                    val flat = FloatArray(n * bundle.latentDim)
                    for (i in 0 until n) System.arraycopy(pending[i], 0, flat, i * bundle.latentDim, bundle.latentDim)
                    pending.clear()
                    val t0 = System.nanoTime()
                    val index = if (n > 0) clock.submitted(n, t0 / 1e6).first else -1
                    if (last) finished[idx] = true
                    submit(DecodeJob(idx, slice++, index, flat, n, last, chunkStartNs, if (last) chunkFrames else 0))
                }
                val onPrompt = { ms: Long -> if (idx == 0) chunk0PromptMs = ms }
                generateChunkLatents(bundle, voiceEmb, miniChunk, idx, onPrompt) { latent ->
                    val nowNs = System.nanoTime()
                    if (chunkFrames > 0) {
                        val interval = (nowNs - lastFrameNs) / 1e6
                        arMs = if (stepsTimed++ == 0) interval else 0.8 * arMs + 0.2 * interval
                    }
                    if (firstFrameNs == 0L) firstFrameNs = nowNs
                    if (idx == 0) chunk0Frames++
                    chunkFrames++
                    generated[idx] = chunkFrames
                    pending.add(latent)
                    if (clock.shouldCut(pending.size, nowNs / 1e6, arMs)) cut(last = false)
                    lastFrameNs = System.nanoTime()
                }
                cut(last = true)
            }
            jobs.close()
            worker?.join()
            bankLock.withLock { send(bank.flush()) }
            streamArMsPerFrame = arMs
        } catch (t: Throwable) {
            // Cancelled or failed: what this stream sent has been cut off.
            horizon.clear()
            throw t
        } finally {
            // A render shorter than its hold ends here, not when the timer fires.
            holdTimer?.cancel()
            jobs.close()
            if (worker == null) decoder.close()
        }
    }

    /**
     * AR loop for one mini-chunk: each latent frame goes to [onLatent] as
     * it is generated. Uses `flow_lm_main` + `flow_lm_flow` only, so it can
     * run while `mimi_decoder` decodes earlier frames on another thread.
     */
    private suspend fun generateChunkLatents(
        bundle: PocketBundle,
        voiceEmb: FloatArray,
        miniChunk: String,
        chunkIdx: Int,
        /** Time spent running the voice prompt (0 when cached). */
        onPrompt: (Long) -> Unit,
        onLatent: suspend (FloatArray) -> Unit,
    ) {
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

        // P-AM.1: time the per-chunk prefill (phase 1 voice cond + phase 2
        // text cond) so logcat shows prefill vs AR-loop share directly.
        val phases = ArrayList<PhaseSpan>(2)
        val ar = startArSession(bundle, voiceEmb, tokens, preprocessed, phases = phases)
        for (p in phases) {
            Log.d(PERF_TAG, "pocket chunk=$chunkIdx prefill ${p.name}=${p.ms}ms${p.detail?.let { " ($it)" } ?: ""}")
        }
        val prompt = phases.first()
        onPrompt(if (prompt.detail == "cached") 0L else prompt.ms)
        try {
            arLoop(ar, miniChunk, chunkIdx, onLatent)
        } finally {
            ar.carry.close()
        }
    }

    /** [generateChunkLatents]' frame loop, from a primed [ar]. */
    private suspend fun arLoop(
        ar: ArSession,
        miniChunk: String,
        chunkIdx: Int,
        onLatent: suspend (FloatArray) -> Unit,
    ) {
        var frames = 0
        // P-AD diagnostics: track latent magnitude + EOS so a glitchy chunk's
        // logcat distinguishes the two leading theories (per adversarial
        // review): (1) EOS-failure → over-generation (eosFired=false,
        // frames==maxFrames); (2) bad noise draw → AR divergence (peakLatentAbs
        // blows up / nonFinite=true). The corruption persists across a whole
        // chunk because the AR loop feeds previousLatent=nextLatent forward.
        var peakLatentAbs = 0f
        var sawNonFinite = false
        val loopStartNs = System.nanoTime()
        while (true) {
            // Cooperative cancellation. The AR step is a non-suspend ORT
            // native call (~50–100 ms on Pixel 8a depending on EP/thread
            // count); without this check, a Stop tap couldn't take effect
            // until the current mini-chunk finished its full AR loop.
            coroutineContext.ensureActive()
            check(!releaseRequested.get()) { "Pocket engine released mid-synthesis" }
            val step = stepAr(ar) ?: break
            for (v in step.latent) {
                if (!v.isFinite()) { sawNonFinite = true; continue }
                val a = if (v < 0f) -v else v
                if (a > peakLatentAbs) peakLatentAbs = a
            }
            frames++
            onLatent(step.latent)
        }

        Log.d(PERF_TAG, ar.times.arLine(chunkIdx, (System.nanoTime() - loopStartNs) / 1_000_000))

        // Per-chunk summary. WARN on the two failure signatures; INFO otherwise.
        val summary = "Pocket chunk $chunkIdx: frames=$frames/${ar.maxFrames} " +
            "eosFired=${ar.eosFired}@${ar.eosFiredAtFrame} " +
            "peakLatentAbs=${"%.2f".format(peakLatentAbs)} nonFinite=$sawNonFinite " +
            "text=\"${miniChunk.take(48)}\""
        if (!ar.eosFired || sawNonFinite) {
            Log.w(TAG, "SUSPECT $summary")
        } else {
            Log.i(TAG, summary)
        }
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
     *     greedy word packing ([PocketTokenSplit.byWords]; a single word
     *     over the limit is cut into pieces).
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

        // Every count is of the text as [generateChunkLatents] encodes it:
        // preprocessForPocket capitalises it and may append a `.`, one more
        // token, so a chunk measured raw could still go over the limit there.
        val chunkTokens = { chunk: String -> tokenizer.encode(preprocessForPocket(chunk, bundle)).size }

        // Step 2: refine — sub-split oversized sentences.
        val segments = ArrayList<String>(sentences.size * 2)
        for (s in sentences) {
            if (chunkTokens(s) <= maxTokens) {
                segments.add(s)
                continue
            }
            val parts = COMMA_SPLIT_REGEX.split(s)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size > 1) {
                for (p in parts) {
                    if (chunkTokens(p) <= maxTokens) {
                        segments.add(p)
                    } else {
                        segments.addAll(PocketTokenSplit.byWords(p, maxTokens, chunkTokens))
                    }
                }
            } else {
                segments.addAll(PocketTokenSplit.byWords(s, maxTokens, chunkTokens))
            }
        }

        // Step 3: greedy bin-pack into chunks.
        return PocketTokenSplit.pack(segments, maxTokens, chunkTokens)
    }

    override fun release() {
        try {
            // Closing a session another thread is mid-run() on is a native
            // SIGSEGV, so release must exclude BOTH a concurrent load
            // (loadLock) and a concurrent synth (synthLock). synthesizeStream
            // holds synthLock for the whole utterance, so we first raise
            // [releaseRequested] — the AR loop's per-frame check aborts the
            // synth within ~one frame — then take synthLock → loadLock and
            // close. That lock order can't deadlock: this is the only place
            // both locks are held at once (load never waits on synthLock;
            // synth takes loadLock only via ensureLoadedSuspending, before
            // acquiring synthLock).
            releaseRequested.set(true)
            try {
                kotlinx.coroutines.runBlocking {
                    synthLock.withLock { loadLock.withLock { releaseInternal() } }
                }
            } finally {
                releaseRequested.set(false)
            }
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
        // The 56 mimi state slots are MB-scale direct buffers — nulling
        // here is what actually frees the native memory after uninstall
        // (this @Singleton outlives the engine's files).
        mimiState = null
        voicePrompt?.frame?.release()
        voicePrompt = null
        flowInitStates.clear()
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
     * **Cap the clip length before wiring this to a UI.** [encodePcm]'s
     * only cap is 30 s, and `mimi_encoder`'s peak memory grows roughly
     * with the square of the clip length (desktop ORT, 3 threads, issue
     * #38: 10 s 0.9 GB, 15 s 1.7 GB, 20 s 3.0 GB, 26 s 4.4 GB; on a Pixel
     * 6a a 10 s reference peaked at +1.1 GB and a 16.8 s one at +2.65 GB).
     * A 26 s reference got the app OOM-killed on a 4 GB emulator, so cut a
     * user's clip to about the built-in voices' 10–13 s before it gets
     * here. The built-in voices avoid the encoder entirely (v27 ships their
     * `voice_cache/<voice>.emb`); a clone can't, so this cap stands.
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
        voiceEmbeddings.remove(localId)?.let { embedding ->
            // Its cached voice prompt can't be matched again: free it once no
            // render holds the lock (without making the caller wait for one).
            warmupScope.launch {
                synthLock.withLock {
                    if (voicePrompt?.embedding === embedding) {
                        voicePrompt?.frame?.release()
                        voicePrompt = null
                    }
                }
            }
        }
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

    /**
     * Live state of an in-flight autoregressive synthesis. Held across
     * many [stepAr] calls; not safe to share between synths.
     *
     * Carved out of the old monolithic `runFlowLm` so the AR loop can
     * be driven frame-by-frame from `synthesizeStream`'s flow builder
     * (the batched `synthesize` collects that same stream).
     */
    private class ArSession(
        val bundle: PocketBundle,
        val ort: OrtEnvironment,
        val main: OrtSession,
        val flow: OrtSession,
        /** flow_lm state (#15 "A"); the caller closes it when the loop ends. */
        val carry: PocketStateCarry,
        val maxFrames: Int,
        val framesAfterEos: Int,
        var previousLatent: FloatArray,
        var frameIdx: Int = 0,
        var eosFired: Boolean = false,
        var eosFiredAtFrame: Int = -1,
        var framesPostEos: Int = 0,
        var done: Boolean = false,
        val times: PocketTimes = PocketTimes(),
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
     * Returns the live AR session whose `carry` holds the post-priming
     * state ready for [stepAr] calls.
     *
     * [phases], when non-null, receives a timing span per conditioning
     * phase (P-AM.1 — logged by callers under [PERF_TAG]).
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

        // Zero-copy (#15 "A"): every chunk starts from the engine-level init
        // state, which ORT only ever reads, so it is never refilled.
        // The voice prompt's state is cached by embedding identity (#15 "C").
        // A symbolic cache axis (#15 "F") is sized for the voice prompt plus
        // the largest normal chunk, so one cached prompt serves every chunk.
        val promptRows = voiceEmbedding.size / bundle.conditioningDim + if (bundle.insertBosBeforeVoice) 1 else 0
        val sizingTokens = maxOf(tokens.size, bundle.maxTokenPerChunk)
        val cacheLen = flowCacheLenKnob ?: if (flowCacheDynamic) {
            PocketCacheShapes.flowLenFor(promptRows, sizingTokens, estimateMaxFrames(bundle, sizingTokens))
        } else {
            null
        }
        val manifest = cacheLen?.let { PocketCacheShapes.flowLm(bundle.flowLmStateManifest, it) } ?: bundle.flowLmStateManifest
        val cachedPrompt = voicePrompt
            ?.takeIf { it.embedding === voiceEmbedding && it.cacheLen == cacheLen }?.frame
        val carry = if (cachedPrompt != null) {
            PocketStateCarry(cachedPrompt.retain())
        } else {
            val init = flowInitStates.getOrPut(cacheLen ?: 0) { initStates(manifest) }
            PocketStateCarry(PocketStateCarry.bindSlots(ort, bundle.flowLmStateManifest, init))
        }
        try {
            // PHASE 1: voice conditioning.
            // text_embeddings = bos_before_voice ++ voice_embedding, shape [1, V+1, conditioningDim]
            val phase1Start = System.currentTimeMillis()
            if (cachedPrompt == null) {
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
                    carry = carry,
                    sequenceData = FloatArray(0),
                    sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
                    textEmbedsData = concat,
                    textEmbedsShape = longArrayOf(1, totalFrames.toLong(), bundle.conditioningDim.toLong()),
                    captureConditioning = false,
                )
                run {
                    voicePrompt?.frame?.release()
                    voicePrompt = VoicePrompt(voiceEmbedding, cacheLen, carry.snapshot())
                }
            }
            phases?.add(
                PhaseSpan(
                    "flow-lm phase 1 (voice cond)",
                    System.currentTimeMillis() - phase1Start,
                    detail = if (cachedPrompt != null) "cached" else null,
                ),
            )

            // PHASE 2: text conditioning.
            val phase2Start = System.currentTimeMillis()
            val tcStart = System.currentTimeMillis()
            val textEmbeds = runTextConditioner(textCond, tokens, bundle, ort)
            val tcMs = System.currentTimeMillis() - tcStart
            runFlowLmMain(
                session = main,
                bundle = bundle,
                ort = ort,
                carry = carry,
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
        } catch (t: Throwable) {
            carry.close()
            throw t
        }

        val estimate = estimateMaxFrames(bundle, tokens.size)
        val cacheRows = cacheLen ?: PocketCacheShapes.flowRows(bundle.flowLmStateManifest)
        val maxFrames = PocketCacheShapes.frameBudget(estimate, cacheRows, promptRows, tokens.size)
        if (maxFrames < estimate) {
            Log.w(TAG, "Frame cap $estimate cut to $maxFrames: flow cache $cacheRows rows, prompt $promptRows, tokens ${tokens.size}")
        }
        return ArSession(
            bundle = bundle,
            ort = ort,
            main = main,
            flow = flow,
            carry = carry,
            maxFrames = maxFrames,
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
            carry = ar.carry,
            sequenceData = ar.previousLatent,
            sequenceShape = longArrayOf(1, 1, ar.bundle.latentDim.toLong()),
            textEmbedsData = FloatArray(0),
            textEmbedsShape = longArrayOf(1, 0, ar.bundle.conditioningDim.toLong()),
            captureConditioning = true,
            times = ar.times,
        )!! // captureConditioning=true guarantees non-null
        val flowStartNs = System.nanoTime()
        val nextLatent = runFlowEuler(ar.flow, ar.ort, capture.conditioning, ar.flowScratch, ar.bundle.latentDim)
        ar.times.flowNs += System.nanoTime() - flowStartNs
        ar.times.frames++
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
     * Run flow_lm_main once (#15 "A", zero-copy): the state tensors come
     * from [carry] and the run's state outputs become its next frame, so no
     * state byte is copied in Java. Phases 1 + 2 and the AR step build
     * `sequence` and `text_embeddings` per call (their shapes differ).
     *
     * @param captureConditioning when true, returns the `conditioning`
     *   and `eos_logit` outputs (phase 3 needs them). When false (phases
     *   1 + 2) returns null.
     */
    private fun runFlowLmMain(
        session: OrtSession,
        bundle: PocketBundle,
        ort: OrtEnvironment,
        carry: PocketStateCarry,
        sequenceData: FloatArray,
        sequenceShape: LongArray,
        textEmbedsData: FloatArray,
        textEmbedsShape: LongArray,
        captureConditioning: Boolean,
        times: PocketTimes? = null,
    ): FlowLmCapture? {
        val seqT = directFloatTensor(ort, sequenceData, sequenceShape)
        val textT = directFloatTensor(ort, textEmbedsData, textEmbedsShape)
        try {
            val bindStartNs = System.nanoTime()
            val inputs = LinkedHashMap<String, OnnxTensor>(bundle.flowLmStateManifest.size + 2)
            inputs["sequence"] = seqT
            inputs["text_embeddings"] = textT
            carry.bind(bundle.flowLmStateManifest, inputs)
            val runStartNs = System.nanoTime()
            val result = session.run(inputs)
            val runEndNs = System.nanoTime()
            val capture = try {
                if (captureConditioning) readCapture(result, bundle) else null
            } catch (t: Throwable) {
                result.close()
                throw t
            }
            carry.advance(bundle.flowLmStateManifest, result)
            if (times != null) {
                times.mainNs += runEndNs - runStartNs
                times.mainStateNs += (runStartNs - bindStartNs) + (System.nanoTime() - runEndNs)
            }
            return capture
        } finally {
            seqT.close()
            textT.close()
        }
    }

    private fun readCapture(result: OrtSession.Result, bundle: PocketBundle): FlowLmCapture {
        val condTensor = result.get("conditioning").orElseThrow {
            IllegalStateException("flow_lm_main did not return 'conditioning'")
        } as OnnxTensor
        val cond = FloatArray(bundle.conditioningDim)
        condTensor.floatBuffer.get(cond)
        val eosTensor = result.get("eos_logit").orElseThrow {
            IllegalStateException("flow_lm_main did not return 'eos_logit'")
        } as OnnxTensor
        return FlowLmCapture(conditioning = cond, eosLogit = eosTensor.floatBuffer.get(0))
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
        val noiseStd = this.noiseStd
        val clamp = NOISE_CLAMP_ABS
        val current = FloatArray(latentDim) {
            var x = random.nextGaussian().toFloat() * noiseStd
            // Rejection sample (trunc_normal_ semantics). Rate ~3e-4 at
            // std=0.837 (temperature 0.7, the slider's top), ~4e-8 at the
            // default 0.3, clamp=3 — the inner loop almost never re-runs.
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
     * Decode [numFrames] latent frames through mimi_decoder, advancing
     * [carry] (zero-copy, #15 "A": the run's state outputs become the next
     * run's inputs).
     */
    private fun decodeMimiChunk(
        bundle: PocketBundle,
        latents: FloatArray,
        numFrames: Int,
        carry: PocketStateCarry,
        times: PocketTimes?,
    ): FloatArray {
        val ort = env ?: error("ORT env missing")
        val session = mimiDecoderSession ?: error("mimi decoder session missing")
        val bindStartNs = System.nanoTime()
        val latT = directFloatTensor(
            ort, latents,
            longArrayOf(1, numFrames.toLong(), bundle.latentDim.toLong()),
        )
        try {
            val inputs = LinkedHashMap<String, OnnxTensor>(bundle.mimiStateManifest.size + 1)
            inputs["latent"] = latT
            carry.bind(bundle.mimiStateManifest, inputs)
            val out = FloatArray(numFrames * bundle.samplesPerFrame)
            val runStartNs = System.nanoTime()
            val result = session.run(inputs)
            val runEndNs = System.nanoTime()
            try {
                val audioTensor = result.get("audio_frame").orElseThrow {
                    IllegalStateException("mimi_decoder did not return 'audio_frame'")
                } as OnnxTensor
                audioTensor.floatBuffer.get(out)
            } catch (t: Throwable) {
                result.close()
                throw t
            }
            carry.advance(bundle.mimiStateManifest, result)
            if (times != null) {
                times.decodeRunNs += runEndNs - runStartNs; times.decodeRuns++
                times.decodeStateNs += (runStartNs - bindStartNs) + (System.nanoTime() - runEndNs)
            }
            return out
        } finally {
            latT.close()
        }
    }

    /**
     * Decode [latents] whole, from [mimiState] (the warmup's decode; the
     * streamed render decodes slice by slice in [streamChunks]).
     */
    private fun runMimiDecoder(
        bundle: PocketBundle,
        latents: FloatArray,
        mimiState: PocketStates,
    ): FloatArray {
        if (latents.isEmpty()) return FloatArray(0)
        val numFrames = latents.size / bundle.latentDim
        val dim = bundle.latentDim
        val spf = bundle.samplesPerFrame
        val pcm = FloatArray(numFrames * spf)

        // [mimiState] is only the init source, never written (#15 "A").
        val carry = PocketStateCarry(PocketStateCarry.bindSlots(env ?: error("ORT env missing"), bundle.mimiStateManifest, mimiState))
        try {
            // #15 "D": consecutive exact batches, nothing decoded twice.
            for (range in PocketMimiBatch.batches(numFrames, decodeBatch)) {
                val n = range.last - range.first + 1
                val part = latents.copyOfRange(range.first * dim, (range.last + 1) * dim)
                val out = decodeMimiChunk(bundle, part, n, carry, times = null)
                System.arraycopy(out, 0, pcm, range.first * spf, out.size)
            }
        } finally {
            carry.close()
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
        // P-AM — line breaks read as sentence pauses (see [PocketLineBreaks]).
        // Android-input robustness — upstream `prepare_text_prompt` collapses
        // newlines to spaces.
        s = PocketLineBreaks.apply(s)
        // Collapse remaining runs of whitespace (tabs/multi-spaces) to single spaces.
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
         * P-T diagnostic toggle. When true, every Pocket session gets
         * VERBOSE ORT logging (level 0). Captures kernel placement at
         * session-create so we can see which ops escape XNNPACK. Flip back
         * to false after capture — VERBOSE logging during inference is a
         * firehose. Inspect with `adb logcat -s onnxruntime`.
         */
        private const val VERBOSE_ORT_DIAGNOSTIC = false

        // The exported mimi_decoder is not window-invariant: a batch longer
        // than its attention ring holds overflows it and corrupts the batch's
        // early frames (the old "distorted first word"). History: P-X (one
        // whole-chunk batch) glitched; P-AI decoded a per-frame ramp then
        // batches; P-AL (alpha.12) re-decoded 64-frame batches behind an
        // 8-frame per-frame lead-in, which still corrupted frames 8–26 of long
        // sentences. #15 "D" found the cause, a 1000-slot ring at 16 decoder
        // steps per frame, and every slice now fits it
        // ([PocketMimiBatch.maxExactBatch], [decodeMimiChunk]). The earlier
        // mask-the-symptom knobs (MIMI_COLD_START_TRIM_MS P-AA, warm-prime
        // MIMI_WARMUP_FRAMES P-AF, a never-wired CHUNK_FADE_IN_MS P-AB) were
        // all removed once the real cause was found.

        /**
         * Euler integration steps for flow_lm_flow. **Upstream ships 1** —
         * LSD (Lagrangian Self-Distillation) trains the flow to collapse the
         * diffusion trajectory into a single step, so 1 is the quality floor,
         * not a corner cut.
         *
         * History: P-AC.D (2026-05) bumped 1 → 4 to mask the intermittent
         * chunk-start artefact (frame 0's BOS-substituted conditioning + a
         * single Euler step could land off-manifold → ~1 degraded word). BUT
         * that artefact was later ROOT-CAUSED (P-AC/P-AL, 2026-06) to the mimi
         * decoder not being window-invariant (fixed by exact decode batches,
         * #15 "D"). So the reason for 4 is superseded — reverted to upstream's
         * 1 to reclaim ~3× of the per-frame flow_lm_flow work (a real RTF
         * win). If a frame-0 artefact returns, that's a separate conditioning
         * bug to fix at the source, not a reason to re-inflate steps.
         */
        private const val LSD_DECODE_STEPS = 1

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

        const val STREAM_INLINE = "inline"
        const val STREAM_OVERLAP = "overlap"

        /**
         * Longest first-sound hold (#15 "E"): a render slower than real time
         * would otherwise hold a long request for most of its render.
         */
        private const val STREAM_HOLD_CAP_MS = 3_000.0

        /** Slices the AR loop may queue for the decoder before it waits ([streamChunks]). */
        private const val DECODE_QUEUE = 2

        /** Starting guess for audio per Pocket token (#15 "E"); refined per chunk. */
        private const val DEFAULT_MS_PER_TOKEN = 110.0

        /** Starting guess for the AR loop's ms per frame (#15 "E"; ~35 on a Pixel 6a). */
        private const val DEFAULT_AR_MS_PER_FRAME = 60.0

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

        // -- chunking knobs (synthesizeStream) -----------------------------
        //
        // Pocket adopted KokoroDirect's former chunking discipline
        // (sentence-only, char-bound, minChars-merge; Kokoro has since moved
        // to token-sized plans, TextChunker.planByTokens). An older
        // token-based bin-pack produced orphan fragments ("Outside,")
        // whenever a sentence got comma-split, which broke Pocket's per-chunk
        // prosody seed and sounded choppy; the main path now uses
        // [TextChunker.chunk] with those params, and the rare oversized-sentence
        // fallback ([chunkPocketByTokens]) caps at bundle.maxTokenPerChunk.

        /** Sentence-boundary cap (KokoroDirect's former character cap). */
        private const val POCKET_MAX_CHARS_PER_CHUNK = 255

        /**
         * Pocket's sentence chunks of [preprocessed] text. At Natural this is
         * the pre-#96 plan exactly. With a pause, tiny chunks still merge
         * across clause marks and abbreviations, never across a real sentence
         * end ([SentenceGaps.isSentenceBreak]), so each sentence gets its pause.
         */
        internal fun pocketOuterChunks(preprocessed: String, sentencePauseMs: Int): List<String> =
            TextChunker.chunk(
                text = preprocessed,
                maxChars = POCKET_MAX_CHARS_PER_CHUNK,
                packSentences = false,
                sentenceOnly = true,
                allowWordSplits = false,
                minChars = POCKET_MIN_CHARS_PER_CHUNK,
                keepApart = if (sentencePauseMs > 0) SentenceGaps::isSentenceBreak else null,
            )

        /**
         * Tiny-sentence merge threshold. Lower than KokoroDirect's 80
         * because Pocket runs sub-realtime: merging two ~70-char sentences
         * into a single ~150-char chunk doubles the AR loop's wall-clock
         * for that chunk, which exceeds the prior chunk's audio duration
         * and starves the consumer (audible gap). Set to 40 so sentences
         * 40+ chars emit alone — keeps per-chunk size variance low. Truly
         * tiny sentences ("Yes.") still merge.
         */
        private const val POCKET_MIN_CHARS_PER_CHUNK = 40

        /** Sentence boundary: `.`, `!`, or `?` followed by whitespace. */
        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")

        /** Sub-sentence boundary: `,`, `;`, or `:` followed by whitespace. */
        private val COMMA_SPLIT_REGEX = Regex("(?<=[,;:])\\s+")

        // Stochastic noise source for the flow/Euler sampler (nextGaussian()
        // per Euler step). NOTE: this is a STATIC companion singleton — shared
        // process-wide, NOT ThreadLocal and NOT engine-scoped. Access is
        // serialised via synthLock so the draw order is deterministic within a
        // synth, but the seed is the global default → output is non-reproducible
        // run-to-run. Seeding it (per utterance) would make generation
        // deterministic — deliberately not done (see the RTF/validation notes).
        private val random: java.util.Random = java.util.Random()
    }
}
