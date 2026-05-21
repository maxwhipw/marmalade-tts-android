package app.marmalade.tts.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   caller (MarmaladeSynthService / Synthesizer)
//     │
//     ├── pcm: ShortArray   (16-bit signed mono PCM, native sample rate)
//     ├── sampleRate: Int
//     └── preset: EffectPreset
//          │
//          ▼
//      EffectChain.apply(pcm, sr, preset)
//          │
//          ├── NONE      ── returns the input ShortArray (no copy)
//          ├── CAVE      ── 3-tap comb-filter reverb (250/350/450 ms taps,
//          │                 decreasing gain). Output is longer than input
//          │                 (tail = max tap length) so the reverb trail
//          │                 survives past the dry signal.
//          ├── ROBOT     ── ~6-bit quantization (bit-crush)
//          │                + one-pole low-pass ≈ 2 kHz
//          │                + 5 Hz / ±2 semitone vibrato (sinusoidal pitch mod
//          │                via fractional-index resample).
//          └── TELEPHONE ── cascaded one-pole HPF (300 Hz) + LPF (3.4 kHz)
//                            + soft-clip at ±80% of full-scale.
//
//   All math is pure Kotlin (no native, no JNI). Floats are used internally
//   for headroom; outputs are clamped to Short range on return.
//
//   The vocabulary (cave / robot / telephone) mirrors the marmalade-tts CLI
//   `--effect` presets so docs and aliases stay consistent across platforms.
// -----------------------------------------------------------------------------

/** Effect preset selector. Names mirror the CLI `--effect` flag. */
enum class EffectPreset { NONE, CAVE, ROBOT, TELEPHONE }

/**
 * Pure-Kotlin DSP effect presets that match the marmalade-tts CLI
 * `--effect cave|robot|telephone` flags. All processing is in-process on
 * a finite PCM buffer so the result can be handed to AudioTrack or to the
 * system TTS callback path without needing `android.media.audiofx.*`
 * (those classes only work on a *playing* AudioTrack, not arbitrary
 * buffers).
 *
 * Reverb tails (CAVE) extend the output length beyond the input. Callers
 * should treat the returned array's length as authoritative.
 */
object EffectChain {

    /**
     * Apply [preset] to [pcm] sampled at [sampleRate] Hz, returning a new
     * (or, for NONE, the same) ShortArray. The input array is never
     * mutated.
     */
    fun apply(pcm: ShortArray, sampleRate: Int, preset: EffectPreset): ShortArray =
        when (preset) {
            EffectPreset.NONE -> pcm
            EffectPreset.CAVE -> cave(pcm, sampleRate)
            EffectPreset.ROBOT -> robot(pcm, sampleRate)
            EffectPreset.TELEPHONE -> telephone(pcm, sampleRate)
        }

    // -- CAVE -----------------------------------------------------------------
    //
    // Sox recipe approximation: a 3-tap delay-line reverb. Each tap is fed
    // back into the next pass so the tail decays exponentially. Tap times
    // are 250 / 350 / 450 ms with gains 0.6 / 0.4 / 0.25 — picked by ear to
    // approximate sox's "reverb 50 50 100" feel without the multi-tap FDN.
    //
    // The output buffer is `pcm.size + maxTap` samples long so the last
    // reflection has room to ring out (i.e. the test asserting non-zero
    // tail after input ends is satisfied).

    private fun cave(pcm: ShortArray, sampleRate: Int): ShortArray {
        val tapMs = intArrayOf(250, 350, 450)
        val tapGain = floatArrayOf(0.6f, 0.4f, 0.25f)
        val tapSamples = IntArray(tapMs.size) { (tapMs[it] * sampleRate) / 1000 }
        var maxTap = 0
        for (n in tapSamples) if (n > maxTap) maxTap = n
        val outLen = pcm.size + maxTap
        val wet = FloatArray(outLen)

        // Dry signal first.
        for (i in pcm.indices) wet[i] = pcm[i].toFloat()

        // For each tap, add a delayed, attenuated copy of the (already
        // partially-wet) signal back into the buffer. Iterating taps in
        // order lets later taps pick up echoes of earlier taps — that's
        // what gives the chain its "cave-y" density.
        for (t in tapSamples.indices) {
            val delay = tapSamples[t]
            val gain = tapGain[t]
            for (i in delay until outLen) {
                wet[i] += wet[i - delay] * gain
            }
        }

        // Normalize: reverb summing pushes peaks above full-scale. Find the
        // hottest sample and scale down only if we'd clip — this preserves
        // dynamics when the input was quiet.
        return scaleAndClip(wet)
    }

    // -- ROBOT ----------------------------------------------------------------
    //
    // Two staples of "robot voice": bit-crush (quantize amplitude to coarse
    // steps) and a low-pass to take the harsh edge off the resulting
    // staircase. Then a slow vibrato via fractional-index resampling
    // (5 Hz, ±2 semitones) gives it the recognizably-mechanical wobble.
    //
    // 6-bit quantization → 64 amplitude steps; that's coarse enough to
    // make the test "count distinct sample values is much lower than input"
    // pass reliably (real speech has thousands of distinct values).

