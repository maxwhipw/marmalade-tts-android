package app.marmalade.tts.engine

import kotlin.math.ceil
import kotlin.math.max

/**
 * Chunk-grain adaptive pre-roll for the sentence-streaming engines —
 * PocketEngine's frame-grain K mechanism generalised to engines whose
 * only timing signal is "chunk N took renderMs and produced audioMs".
 *
 * The producer feeds every rendered chunk through [onChunkRendered] and
 * emits whatever comes back; anything still held when the input runs
 * out (empty trailing chunks, fewer chunks than expected) is returned
 * by [drain].
 *
 * K — how many chunks to hold before the first emit — is decided once,
 * from chunk 0, against the *played* clock: a downstream time-stretch
 * (see [app.marmalade.tts.service.SpeedPlan.playbackRate]) makes the
 * audio drain `playbackRate`× faster than the engine's own clock, so
 *
 *   playedMs        = audioMs / playbackRate
 *   deficitFraction = max(0, renderMs / playedMs − REALTIME_MARGIN)
 *   K               = 1 + ceil((totalChunks − 1) × deficitFraction)
 *
 * capped at [maxPreroll]. deficitFraction is the extra render time each
 * played chunk-length costs; over the remaining totalChunks − 1 chunks
 * that shortfall must be covered by audio banked before playback
 * starts, and each held chunk banks ≈ one chunk-length. At or above
 * realtime (with [REALTIME_MARGIN] headroom to spare) K stays 1 and
 * behaviour is exactly what it was before this class existed.
 *
 * There is deliberately no speed threshold: measured on the 8a
 * (2026-09-19), warm Kokoro renders at RTF 0.51–0.56, so at 1.0× the
 * played RTF is ~0.55 → K = 1, at 1.5× ~0.84 → still K = 1, and at
 * 2.0× ~1.02–1.12 → K = 2, which is the ~1.1 s between-sentence stall
 * this fixes. The gate self-calibrates from what chunk 0 actually cost
 * on this device, this session.
 */
class PrerollGate<T>(
    private val playbackRate: Float,
    private val totalChunks: Int,
    private val maxPreroll: Int = MAX_PREROLL_CHUNKS,
) {
    /** Decided after the first rendered chunk; 1 until then. */
    var prerollChunks: Int = 1
        private set

    private val held = ArrayList<T>()
    private var seen = 0

    /**
     * Account for one rendered chunk and return the chunks (in order)
     * that should be emitted now — empty while the pre-roll is still
     * filling.
     */
    fun onChunkRendered(chunk: T, renderMs: Long, audioMs: Long): List<T> {
        if (seen == 0 && audioMs > 0 && playbackRate > 0f) {
            val playedMs = audioMs / playbackRate
            val deficitFraction = max(0.0, renderMs / playedMs - REALTIME_MARGIN)
            prerollChunks = (1 + ceil((totalChunks - 1) * deficitFraction).toInt())
                .coerceIn(1, maxPreroll)
        }
        val idx = seen++
        // Mirrors PocketEngine's emitOrBuffer: chunks 0..K−2 are held,
        // chunk K−1 flushes everything — unless it's the last chunk,
        // where holding would just delay the tail for nothing.
        return if (idx < prerollChunks - 1 && idx < totalChunks - 1) {
            held.add(chunk)
            emptyList()
        } else {
            val out = ArrayList<T>(held.size + 1)
            out.addAll(held)
            held.clear()
            out.add(chunk)
            out
        }
    }

    /** Chunks still held when the producer ran out of input. */
    fun drain(): List<T> {
        val out = ArrayList<T>(held)
        held.clear()
        return out
    }

    companion object {
        /**
         * Required headroom before K stays at 1: a played RTF of 0.95
         * has technically no deficit but also no slack for a slow
         * sentence, so anything above 0.9 banks chunks.
         */
        const val REALTIME_MARGIN = 0.9

        /**
         * TTFA bound: K chunks must render before the first emit, and a
         * warm Kokoro chunk is ~1–2 s of wall time. 3 keeps the worst
         * added wait in the "long sentence" range rather than the
         * "is it broken?" range. (Pocket's own ceiling is 2 — its
         * chunks render slower, so its TTFA budget is tighter.)
         */
        const val MAX_PREROLL_CHUNKS = 3
    }
}
