package app.marmalade.tts.reader

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.marmalade.tts.service.MarmaladeSynthService
import app.marmalade.tts.service.SpeakDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's door into [MarmaladeSynthService] — one speak request per
 * article block, plus the transport actions.
 *
 * An interface only so [ReaderPlaybackController]'s sequencing can be unit
 * tested on a plain JVM: the real implementation is four intents.
 *
 * Note this is NOT [app.marmalade.tts.audio.Synthesizer]: that class tracks a
 * single `activeRequestId` because the Speak screen only ever has one playback
 * in flight, whereas the reader deliberately keeps three queued at once so the
 * gap between blocks is engine latency and nothing more. Both talk to the same
 * service over the same intent contract and await the same
 * [app.marmalade.tts.service.PreviewCompletions] events.
 */
interface ReaderSpeechClient {

    /**
     * Enqueue [text] under [requestId]. Returns false if the service refused
     * to start (a background start with no foreground-service exemption), in
     * which case no completion will ever arrive for [requestId].
     */
    fun speak(requestId: Long, text: String): Boolean

    /** Cancel one request — queued or playing — leaving the rest alone. */
    fun stopRequest(requestId: Long)

    /** Pause whatever the service is playing (its `paused` flag is global). */
    fun pause()

    /** Undo [pause]. */
    fun resume()
}

@Singleton
class SynthServiceReaderSpeechClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReaderSpeechClient {

    override fun speak(requestId: Long, text: String): Boolean {
        // No EXTRA_VOICE on purpose: leaving it off is what makes the service
        // resolve the user's primary alias (voice, speed, effect, language),
        // which is exactly the voice the share-sheet path already reads in.
        // The reader has no voice picker of its own by design.
        val intent = Intent(context, MarmaladeSynthService::class.java).apply {
            action = MarmaladeSynthService.ACTION_SPEAK
            putExtra(
                MarmaladeSynthService.EXTRA_TEXT,
                text.take(SpeakDispatcher.MAX_TEXT_LENGTH),
            )
            putExtra(MarmaladeSynthService.EXTRA_REQUEST_ID, requestId)
            setPackage(context.packageName)
        }
        return runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w(TAG, "Reader speak request refused", it) }
            .isSuccess
    }

    override fun stopRequest(requestId: Long) =
        send(MarmaladeSynthService.ACTION_STOP_REQUEST) {
            putExtra(MarmaladeSynthService.EXTRA_REQUEST_ID, requestId)
        }

    override fun pause() = send(MarmaladeSynthService.ACTION_PAUSE)

    override fun resume() = send(MarmaladeSynthService.ACTION_RESUME)

    /**
     * Transport/cancel actions only ever apply to a service that is already
     * running and foregrounded, so `startService` is right here — and a
     * refusal (the service died first) means there was nothing to act on.
     */
    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(context, MarmaladeSynthService::class.java)
            .setAction(action)
            .apply(extras)
        runCatching { context.startService(intent) }
    }

    private companion object {
        const val TAG = "ReaderSpeechClient"
    }
}