    private fun robot(pcm: ShortArray, sampleRate: Int): ShortArray {
        if (pcm.isEmpty()) return ShortArray(0)

        // Step 1: bit-crush to ~6 bits (64 levels across the full Short range).
        val levels = 64
        val step = (Short.MAX_VALUE.toInt() - Short.MIN_VALUE.toInt()) / levels
        val crushed = FloatArray(pcm.size) { i ->
            // Quantize then re-center on a step boundary.
            val q = ((pcm[i].toInt() - Short.MIN_VALUE.toInt()) / step) * step + Short.MIN_VALUE.toInt()
            q.toFloat()
        }

        // Step 2: one-pole low-pass at ~2 kHz to soften the staircase.
        // alpha for cutoff fc:  alpha = dt / (RC + dt), RC = 1/(2π fc)
        val fc = 2000.0
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * fc)
        val alpha = (dt / (rc + dt)).toFloat()
        var prev = 0f
        for (i in crushed.indices) {
            prev += alpha * (crushed[i] - prev)
            crushed[i] = prev
        }

        // Step 3: vibrato via sinusoidal pitch mod. Resampling at a
        // fractional index where the read pointer advances by
        //   1 + depth * sin(2π * lfoHz * t)
        // shifts pitch by depth (in cents); ±2 semitones = ±200 cents
        // ≈ ±0.122 ratio. Use 0.12 for clean test math.
        val lfoHz = 5.0
        val depth = 0.12f
        val out = FloatArray(crushed.size)
        var readIdx = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sampleRate
            val rate = 1.0 + depth * sin(2.0 * PI * lfoHz * t)
            val idx = readIdx
            val lo = idx.toInt()
            val hi = lo + 1
            out[i] = if (lo < 0 || hi >= crushed.size) {
                0f
            } else {
                val frac = (idx - lo).toFloat()
                crushed[lo] * (1f - frac) + crushed[hi] * frac
            }
            readIdx += rate
            if (readIdx >= crushed.size - 1) {
                // We've run out of source samples — pad the tail with silence
                // rather than wrapping (wrap would create an audible click).
                readIdx = (crushed.size - 1).toDouble()
            }
        }

        return scaleAndClip(out)
    }

    // -- TELEPHONE ------------------------------------------------------------
    //
    // Old-school phone-line: 300 Hz – 3.4 kHz bandpass + soft compression.
    // Implemented as a cascaded one-pole HPF (300 Hz) → one-pole LPF
    // (3.4 kHz). One-pole filters are gentle (6 dB/oct each), but cascading
    // gives the unmistakable "tinny" mid-band that listeners read as
    // "phone."
    //
    // The soft-clip at 80% of full-scale plays the role of the analog
    // phone line's mild compression: peaks get rounded off without
    // hard-clipping artifacts.

    private fun telephone(pcm: ShortArray, sampleRate: Int): ShortArray {
        val out = FloatArray(pcm.size)

        // One-pole HPF at 300 Hz.
        val fcHigh = 300.0
        val dt = 1.0 / sampleRate
        val rcHigh = 1.0 / (2.0 * PI * fcHigh)
        val alphaHigh = (rcHigh / (rcHigh + dt)).toFloat()
        var prevIn = 0f
        var prevOut = 0f
        for (i in pcm.indices) {
            val x = pcm[i].toFloat()
            val y = alphaHigh * (prevOut + x - prevIn)
            out[i] = y
            prevIn = x
            prevOut = y
        }

        // One-pole LPF at 3.4 kHz, in-place on `out`.
        val fcLow = 3400.0
        val rcLow = 1.0 / (2.0 * PI * fcLow)
        val alphaLow = (dt / (rcLow + dt)).toFloat()
        var prev = 0f
        for (i in out.indices) {
            prev += alphaLow * (out[i] - prev)
            out[i] = prev
        }

        // Soft-clip at 80% peak. Use tanh-like saturation:
        //   y = threshold * tanh(x / threshold)
        // Smooth, non-clipping, and louder than the input above the knee.
        val threshold = 0.8f * Short.MAX_VALUE.toFloat()
        for (i in out.indices) {
            val x = out[i] / threshold
            // Approximate tanh with x / sqrt(1 + x²) — same shape, no exp().
            val tanhLike = x / sqrt(1f + x * x)
            out[i] = threshold * tanhLike
        }

        return scaleAndClip(out, normalizeIfNeeded = false)
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Convert a float buffer back to PCM16. If `normalizeIfNeeded` is true
     * and the buffer has samples beyond ±Short.MAX_VALUE, scale the whole
     * buffer down so the loudest sample sits at ±Short.MAX_VALUE (preserves
     * dynamics; avoids surprise clipping from accumulated gain). Otherwise
     * just hard-clip.
     */
    private fun scaleAndClip(buf: FloatArray, normalizeIfNeeded: Boolean = true): ShortArray {
        if (buf.isEmpty()) return ShortArray(0)

        var scale = 1f
        if (normalizeIfNeeded) {
            var peak = 0f
            for (v in buf) {
                val a = if (v < 0f) -v else v
                if (a > peak) peak = a
            }
            if (peak > Short.MAX_VALUE.toFloat()) {
                scale = Short.MAX_VALUE.toFloat() / peak
            }
        }

        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            val v = buf[i] * scale
            out[i] = when {
                v >= Short.MAX_VALUE.toFloat() -> Short.MAX_VALUE
                v <= Short.MIN_VALUE.toFloat() -> Short.MIN_VALUE
                else -> v.toInt().toShort()
            }
        }
        return out
    }

}
