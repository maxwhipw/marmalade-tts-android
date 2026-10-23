package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.audio.TailTrim
import app.marmalade.tts.engine.SentenceGaps
import app.marmalade.tts.engine.kitten.SPACE_TOKEN
import kotlin.math.abs

/**
 * Where an alias's pause between sentences (#96) goes in Kokoro's audio,
 * kept free of Android and ORT so the JVM tests pin it.
 *
 * Kokoro renders several short sentences as one piece, and re-cutting them
 * one per call changes their intonation (the #96 lab measured up to 1.3
 * semitones at the joins) and costs a planner call each. So the pieces stay
 * as they are and the silence goes INSIDE a piece: at a sentence end the
 * planner gives the period (and the space after it) a run of duration frames
 * that the generator renders as the voice's own pause, and the extra silence
 * is spliced into the middle of that run. The voice is rendered exactly as at
 * Natural; only zeros are added.
 *
 * A piece that ends a sentence and isn't the request's last gets the pause
 * after its audio instead ([endsSentence]).
 */
internal object KokoroSentencePause {

    /** Kokoro's `.` `!` `?` token ids (the shared IPA vocab, `tokens.txt`). */
    private val SENTENCE_ENDERS = intArrayOf(4, 5, 6)

    /** How far a splice may move to land on the quietest sample (10 ms at 24 kHz). */
    const val SNAP_SAMPLES = 240

    private fun isPause(id: Int) = id <= SPACE_TOKEN

    /**
     * The pause for one planned piece ([ids] wrapped, [durFrames] planned),
     * or null when it adds nothing: at Natural (so the piece renders exactly
     * as before #96), or when the piece has no sentence end with speech after
     * it, inside it or (unless it is the [lastPiece] of the request) at its end.
     */
    fun forPiece(ids: IntArray, durFrames: LongArray, sentencePauseMs: Int, lastPiece: Boolean, sampleRate: Int): Piece? {
        if (sentencePauseMs <= 0) return null
        val splices = spliceFrames(ids, durFrames).map { it * TailTrim.FRAME_SAMPLES }
        val trailing = !lastPiece && endsSentence(ids)
        if (splices.isEmpty() && !trailing) return null
        return Piece(splices, SentenceGaps.samples(sentencePauseMs, sampleRate), trailing)
    }

    /**
     * One piece's pause: [silence] samples at each of [spliceSamples] (piece
     * sample positions), and after the piece's last audio when [trailing].
     */
    class Piece(val spliceSamples: List<Int>, val silence: Int, val trailing: Boolean) {
        /** [pcm] (the piece's audio from sample [offset]) with its pauses; [last] = the piece's last audio. */
        fun apply(pcm: ShortArray, offset: Int, last: Boolean): ShortArray {
            val spliced = insertSilence(pcm, offset, spliceSamples, silence)
            return if (last && trailing) spliced.copyOf(spliced.size + silence) else spliced
        }
    }

    /**
     * The frames, within a piece, where a pause goes: the middle of each run
     * of pause tokens (pad / punctuation / space, ids ≤ [SPACE_TOKEN]) that
     * holds a sentence end and has speech on both sides. [ids] are the piece's
     * wrapped token ids and [durFrames] their planned durations.
     */
    fun spliceFrames(ids: IntArray, durFrames: LongArray): List<Int> {
        require(ids.size == durFrames.size) { "${ids.size} ids, ${durFrames.size} durations" }
        val out = ArrayList<Int>()
        var frame = 0L
        var i = 0
        var spoken = false
        while (i < ids.size) {
            if (!isPause(ids[i])) {
                spoken = true
                frame += durFrames[i++]
                continue
            }
            val runStart = frame
            var ender = false
            while (i < ids.size && isPause(ids[i])) {
                if (ids[i] in SENTENCE_ENDERS) ender = true
                frame += durFrames[i++]
            }
            if (ender && spoken && i < ids.size) out.add(((runStart + frame) / 2).toInt())
        }
        return out
    }

    /** True when the piece's trailing run of pause tokens holds a sentence end. */
    fun endsSentence(ids: IntArray): Boolean {
        for (i in ids.indices.reversed()) {
            if (!isPause(ids[i])) return false
            if (ids[i] in SENTENCE_ENDERS) return true
        }
        return false
    }

    /**
     * [pcm], the piece's audio from sample [offset] on, with [silence] zero
     * samples inserted at every position of [atSamples] (piece samples) that
     * falls inside it. Each splice moves, by at most [SNAP_SAMPLES] and never
     * outside [pcm], to where the samples either side of the cut are
     * quietest, so the cut makes no click. [pcm] itself when none falls
     * inside.
     */
    fun insertSilence(pcm: ShortArray, offset: Int, atSamples: List<Int>, silence: Int): ShortArray {
        if (silence <= 0) return pcm
        val cuts = atSamples.map { it - offset }.filter { it in 0 until pcm.size }.map { quietestCut(pcm, it) }
        if (cuts.isEmpty()) return pcm
        val out = ShortArray(pcm.size + cuts.size * silence)
        var from = 0
        var to = 0
        for (c in cuts.sorted()) {
            pcm.copyInto(out, to, from, c)
            to += c - from + silence
            from = c
        }
        pcm.copyInto(out, to, from, pcm.size)
        return out
    }

    /**
     * The cut near [at] whose neighbours (the samples before and after it) are
     * quietest. Only cuts with a neighbour on both sides inside [pcm] compete:
     * an edge has a neighbour in the slice next to it, which this can't see.
     */
    private fun quietestCut(pcm: ShortArray, at: Int): Int {
        val lo = maxOf(1, at - SNAP_SAMPLES)
        val hi = minOf(pcm.size - 1, at + SNAP_SAMPLES)
        if (lo > hi) return at
        var best = at.coerceIn(lo, hi)
        var bestLevel = Int.MAX_VALUE
        for (c in lo..hi) {
            val level = maxOf(abs(pcm[c - 1].toInt()), abs(pcm[c].toInt()))
            if (level < bestLevel || (level == bestLevel && abs(c - at) < abs(best - at))) {
                best = c
                bestLevel = level
            }
        }
        return best
    }
}
