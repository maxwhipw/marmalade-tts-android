package app.marmalade.tts.audio

import android.content.Context
import android.content.Intent
import android.util.Log
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.PocketDevEngine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.api.CloudApiEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.service.MarmaladeSynthService
import app.marmalade.tts.service.PreviewCompletions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   ViewModel.speak(text, voiceId, speed, effectBlocks, lang)
//     │
//     ▼
//   Synthesizer.speak(...)   — a thin client of MarmaladeSynthService
//     │
//     ├── requestId = PreviewCompletions.newRequestId()
//     ├── subscribe to PreviewCompletions.events (BEFORE the start)
//     ├── startForegroundService(ACTION_SPEAK + explicit voice/speed/
//     │     effect-blocks-JSON/lang + requestId)
//     │       └── the service owns the WHOLE pipeline: preprocessing,
//     │           language auto-detect, prosody, effects, streaming,
//     │           AudioTrack, audio focus, MediaSession, residency,
//     │           TTFA latency sampling
//     └── await the requestId's completion  ──► Result
//
//   Why not a local AudioTrack (the pre-2026-08-09 design): Android 16's
//   audio hardening mutes-and-parks a bare activity's track (gain -inf,
//   frames never consumed) while the service's playback mixes normally.
//
//   Errors (posted by the service through PreviewCompletions):
//     MODEL_MISSING ──► Result.failure(SynthesizerException.ModelMissing)
//     FAILED        ──► Result.failure(SynthesizerException.SynthesisFailed)
// -----------------------------------------------------------------------------

/** Typed errors so callers can switch on the failure mode without parsing strings. */
sealed class SynthesizerException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Engine assets haven't been downloaded yet. UI routes user to Engines screen. */
    object ModelMissing : SynthesizerException("TTS engine not installed")

    /** Anything else — JNI failure, invalid voice ID, AudioTrack init, etc. */
    class SynthesisFailed(cause: Throwable) :
        SynthesizerException(cause.message ?: "synthesis failed", cause)
}

/**
 * Surface area that the ViewModels actually consume from [Synthesizer].
 *
 * Extracted so JVM unit tests can substitute a fake without standing up
 * a real engine (which needs an Android Context for asset access and an
 * ONNX Runtime session).
 */
interface SpeechPlayer {
    /**
     * Synthesize [text] using [voiceId], optionally rate-scaled by [speed]
     * (1.0 = native pace) and shaped by the [effectBlocks] chain (empty =
     * dry). Returns when audio has finished playing (or been cancelled).
     *
     * Defaults are provided so existing callers that don't care about
     * speed / effect can keep calling `speak(text, voiceId)`.
     */
    suspend fun speak(
        text: String,
        voiceId: String,
        speed: Float = 1.0f,
        effectBlocks: List<EffectBlock> = emptyList(),
        /**
         * Optional espeak language override (e.g. `"en-us"`, `"ja"`).
         * KokoroDirect is the only engine that reads it: null means
         * "derive from the voice's key prefix", non-null forces the
         * espeak language. KittenDirect always phonemizes as `en-us`
         * (English-only model), and Pocket and the cloud engine don't
         * phonemize through espeak at all — for those three the value
         * has no effect. The alias editor only offers the control for
         * Kokoro voices for that reason.
         */
        phonemizationLanguage: String? = null,
    ): Result<Unit>
    fun cancel()

    /**
     * Eagerly load the engine that backs [voiceId] so a subsequent
     * [speak] call doesn't pay model-load + warmup latency on the user's
     * critical path. Idempotent + non-throwing — a failure logs and
     * returns; the first real `speak` will surface the error.
     *
     * The Speak screen calls this on voice-selection so navigating to
     * Speak with voice X selected starts a background pre-load.
     */
    /** Returns true if the engine for [voiceId] is present + loaded. */
    suspend fun preload(voiceId: String): Boolean

