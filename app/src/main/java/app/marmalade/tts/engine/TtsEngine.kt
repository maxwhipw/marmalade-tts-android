package app.marmalade.tts.engine

/**
 * Engine-agnostic TTS surface.
 *
 * Lifted out of [SherpaEngine] so non-sherpa engines (initially Pocket
 * TTS, which runs on Microsoft `onnxruntime-android` directly) can
 * implement the same contract without inheriting sherpa-onnx-specific
 * machinery (model-config builder, speaker-id map, etc.).
 *
 * Implementations must be thread-safe — [synthesize] runs on
 * `Dispatchers.Default` from any caller, and [ensureModelLoaded] /
 * [release] are called from arbitrary background coroutines.
 *
 * The engine is allowed to be expensive to construct (Hilt @Singleton is
 * the expected scope) and expensive to *load* — call [ensureModelLoaded]
 * lazily, idempotently, and let the first synth pay the cost.
 */
interface TtsEngine {

    /**
     * Catalog identifier. Matches `VoiceMeta.engine` rows and the
     * install directory name under `${filesDir}/engines/<name>/`.
     */
    val engineName: String

    /** PCM sample rate emitted by [synthesize], in Hz. */
    val sampleRate: Int

    /**
     * True if the engine's bundle is present on disk and structurally
     * valid. Cheap — does not load the model into memory.
     */
    fun isInstalled(): Boolean

    /**
     * Lazily load the model into memory. Idempotent and thread-safe.
     *
     * @throws EngineNotInstalledException if the bundle isn't present.
     * @throws IllegalStateException for other init failures.
     */
    fun ensureModelLoaded()

    /**
     * Synthesize [text] via the given [voiceId] at [speed]. Suspends on
     * `Dispatchers.Default` internally; callers can `runBlocking` on a
     * worker thread without monopolising the dispatcher.
     */
    suspend fun synthesize(text: String, voiceId: String, speed: Float): SynthAudio

    /**
     * Release native resources (file handles, mmap'd weights, ORT
     * sessions). Idempotent — calling on an unloaded engine is a no-op.
     * Safe to call from any thread.
     */
    fun release()

    /**
     * Synthesize + return timing breakdown alongside the audio.
     *
     * Default implementation measures only the coarse wall-clock spans
     * (load, total) — adequate for engines whose synth pipeline is a
     * single opaque native call (the sherpa-backed engines all fall
     * here: `OfflineTts.generate` is a black box from our side, so
     * decomposing it would mean forking sherpa-onnx).
     *
     * Engines that own their inference pipeline (currently
     * [app.marmalade.tts.engine.PocketEngine]) override this to attach
     * per-phase detail (tokenize, voice-encode, text conditioner,
     * autoregressive loop, decoder, ...).
     *
     * Only used by the debug benchmark screen — production callers
     * stay on plain [synthesize] to avoid the (tiny) overhead.
     */
    suspend fun synthesizeWithTimings(
        text: String,
        voiceId: String,
        speed: Float,
    ): TimedSynthAudio {
        val loadStart = System.currentTimeMillis()
        ensureModelLoaded()
        val loadMs = System.currentTimeMillis() - loadStart
        val t0 = System.currentTimeMillis()
        val audio = synthesize(text, voiceId, speed)
        val totalMs = System.currentTimeMillis() - t0
        return TimedSynthAudio(
            audio = audio,
            timings = EnginePhaseTimings(
                engineName = engineName,
                totalMs = totalMs,
                loadMs = loadMs,
            ),
        )
    }
}

/**
 * Wall-clock measurements from one [TtsEngine.synthesizeWithTimings]
 * call. The `phases` list is empty for engines whose synth is a single
 * opaque call; Pocket TTS populates it with its tokenize / voice-encode
 * / text-conditioner / AR-loop / decoder breakdown.
 */
data class EnginePhaseTimings(
    val engineName: String,
    /** Wall-clock spent in [TtsEngine.synthesize] proper (excludes load). */
    val totalMs: Long,
    /**
     * Wall-clock spent in `ensureModelLoaded`. 0 on the warm path; only
     * non-zero on the first synth after process start (or after release).
     */
    val loadMs: Long,
    /**
     * Optional per-phase breakdown. Order is meaningful (phases run
     * sequentially in the order they appear here).
     */
    val phases: List<PhaseSpan> = emptyList(),
)

/** Named span of measured wall-clock work. */
data class PhaseSpan(
    val name: String,
    val ms: Long,
    /** Optional one-liner shown alongside the duration (e.g. `"167 frames @ 4.3 ms/frame"`). */
    val detail: String? = null,
)

/** Synth output + timing metadata. Returned by [TtsEngine.synthesizeWithTimings]. */
data class TimedSynthAudio(
    val audio: SynthAudio,
    val timings: EnginePhaseTimings,
)
