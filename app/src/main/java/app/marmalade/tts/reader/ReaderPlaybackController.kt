package app.marmalade.tts.reader

import android.os.SystemClock
import app.marmalade.tts.service.PlaybackTransport
import app.marmalade.tts.service.PreviewCompletions
import app.marmalade.tts.service.ReaderTransportState
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
//   Who owns reader playback
// -----------------------------------------------------------------------------
//
//   This controller is an application-scoped @Singleton, NOT the ViewModel.
//   Design point 7 says the back action returns to the sharing app while the
//   article keeps being read; a ViewModel-owned pipeline would stop topping
//   the queue up the moment the screen went away, so the article would die two
//   blocks after the user left. Living at application scope also means
//   re-opening the reader (re-share, or the user coming back) rebinds to a
//   playback that never stopped, with the highlight already on the right
//   block. ReaderViewModel is a thin binding over this.
//
//   Nothing here is persisted (design point 8): the article text lives in this
//   object's memory and dies with the process.
//
//   ┌ blocks[]  ── article text, document order (headings included)
//   │
//   ├ pending   ── the requests handed to MarmaladeSynthService, in order.
//   │              Head = playing, the rest are queued behind it in the
//   │              service's own FIFO. Capped at LOOKAHEAD so the next block
//   │              is already synthesising while the current one plays.
//   │
//   └ completions.events ── one event per finished request. The head
//                  completing IS the "block finished" signal: drop it,
//                  make the new head current (that's the highlight), and
//                  top the queue back up.
//
//   PlaybackTransport is the two-way seam with the service: we publish what
//   the notification needs (is an article being read, can it step forward)
//   and we collect the service's `paused` flag, because a pause from the
//   notification — or from an audio-focus duck — is not something we asked
//   for and the screen would otherwise keep claiming to be playing.
//
//   Seeking (tap a block / forward / backward) can't be a seek in any real
//   sense — audio is synthesised per block, so there is nothing to scrub
//   through. It is: cancel every request we have outstanding
//   (ACTION_STOP_REQUEST, which never touches an unrelated share-sheet read)
//   and enqueue afresh from the target block.
// -----------------------------------------------------------------------------

/** Coarse transport state — everything the reader UI needs to draw itself. */
enum class ReaderPlaybackStatus {
    /** An article is loaded but nothing has been spoken (or playback stopped). */
    Idle,

    Playing,
    Paused,

    /** The last block finished. Play starts the article over from the top. */
    Finished,
}

/**
 * [articleKey] is the article's URL — the identity check that decides whether
 * re-entering the reader resumes an in-flight read or starts a new one.
 * [currentIndex] is always a valid index into the article (0 when idle), so
 * the "N of M" readout never has to deal with a null.
 */
data class ReaderPlaybackState(
    val articleKey: String? = null,
    val blockCount: Int = 0,
    val currentIndex: Int = 0,
    val status: ReaderPlaybackStatus = ReaderPlaybackStatus.Idle,
    /**
     * The reading session's speed, as a factor on the speed the user's alias
     * resolves to (see [ReaderPlaybackController.setSpeedMultiplier]).
     *
     * Lives in this in-memory state and nowhere else — not in DataStore, not
     * in settings. It is a property of *this reading* of *this article*: a new
     * article (or a new process) starts back at 1.0, because a speed picked to
     * skim one long post is not a preference about how the app speaks.
     */
    val speedMultiplier: Float = 1.0f,
) {
    /** True while a block is the one being read — i.e. worth highlighting. */
    val isActive: Boolean
        get() = status == ReaderPlaybackStatus.Playing || status == ReaderPlaybackStatus.Paused
}

