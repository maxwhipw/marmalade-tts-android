package app.marmalade.tts.engine.kitten

// -----------------------------------------------------------------------------
// Duration-exact lead/tail trim for Kitten renders.
//
// Kitten's ONNX emits a per-token duration output (frames of exactly 600
// samples each) alongside the waveform. The BOS pad token renders ~460 ms
// of lead silence and the trailing punctuation-pause + EOS group several
// hundred ms more; concatenating untrimmed sentence renders produces the
// dead air the CLI's listening lab spent rounds eliminating. This is a
// faithful port of the CLI daemon's `_trim_run` (kitten-daemon.py): trim
// the lead pad to a small onset margin, trim the trailing non-speech token
// group to a natural gap, never guess from the waveform alone.
//
// Pure sequence ops — unit-tested without the model. If the duration
// contract is broken (wav length ≠ 600 × Σdur), return null and let the
// caller fall back to the legacy blind trim rather than cut speech.
// -----------------------------------------------------------------------------

internal object KittenTrim {

    /** Samples per duration frame; fixed by the trained vocoder. */
    const val FRAME_SAMPLES = 600

    /** Frames of lead pad kept (~50 ms) — onset safety margin. */
    const val HEAD_KEEP_FRAMES = 2L

    /** Frames of tail pad kept (~75 ms) — natural inter-sentence gap. */
    const val TAIL_KEEP_FRAMES = 3L

    /**
     * The speech-bearing slice of one raw render, or null when the
     * duration output doesn't match the waveform (caller falls back).
     *
     * [ids] are the wrapped input tokens (`[0, ...phonemes..., 10, 0]`),
     * [durFrames] the model's per-token frame counts. The tail group is
     * every trailing token ≤ [SPACE_TOKEN] — pad, punctuation, space —
     * whose frames are pause, not speech.
     */
    fun trim(ids: IntArray, wav: FloatArray, durFrames: LongArray): FloatArray? {
        if (ids.size != durFrames.size || ids.isEmpty()) return null
        var total = 0L
        for (d in durFrames) total += d
        if (wav.size.toLong() != total * FRAME_SAMPLES) return null

        val start = (durFrames[0] - HEAD_KEEP_FRAMES).coerceAtLeast(0L) * FRAME_SAMPLES
        var tailFrames = 0L
        for (i in ids.indices.reversed()) {
            if (ids[i] <= SPACE_TOKEN) tailFrames += durFrames[i] else break
        }
        val tailPad = (tailFrames - TAIL_KEEP_FRAMES).coerceAtLeast(0L) * FRAME_SAMPLES
        val end = wav.size - tailPad
        if (start >= end) return null
        return wav.copyOfRange(start.toInt(), end.toInt())
    }
}
