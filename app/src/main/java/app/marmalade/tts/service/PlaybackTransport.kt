package app.marmalade.tts.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place [MarmaladeSynthService] and the reader can see each other's
 * transport state without either holding a reference to the other's lifecycle.
 *
 * Two independent halves, each with a single writer:
 *
 *  - [paused] is written by the service (its `paused` flag), read by
 *    [app.marmalade.tts.reader.ReaderPlaybackController]. Without it a pause
 *    from the notification or from an audio-focus duck would stop the audio
 *    while the reader's own state — and so the screen's play/pause button —
 *    still said Playing.
 *
 *  - [reader] is written by the controller, read by the service. It is what
 *    lets the notification grow next/previous-block actions (and a tap target
 *    that reopens the article) only while an article is actually being read;
 *    share-sheet and clipboard playback see exactly the notification they
 *    always did.
 *
 * A @Singleton like [PreviewCompletions], injected into both sides, rather
 * than a service binding: the service comes and goes with every utterance,
 * and the controller outlives it.
 */
@Singleton
class PlaybackTransport @Inject constructor() {

    private val _paused = MutableStateFlow(false)

    /** True while the service has playback paused, whoever asked for it. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _reader = MutableStateFlow(ReaderTransportState())

    /** What the reader is doing, for the notification to reflect. */
    val reader: StateFlow<ReaderTransportState> = _reader.asStateFlow()

    /** Service side: mirror the service's `paused` flag. */
    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    /** Reader side: publish the article and what its transport can do. */
    fun setReader(state: ReaderTransportState) {
        _reader.value = state
    }
}

/**
 * The reader's transport, as the notification needs to see it.
 *
 * [articleUrl] is non-null exactly when an article is loaded, and is what the
 * notification's tap target reopens. [active] means it is playing or paused —
 * an article sitting Idle or Finished gets no transport actions, because there
 * is no block in flight to step away from.
 */
data class ReaderTransportState(
    val articleUrl: String? = null,
    val active: Boolean = false,
    /** False on the last block: forward from there only ends the article. */
    val canNext: Boolean = false,
    /** Backward always means something — it restarts the current block. */
    val canPrevious: Boolean = false,
) {
    /** True when the notification should show the reader's controls. */
    val isReading: Boolean get() = active && articleUrl != null
}
