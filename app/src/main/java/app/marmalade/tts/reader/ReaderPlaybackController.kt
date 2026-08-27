package app.marmalade.tts.reader

import android.os.SystemClock
import app.marmalade.tts.service.PreviewCompletions
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
) {
    /** True while a block is the one being read — i.e. worth highlighting. */
    val isActive: Boolean
        get() = status == ReaderPlaybackStatus.Playing || status == ReaderPlaybackStatus.Paused
}

@Singleton
class ReaderPlaybackController internal constructor(
    private val speech: ReaderSpeechClient,
    private val completions: PreviewCompletions,
    /** Monotonic milliseconds; injected so the backward-window test can lie. */
    private val clock: () -> Long,
    scope: CoroutineScope,
) {

    @Inject
    constructor(
        speech: ReaderSpeechClient,
        completions: PreviewCompletions,
    ) : this(
        speech = speech,
        completions = completions,
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
    }

    /**
     * Bind [blocks] (article text in document order) under [key], which should
     * be the article URL.
     *
     * Returns true when this is a new article — the caller's cue to start
     * playing. Returns false when [key] is already loaded, meaning playback is
     * still going (or paused) from a previous visit to the screen and must be
     * left exactly as it is.
     */
    fun open(key: String, blocks: List<String>): Boolean = synchronized(lock) {
        if (key == _state.value.articleKey && this.blocks.isNotEmpty()) return false
        cancelPendingLocked()
        this.blocks = blocks
        nextIndex = 0
        _state.value = ReaderPlaybackState(
            articleKey = key,
            blockCount = blocks.size,
            currentIndex = 0,
            status = ReaderPlaybackStatus.Idle,
        )
        return true
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
        if (pending.isEmpty()) {
            // Paused across a seek — nothing was ever enqueued for this block.
            startAtLocked(_state.value.currentIndex)
            return
        }
        speech.resume()
        // The pause didn't age the block, so the backward window doesn't move.
        blockStartedAt += clock() - pausedAt
        setStatusLocked(ReaderPlaybackStatus.Playing)
    }

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
            if (!speech.speak(requestId, blocks[index])) {
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
    }
}
