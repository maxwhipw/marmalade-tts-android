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
}
