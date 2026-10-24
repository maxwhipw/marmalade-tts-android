package app.marmalade.tts.engine

import android.util.Log
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.perf.RenderPace
import app.marmalade.tts.perf.SpeedPerfWarning
import java.io.File
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// -----------------------------------------------------------------------------
// Whole pieces when the engine can't keep up (#54).
//
// The streaming engines (KokoroDirect's streaming path, KittenDirect, Pocket)
// send a piece (a sentence, or a clause piece of one) slice by slice as it
// renders. When the render is slower than playback at the chosen speed, a
// slice comes late and playback stops INSIDE the sentence: a stutter. Max,
// 2026-10-10: "once it gets too fast to keep up the method needs to switch
// to chunking". So, per request:
//
//  - [RenderMode.decide]: stream (today's behaviour) while the render keeps
//    up comfortably, else chunk mode: exactly where the speed-up warning
//    shows ([app.marmalade.tts.perf.SpeedPerfWarning.behind], fed the same
//    measured-else-predicted RTF through [app.marmalade.tts.perf.RenderPace]).
//    The decision is per piece ([RequestPace]): the in-app reader's speed can
//    change mid-segment ([LivePlaybackRate]), and the next piece follows it.
//  - In chunk mode a [PieceGate] holds each piece's slices until the rest of
//    that piece can't run dry ([holdMs]), or the piece is fully rendered.
//    Any wait then falls BEFORE a sentence or clause, never inside one.
//    Kokoro and Kitten also cut smaller slices then
//    ([app.marmalade.tts.engine.kitten.KittenStreamSlicing.CHUNK_MODE_MAX_SLICE_FRAMES]).
//    The audio a gate counts as still ahead is this request's own
//    ([PlaybackHorizon.requestRemainingMs]): a Stop the engine never hears
//    about (a client flushing an utterance whose render had finished) must
//    not make the next request's first piece look covered.
// -----------------------------------------------------------------------------

/** Logcat tag of the per-request mode line (Info, so release builds keep it: the release gate reads it). */
const val RENDER_MODE_TAG = "RenderMode"

/** How one request is sent: slices as they render, or whole pieces. */
data class RenderMode(
    val chunk: Boolean,
    /** Render ms per audio ms at 1.0× the decision used, or null when nothing was known. */
    val rtf: Double?,
    /** Where [rtf] came from: `measured`, `predicted`, `none` or `forced`. */
    val source: String,
    val playbackRate: Float,
) {
    /**
     * One Info line per request:
     * `<engine> mode=chunk rtf=0.43 (predicted) speed=2.00 played=0.86 path=sliced`. [path] `whole`:
     * the engine renders every piece whole before sending it (Kokoro without
     * the streaming layout), so it plays whole pieces in either mode.
     */
    fun log(engineName: String, path: String = "sliced") {
        Log.i(RENDER_MODE_TAG, line(engineName, path))
    }

    fun line(engineName: String, path: String = "sliced"): String {
        // Locale.ROOT: the release gate parses it, whatever the phone's language.
        fun f(x: Double) = String.format(Locale.ROOT, "%.2f", x)
        val played = rtf?.let { f(it * playbackRate) } ?: "-"
        return "$engineName mode=${if (chunk) "chunk" else "stream"} " +
            "rtf=${rtf?.let { f(it) } ?: "-"} ($source) " +
            "speed=${f(playbackRate.toDouble())} played=$played path=$path"
    }

    companion object {
        /**
         * The mode for a request played at [playbackRate], from [rtf] (render
         * ms per audio ms at 1.0×, null when nothing is known): chunk mode
         * exactly where the speed warning shows ([SpeedPerfWarning.behind]).
         * [force] (a debug knob) overrides the decision.
         */
        fun decide(rtf: Double?, source: String, playbackRate: Float, force: Boolean? = null): RenderMode {
            val rate = if (playbackRate > 0f) playbackRate else 1f
            if (force != null) return RenderMode(force, rtf, "forced", rate)
            return RenderMode(SpeedPerfWarning.behind(rtf, rate), rtf, if (rtf == null) "none" else source, rate)
        }

        /**
         * One request of [engineName]'s pace: the RTF the speed warning reads
         * ([RenderPace]: the app's measured RTF, else the prediction), so the
         * warning shows exactly when an engine plays whole pieces, and the
         * rate it is heard at: [playbackRate], or the caller's
         * [LivePlaybackRate]. Debug builds read the `chunkMode` knob.
         */
        suspend fun paceFor(
            engineName: String,
            settings: SettingsRepository,
            filesDir: File,
            playbackRate: Float,
        ): RequestPace {
            val whole = currentCoroutineContext()[CompletePieceOutput] != null
            return RequestPace(
                engineName,
                RenderPace.estimate(settings, engineName),
                playbackRate,
                forced(EngineDevKnobs.get(filesDir, "chunkMode")),
                currentCoroutineContext()[LivePlaybackRate]?.rate,
                log = { it.log(engineName, path = if (whole) "whole" else "sliced") },
            )
        }

        /** Debug knob `chunkMode=on|off` ([EngineDevKnobs]); null = decide. */
        fun forced(knob: String?): Boolean? = when (knob?.trim()?.lowercase()) {
            "on", "true", "chunk" -> true
            "off", "false", "stream" -> false
            else -> null
        }
    }
}

