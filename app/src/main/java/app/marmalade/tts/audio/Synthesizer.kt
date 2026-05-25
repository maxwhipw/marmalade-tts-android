package app.marmalade.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.KittenMiniEngine
import app.marmalade.tts.engine.KittenNanoEngine
import app.marmalade.tts.engine.KokoroV10Engine
import app.marmalade.tts.engine.KokoroV11Engine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.preprocessing.Preprocessor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   ViewModel.speak(text, voiceId, speed, effect)
//     │
//     ▼
//   Synthesizer.speak(text, voiceId, speed, effect)
//     │
//     ├── engineNameFor(voiceId) ──► "kokoro" | "kitten"
//     │     (split on ':' — voice IDs use "<engine>:<voiceName>")
//     │
//     ├── settings.enabledRules(engineName).first() ──► Set<String>
//     ├── Preprocessor.apply(text, enabled)         ──► normalized text
//     │     (HTML decode, currency, numbers-to-words, etc. per user's
//     │      Settings → Text preprocessing toggles)
//     │
//     ├── synthesizeForEngine(engineName, ...)
//     │     ├── "kokoro" → KokoroEngine.synthesize(...) ──► SynthAudio
//     │     └── "kitten" → KittenEngine.synthesize(...) ──► SynthAudio
//     │     (both CPU-bound; engines hop onto Dispatchers.Default)
//     │
//     ├── EffectChain.apply(pcm, sampleRate, effect)
//     │     (pure-Kotlin DSP; NONE returns the input array unchanged)
//     │
//     ├── build AudioTrack (PCM_16BIT, mono, sampleRate)
//     │
//     ├── AudioTrack.play()
//     │
//     ├── AudioTrack.write(pcm)                  ── blocks until queued
//     │
//     ├── busy-wait for playback head to catch up to write head
//     │     (so callers know when audio actually finishes, not just queued)
//     │
//     └── stop + release AudioTrack
//
//   Errors:
//     UnsupportedOperationException (engine assets missing)
//        ──► Result.failure(SynthesizerException.ModelMissing)
//     Anything else
//        ──► Result.failure(SynthesizerException.SynthesisFailed(cause))
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
 * the real [KittenEngine] (which needs an Android Context for asset
 * access and a Sherpa-ONNX JNI handle).
 */
interface SpeechPlayer {
    /**
     * Synthesize [text] using [voiceId], optionally rate-scaled by [speed]
     * (1.0 = native pace) and shaped by [effect] (NONE = dry). Returns
     * when audio has finished playing (or been cancelled).
     *
     * Defaults are provided so existing callers that don't care about
     * speed / effect can keep calling `speak(text, voiceId)`.
     */
    suspend fun speak(
        text: String,
        voiceId: String,
        speed: Float = 1.0f,
        effect: EffectPreset = EffectPreset.NONE,
    ): Result<Unit>
    fun cancel()
}

/**
 * Single playback pipeline shared by both ViewModels.
 *
 * Why a separate class instead of inlining into each ViewModel:
 * - The "synthesise → play via AudioTrack → wait for playback to drain"
 *   sequence is identical for the main Speak button and the voice-picker
 *   Preview button. Bug fixes need to land in one place.
 * - The TTS engine itself is a `@Singleton`, so it's natural to colocate
 *   the audio I/O around it in another `@Singleton`.
 *
 * Thread model:
 * - [speak] is `suspend`; it runs synthesis on `Dispatchers.Default` (via
 *   the engine) and AudioTrack I/O on `Dispatchers.IO`. Returns when
 *   playback has fully drained — not just when the buffer is queued.
 * - [cancel] flips a volatile flag and stops the active AudioTrack. Safe
 *   from any thread.
 *
 * v0.1 design choice: one playback at a time. If a second speak() comes in
 * while the first is active, the caller should call cancel() first. We
 * don't queue; we don't pre-empt; the UI gates the button instead.
 */
