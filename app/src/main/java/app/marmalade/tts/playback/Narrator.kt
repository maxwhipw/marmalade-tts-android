package app.marmalade.tts.playback

import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NarratorConfig(
    /** On-device ahead budget, in played ms (§6 Q2: about a minute). */
    val budgetMs: Double = RenderPlanner.ON_DEVICE_BUDGET_MS,
    /** Previous restarts the segment after this long into it (from its first HEARD frame). */
    val restartWindowMs: Long = 2_000L,
    /** A paused session is stopped after this long (§6 Q7: 30 min; the shell then stops itself). */
    val pausedTimeoutMs: Long = 30 * 60_000L,
    /** How long audio may wait for the host to become foreground before the session fails. */
    val hostTimeoutMs: Long = 5_000L,
    /** Heard audio kept for an instant Back / restart, in played ms. */
    val retainHeardMs: Double = 60_000.0,
    /** Audio ms per character before a session has measured its own. */
    val defaultMsPerChar: Double = 65.0,
    val maxHoldChunks: Int = StartPolicy.MAX_HOLD_CHUNKS,
    /**
     * Prepare every voice only as far as today (this block + the next), as
     * cloud voices always do. Plan §2.5: C1–C3 run with this set; the minute
     * of read-ahead waits for G, because a Pocket render parked ahead holds
     * its engine lock for a whole utterance and would block TalkBack.
     */
    val todaysDepthOnly: Boolean = false,
)

/**
 * **The one owner of in-app speech** (reader-session redesign, step B): the
 * article (segments, voice, speed, cursor, buffered audio), the one-shots
 * (shares, the Speak screen, previews, Tasker), focus, and what the writer
 * plays. Everyone else sends commands and reads [state].
 *
 * NOT wired in yet: nothing in the app constructs a Narrator until step C1.
 *
 * **Threading.** One command loop on a dedicated single-thread dispatcher
 * ([newLoopDispatcher]); it suspends only on `receive()`. Every public method
 * is a `trySend`. Anything that does I/O or real work — voice resolution
 * ([SessionResolver]), text preparation and rendering ([SegmentRenderer]) —
 * runs as a child job on [workDispatcher] and reports back as a tagged event.
 * The loop's handlers never suspend.
 *
 * **Staleness.** Audio carries `(sessionId, epoch)`: a jump, stop, flush or
 * resume-after-interrupt bumps the session's epoch and flushes the writer,
 * which then drops anything older; the loop drops writer events of an old
 * epoch. Child jobs carry their own token (a render survives a jump that
 * keeps it useful, so it can't be keyed on the playback epoch); an event
 * from a job that is no longer current is dropped.
 *
 * **Decisions (§6, Max 2026-09-29) modelled here:**
 *  - a one-shot arriving while the article plays interrupts it on its own
 *    track; the article resumes from the start of the interrupted chunk;
 *  - on-device voices prepare ~1 min ahead, cloud only this segment + the
 *    next ([RenderPlanner]); preparing continues while paused, except after
 *    a Navigation pause;
 *  - speed is a time-stretch in the writer for every voice, cloud included:
 *    a change never re-renders;
 *  - a permanent focus loss pauses the article and keeps its place
 *    ([PausedBy.FocusLoss]);
 *  - a session paused for [NarratorConfig.pausedTimeoutMs] is stopped (the
 *    shell sees Idle and stops, clearing the notification);
 *  - shared text has no Previous/Next: they act on the article only.
 */