/**
 * The rate a render is heard at, live: the in-app reader's speed can change
 * while a segment renders (the Narrator time-stretches what is already
 * rendered), so its render job carries this and the engines read it per
 * piece. Without it (system TTS: the rate is fixed per utterance) the
 * request's playbackRate holds.
 */
class LivePlaybackRate(val rate: StateFlow<Float>) : AbstractCoroutineContextElement(LivePlaybackRate) {
    companion object Key : CoroutineContext.Key<LivePlaybackRate>
}

/**
 * Ask a streaming engine to hand off only complete pieces. System TTS has
 * no playback-buffer feedback, so a predicted render pace cannot guarantee
 * that a late slice will arrive before the framework runs out of audio.
 */
class CompletePieceOutput : AbstractCoroutineContextElement(CompletePieceOutput) {
    companion object Key : CoroutineContext.Key<CompletePieceOutput>
}

/**
 * One request's pace: the RTF chunk mode decides on (read once per request)
 * and the rate it is heard at, [rate] (live when [live] is given). An engine
 * asks [pieceMode] at each piece's start, so a speed change mid-segment
 * reaches the next piece; the [RENDER_MODE_TAG] line is logged for the
 * first piece and again whenever the mode or the rate changes.
 */
class RequestPace(
    private val engineName: String,
    private val estimate: RenderPace.Estimate?,
    private val requestRate: Float,
    private val force: Boolean?,
    private val live: StateFlow<Float>? = null,
    private val log: (RenderMode) -> Unit = { it.log(engineName) },
) {
    private var logged: RenderMode? = null

    /** The rate the audio is heard at now (> 0). */
    fun rate(): Float = (live?.value ?: requestRate).takeIf { it > 0f } ?: 1f

    /** The mode at [rate] now, without logging. */
    fun mode(): RenderMode = RenderMode.decide(estimate?.rtf, estimate?.source ?: "none", rate(), force)

    /** The mode for the piece starting now; logs it when it differs from the last one logged. */
    fun pieceMode(): RenderMode = mode().also { if (it != logged) { log(it); logged = it } }
}

/** One slice of a piece still to render: its render time (wall ms) and its audio length (ms at 1.0×). */
data class SliceEstimate(val renderMs: Double, val audioMs: Double)

/**
 * How long from now to hold a piece so it plays to its end without running
 * dry (0: release now; infinite: unknown until more of it renders). The
 * listener has [aheadMs] (played ms) of earlier audio still to hear, then
 * the piece's [bankedAudioMs] already rendered, then [rest] (in order) as
 * each finishes rendering. Slice j arrives once it and every slice before it
 * rendered (each charged [margin] × its estimate) and is needed once
 * everything before it has played. Held audio queues behind what is ahead,
 * so the piece starts once both the hold and [aheadMs] are over. With
 * [slowSlice], one slice may instead take that many times its estimate
 * (the worst place for it: the biggest slice so far), so a single slow
 * render can't stall a released piece (#57).
 *
 * Exact for the engines' slice grids (they know every later slice's frames
 * once a piece is planned); render time is wall time, audio lengths play in
 * audio ÷ [rate] ms.
 */