    /**
     * True if the engine backing [voiceId] is already resident, so a
     * [speak] call starts synthesising immediately rather than paying a
     * cold model load first.
     *
     * Cheap + non-blocking (a volatile field read per [TtsEngine.isLoaded]),
     * so the Speak screen can call it on the main thread to decide between
     * showing "Loading <engine>…" and going straight to "Speaking…".
     */
    fun isWarm(voiceId: String): Boolean

    /**
     * Release every loaded engine so the next [speak]/[preload] rebuilds it.
     * Used when a setting that's only read at engine-load time changes (the
     * ONNX thread count) — without this, a warm engine keeps the old value
     * until the process is killed, so the setting looks like a no-op.
     */
    suspend fun releaseAll()
}

/**
 * The ViewModels' door into speech playback — a thin client of
 * [MarmaladeSynthService], which owns the one real pipeline (see the
 * data-flow chart above). [speak] carries the request over the service's
 * intent contract and suspends until the service posts the request's
 * terminal state through [PreviewCompletions]; engine warm-up management
 * ([preload]/[isWarm]/[releaseAll]) stays here because the engines are
 * process-wide singletons shared with the service.
 *
 * One playback at a time from the UI's perspective: the Speak screen
 * gates its button, and [cancel] stops the service's current job and
 * queue. (A speak issued while the share-sheet reader is mid-article
 * queues behind it — same transport, same queue.)
 */
