package app.marmalade.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import app.marmalade.tts.engine.KittenEngine
import app.marmalade.tts.engine.SynthAudio
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   ViewModel.speak(text, voiceId)
//     │
//     ▼
//   Synthesizer.speak(text, voiceId)
//     │
//     ├── KittenEngine.synthesize(text, voiceId) ──► SynthAudio(pcm, sr)
//     │     (CPU-bound; runs on Dispatchers.Default inside the engine)
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

    /** Engine assets aren't bundled yet. Shown as "Model not installed" in the UI. */
    object ModelMissing : SynthesizerException("Kitten model assets not bundled")

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
    suspend fun speak(text: String, voiceId: String): Result<Unit>
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
    private val engine: KittenEngine,
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
    override suspend fun speak(text: String, voiceId: String): Result<Unit> {
        cancelled = false

        val audio: SynthAudio = try {
            engine.synthesize(text, voiceId)
        } catch (_: UnsupportedOperationException) {
            // Engine clearly signals "model isn't installed" — propagate as
            // a typed result so the UI can show the right copy.
            return Result.failure(SynthesizerException.ModelMissing)
        } catch (t: Throwable) {
            Log.w(TAG, "Synthesis failed", t)
            return Result.failure(SynthesizerException.SynthesisFailed(t))
        }

        return try {
            playPcm(audio.pcm, audio.sampleRate)
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
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(pcm.size * 2 /* shorts → bytes */)

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
