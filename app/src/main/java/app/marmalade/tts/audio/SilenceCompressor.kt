package app.marmalade.tts.audio

import kotlin.math.abs

// -----------------------------------------------------------------------------
// SilenceCompressor — trims the decoder's trailing-silence ring-out per chunk.
// -----------------------------------------------------------------------------
//
// Why we want this:
//   - Direct-ORT engines synth one chunk at a time. The decoder emits
//     ~0.5s of trailing silence per chunk that our fixed TRIM_SAMPLES
//     (5000 = 0.21s) only partially removes.
//   - Over many chunks the residual silence accumulates into seconds of
//     dead air, producing noticeably long, draggy playback.
//   - Rescaling only the TRAILING silence run per chunk recovers tight
//     pacing without touching the model output or our chunking.
//
// IMPORTANT — only the trailing run is compressed. Earlier versions
// compressed EVERY silence run ≥100 ms, which crushed the model's
// intentional inter-sentence pauses (a 0.3s pause → 0.06s) and made
// multi-sentence chunks — Japanese especially, where the chunker merges
// short sentences via minChars — run together too fast. The fix: leave
// internal pauses (between sentences/clauses) exactly as the model
// rendered them, and only shorten the dead air hanging off the end.
//
// Designed from first principles — no GPL source consulted.
// -----------------------------------------------------------------------------

object SilenceCompressor {

    /**
     * Default amplitude threshold for treating a sample as "silent".
     * Picked to be just above the model's natural noise floor
     * (~0.3% of full scale on 16-bit PCM). Genuine speech rarely
     * sits this low for ≥ 100 ms; the model's tail decay does.
     */
    const val DEFAULT_THRESHOLD: Int = 200

    /**
     * Minimum silence-run length (in samples) to compress. Shorter
     * runs are kept intact so brief in-sentence pauses (between
     * clauses, after commas) sound natural. 100 ms at 24 kHz =
     * 2400 samples; runs below that are left alone.
     */
    const val DEFAULT_MIN_RUN_SAMPLES: Int = 2400

    /**
     * Default silence-scale factor — matches sherpa-onnx's
     * `OfflineTtsConfig.silence_scale = 0.2f`. Compresses qualifying
     * silence runs to 20% of their original length.
     */
    const val DEFAULT_SCALE: Float = 0.2f

    /**
     * Shorten the TRAILING near-silence run of [pcm] to `floor(runLen ×
     * scale)` samples. Internal silence (inter-sentence / inter-clause
     * pauses) is left untouched — only dead air hanging off the very end
     * is compressed. Returns a possibly-smaller ShortArray.
     *
     * Pass-through when [scale] >= 1.0, [pcm] is empty, or the trailing
     * silence run is shorter than [minRunSamples] (nothing worth trimming).
     */
    fun compress(
        pcm: ShortArray,
        scale: Float = DEFAULT_SCALE,
        threshold: Int = DEFAULT_THRESHOLD,
        minRunSamples: Int = DEFAULT_MIN_RUN_SAMPLES,
    ): ShortArray {
        if (scale >= 1.0f || pcm.isEmpty()) return pcm

        // Walk backwards from the end to find where the trailing silence
        // run begins. Everything before `start` is kept verbatim (including
        // any internal pauses); the trailing run is rescaled.
        var start = pcm.size
        while (start > 0 && abs(pcm[start - 1].toInt()) < threshold) start--

        val runLen = pcm.size - start
        if (runLen < minRunSamples) return pcm

        val keep = (runLen * scale).toInt().coerceAtLeast(1)
        // out is zero-initialised, so the kept trailing span is already silent.
        val out = ShortArray(start + keep)
        System.arraycopy(pcm, 0, out, 0, start)
        return out
    }
}