@Singleton
class Synthesizer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val kittenDirect: KittenDirectEngine,
    private val kokoroDirect: KokoroDirectEngine,
    private val pocket: PocketEngine,
    private val pocketDev: PocketDevEngine,
    private val cloudApi: CloudApiEngine,
    private val residency: app.marmalade.tts.service.EngineResidency,
    private val completions: PreviewCompletions,
) : SpeechPlayer {

    /**
     * Synthesize [text] using [voiceId], play it, and return when playback
     * has fully drained.
     *
     * Returns:
     *   Result.success(Unit)              — played to completion (or cancelled)
     *   Result.failure(ModelMissing)      — engine assets not yet bundled
     *   Result.failure(SynthesisFailed)   — anything else
     */
    override suspend fun speak(
        text: String,
        voiceId: String,
        speed: Float,
        effectBlocks: List<EffectBlock>,
        phonemizationLanguage: String?,
    ): Result<Unit> = coroutineScope {
        if (text.isBlank()) return@coroutineScope Result.success(Unit)
        // Playback runs inside MarmaladeSynthService, not on a local
        // AudioTrack: Android 16's audio hardening mutes-and-parks a bare
        // activity's track (gain forced to -inf, frames never consumed)
        // while the service's playback — foreground service + MediaSession
        // + audio focus — mixes normally; both were observed side by side
        // in audio_flinger on device, 2026-08-09. The service owns the
        // whole pipeline (preprocessing, language auto-detection, prosody,
        // effects, streaming, focus, residency, TTFA latency sampling), so
        // this class only carries the request over and waits.
        //
        // The suspend-until-done contract survives via [PreviewCompletions]:
        // the service posts this request id's terminal state on every exit,
        // including its own teardown.
        val requestId = completions.newRequestId()
        // Subscribe BEFORE the service starts so a fast completion can't
        // slip through between startForegroundService and the collector.
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            completions.events.first { it.requestId == requestId }
        }
        val intent = Intent(appContext, MarmaladeSynthService::class.java).apply {
            action = MarmaladeSynthService.ACTION_SPEAK
            putExtra(MarmaladeSynthService.EXTRA_TEXT, text)
            // Explicit voice → the service skips its primary-alias rerouting.
            putExtra(MarmaladeSynthService.EXTRA_VOICE, voiceId)
            putExtra(MarmaladeSynthService.EXTRA_SPEED, speed)
            putExtra(
                MarmaladeSynthService.EXTRA_EFFECT_BLOCKS,
                EffectBlockJson.encode(effectBlocks),
            )
            phonemizationLanguage?.let { putExtra(MarmaladeSynthService.EXTRA_LANG, it) }
            putExtra(MarmaladeSynthService.EXTRA_REQUEST_ID, requestId)
        }
        try {
            appContext.startForegroundService(intent)
        } catch (t: Throwable) {
            completion.cancel()
            return@coroutineScope Result.failure(SynthesizerException.SynthesisFailed(t))
        }
        val done = try {
            completion.await()
        } catch (e: CancellationException) {
            // Caller's scope died (ViewModel teardown) — nobody is left to
            // hear the result, so stop the playback on the way out.
            cancel()
            throw e
        }
        when (done.error) {
            null -> Result.success(Unit)
            PreviewCompletions.ErrorKind.MODEL_MISSING ->
                Result.failure(SynthesizerException.ModelMissing)
            PreviewCompletions.ErrorKind.FAILED -> Result.failure(
                SynthesizerException.SynthesisFailed(
                    RuntimeException(done.message ?: "synthesis failed"),
                ),
            )
        }
    }

    override suspend fun preload(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        val engineName = engineNameFor(voiceId)
        // The Speak screen pre-loads on every voice change, which makes this
        // the in-app "currently selected engine" signal residency protects —
        // and the moment the previously selected engine stops being special.
        // Recorded for the cloud engine too; residency simply has nothing to
        // evict for it.
        residency.select(engineName)
        try {
            engineFor(engineName).ensureModelLoaded()
            true
        } catch (t: Throwable) {
            // Pre-load is best-effort. ModelMissing is the common case
            // (user hasn't downloaded the engine yet) — surface only on
            // explicit speak(). Returns false so callers can clear a stale
            // ModelMissing banner once the engine IS present (true).
            Log.d(TAG, "preload($voiceId) skipped: ${t.message}")
            false
        }
    }

    override fun isWarm(voiceId: String): Boolean =
        engineFor(engineNameFor(voiceId)).isLoaded()

    override suspend fun releaseAll() = withContext(Dispatchers.IO) {
        // Stop any active playback first, then drop every engine's loaded
        // sessions. The next speak()/preload() reloads on demand — picking up
        // settings (e.g. ONNX thread count) that are only read at load time.
        cancel()
        listOf(
            kittenDirect, kokoroDirect, pocket, pocketDev,
        ).forEach { runCatching { it.release() } }
    }

    override fun cancel() {
        // Stops the service's current playback + queue; the awaiting
        // speak() resolves through its completion (cancel = success).
        // Idempotent when nothing is playing, and runCatching because a
        // background-state start can legitimately be refused — in which
        // case the service isn't running and there is nothing to stop.
        runCatching {
            appContext.startService(
                Intent(appContext, MarmaladeSynthService::class.java)
                    .setAction(MarmaladeSynthService.ACTION_STOP),
            )
        }
    }

    // -- private --------------------------------------------------------------

    /**
     * Engine name embedded in [voiceId] (everything before the first `:`).
     * Falls back to the recommended Kokoro Direct engine for malformed
     * inputs.
     */
    private fun engineNameFor(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        if (sep <= 0) return KokoroDirectVoiceCatalog.ENGINE
        val name = voiceId.substring(0, sep)
        return when (name) {
            KokoroDirectVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
            PocketDevVoiceCatalog.ENGINE,
            CloudApiVoiceCatalog.ENGINE -> name
            else -> KokoroDirectVoiceCatalog.ENGINE
        }
    }

    /** TtsEngine handle for an engine name. Used for the maxInputChars lookup. */
    private fun engineFor(engineName: String): TtsEngine = when (engineName) {
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect
        PocketVoiceCatalog.ENGINE -> pocket
        PocketDevVoiceCatalog.ENGINE -> pocketDev
        CloudApiVoiceCatalog.ENGINE -> cloudApi
        else -> kokoroDirect
    }

    companion object {
        private const val TAG = "Synthesizer"

    }
}
