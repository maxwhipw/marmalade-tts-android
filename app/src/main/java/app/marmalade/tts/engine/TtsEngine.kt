package app.marmalade.tts.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Engine-agnostic TTS surface.
 *
 * Every engine (Kokoro Direct, Kitten Direct, Pocket TTS — all running on
 * Microsoft `onnxruntime-android` directly) implements this contract so
 * the synthesis pipeline can route to any of them through one interface.
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
     * Maximum input characters the engine handles in a single
     * [synthesize] / [synthesizeStream] call without quality
     * degradation or stalling. Callers ([app.marmalade.tts.audio.Synthesizer]
     * + the TTS services) split longer inputs into ≤ this size before
     * dispatching, then concatenate the audio.
     *
     * Reasonable bounds:
     *  - Kokoro Direct / Kitten Direct: around 400 chars. Larger inputs
     *    stress buffer allocation and risk hitting the Android TTS
     *    service's 10-second synth watchdog on long sentences.
     *  - Pocket TTS: ~120 chars, mapping to its ~50-token-per-chunk
     *    bundle constraint. Beyond this the model skips words.
     *
     * Default is unlimited — engines opt in by overriding this.
     */
    val maxInputChars: Int get() = Int.MAX_VALUE

    /**
     * True if the engine's bundle is present on disk and structurally
     * valid. Cheap — does not load the model into memory.
     */
    fun isInstalled(): Boolean

    /**
     * True if the model is resident right now — i.e. the next
     * [synthesize] pays no load cost.
     *
     * Must be cheap and non-blocking: a plain read of the engine's
     * publish-last "loaded" marker. It must not take the load lock, touch
     * the filesystem, or trigger a load, because the UI calls it on the
     * main thread to decide whether a Speak tap needs a visible
     * "Loading <engine>…" state or can go straight to "Speaking…".
     *
     * Racing a concurrent load is harmless: a false negative costs one
     * redundant (idempotent) `preload` call, a false positive is
     * impossible because engines publish the marker last.
     */
    fun isLoaded(): Boolean

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
     *
     * [phonemizationLanguage] is an optional espeak voice/language code
     * (e.g. `"en-us"`, `"ja"`, `"cmn"`) — when non-null, engines that
     * use espeak (KokoroDirect, KittenDirect) override their voice's
     * natural language with this. null means "engine decides" — for
     * KokoroDirect that's per-voice via `espeakVoiceFor(voiceKey)`,
     * for everyone else it's a no-op. Sherpa-backed engines have their
     * lexicon baked in and ignore this field.
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String? = null,
    ): SynthAudio

    /**
     * Release native resources (file handles, mmap'd weights, ORT
     * sessions). Idempotent — calling on an unloaded engine is a no-op.
     * Safe to call from any thread.
     */
    fun release()

    /**
     * Streaming variant of [synthesize]: emit one or more PCM chunks as
     * they become available. Each emitted [SynthAudio] is mono PCM16
     * at the engine's sample rate.
     *
     * Default implementation emits the full synth result as a single
     * element — back-compatible for engines whose pipeline can't
     * produce partial output (Kokoro Direct + Kitten Direct fall here:
     * each ORT run is a single-shot call).
     * PocketEngine overrides this to emit per-chunk during the
     * autoregressive loop, cutting time-to-first-audio.
     *
     * The consumer (Synthesizer / system TTS service) decides whether
     * to use this path — it must be willing to consume audio
     * progressively (AudioTrack MODE_STREAM, or per-chunk callback).
     * Streaming is incompatible with stateful post-processing (CAVE
     * reverb tail, chorus/tremolo LFO phase), so the consumer typically
     * gates the streaming path on effect=NONE and emotion=neutral.
     *
     * The flow is cancellable — collectors that throw or cancel will
     * tear down the engine's generation loop cleanly via structured
     * concurrency.
     */
    fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String? = null,
    ): Flow<SynthAudio> = flow {
        emit(synthesize(text, voiceId, speed, phonemizationLanguage))
    }

    /**
     * Synthesize + return timing breakdown alongside the audio.
     *
     * Default implementation measures only the coarse wall-clock spans
     * (load, total) — adequate for engines whose synth pipeline is a
     * single opaque ORT call (Kokoro Direct + Kitten Direct fall here).
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