fun holdMs(
    aheadMs: Double,
    bankedAudioMs: Double,
    rest: List<SliceEstimate>,
    rate: Float = 1f,
    margin: Double = PieceGate.RENDER_MARGIN,
    slowSlice: Double? = null,
): Double {
    val r = if (rate > 0f) rate.toDouble() else 1.0
    // Beyond the margin; 0 × an infinite estimate would be NaN.
    val slowExtra = ((slowSlice ?: margin) - margin).coerceAtLeast(0.0)
    var arrives = 0.0
    var slowest = 0.0
    var before = bankedAudioMs / r
    var start = 0.0 // when the piece must start, from now
    for (s in rest) {
        arrives += s.renderMs * margin
        slowest = maxOf(slowest, s.renderMs)
        start = maxOf(start, arrives + (if (slowExtra > 0.0) slowExtra * slowest else 0.0) - before)
        before += s.audioMs / r
    }
    return if (start <= aheadMs) 0.0 else start
}

/** Whether a piece released now plays to its end without running dry ([holdMs] is 0). */
fun keepsUp(
    aheadMs: Double,
    bankedAudioMs: Double,
    rest: List<SliceEstimate>,
    rate: Float = 1f,
    margin: Double = PieceGate.RENDER_MARGIN,
    slowSlice: Double? = null,
): Boolean = holdMs(aheadMs, bankedAudioMs, rest, rate, margin, slowSlice) <= 0.0

/**
 * Chunk mode's hold, per piece (see the file comment). A streaming engine
 * calls [startPiece] (with the piece's mode) before a piece's first slice,
 * [offer] with each rendered slice, and [endPiece] once the piece is fully
 * rendered; it sends whatever comes back, in order. Out of chunk mode every
 * slice comes straight back.
 *
 * Holding stops for the rest of the piece once [keepsUp] says the slices
 * still to render will each arrive before playback needs them: at the next
 * slice, or earlier at [dueAtMs] if the engine calls [recheck] then
 * ([TimedPieceGate] does). A piece that never gets there plays once fully
 * rendered, so playback can only wait at a piece's start. Audio lengths are
 * audio ms; [rate] (read at every check) turns them into played time, so a
 * speed change mid-piece re-times the hold. Once released nothing can
 * call a piece back, so the hold also allows one slice per piece to take
 * [slowSlice] times its estimate ([SLOW_SLICE]; null: none, for an engine
 * whose estimates already allow for one). [onRelease] hears about each
 * piece that was held: how long after its first slice it went out, how many
 * slices went at once, and how many were still to render. Not thread-safe.
 */