class Narrator(
    private val engines: EngineLookup,
    private val resolver: SessionResolver,
    preparer: SegmentPreparer,
    private val output: AudioOutput,
    private val focus: FocusPort,
    private val host: HostPort,
    private val residency: ResidencyPort,
    private val keepalive: KeepalivePort,
    stats: SynthStatsPort,
    private val clock: () -> Long,
    loopDispatcher: CoroutineDispatcher,
    private val workDispatcher: CoroutineDispatcher,
    private val config: NarratorConfig = NarratorConfig(),
    private val log: (String) -> Unit = {},
) : NarratorControl, NarratorShell {

    private val scope = CoroutineScope(SupervisorJob() + loopDispatcher)
    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val renderer = SegmentRenderer(preparer, stats, clock)
    private val ids = AtomicLong(0)

    private val _state = MutableStateFlow(NarratorState())
    override val state: StateFlow<NarratorState> = _state.asStateFlow()

    // -- loop state (touched only on the loop) ---------------------------------

    private var article: Session? = null
    private val oneShots = ArrayList<Session>()
    private var render: RenderJob? = null
    private var renderSeq = 0L
    private var focusHeld = false

    /** A transient focus loss is in effect: nothing plays until GAIN. */
    private var focusSuspended = false
    private var notice: ErrorNotice? = null
    private val trackPlaying = HashMap<Track, Boolean>()

    /** The Interrupt track holds (or held) audio and must be released when its one-shots end. */
    private var interruptOpen = false
    private var hostTimer: Job? = null

    init {
        output.attach { inbox.trySend(Msg.Out(it)) }
        scope.launch {
            for (msg in inbox) {
                handle(msg)
                settle()
            }
        }
    }

    /** Stop the loop and every child job. The shell never needs this; tests do. */
    fun close() {
        scope.cancel()
    }

    // -- public commands --------------------------------------------------------

    override fun openArticle(
        key: String,
        segments: List<String>,
        voice: app.marmalade.tts.lang.VoiceChoice,
        speed: Float,
        autoplay: Boolean,
    ) {
        inbox.trySend(Msg.OpenArticle(key, segments, voice, clampSpeed(speed), autoplay))
    }

    override fun play() { inbox.trySend(Msg.Play) }
    override fun pause(reason: PauseReason) { inbox.trySend(Msg.Pause(reason)) }
    override fun togglePlayPause() { inbox.trySend(Msg.Toggle) }
    override fun jumpTo(index: Int, play: Boolean) { inbox.trySend(Msg.Jump(index, play)) }
    override fun next() { inbox.trySend(Msg.Next) }
    override fun previous() { inbox.trySend(Msg.Previous) }
    override fun setSpeed(speed: Float) { inbox.trySend(Msg.Speed(clampSpeed(speed))) }
    override fun stop() { inbox.trySend(Msg.Stop) }
    override fun cancelOneShot(id: Long) { inbox.trySend(Msg.CancelOneShot(id)) }

    override fun speakOneShot(request: OneShotRequest): OneShotHandle {
        val id = ids.incrementAndGet()
        val outcome = CompletableDeferred<Outcome>()
        inbox.trySend(Msg.OneShot(id, request, outcome))
        return OneShotHandle(id, outcome)
    }

    override fun onFocusChange(change: FocusChange) { inbox.trySend(Msg.Focus(change)) }
    override fun onHostChanged(foreground: Boolean) { inbox.trySend(Msg.Host(foreground)) }

    // -- messages -----------------------------------------------------------------

    private sealed interface Msg {
        data class OpenArticle(
            val key: String,
            val segments: List<String>,
            val voice: app.marmalade.tts.lang.VoiceChoice,
            val speed: Float,
            val autoplay: Boolean,
        ) : Msg
        data object Play : Msg
        data class Pause(val reason: PauseReason) : Msg
        data object Toggle : Msg
        data class Jump(val index: Int, val play: Boolean) : Msg
        data object Next : Msg
        data object Previous : Msg
        data class Speed(val speed: Float) : Msg
        data object Stop : Msg
        data class OneShot(val id: Long, val request: OneShotRequest, val outcome: CompletableDeferred<Outcome>) : Msg
        data class CancelOneShot(val id: Long) : Msg
        data class Focus(val change: FocusChange) : Msg
        data class Host(val foreground: Boolean) : Msg

        // child-job and writer events
        data class Resolved(val sessionId: Long, val token: Long, val result: Result<ResolvedVoice>) : Msg
        class Rendered(val sessionId: Long, val token: Long, val event: RenderEvent) : Msg
        data class Out(val event: OutputEvent) : Msg
        data class PausedTimeout(val sessionId: Long, val token: Long) : Msg
        data object HostTimeout : Msg
    }

    // -- sessions --------------------------------------------------------------------

    private class Chunk(val pcm: ShortArray, val sampleRate: Int) {
        val audioMs: Double get() = pcm.size * 1000.0 / sampleRate
    }

    private class Seg(val text: String) {
        val chunks = TreeMap<Int, Chunk>()

        /** Chunks the engine has emitted for this segment (indices 0 until this). */
        var rendered = 0
        var complete = false

        fun reset() {
            chunks.clear()
            rendered = 0
            complete = false
        }

        /** Every chunk from [from] on is still held. */
        fun intactFrom(from: Int): Boolean = (from until rendered).all { it in chunks }

        fun audioMsFrom(from: Int): Double = chunks.tailMap(from, true).values.sumOf { it.audioMs }
    }

    private sealed interface Kind {
        data class Article(val key: String) : Kind
        data class OneShot(val origin: Origin, val outcome: CompletableDeferred<Outcome>) : Kind
    }

    private inner class Session(
        val id: Long,
        val kind: Kind,
        texts: List<String>,
        val target: ResolveTarget,
        /** Null for a one-shot that takes its voice's own speed, until resolved. */
        var speed: Float?,
        val startingSpeed: Float,
    ) {
        val segs: List<Seg> = texts.map(::Seg)
        var voice: ResolvedVoice? = null
        var resolveToken = 0L
        var resolveJob: Job? = null
        var status: Status = Status.Idle
        var epoch = 0
        var track = Track.Main

        /** The segment the listener is in. */
        var cursor = 0

        /** Chunk of [cursor] whose first frame was heard last; null before any. */
        var heardChunk: Int? = null
        var heardStartAt: Long? = null
        var pausedAt: Long? = null
        var pausedInSegmentMs = 0L
        var pausedToken = 0L

        /** Next chunk to hand the writer. */
        var writeSeg = 0
        var writeChunk = 0
        var begun = false
        var ended = false
        var startGranted = false
        val rtf = RtfEstimator()
        var residencyEngine: String? = null

        val isArticle: Boolean get() = kind is Kind.Article
        val active: Boolean
            get() = status is Status.Buffering || status is Status.Playing || status is Status.Paused
        val wantsAudio: Boolean get() = status is Status.Buffering || status is Status.Playing
        val cloud: Boolean get() = voice?.cloud == true
        val tempo: Float get() = speed ?: voice?.aliasSpeed ?: 1f

        fun tag(seg: Int, chunk: Int) = ChunkTag(id, epoch, seg, chunk)

        /** Listener's position: where playback (re)starts. */
        fun listenerChunk(): Int = heardChunk ?: 0

        fun dropPcm() {
            segs.forEach { it.reset() }
        }
    }

    private inner class RenderJob(
        val token: Long,
        val session: Session,
        val segment: Int,
        val job: Job,
        val permit: MutableStateFlow<Boolean>,
    )

    // -- handlers (never suspend) ---------------------------------------------------------

    private fun handle(msg: Msg) {
        when (msg) {
            is Msg.OpenArticle -> openArticleNow(msg)
            Msg.Play -> controlTarget()?.let(::playSession)
            is Msg.Pause -> pauseNow(msg.reason)
            Msg.Toggle -> controlTarget()?.let {
                if (it.wantsAudio) pauseSession(it, PausedBy.User) else playSession(it)
            }
            is Msg.Jump -> article?.let { jump(it, msg.index, msg.play) }
            Msg.Next -> article?.let { if (it.cursor + 1 < it.segs.size) jump(it, it.cursor + 1, play = false) }
            Msg.Previous -> article?.let(::previousNow)
            is Msg.Speed -> article?.let { setSpeedNow(it, msg.speed) }
            Msg.Stop -> stopAll()
            is Msg.OneShot -> oneShotNow(msg)
            is Msg.CancelOneShot -> oneShots.firstOrNull { it.id == msg.id }?.let {
                endOneShot(it, Outcome.Stopped)
            }
            is Msg.Focus -> focusNow(msg.change)
            is Msg.Host -> if (!msg.foreground) hostLost()
            is Msg.Resolved -> resolvedNow(msg)
            is Msg.Rendered -> renderedNow(msg)
            is Msg.Out -> outputNow(msg.event)
            is Msg.PausedTimeout -> pausedTimeoutNow(msg)
            Msg.HostTimeout -> hostTimeoutNow()
        }
    }

    private fun openArticleNow(msg: Msg.OpenArticle) {
        val current = article
        if (current != null && (current.kind as Kind.Article).key == msg.key) return
        current?.let { retire(it) }
        val s = Session(
            id = ids.incrementAndGet(),
            kind = Kind.Article(msg.key),
            texts = msg.segments,
            target = ResolveTarget.Article(msg.voice, msg.segments.joinToString("\n").take(SAMPLE_CHARS)),
            speed = msg.speed,
            startingSpeed = msg.speed,
        )
        article = s
        log("open key=${msg.key} segs=${msg.segments.size} speed=${msg.speed}")
        if (msg.autoplay) playSession(s)
    }

    /** What play/pause/toggle act on: the one-shot on air, else the article. */
    private fun controlTarget(): Session? =
        oneShots.firstOrNull { it.active } ?: article

    private fun playSession(s: Session) {
        when (val st = s.status) {
            Status.Buffering, Status.Playing -> return
            is Status.Paused -> {
                if (!acquireFocus(s)) return
                s.pausedAt?.let { s.pausedInSegmentMs += clock() - it }
                s.pausedAt = null
                if (st.by == PausedBy.Interrupt && oneShots.any { it.track == Track.Interrupt }) return
                resumeSession(s)
            }
            Status.Idle, Status.Finished, is Status.Failed -> {
                if (s.status == Status.Finished) {
                    s.cursor = 0
                    s.heardChunk = null
                }
                startSession(s)
            }
        }
    }

    /** Cold start from [Session.cursor]: focus, keepalive, resolution, then the start policy. */
    private fun startSession(s: Session) {
        if (!acquireFocus(s)) return
        keepalive.onSessionStarted()
        s.status = Status.Buffering
        s.epoch++
        s.begun = false
        s.ended = false
        s.writeSeg = s.cursor
        s.writeChunk = s.listenerChunk()
        s.startGranted = false
        s.heardStartAt = null
        s.pausedInSegmentMs = 0
        if (s.voice == null) resolve(s)
    }

    /** Paused → on air: carry on from the writer's audio, or start from the listener's chunk. */
    private fun resumeSession(s: Session) {
        if (s.begun && s.heardChunk != null) {
            s.status = Status.Playing
            s.startGranted = true
        } else {
            s.status = Status.Buffering
            s.startGranted = false
        }
    }

    private fun resolve(s: Session) {
        val token = ++renderSeq
        s.resolveToken = token
        s.resolveJob?.cancel()
        s.resolveJob = scope.launch(workDispatcher) {
            val result = runCatching { resolver.resolve(s.target) }
            inbox.trySend(Msg.Resolved(s.id, token, result))
        }
    }

    private fun resolvedNow(msg: Msg.Resolved) {
        val s = sessionById(msg.sessionId) ?: return
        if (s.resolveToken != msg.token || !s.active) return
        s.resolveJob = null
        msg.result
            .onSuccess { v ->
                s.voice = v
                if (s.speed == null) s.speed = clampSpeed(v.aliasSpeed)
                log("voice session=${s.id} engine=${v.engine} voice=${v.voiceId} speed=${s.tempo}")
            }
            .onFailure { fail(s, FailureKind.FAILED, it.message) }
    }

    private fun pauseNow(reason: PauseReason) {
        when (reason) {
            PauseReason.User -> controlTarget()?.let { if (it.wantsAudio) pauseSession(it, PausedBy.User) }
            PauseReason.Navigation -> article?.let { a ->
                if (a.active) pauseSession(a, PausedBy.Navigation)
            }
        }
    }

    private fun pauseSession(s: Session, by: PausedBy) {
        if (s.pausedAt == null) s.pausedAt = clock()
        s.status = Status.Paused(by)
        log("pause session=${s.id} by=$by")
        if (by == PausedBy.User || by == PausedBy.FocusLoss || by == PausedBy.Navigation) {
            val token = ++renderSeq
            s.pausedToken = token
            scope.launch {
                delay(config.pausedTimeoutMs)
                inbox.trySend(Msg.PausedTimeout(s.id, token))
            }
        }
    }

    private fun pausedTimeoutNow(msg: Msg.PausedTimeout) {
        val s = sessionById(msg.sessionId) ?: return
        val st = s.status
        if (s.pausedToken != msg.token || st !is Status.Paused) return
        if (st.by == PausedBy.Focus || st.by == PausedBy.Interrupt) return
        log("paused timeout session=${s.id}")
        if (s.isArticle) stopArticle(s) else endOneShot(s, Outcome.Stopped)
    }

    private fun jump(s: Session, index: Int, play: Boolean) {
        val target = index.coerceIn(0, s.segs.size - 1)
        val wasActive = s.wantsAudio
        val from = s.cursor
        if (s.begun) flushTrack(s)
        s.epoch++
        s.cursor = target
        s.heardChunk = null
        s.heardStartAt = null
        s.pausedInSegmentMs = 0
        s.writeSeg = target
        s.writeChunk = 0
        s.begun = false
        s.ended = false
        // Keep the target and everything after it, plus the block before it
        // for an instant Back; anything further back goes.
        for (i in 0 until (target - 1).coerceAtLeast(0)) s.segs[i].reset()
        val kept = s.segs[target].audioMsFrom(0)
        log("jump $from→$target epoch=${s.epoch} kept=${kept.toLong()}ms")
        if (s.status == Status.Finished) s.status = Status.Idle
        when {
            wasActive -> {
                s.status = Status.Buffering
                s.startGranted = false
            }
            // A seek while paused stays paused and silent; a tap resumes at the target.
            s.status is Status.Paused -> if (play) playSession(s)
            play -> playSession(s)
        }
    }

    private fun previousNow(s: Session) {
        val heardAt = s.heardStartAt
        val paused = s.pausedAt?.let { clock() - it } ?: 0L
        val elapsed = if (heardAt == null) 0L else clock() - heardAt - s.pausedInSegmentMs - paused
        val target = if (elapsed < config.restartWindowMs && s.cursor > 0) s.cursor - 1 else s.cursor
        jump(s, target, play = false)
    }

    private fun setSpeedNow(s: Session, speed: Float) {
        val old = s.tempo
        s.speed = speed
        log("speed $old→$speed")
        if (s.begun) output.submit(OutputCommand.SetTempo(s.track, s.id, speed))
    }

    private fun oneShotNow(msg: Msg.OneShot) {
        val req = msg.request
        val texts = splitParagraphs(req.text)
        val s = Session(
            id = msg.id,
            kind = Kind.OneShot(req.origin, msg.outcome),
            texts = texts,
            target = ResolveTarget.OneShot(req),
            speed = req.speed?.let(::clampSpeed),
            startingSpeed = req.speed ?: 1f,
        )
        if (texts.isEmpty()) {
            msg.outcome.complete(Outcome.Played)
            return
        }
        notice = null
        val a = article
        val policy: String
        when (val st = a?.status) {
            Status.Buffering, Status.Playing -> {
                policy = "interrupt"
                pauseSession(a, PausedBy.Interrupt)
                s.track = Track.Interrupt
            }
            is Status.Paused -> when (st.by) {
                PausedBy.Interrupt, PausedBy.Focus -> {
                    // Behind the current interruption, or until the transient
                    // loss ends — then it reads over the article, which resumes after.
                    policy = "queue"
                    s.track = Track.Interrupt
                }
                PausedBy.User, PausedBy.FocusLoss, PausedBy.Navigation -> {
                    policy = "replace"
                    stopArticle(a)
                    s.track = Track.Main
                }
            }
            else -> {
                policy = if (oneShots.isEmpty()) "play" else "queue"
                s.track = oneShots.lastOrNull { it.active }?.track ?: Track.Main
            }
        }
        log("one-shot id=${s.id} policy=$policy")
        oneShots.add(s)
        startSession(s) // a focus denial resolves and removes it
    }

    private fun endOneShot(s: Session, outcome: Outcome) {
        val kind = s.kind as Kind.OneShot
        cancelJobs(s)
        // Played to its end: the next session is already on the track behind it.
        if (s.begun && outcome != Outcome.Played) flushTrack(s)
        s.status = when (outcome) {
            Outcome.Played -> Status.Finished
            Outcome.Stopped -> Status.Idle
            is Outcome.Failed -> Status.Failed(outcome.kind)
        }
        s.dropPcm()
        releaseResidency(s)
        oneShots.remove(s)
        kind.outcome.complete(outcome)
        log("one-shot id=${s.id} outcome=$outcome")
        afterInterruptMaybe()
    }

    /** The interrupting one-shots are all done: resume the article from its interrupted chunk. */
    private fun afterInterruptMaybe() {
        if (oneShots.any { it.track == Track.Interrupt }) return
        if (interruptOpen) {
            output.submit(OutputCommand.Release(Track.Interrupt))
            trackPlaying.remove(Track.Interrupt)
            interruptOpen = false
        }
        val a = article ?: return
        val st = a.status
        if (st is Status.Paused && st.by == PausedBy.Interrupt) {
            a.pausedAt?.let { a.pausedInSegmentMs += clock() - it }
            a.pausedAt = null
            // The writer still holds the article from mid-chunk; restart that
            // chunk from its first frame instead.
            if (a.begun) flushTrack(a)
            a.epoch++
            a.begun = false
            a.ended = false
            a.writeSeg = a.cursor
            a.writeChunk = a.listenerChunk()
            a.status = Status.Buffering
            a.startGranted = false
            log("resume after interrupt seg=${a.cursor} chunk=${a.writeChunk} epoch=${a.epoch}")
        }
    }

    /**
     * Idle with the article loaded at its block. Its audio is dropped, so Play
     * restarts the block from its start: a re-render need not cut the same
     * chunks (Kokoro's short first piece depends on the engine's own state).
     */
    private fun stopArticle(a: Session) {
        cancelJobs(a)
        if (a.begun) flushTrack(a)
        a.epoch++
        a.begun = false
        a.ended = false
        a.status = Status.Idle
        a.pausedAt = null
        a.heardChunk = null
        a.dropPcm()
        releaseResidency(a)
    }

    /** Replace the article: its session is gone for good. */
    private fun retire(a: Session) {
        stopArticle(a)
        article = null
    }

    private fun stopAll() {
        log("stop")
        article?.let { if (it.status != Status.Idle || it.begun) stopArticle(it) }
        for (s in oneShots.toList()) endOneShot(s, Outcome.Stopped)
        render?.job?.cancel()
        render = null
    }

    private fun fail(s: Session, kind: FailureKind, message: String?) {
        log("failed session=${s.id} kind=$kind msg=$message")
        if (s.isArticle) {
            stopArticle(s)
            s.status = Status.Failed(kind)
        } else {
            val k = s.kind as Kind.OneShot
            if (k.origin == Origin.External) notice = ErrorNotice(s.id, kind, s.voice?.engine)
            endOneShot(s, Outcome.Failed(kind, message))
        }
    }

    private fun cancelJobs(s: Session) {
        s.resolveJob?.cancel()
        s.resolveJob = null
        s.resolveToken = 0
        val r = render
        if (r != null && r.session === s) {
            r.job.cancel()
            render = null
        }
    }

    // -- focus and host ---------------------------------------------------------------------

    private fun acquireFocus(s: Session): Boolean {
        if (focusHeld) return true
        log("focus request")
        focusHeld = focus.request()
        if (!focusHeld) {
            log("focus denied")
            if (s.isArticle) {
                s.status = Status.Failed(FailureKind.FOCUS_DENIED)
            } else {
                (s.kind as Kind.OneShot).outcome.complete(Outcome.Failed(FailureKind.FOCUS_DENIED, "audio focus denied"))
                s.status = Status.Failed(FailureKind.FOCUS_DENIED)
                oneShots.remove(s)
            }
        }
        return focusHeld
    }

    private fun focusNow(change: FocusChange) {
        log("focus $change")
        when (change) {
            FocusChange.LossTransient, FocusChange.LossTransientCanDuck -> {
                focusSuspended = true
                for (s in onAir()) if (s.wantsAudio) pauseSession(s, PausedBy.Focus)
            }
            FocusChange.Gain -> {
                focusSuspended = false
                val a = article
                for (s in listOfNotNull(a) + oneShots) {
                    val st = s.status
                    if (st !is Status.Paused || st.by != PausedBy.Focus) continue
                    if (s === a && oneShots.any { it.track == Track.Interrupt }) {
                        s.status = Status.Paused(PausedBy.Interrupt)
                        continue
                    }
                    s.pausedAt?.let { s.pausedInSegmentMs += clock() - it }
                    s.pausedAt = null
                    resumeSession(s)
                }
            }
            FocusChange.Loss -> {
                if (focusHeld) focus.abandon()
                focusHeld = false
                focusSuspended = false
                for (s in oneShots.toList()) endOneShot(s, Outcome.Stopped)
                article?.let { a ->
                    val st = a.status
                    val byUser = st is Status.Paused && st.by in setOf(PausedBy.User, PausedBy.Navigation)
                    if (a.active && !byUser) pauseSession(a, PausedBy.FocusLoss)
                }
            }
        }
    }

    private fun hostLost() {
        if (article?.active != true && oneShots.none { it.active }) return
        log("host demoted: stopping")
        stopAll()
    }

    private fun hostTimeoutNow() {
        hostTimer = null
        if (host.isForeground()) return
        log("host never foreground: failing")
        // Everything waiting to be heard fails — including an article paused
        // under an interrupting one-shot, which would otherwise resume into
        // the same dead host.
        val waiting = (oneShots + listOfNotNull(article)).filter {
            it.wantsAudio || it.status == Status.Paused(PausedBy.Interrupt)
        }
        for (s in waiting) fail(s, FailureKind.FAILED, "host never became foreground")
    }

    // -- render jobs -------------------------------------------------------------------------

    private fun renderedNow(msg: Msg.Rendered) {
        val r = render ?: return
        if (r.token != msg.token || r.session.id != msg.sessionId) return
        val s = sessionById(msg.sessionId) ?: return
        val seg = s.segs[r.segment]
        when (val e = msg.event) {
            is RenderEvent.Chunk -> {
                seg.chunks[e.index] = Chunk(e.audio.pcm, e.audio.sampleRate)
                seg.rendered = e.index + 1
                if (!e.skewed) {
                    s.rtf.add(e.renderMs.toDouble(), e.audio.pcm.size * 1000.0 / e.audio.sampleRate)
                }
            }
            RenderEvent.Done -> {
                seg.complete = true
                render = null
            }
            is RenderEvent.Failed -> {
                render = null
                fail(s, e.kind, e.message)
            }
        }
    }

    private fun startRender(s: Session, segment: Int) {
        val voice = s.voice ?: return
        val seg = s.segs[segment]
        seg.reset()
        val token = ++renderSeq
        val permit = MutableStateFlow(true)
        if (s.residencyEngine == null) {
            residency.beginSynth(voice.engine)
            s.residencyEngine = voice.engine
        }
        val engine = engines.engine(voice.engine)
        val sessionId = s.id
        val rate = s.tempo
        val job = scope.launch(workDispatcher) {
            renderer.render(engine, voice, seg.text, rate, permit) { event ->
                inbox.trySend(Msg.Rendered(sessionId, token, event))
            }
        }
        render = RenderJob(token, s, segment, job, permit)
    }

    private fun cancelRender(reason: String) {
        val r = render ?: return
        log("render cancelled seg=${r.segment} ($reason)")
        r.job.cancel()
        // A cancelled render's partial segment can't be continued (the engine
        // stream can't resume mid-segment): drop it; it re-renders from its start.
        val s = r.session
        val seg = s.segs[r.segment]
        if (!seg.complete) {
            seg.reset()
            // Its start may already be on the track; a resume must restart it.
            if (s.cursor == r.segment && s.begun) {
                flushTrack(s)
                s.epoch++
                s.begun = false
                s.ended = false
                s.writeSeg = s.cursor
                s.writeChunk = 0
                s.heardChunk = null
                if (s.wantsAudio) {
                    s.status = Status.Buffering
                    s.startGranted = false
                }
            }
        }
        render = null
    }

    // -- the settle pass: plan, start, write, sync, publish ----------------------------------------

    private fun settle() {
        planRender()
        evaluateStarts()
        pump(Track.Main)
        pump(Track.Interrupt)
        syncTracks()
        syncFocusAndResidency()
        publish()
    }

    /** Sessions in play order: the interrupting one-shots, then the Main track's program. */
    private fun playOrder(): List<Session> = program(Track.Interrupt) + program(Track.Main)

    private fun program(track: Track): List<Session> {
        val shots = oneShots.filter { it.track == track && it.active }
        val a = article
        return if (track == Track.Main && a != null && a.active) shots + a else shots
    }

    private fun trackHead(track: Track): Session? = program(track).firstOrNull()

    private fun onAir(): List<Session> =
        listOfNotNull(trackHead(Track.Interrupt), trackHead(Track.Main)).distinct()

    private fun planRender() {
        val items = ArrayList<RenderPlanner.Item>()
        val owners = ArrayList<Session>()
        var aheadBefore = 0.0
        var ahead = 0
        for (s in playOrder()) {
            val st = s.status
            if (s.voice == null) break
            if (st is Status.Paused && st.by == PausedBy.Navigation) continue
            for (i in s.cursor until s.segs.size) {
                val seg = s.segs[i]
                val from = if (i == s.cursor) s.listenerChunk() else 0
                val own = seg.audioMsFrom(from) / s.tempo
                items += RenderPlanner.Item(
                    sessionId = s.id,
                    segment = i,
                    complete = seg.complete && seg.intactFrom(from),
                    depthLimited = s.cloud || config.todaysDepthOnly,
                    segmentsAhead = ahead++,
                    aheadMsBefore = aheadBefore,
                    ownAheadMs = own,
                )
                owners += s
                aheadBefore += own
            }
        }
        val r = render
        if (r != null) {
            val idx = items.indexOfFirst { it.sessionId == r.session.id && it.segment == r.segment }
            val firstIncomplete = items.indexOfFirst { !it.complete }
            when {
                idx < 0 -> cancelRender("no longer needed")
                firstIncomplete in 0 until idx -> cancelRender("seg ${items[firstIncomplete].segment} needed first")
                else -> r.permit.value = RenderPlanner.mayContinue(items[idx], config.budgetMs)
            }
        }
        if (render == null) {
            val next = RenderPlanner.next(items, config.budgetMs) ?: return
            val owner = owners[items.indexOf(next)]
            startRender(owner, next.segment)
        }
    }

    private fun evaluateStarts() {
        for (s in playOrder()) {
            if (s.status != Status.Buffering || s.startGranted || s.voice == null) continue
            // The contiguous run of audio from the listener's position, across
            // segment boundaries (a blank segment renders to nothing).
            var segI = s.cursor
            var c = s.listenerChunk()
            var banked = 0.0
            var chunks = 0
            while (segI < s.segs.size) {
                val seg = s.segs[segI]
                val chunk = seg.chunks[c]
                if (chunk != null) {
                    banked += chunk.audioMs / s.tempo
                    chunks++
                    c++
                    continue
                }
                if (!seg.complete || c < seg.rendered) break
                segI++
                c = 0
            }
            if (segI >= s.segs.size && chunks == 0) {
                // Nothing speakable at all (blank / emoji-only): done, silently.
                if (s.isArticle) s.status = Status.Finished else endOneShot(s, Outcome.Played)
                continue
            }
            val seg = s.segs[s.cursor]
            val remaining = (seg.text.length * learnedMsPerChar(s) - seg.audioMsFrom(0)).coerceAtLeast(0.0) / s.tempo
            val listenerSegDone = segI > s.cursor
            val start = StartPolicy.shouldStart(
                StartPolicy.Input(
                    bankedPlayedMs = banked,
                    bankedChunks = chunks,
                    renderComplete = listenerSegDone,
                    rtf = s.rtf.value,
                    speed = s.tempo,
                    remainingPlayedMs = if (listenerSegDone) 0.0 else remaining,
                    maxHoldChunks = config.maxHoldChunks,
                ),
            )
            if (start) {
                s.startGranted = true
                log(
                    "start session=${s.id} seg=${s.cursor} banked=${banked.toLong()}ms " +
                        "chunks=$chunks rtf=${s.rtf.value?.let { "%.2f".format(it) }}",
                )
            }
        }
    }

    private fun learnedMsPerChar(s: Session): Double {
        var chars = 0
        var ms = 0.0
        for (seg in s.segs) {
            if (!seg.complete || seg.chunks.size != seg.rendered) continue
            chars += seg.text.length
            ms += seg.audioMsFrom(0)
        }
        return if (chars > 0 && ms > 0) ms / chars else config.defaultMsPerChar
    }

    /** Hand the writer every chunk it can take, in play order, per track. */
    private fun pump(track: Track) {
        if (!host.isForeground()) return
        for (s in program(track)) {
            // Only a session the start policy released (or one following
            // another on this track) is written: a held one creates no track.
            if (s.voice == null || !s.startGranted) return
            if (!s.begun) {
                val first = firstWritable(s) ?: return
                output.submit(
                    OutputCommand.Begin(track, s.id, s.epoch, first.sampleRate, s.voice!!.effectBlocks, s.tempo),
                )
                s.begun = true
                if (track == Track.Interrupt) interruptOpen = true
            }
            while (!s.ended) {
                val seg = s.segs[s.writeSeg]
                val chunk = seg.chunks[s.writeChunk]
                if (chunk != null) {
                    output.submit(OutputCommand.Write(track, s.tag(s.writeSeg, s.writeChunk), chunk.pcm))
                    s.writeChunk++
                    continue
                }
                if (!seg.complete || s.writeChunk < seg.rendered) return
                if (s.writeSeg + 1 < s.segs.size) {
                    s.writeSeg++
                    s.writeChunk = 0
                    continue
                }
                output.submit(OutputCommand.End(track, s.id, s.epoch))
                s.ended = true
            }
            // Written to its end: the next session on this track follows gaplessly.
            program(track).getOrNull(program(track).indexOf(s) + 1)?.let { it.startGranted = true }
        }
    }

    private fun firstWritable(s: Session): Chunk? {
        var seg = s.writeSeg
        var c = s.writeChunk
        while (seg < s.segs.size) {
            s.segs[seg].chunks[c]?.let { return it }
            if (!s.segs[seg].complete) return null
            seg++
            c = 0
        }
        return null
    }

    private fun syncTracks() {
        var wantsHost = false
        for (track in Track.entries) {
            val head = trackHead(track)
            val interruptActive = track == Track.Main && program(Track.Interrupt).isNotEmpty()
            val want = head != null && head.wantsAudio && head.startGranted && !interruptActive
            if (want && !host.isForeground()) wantsHost = true
            val play = want && host.isForeground() && focusHeld && !focusSuspended && head!!.begun
            val now = trackPlaying[track]
            if (now == null && !play) continue
            if (play != (now == true)) {
                output.submit(if (play) OutputCommand.Play(track) else OutputCommand.Pause(track))
                trackPlaying[track] = play
            }
        }
        if (wantsHost && hostTimer == null) {
            hostTimer = scope.launch {
                delay(config.hostTimeoutMs)
                inbox.trySend(Msg.HostTimeout)
            }
        } else if (!wantsHost) {
            hostTimer?.cancel()
            hostTimer = null
        }
    }

    private fun syncFocusAndResidency() {
        val anyActive = (article?.active == true) || oneShots.any { it.active }
        if (!anyActive && focusHeld) {
            log("focus abandon")
            focus.abandon()
            focusHeld = false
        }
        article?.let { if (!it.active) releaseResidency(it) }
    }

    private fun releaseResidency(s: Session) {
        s.residencyEngine?.let { residency.endSynth(it) }
        s.residencyEngine = null
    }

    private fun publish() {
        val a = article
        val shot = oneShots.firstOrNull { it.active }
        _state.value = NarratorState(
            article = a?.let {
                ArticleView(
                    key = (it.kind as Kind.Article).key,
                    segmentCount = it.segs.size,
                    index = it.cursor,
                    speed = it.tempo,
                    startingSpeed = it.startingSpeed,
                    status = it.status,
                )
            },
            oneShot = shot?.let {
                OneShotView(it.id, (it.kind as Kind.OneShot).origin, it.status, oneShots.count { o -> o.active } - 1)
            },
            focusHeld = focusHeld,
            notice = notice,
        )
    }

    // -- writer events ---------------------------------------------------------------------------

    private fun outputNow(event: OutputEvent) {
        when (event) {
            is OutputEvent.ChunkStarted -> {
                val s = sessionById(event.tag.sessionId) ?: return
                if (event.tag.epoch != s.epoch) return
                if (event.tag.segment != s.cursor || s.heardStartAt == null) {
                    if (event.tag.segment != s.cursor) {
                        s.cursor = event.tag.segment
                        s.pausedInSegmentMs = 0
                    }
                    s.heardStartAt = clock()
                    trimHeard(s)
                }
                s.heardChunk = event.tag.chunk
                if (s.status == Status.Buffering) s.status = Status.Playing
            }
            is OutputEvent.ChunkFinished -> {
                val s = sessionById(event.tag.sessionId) ?: return
                if (event.tag.epoch != s.epoch) return
                trimHeard(s, finished = event.tag)
            }
            is OutputEvent.SessionEnded -> {
                val s = sessionById(event.sessionId) ?: return
                if (event.epoch != s.epoch) return
                if (s.isArticle) {
                    log("article finished")
                    cancelJobs(s)
                    s.status = Status.Finished
                    s.begun = false
                    s.dropPcm()
                    releaseResidency(s)
                } else {
                    endOneShot(s, Outcome.Played)
                }
            }
        }
    }

    /**
     * Drop heard audio: every segment before the previous one, and — so one
     * huge segment can't grow without bound — the oldest heard chunks beyond
     * [NarratorConfig.retainHeardMs].
     */
    private fun trimHeard(s: Session, finished: ChunkTag? = null) {
        for (i in 0 until (s.cursor - 1).coerceAtLeast(0)) s.segs[i].chunks.clear()
        var heardMs = 0.0
        val heard = ArrayList<Pair<Int, Int>>()
        for (i in (s.cursor - 1).coerceAtLeast(0)..s.cursor) {
            val seg = s.segs[i]
            for ((c, chunk) in seg.chunks) {
                val isHeard = i < s.cursor ||
                    (finished != null && finished.segment == i && c <= finished.chunk) ||
                    (i == s.cursor && c < (s.heardChunk ?: 0))
                if (!isHeard) continue
                heard += i to c
                heardMs += chunk.audioMs
            }
        }
        var k = 0
        while (heardMs > config.retainHeardMs && k < heard.size) {
            val (i, c) = heard[k++]
            s.segs[i].chunks.remove(c)?.let { heardMs -= it.audioMs }
        }
    }

    /**
     * Flush [s]'s track. Everything queued on it goes, so every other session
     * on that track is rewritten from its listener position too.
     */
    private fun flushTrack(s: Session) {
        output.submit(OutputCommand.Flush(s.track, s.id, s.epoch + 1))
        for (o in program(s.track)) {
            if (o === s) continue
            o.begun = false
            o.ended = false
            o.writeSeg = o.cursor
            o.writeChunk = o.listenerChunk()
        }
        trackPlaying[s.track]?.let {
            if (it) output.submit(OutputCommand.Pause(s.track))
            trackPlaying[s.track] = false
        }
    }

    private fun sessionById(id: Long): Session? =
        article?.takeIf { it.id == id } ?: oneShots.firstOrNull { it.id == id }

    // -- test hooks (loop state: read only from the loop's own thread, i.e. the test scheduler) ---------

    /** Held PCM bytes across all sessions (the memory bound, §2.5). */
    internal fun bufferedBytes(): Long =
        (listOfNotNull(article) + oneShots).sumOf { s -> s.segs.sumOf { seg -> seg.chunks.values.sumOf { it.pcm.size * 2L } } }

    /** The article session's current RTF estimate. */
    internal fun articleRtf(): Double? = article?.rtf?.value

    /** The article session's current epoch. */
    internal fun articleEpoch(): Int? = article?.epoch

    internal fun articleId(): Long? = article?.id

    companion object {
        private const val SAMPLE_CHARS = 2_000

        fun clampSpeed(speed: Float): Float =
            if (speed.isNaN()) 1f else speed.coerceIn(NarratorControl.MIN_SPEED, NarratorControl.MAX_SPEED)

        /** Shared text is spoken as paragraphs (blank-line separated); blank ones are dropped. */
        fun splitParagraphs(text: String): List<String> =
            text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

        /** The loop's dedicated thread (not `Default.limitedParallelism(1)`, §2.3). */
        fun newLoopDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "narrator-loop").apply { isDaemon = true } }
                .asCoroutineDispatcher()
    }
}