@Singleton
class ReaderPlaybackController internal constructor(
    private val speech: ReaderSpeechClient,
    private val completions: PreviewCompletions,
    private val transport: PlaybackTransport,
    /** Monotonic milliseconds; injected so the backward-window test can lie. */
    private val clock: () -> Long,
    scope: CoroutineScope,
) {

    @Inject
    constructor(
        speech: ReaderSpeechClient,
        completions: PreviewCompletions,
        transport: PlaybackTransport,
    ) : this(
        speech = speech,
        completions = completions,
        transport = transport,
        clock = SystemClock::elapsedRealtime,
        // Application-lifetime on purpose: this scope is what keeps the queue
        // being topped up after the reader screen is gone.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /**
     * Guards every field below. Commands arrive on the main thread (UI) while
     * completions arrive on [scope]'s dispatcher, and "drop the head, then top
     * up" must not interleave with "cancel everything and re-enqueue".
     */
    private val lock = Any()

    /** The article being read, kept so the screen can rebind without a refetch. */
    private var article: ReaderArticle? = null

    /** [article]'s block texts, in document order — what actually gets spoken. */
    private var blocks: List<String> = emptyList()

    /** Requests in flight, oldest first. `pending[0]` is the one being spoken. */
    private val pending = ArrayDeque<Pending>()

    /** The next block to hand to the service; blocks `[current, nextIndex)` are pending. */
    private var nextIndex = 0

    /** When the current block became current, on [clock]'s timebase. */
    private var blockStartedAt = 0L

    /** [clock] reading at the moment of pause, so the pause doesn't age the block. */
    private var pausedAt = 0L

    private val _state = MutableStateFlow(ReaderPlaybackState())
    val state: StateFlow<ReaderPlaybackState> = _state.asStateFlow()

    init {
        scope.launch {
            completions.events.collect(::onCompletion)
        }
        // Publishing off the state flow rather than at every mutation site
        // means a new transition can't forget to tell the notification.
        scope.launch {
            _state.collect { transport.setReader(transportStateOf(it)) }
        }
        scope.launch {
            transport.paused.collect(::onServicePauseChanged)
        }
    }

    /**
     * Bind [article], keyed on its URL.
     *
     * Returns true when this is a new article — the caller's cue to start
     * playing. Returns false when that URL is already loaded, meaning playback
     * is still going (or paused) from a previous visit to the screen and must
     * be left exactly as it is.
     */
    fun open(article: ReaderArticle): Boolean = synchronized(lock) {
        if (article.url == _state.value.articleKey && this.blocks.isNotEmpty()) return false
        cancelPendingLocked()
        this.article = article
        this.blocks = article.blocks.map { it.text }
        nextIndex = 0
        // A whole new state value, so the session speed resets to 1.0 with it
        // — deliberate: the multiplier belongs to the article being read, and
        // the same-key rebind above returns before ever getting here.
        _state.value = ReaderPlaybackState(
            articleKey = article.url,
            blockCount = blocks.size,
            currentIndex = 0,
            status = ReaderPlaybackStatus.Idle,
        )
        return true
    }

    /**
     * The article held under [key], or null if we're holding a different one
     * (or none). The reader screen asks this before fetching: reopening from
     * the notification, or re-sharing a link that is already being read, must
     * not hit the network again.
     */
    fun article(key: String): ReaderArticle? = synchronized(lock) {
        article?.takeIf { it.url == key }
    }

    /** Play, resume, or (after the end) start the article again. */
    fun play() {
        synchronized(lock) {
            if (blocks.isEmpty()) return
            when (_state.value.status) {
                ReaderPlaybackStatus.Playing -> Unit
                ReaderPlaybackStatus.Paused -> resumeLocked()
                ReaderPlaybackStatus.Idle -> startAtLocked(_state.value.currentIndex)
                ReaderPlaybackStatus.Finished -> startAtLocked(0)
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (_state.value.status != ReaderPlaybackStatus.Playing) return
            if (pending.isNotEmpty()) speech.pause()
            pausedAt = clock()
            setStatusLocked(ReaderPlaybackStatus.Paused)
        }
    }

    fun togglePlayPause() {
        synchronized(lock) {
            if (_state.value.status == ReaderPlaybackStatus.Playing) pause() else play()
        }
    }

    /** Move playback to [index] — the tap-a-block action. */
    fun seekTo(index: Int) {
        synchronized(lock) {
            if (index !in blocks.indices) return
            if (_state.value.status == ReaderPlaybackStatus.Paused) {
                // Stay paused: a seek must not start audio the user asked to
                // stop. Nothing is enqueued until resume, which re-enqueues
                // from here.
                cancelPendingLocked()
                nextIndex = index
                blockStartedAt = clock()
                pausedAt = blockStartedAt
                _state.value = _state.value.copy(currentIndex = index)
            } else {
                startAtLocked(index)
            }
        }
    }

    /** Forward — the next block, or the end of the article if there isn't one. */
    fun next() {
        synchronized(lock) {
            val target = _state.value.currentIndex + 1
            if (target in blocks.indices) {
                seekTo(target)
            } else {
                cancelPendingLocked()
                _state.value = _state.value.copy(
                    currentIndex = blocks.lastIndex.coerceAtLeast(0),
                    status = ReaderPlaybackStatus.Finished,
                )
            }
        }
    }

    /**
     * Backward, with music-player semantics: restart the current block, unless
     * we're still near its start, in which case go to the previous block.
     *
     * "Near its start" is measured from when the block became current, which
     * for the first block of a run includes the engine's model-load time — the
     * service gives us no "audio started" event to measure from. In practice
     * that only widens the window on a cold start.
     */
    fun previous() {
        synchronized(lock) {
            val current = _state.value.currentIndex
            val target = if (elapsedInBlockLocked() < RESTART_WINDOW_MS) {
                (current - 1).coerceAtLeast(0)
            } else {
                current
            }
            seekTo(target)
        }
    }

    /**
     * Set the session's speed — a factor on the alias's own speed, not an
     * absolute rate (see [ReaderPlaybackState.speedMultiplier]).
     *
     * Blocks already handed to the service are already synthesised (or being
     * synthesised) at the old speed and cannot be re-speeded, so a change that
     * lands mid-article re-enqueues from the current block. That is exactly
     * what tapping the current block does, so it goes through [seekTo]:
     * playing restarts the block at the new speed, paused stays paused and
     * drops the queue for the resume to re-enqueue. Idle/Finished only store
     * it — the next play picks it up.
     */
    fun setSpeedMultiplier(multiplier: Float) {
        synchronized(lock) {
            val clamped = multiplier.coerceIn(MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER)
            if (clamped == _state.value.speedMultiplier) return
            _state.value = _state.value.copy(speedMultiplier = clamped)
            if (_state.value.isActive) seekTo(_state.value.currentIndex)
        }
    }

    /** Stop this article's playback entirely, leaving the article loaded. */
    fun stop() {
        synchronized(lock) {
            cancelPendingLocked()
            setStatusLocked(ReaderPlaybackStatus.Idle)
        }
    }

    // -- internals ------------------------------------------------------------

    private fun elapsedInBlockLocked(): Long {
        val now = if (_state.value.status == ReaderPlaybackStatus.Paused) pausedAt else clock()
        return now - blockStartedAt
    }

    private fun resumeLocked() {
        if (pending.isNotEmpty()) speech.resume()
        adoptResumeLocked()
    }

    /**
     * Come back to Playing without touching the service's transport — the
     * caller has either just driven it ([resumeLocked]) or is reacting to it
     * having resumed on its own ([onServicePauseChanged]).
     */
    private fun adoptResumeLocked() {
        if (pending.isEmpty()) {
            // Paused across a seek — nothing was ever enqueued for this block.
            startAtLocked(_state.value.currentIndex)
            return
        }
        // The pause didn't age the block, so the backward window doesn't move.
        blockStartedAt += clock() - pausedAt
        setStatusLocked(ReaderPlaybackStatus.Playing)
    }

    /**
     * Reconcile with a pause/resume we didn't ask for: the notification's own
     * pause button, a media-button press, or an audio-focus duck. The service
     * owns one global `paused` flag and gives us no callback, so this flag is
     * the only way the reader learns its audio stopped.
     *
     * Deliberately does NOT send ACTION_PAUSE / ACTION_RESUME back: the
     * service is already in the state we're adopting, and echoing it would be
     * a loop. Our own [pause] / [play] set our status before the service ever
     * reports back, so their echo lands here as a no-op — which makes this
     * idempotent for any number of repeats of the same flag value.
     *
     * Gated on having requests in flight, because the flag is global: the
     * service clears it whenever it starts a request, and a reader that is
     * paused with nothing enqueued (paused, then seeked) must not read an
     * unrelated share-sheet playback starting up as its own cue to speak.
     */
    private fun onServicePauseChanged(servicePaused: Boolean) {
        synchronized(lock) {
            if (pending.isEmpty()) return
            when (_state.value.status) {
                ReaderPlaybackStatus.Playing ->
                    if (servicePaused) {
                        pausedAt = clock()
                        setStatusLocked(ReaderPlaybackStatus.Paused)
                    }
                ReaderPlaybackStatus.Paused -> if (!servicePaused) adoptResumeLocked()
                // Idle / Finished: nothing of ours is playing, so a pause of
                // whatever else the service is doing is none of our business.
                else -> Unit
            }
        }
    }

    /** Projection of [state] onto what the service's notification needs. */
    private fun transportStateOf(state: ReaderPlaybackState) = ReaderTransportState(
        articleUrl = state.articleKey,
        active = state.isActive,
        canNext = state.currentIndex < state.blockCount - 1,
        canPrevious = state.blockCount > 0,
    )

    private fun startAtLocked(index: Int) {
        cancelPendingLocked()
        nextIndex = index
        blockStartedAt = clock()
        _state.value = _state.value.copy(
            currentIndex = index,
            status = ReaderPlaybackStatus.Playing,
        )
        topUpLocked()
    }

    private fun cancelPendingLocked() {
        pending.forEach { speech.stopRequest(it.requestId) }
        pending.clear()
    }

    private fun topUpLocked() {
        while (pending.size < LOOKAHEAD && nextIndex < blocks.size) {
            val requestId = completions.newRequestId()
            val index = nextIndex
            nextIndex++
            pending.addLast(Pending(requestId, index))
            if (!speech.speak(requestId, blocks[index], _state.value.speedMultiplier)) {
                // The service wouldn't start, so this request will never
                // complete and the pipeline would stall silently. Nothing is
                // playable in that state — unwind to Idle.
                cancelPendingLocked()
                nextIndex = index
                setStatusLocked(ReaderPlaybackStatus.Idle)
                return
            }
        }
    }

    private fun setStatusLocked(status: ReaderPlaybackStatus) {
        _state.value = _state.value.copy(status = status)
    }

    private fun onCompletion(completion: PreviewCompletions.Completion) {
        synchronized(lock) {
            // Completions for requests we cancelled (the service posts those
            // too) and for every other in-app speak land here — ours are
            // exactly the ones still in `pending`.
            val entry = pending.firstOrNull { it.requestId == completion.requestId } ?: return

            if (entry !== pending.first()) {
                // A request of ours retiring before the one that is playing
                // means something outside cancelled our queue — in practice
                // the notification's Stop (or a permanent audio-focus loss),
                // both of which run the service's doStop: it resolves every
                // queued request first, then the active one. The service
                // reports a cancel and a played-through block identically
                // (PreviewCompletions.error is null for both), so this
                // ordering is the only signal we get, and without honouring
                // it the reader would treat Stop as "block finished" and
                // enqueue the rest of the article right back.
                cancelPendingLocked()
                setStatusLocked(ReaderPlaybackStatus.Idle)
                return
            }
            pending.removeFirst()

            if (completion.error != null) {
                // A missing engine or a synthesis failure will hit the queued
                // blocks too; stop rather than machine-gun the same error.
                cancelPendingLocked()
                setStatusLocked(ReaderPlaybackStatus.Idle)
                return
            }

            val nextBlock = pending.firstOrNull()?.blockIndex ?: nextIndex
            if (nextBlock >= blocks.size) {
                _state.value = _state.value.copy(
                    currentIndex = blocks.lastIndex.coerceAtLeast(0),
                    status = ReaderPlaybackStatus.Finished,
                )
                return
            }
            blockStartedAt = clock()
            _state.value = _state.value.copy(currentIndex = nextBlock)
            topUpLocked()
        }
    }

    /** One request handed to the service, and the block it speaks. */
    private data class Pending(val requestId: Long, val blockIndex: Int)

    companion object {
        /**
         * Requests outstanding at once: the one playing plus two synthesising
         * behind it. Deeper buys nothing — the service plays strictly one at a
         * time — and makes every seek cancel more work than it needs to.
         */
        internal const val LOOKAHEAD = 3

        /**
         * Backward within this long of a block's start goes to the previous
         * block instead of restarting the current one.
         */
        internal const val RESTART_WINDOW_MS = 2_000L

        /**
         * Bounds on the session speed. Wider than the sheet offers on purpose
         * — the sheet's chips are the curated set, these are the limits past
         * which the engines stop producing anything worth listening to.
         */
        internal const val MIN_SPEED_MULTIPLIER = 0.5f
        internal const val MAX_SPEED_MULTIPLIER = 3.0f
    }
}