@Singleton
class Synthesizer @Inject constructor(
    private val kittenNano: KittenNanoEngine,
    private val kittenMini: KittenMiniEngine,
    private val kokoroV10: KokoroV10Engine,
    private val kokoroV11: KokoroV11Engine,
    private val pocket: PocketEngine,
    private val preprocessor: Preprocessor,
    private val settings: SettingsRepository,
) : SpeechPlayer {

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var cancelled: Boolean = false

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
        effect: EffectPreset,
    ): Result<Unit> {
        cancelled = false

        // Route by the engine the voice belongs to. Voice IDs follow the
        // "<engine>:<voiceName>" convention, so the part before the first
        // colon is the routing key.
        val engineName = engineNameFor(voiceId)

        // Apply the user's per-engine preprocessing profile before the
        // engine sees the text. Each engine has its own profile so users
        // can curate kokoro's rules separately from kitten's.
        val enabled = settings.enabledRules(engineName).first()

        // Canonical pipeline (shared with MarmaladeTtsService and
        // MarmaladeSynthService): emoji-detect → preprocess → strip → synth
        // → emotion shaping → effect chain. v0.1.16 and earlier skipped
        // emoji prosody on this path, so the same input could sound
        // different depending on whether the user pressed Speak in-app vs.
        // routed the text through their system TTS engine.
        val result = try {
            runSynthesisPipeline(
                rawText = text,
                voiceId = voiceId,
                speed = speed,
                enabledRules = enabled,
                effect = effect,
                preprocessor = preprocessor,
                synthesize = { t, v, s -> synthesizeForEngine(engineName, t, v, s) },
            )
        } catch (_: UnsupportedOperationException) {
            // Engine clearly signals "model isn't installed" — propagate as
            // a typed result so the UI can show the right copy.
            return Result.failure(SynthesizerException.ModelMissing)
        } catch (t: Throwable) {
            Log.w(TAG, "Synthesis failed", t)
            return Result.failure(SynthesizerException.SynthesisFailed(t))
        }

        // Empty input (blank text, or emoji-only that stripped to nothing)
        // returns success without invoking AudioTrack. Matches the existing
        // contract: callers see success even when there's nothing to play.
        val shaped = when (result) {
            is PipelineResult.Empty -> return Result.success(Unit)
            is PipelineResult.Audio -> result
        }

        return try {
            playPcm(shaped.pcm, shaped.sampleRate)
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "Playback failed", t)
            Result.failure(SynthesizerException.SynthesisFailed(t))
        }
    }

    /**
     * Stop any in-flight playback. Safe to call from any thread and any
     * state — no-op if nothing is playing.
     */
    override fun cancel() {
        cancelled = true
        val track = currentTrack ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                track.pause()
                track.flush()
                track.stop()
            }
        } catch (_: IllegalStateException) {
            // Track already in a terminal state — nothing to do.
        }
    }

    // -- private --------------------------------------------------------------

    /**
     * Engine name embedded in [voiceId] (everything before the first `:`).
     * Falls back to the Kokoro v1.0 default for malformed inputs.
     */
    private fun engineNameFor(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        if (sep <= 0) return KokoroV10VoiceCatalog.ENGINE
        val name = voiceId.substring(0, sep)
        return when (name) {
            KokoroV10VoiceCatalog.ENGINE,
            KokoroV11VoiceCatalog.ENGINE,
            KittenNanoVoiceCatalog.ENGINE,
            KittenMiniVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE -> name
            else -> KokoroV10VoiceCatalog.ENGINE
        }
    }

    /** Route synthesis to the right engine handle. */
    private suspend fun synthesizeForEngine(
        engineName: String,
        text: String,
        voiceId: String,
        speed: Float,
    ): SynthAudio = when (engineName) {
        KokoroV10VoiceCatalog.ENGINE -> kokoroV10.synthesize(text, voiceId, speed)
        KokoroV11VoiceCatalog.ENGINE -> kokoroV11.synthesize(text, voiceId, speed)
        KittenNanoVoiceCatalog.ENGINE -> kittenNano.synthesize(text, voiceId, speed)
        KittenMiniVoiceCatalog.ENGINE -> kittenMini.synthesize(text, voiceId, speed)
        PocketVoiceCatalog.ENGINE -> pocket.synthesize(text, voiceId, speed)
        // Defensive: engineNameFor already narrows to known values, but
        // the exhaustive `when` keeps the compiler honest.
        else -> kokoroV10.synthesize(text, voiceId, speed)
    }

    /**
     * Allocate an AudioTrack at [sampleRate] (mono PCM16), write the full
     * buffer, and block until the playback head has caught up with the
     * write head. Releases the track in `finally`.
     *
     * Why busy-wait on the playback head and not e.g. `setNotificationMarkerPosition`:
     * the marker callback runs on a Handler thread we don't control, so
     * pairing it with `suspendCancellableCoroutine` is more wiring than
     * a sub-100ms sleep loop is worth for v0.1. The loop also responds
     * promptly to [cancel] via the volatile flag.
     */
    private suspend fun playPcm(pcm: ShortArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        // Aim for ~250 ms of headroom — responsive cancel, no constant
        // back-pressure stalls on the write loop. Sizing this to the entire
        // PCM payload defeats MODE_STREAM and wastes memory on long inputs.
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2 / 4 /* shorts → bytes, 250 ms */)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentTrack = track
        try {
            track.play()
            // Single write; AudioTrack handles internal chunking. write()
            // returns the number of shorts written; for streaming mode we
            // loop until everything is queued or playback is cancelled.
            var written = 0
            while (written < pcm.size && !cancelled) {
                val remaining = pcm.size - written
                val n = track.write(pcm, written, remaining, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) {
                    Log.w(TAG, "AudioTrack.write returned $n; aborting")
                    break
                }
                written += n
            }

            // Block until the playback head reaches the end of what we wrote.
            // 10 ms granularity keeps the cancel path responsive without
            // pinning the IO thread.
            while (!cancelled) {
                val pos = track.playbackHeadPosition
                if (pos >= written) break
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: IllegalStateException) {
                // Already stopped — ignore.
            }
            track.release()
            if (currentTrack === track) {
                currentTrack = null
            }
        }
    }

    companion object {
        private const val TAG = "Synthesizer"
    }
}
