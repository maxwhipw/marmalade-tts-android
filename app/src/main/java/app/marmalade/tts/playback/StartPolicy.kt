package app.marmalade.tts.playback

import kotlin.math.max

/**
 * When may audio start? Pure: the Narrator calls it whenever a session is
 * waiting to start (cold start after chunk 0, a resume, a jump) with what is
 * actually buffered and what the engine has been measured to do.
 *
 * It is today's [app.marmalade.tts.engine.PrerollGate] formula, moved out of
 * the engine and fed the audio already banked:
 *
 *   playedRtf = rtf × speed                  (render ms per PLAYED ms)
 *   deficit   = max(0, playedRtf − 0.9)      (REALTIME_MARGIN)
 *   stall     = remainingPlayedMs × deficit  (silence the rest would cost)
 *
 * and start once the banked audio covers the stall to within half a chunk
 * (PrerollGate's "round, not ceil": K = 1 + round(shortfall)), or once
 * [maxHoldChunks] chunks are banked (the TTFA cap, PrerollGate's
 * MAX_PREROLL_CHUNKS = 2), or when nothing is left to render. A block
 * prepared behind the playing one therefore needs no hold — it is all banked.
 */
object StartPolicy {

    const val REALTIME_MARGIN = 0.9

    /** PrerollGate.MAX_PREROLL_CHUNKS: start within about one extra sentence at most. */
    const val MAX_HOLD_CHUNKS = 2

    data class Input(
        /** Played ms of contiguous audio buffered from the listener's position. */
        val bankedPlayedMs: Double,
        /** Chunks in that banked run. */
        val bankedChunks: Int,
        /** True when everything from the listener's position to the segment end is rendered. */
        val renderComplete: Boolean,
        /** Measured render-ms per audio-ms (EWMA), null before any chunk rendered. */
        val rtf: Double?,
        val speed: Float,
        /** Estimated played ms still to render in the listener's segment. */
        val remainingPlayedMs: Double,
        val maxHoldChunks: Int = MAX_HOLD_CHUNKS,
    )

    fun shouldStart(input: Input): Boolean {
        if (input.bankedChunks == 0) return input.renderComplete && input.bankedPlayedMs > 0.0
        if (input.renderComplete) return true
        if (input.bankedChunks >= input.maxHoldChunks) return true
        val rtf = input.rtf ?: return false
        val deficit = max(0.0, rtf * input.speed - REALTIME_MARGIN)
        if (deficit == 0.0) return true
        val stall = input.remainingPlayedMs * deficit
        val avgChunk = input.bankedPlayedMs / input.bankedChunks
        // K = 1 + round(shortfall): banked beyond the first chunk must cover
        // the stall to within half a chunk-length.
        return input.bankedPlayedMs - avgChunk >= stall - avgChunk / 2
    }
}

/**
 * The session's measured render speed: an EWMA over recent unskewed chunks
 * (render ms / audio ms). Chunk 0 is noisy and the phone heats up over an
 * article, so later decisions follow the recent past, not one sample.
 */
class RtfEstimator(private val alpha: Double = 0.5) {
    var value: Double? = null
        private set

    fun add(renderMs: Double, audioMs: Double) {
        if (audioMs <= 0.0) return
        val sample = renderMs / audioMs
        value = value?.let { it + alpha * (sample - it) } ?: sample
    }
}
