package app.marmalade.tts.reader

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.marmalade.tts.service.LiveSessionSpeed
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
     * Enqueue [text] under [requestId], spoken at [speed] — an absolute speed
     * that replaces the one the user's alias resolves to (the alias still
     * supplies voice, effect and language). Returns false if the service refused
     * to start (a background start with no foreground-service exemption), in
     * which case no completion will ever arrive for [requestId].
     *
     * [continuation] marks a block that follows one already handed over. It
     * queues even behind paused playback; a non-continuation request (the
     * first block of a play) replaces paused playback, the reader's own
     * included — see [MarmaladeSynthService.EXTRA_CONTINUATION].
     */
    fun speak(
        requestId: Long,
        text: String,
        speed: Float,
        continuation: Boolean,
    ): Boolean

    /**
     * Move [requestIds] — requests already handed over — to [speed] without
     * restarting them. True when all of them follow it live (the speed is a
     * playback-side time-stretch, audible within a few hundred ms); false when
     * at least one has the speed baked into its audio (a cloud voice, or the
     * batched emoji path), and the caller must re-enqueue to be heard at
     * [speed]. Either way, requests sent later carry [speed] themselves.
     */
    fun changeSpeed(requestIds: List<Long>, speed: Float): Boolean

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
    private val sessionSpeeds: LiveSessionSpeed,
) : ReaderSpeechClient {

    override fun speak(
        requestId: Long,
        text: String,
        speed: Float,
        continuation: Boolean,
    ): Boolean {
        // The live value wins over the extra below in the service; setting it
        // here is what makes a new article's starting speed replace the last
        // article's (see LiveSessionSpeed).
        sessionSpeeds.set(speed)
        // No EXTRA_VOICE on purpose: leaving it off is what makes the service
        // resolve the user's primary alias (voice, speed, effect, language),
        // which is exactly the voice the share-sheet path already reads in.
        // The reader has no voice picker of its own by design.
        //
        // EXTRA_SPEED can't carry the session speed: on this route the
        // alias's speed replaces it. EXTRA_SESSION_SPEED is applied AFTER
        // alias routing instead, so it replaces the alias's speed and leaves
        // the rest of the alias alone.
        val intent = Intent(context, MarmaladeSynthService::class.java).apply {
            action = MarmaladeSynthService.ACTION_SPEAK
            // A backstop only: ArticleExtractor already splits any block
            // longer than this, so real article text never reaches the cut.
            putExtra(
                MarmaladeSynthService.EXTRA_TEXT,
                text.take(SpeakDispatcher.MAX_TEXT_LENGTH),
            )
            putExtra(MarmaladeSynthService.EXTRA_REQUEST_ID, requestId)
            putExtra(MarmaladeSynthService.EXTRA_SESSION_SPEED, speed)
            putExtra(MarmaladeSynthService.EXTRA_CONTINUATION, continuation)
            setPackage(context.packageName)
        }
        return runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w(TAG, "Reader speak request refused", it) }
            .isSuccess
    }

    override fun changeSpeed(requestIds: List<Long>, speed: Float): Boolean =
        sessionSpeeds.change(requestIds, speed).also { live ->
            Log.d(
                TAG,
                if (live) {
                    "Session speed -> $speed applied live to ${requestIds.size} request(s)"
                } else {
                    "Session speed -> $speed: a request has it baked in; re-enqueueing"
                },
            )
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