class PieceGate<T>(
    private val margin: Double = RENDER_MARGIN,
    private val slowSlice: Double? = SLOW_SLICE,
    private val rate: () -> Float = { 1f },
    private val onRelease: (heldMs: Long, slices: Int, rest: Int) -> Unit = { _, _, _ -> },
) {
    private val held = ArrayList<T>()
    private var open = true
    private var holdUntilEnd = false
    private var heldSince = 0L
    private var restAtDue = 0
    private var lastOfferAt = 0L
    private var lastRest: () -> List<SliceEstimate> = { emptyList() }

    /** Audio ms of the current piece held so far. */
    var heldAudioMs = 0.0
        private set

    /** While holding: when releasing may be safe, as of the last check. Null otherwise. */
    var dueAtMs: Long? = null
        private set

    /**
     * A new piece. [holdUntilEnd] disables estimate/timer-based early release:
     * only [endPiece] may hand off its slices, regardless of [chunk].
     */
    fun startPiece(chunk: Boolean, holdUntilEnd: Boolean = false) {
        // A piece the engine abandoned (empty render) leaves nothing to send.
        held.clear()
        heldAudioMs = 0.0
        dueAtMs = null
        this.holdUntilEnd = holdUntilEnd
        open = !chunk && !holdUntilEnd
    }

    /**
     * One rendered slice of [audioMs] at [nowMs]. [aheadMs]: earlier audio
     * (played ms) still to be heard before this piece starts; [rest]: the
     * piece's slices still to render. Returns the slices to send now.
     */
    fun offer(slice: T, audioMs: Double, nowMs: Long, aheadMs: Double, rest: () -> List<SliceEstimate>): List<T> {
        if (open) return listOf(slice)
        if (held.isEmpty()) heldSince = nowMs
        held += slice
        heldAudioMs += audioMs
        if (holdUntilEnd) return emptyList()
        lastOfferAt = nowMs
        lastRest = rest
        return check(nowMs, aheadMs, rest())
    }

    /**
     * At [dueAtMs]: release if it is still safe, else hold on. The slice in
     * flight has been rendering since the last [offer]; if it is already
     * later than its estimate allowed, the piece waits for it (the next
     * [offer] decides), else the hold is recomputed with its remaining
     * time, the current [rate] and [aheadMs].
     */
    fun recheck(nowMs: Long, aheadMs: Double): List<T> {
        if (open || held.isEmpty() || holdUntilEnd) {
            dueAtMs = null
            return emptyList()
        }
        val rest = lastRest()
        val elapsed = (nowMs - lastOfferAt).toDouble()
        val inFlight = rest.firstOrNull()
        if (inFlight != null && elapsed >= inFlight.renderMs * margin) {
            dueAtMs = null
            restAtDue = rest.size
            return emptyList()
        }
        val remaining = if (inFlight == null) rest else listOf(inFlight.copy(renderMs = inFlight.renderMs - elapsed / margin)) + rest.drop(1)
        return check(nowMs, aheadMs, remaining)
    }

    /** The piece is fully rendered: whatever is still held goes. */
    fun endPiece(nowMs: Long): List<T> = if (held.isEmpty()) emptyList() else release(nowMs, 0)

    private fun check(nowMs: Long, aheadMs: Double, rest: List<SliceEstimate>): List<T> {
        val wait = holdMs(aheadMs, heldAudioMs, rest, rate(), margin, slowSlice)
        if (wait > 0.0) {
            // Infinite: a length the engine can't bound yet; the next slice decides.
            dueAtMs = if (wait.isInfinite()) null else nowMs + Math.ceil(wait).toLong()
            restAtDue = rest.size
            return emptyList()
        }
        return release(nowMs, rest.size)
    }

    private fun release(nowMs: Long, rest: Int): List<T> {
        open = true
        dueAtMs = null
        // One slice let go at once was never held.
        if (held.size > 1 || rest == 0 || nowMs > heldSince) onRelease(nowMs - heldSince, held.size, rest)
        val out = ArrayList(held)
        held.clear()
        heldAudioMs = 0.0
        return out
    }

    companion object {
        /**
         * Each later slice is assumed to take this many times its estimated
         * render. Small on purpose: near real time (Kokoro at 2× on a 6a
         * renders at ~0.85 of playback) a large margin reads as "behind" and
         * holds nearly the whole sentence (1.3 made a 2.3 s wait where
         * streaming had stuttered ~1.1 s in all, #54).
         */
        const val RENDER_MARGIN = 1.1

        /**
         * One slice per piece may take this many times its estimate. A
         * slow slice is a one-off, not a trend: on the 6a the first 2 s
         * slice of a Kokoro piece took up to 1.35x the fit while later ones
         * stayed within 1.11x, and one such slice at about 1.8x stalled a
         * released piece 468 ms (#57). Charging every slice that much would
         * hold whole sentences near real time (see [RENDER_MARGIN]).
         */
        const val SLOW_SLICE = 2.0
    }
}

/**
 * A [PieceGate] whose hold ends at [PieceGate.dueAtMs], not only when the
 * next slice is rendered: a timer on [scope] re-checks then
 * ([PieceGate.recheck]) and releases if it is still safe, else re-arms or
 * waits for the next slice, so a sentence waits no longer than it must even
 * while the engine is busy with a long slice. Every call (and [send]) runs
 * under one lock; an engine that reads state [send] changes does so inside
 * [offer]'s lambdas or between pieces.
 */
class TimedPieceGate<T>(
    private val scope: CoroutineScope,
    private val send: suspend (List<T>) -> Unit,
    rate: () -> Float = { 1f },
    onRelease: (heldMs: Long, slices: Int, rest: Int) -> Unit = { _, _, _ -> },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    slowSlice: Double? = PieceGate.SLOW_SLICE,
) {
    private val gate = PieceGate<T>(slowSlice = slowSlice, rate = rate, onRelease = onRelease)
    private val lock = Mutex()
    private var timer: Job? = null

    suspend fun startPiece(chunk: Boolean) = lock.withLock {
        cancelTimer()
        gate.startPiece(chunk)
    }

    suspend fun offer(slice: T, audioMs: Double, aheadMs: () -> Double, rest: () -> List<SliceEstimate>) = lock.withLock {
        cancelTimer()
        send(gate.offer(slice, audioMs, nowMs(), aheadMs(), rest))
        val first = gate.dueAtMs ?: return@withLock
        timer = scope.launch {
            var due: Long? = first
            while (due != null) {
                delay((due - nowMs()).coerceAtLeast(0))
                due = lock.withLock {
                    send(gate.recheck(nowMs(), aheadMs()))
                    gate.dueAtMs
                }
            }
        }
    }

    suspend fun endPiece() = lock.withLock {
        cancelTimer()
        send(gate.endPiece(nowMs()))
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}
