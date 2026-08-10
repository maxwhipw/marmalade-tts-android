package app.marmalade.tts.service

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Completion signals for in-app playback requests routed through
 * [MarmaladeSynthService].
 *
 * Android 16's audio hardening mutes a bare activity's AudioTrack (gain
 * forced to -inf, track parked with its frames unconsumed) while the
 * service's playback — foreground service + MediaSession + audio focus —
 * mixes normally (both observed in audio_flinger on device, 2026-08-09).
 * So the in-app Speak/preview path sends its requests to the service and
 * awaits the matching completion here, which is what lets
 * [app.marmalade.tts.audio.Synthesizer.speak] keep its suspend-until-done
 * contract for the ViewModels.
 *
 * External callers (share sheet, Tasker, clipboard tile) carry no request
 * id; the service posts nothing for them.
 */
@Singleton
class PreviewCompletions @Inject constructor() {

    enum class ErrorKind { MODEL_MISSING, FAILED }

    /** [error] null = played to completion or was cancelled — both "done". */
    data class Completion(
        val requestId: Long,
        val error: ErrorKind?,
        val message: String? = null,
    )

    private val ids = AtomicLong(0)

    // Never suspends the service: posts use tryEmit against buffered
    // capacity, and an event nobody is awaiting is simply dropped.
    private val _events = MutableSharedFlow<Completion>(extraBufferCapacity = 16)
    val events: SharedFlow<Completion> = _events

    fun newRequestId(): Long = ids.incrementAndGet()

    fun post(requestId: Long, error: ErrorKind?, message: String? = null) {
        if (requestId == 0L) return
        _events.tryEmit(Completion(requestId, error, message))
    }
}
