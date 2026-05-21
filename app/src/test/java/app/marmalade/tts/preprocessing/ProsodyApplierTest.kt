package app.marmalade.tts.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests for ProsodyApplier — the per-Emotion pitch/rate/volume DSP curve.
 *
 * Tested behavior (all property-based, no coefficient-pinning):
 *  - Neutral is exact identity (returns the input reference).
 *  - Happy raises pitch (zero-crossing density goes up).
 *  - Sad lowers volume (RMS drops by >=1 dB vs neutral).
 *  - Angry raises volume (RMS climbs by >=1 dB vs neutral).
 *  - Nervous adds an amplitude wobble (per-window RMS std dev jumps).
 *  - Every emotion produces a sensible output length (±20 % of input).
 *  - Empty input is a no-op for every emotion.
 *
 * Documented expected length ratios for a 24-kHz, 4800-sample input
 * (apply does pitch resample by pitchRatio, then rate resample by
 *  1/rateRatio when rateRatio != 1; net length ≈ inLen * rateRatio /
 *  pitchRatio, with pitchRatio = 2^(semitones/12)):
 *
 *   Emotion     | rateRatio | semitones | pitchRatio | netLen / inLen
 *   ------------|-----------|-----------|-----------|----------------
 *   Amused      |   1.05    |    +2     |   1.122   |   0.936
 *   Sad         |   0.92    |    -2     |   0.891   |   1.033
 *   Angry       |   1.03    |    +1     |   1.060   |   0.972
 *   Loving      |   0.97    |    -1     |   0.944   |   1.028
 *   Cool        |   1.00    |    -1     |   0.944   |   1.060
 *   Sarcastic   |   0.98    |    +1     |   1.060   |   0.925
 *   Happy       |   1.03    |    +1     |   1.060   |   0.972
 *   Calm        |   0.95    |     0     |   1.000   |   0.950
 *   Surprised   |   1.08    |    +3     |   1.189   |   0.908
 *   Nervous     |   1.00    |   +0.5    |   1.029   |   0.972
 *   Thoughtful  |   0.97    |     0     |   1.000   |   0.970
 *   Neutral     |   identity (returns input reference)
 *
 *  All ratios fall comfortably inside ±20 % of 1.0; the assertion uses
 *  that tolerance to absorb integer rounding in resampleLinear.
 *
 * Pure-JVM unit tests. No Robolectric, no Android context.
 */
class ProsodyApplierTest {

    private val sampleRate = 24_000

    // ── identity ─────────────────────────────────────────────────────

    @Test
    fun `Neutral returns the input untouched`() {
        val input = sineWave(440.0, sampleRate, durationMs = 100, amplitude = 8_000)
        val out = ProsodyApplier.apply(input, sampleRate, Emotion.Neutral)
        // Spec budgets ±2 LSB to absorb any internal float pipeline. The
        // production code short-circuits and returns the same reference,
        // so equality is exact — but we honor the spec's tolerance.
        assertEquals("Neutral must preserve length", input.size, out.size)
        for (i in input.indices) {
            val delta = abs(out[i].toInt() - input[i].toInt())
            assertTrue("Neutral altered sample $i by $delta", delta <= 2)
        }
    }

    // ── pitch (Happy raises pitch) ──────────────────────────────────

    @Test
    fun `Happy raises pitch (higher zero-crossing density than Neutral)`() {
        // 200 Hz sine, 1 s at 24 kHz, half-scale. NEUTRAL returns the
        // input untouched (24000 samples, ~400 zero crossings). HAPPY
        // does a net ~+3 % pitch shift (pitchRatio=1.060, rateRatio=1.03
        // → second resample by 1/1.03 nets pitch ≈ 1.029), so the output
        // is slightly shorter and contains slightly more cycles. We compare
        // ZC density (per sample) because raw ZC count is invariant under
        // pure resampling (cycles are preserved, only how many samples
        // represent each cycle changes).
        val input = sineWave(200.0, sampleRate, durationMs = 1000, amplitude = 16_384)
        val neutralOut = ProsodyApplier.apply(input, sampleRate, Emotion.Neutral)
        val happyOut = ProsodyApplier.apply(input, sampleRate, Emotion.Happy)

        val neutralDensity = zeroCrossingCount(neutralOut).toDouble() / neutralOut.size
        val happyDensity = zeroCrossingCount(happyOut).toDouble() / happyOut.size

        assertTrue("Happy density ($happyDensity) should exceed " +
                   "Neutral density ($neutralDensity)",
                   happyDensity > neutralDensity)
    }

    // ── volume (Sad quieter, Angry louder) ──────────────────────────

    @Test
    fun `Sad lowers volume vs Neutral`() {
        val input = sineWave(440.0, sampleRate, durationMs = 200, amplitude = 16_384)
        val neutralOut = ProsodyApplier.apply(input, sampleRate, Emotion.Neutral)
        val sadOut = ProsodyApplier.apply(input, sampleRate, Emotion.Sad)

        val neutralRms = rms(neutralOut)
        val sadRms = rms(sadOut)
        val ratio = sadRms / neutralRms

        // Spec: at least 1 dB quieter (ratio < 0.89). SAD's gainDb is -3,
        // so the linear ratio is 10^(-3/20) ≈ 0.708 — well under the
        // threshold.
        assertTrue("Sad should be quieter than Neutral " +
                   "(ratio=$ratio, expected < 0.89)",
                   ratio < 0.89)
    }

    @Test
    fun `Angry raises volume vs Neutral`() {
        val input = sineWave(440.0, sampleRate, durationMs = 200, amplitude = 16_384)
        val neutralOut = ProsodyApplier.apply(input, sampleRate, Emotion.Neutral)
        val angryOut = ProsodyApplier.apply(input, sampleRate, Emotion.Angry)

        val neutralRms = rms(neutralOut)
        val angryRms = rms(angryOut)
        val ratio = angryRms / neutralRms

        // Spec: at least 1 dB louder (ratio > 1.12). ANGRY's gainDb is +3
        // (linear 1.413) plus distortion 0.25 (soft-saturator adds
        // harmonics → more RMS). Clipping at Short.MAX_VALUE caps RMS
        // gain, but the louder signal still has higher RMS.
        assertTrue("Angry should be louder than Neutral " +
                   "(ratio=$ratio, expected > 1.12)",
                   ratio > 1.12)
    }

    // ── tremolo (Nervous) ───────────────────────────────────────────

    @Test
    fun `Nervous adds an amplitude wobble (windowed-RMS std dev jumps)`() {
        // Constant-amplitude 1 kHz sine — many cycles per analysis window
        // so per-window RMS is stable absent any modulation.
        val input = sineWave(1_000.0, sampleRate, durationMs = 1000, amplitude = 16_384)
        val neutralOut = ProsodyApplier.apply(input, sampleRate, Emotion.Neutral)
        val nervousOut = ProsodyApplier.apply(input, sampleRate, Emotion.Nervous)

        // 10 ms windows: ten full cycles of a 1 kHz tone → very stable
        // per-window RMS. (50-sample windows as the spec suggests give
        // only ~2 cycles, which is too few for a clean RMS — we observe
        // the same wobble with a larger window and lower noise floor.)
        val windowSize = 240   // 10 ms at 24 kHz
        val neutralStd = stdDevOfWindowedRms(neutralOut, windowSize)
        val nervousStd = stdDevOfWindowedRms(nervousOut, windowSize)

        // Tremolo depth ~26 % at 5 Hz: 1 s of output contains ~5 cycles
        // of modulation. Expected per-window-RMS std dev for a
        // 11585-amplitude sine modulated ±26 %: ≈ 11585 * 0.259 / √2 ≈
        // 2120. Comfortable headroom above the 200 floor.
        assertTrue("Neutral should have flat envelope (std=$neutralStd)",
                   neutralStd < 50.0)
        assertTrue("Nervous should wobble (std=$nervousStd)",
                   nervousStd > 200.0)
        assertTrue("Nervous std ($nervousStd) should exceed " +
                   "Neutral std ($neutralStd)",
                   nervousStd > neutralStd * 4.0)
    }

    // ── length contract ─────────────────────────────────────────────

    @Test
    fun `All emotions land within plus or minus 20 percent of input length`() {
        val n = 4_800
        val input = sineWave(440.0, sampleRate, durationMs = 200, amplitude = 8_000)
        // Sanity: input is exactly 4800 samples (200 ms at 24 kHz).
        assertEquals(n, input.size)

        for (emotion in Emotion.values()) {
            val out = ProsodyApplier.apply(input, sampleRate, emotion)
            val ratio = out.size.toDouble() / n
            assertTrue(
                "$emotion produced length ${out.size} (ratio=$ratio), " +
                "expected within ±20 % of $n",
                ratio in 0.80..1.20,
            )
        }
    }

    // ── empty input ─────────────────────────────────────────────────

    @Test
    fun `Empty input returns empty output for every emotion`() {
        // The production short-circuits on isEmpty (and on Neutral). The
        // result for any emotion must be a zero-length array; never a
        // crash or a non-empty allocation.
        for (emotion in Emotion.values()) {
            val out = ProsodyApplier.apply(ShortArray(0), sampleRate, emotion)
            assertEquals("$emotion should pass empty through", 0, out.size)
        }
    }
}

// ── helpers (file-private top-level funs) ───────────────────────────

private fun sineWave(
    freqHz: Double,
    sampleRate: Int,
    durationMs: Int,
    amplitude: Int,
): ShortArray {
    val n = (sampleRate.toLong() * durationMs / 1000L).toInt()
    val out = ShortArray(n)
    val twoPiF = 2.0 * PI * freqHz
    for (i in 0 until n) {
        val t = i.toDouble() / sampleRate
        out[i] = (amplitude * sin(twoPiF * t)).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
    return out
}

private fun rms(pcm: ShortArray): Double {
    if (pcm.isEmpty()) return 0.0
    var acc = 0.0
    for (v in pcm) {
        val f = v.toDouble()
        acc += f * f
    }
    return sqrt(acc / pcm.size)
}

/**
 * Count sign changes through zero. A sample exactly at 0 is treated as
 * non-negative, so the boundary 0→-1 counts once and -1→0 counts once;
 * good enough for "does the output have more cycles than the input."
 */
private fun zeroCrossingCount(pcm: ShortArray): Int {
    if (pcm.size < 2) return 0
    var count = 0
    var prevSign = if (pcm[0] >= 0) 1 else -1
    for (i in 1 until pcm.size) {
        val sign = if (pcm[i] >= 0) 1 else -1
        if (sign != prevSign) {
            count++
            prevSign = sign
        }
    }
    return count
}

/**
 * Split [pcm] into non-overlapping windows of [windowSize] samples,
 * compute each window's RMS, and return the population standard deviation
 * of those RMS values. Used to detect envelope wobble (tremolo).
 */
private fun stdDevOfWindowedRms(pcm: ShortArray, windowSize: Int): Double {
    val nWindows = pcm.size / windowSize
    if (nWindows < 2) return 0.0
    val rmsValues = DoubleArray(nWindows)
    for (w in 0 until nWindows) {
        val start = w * windowSize
        var acc = 0.0
        for (i in 0 until windowSize) {
            val f = pcm[start + i].toDouble()
            acc += f * f
        }
        rmsValues[w] = sqrt(acc / windowSize)
    }
    var mean = 0.0
    for (v in rmsValues) mean += v
    mean /= nWindows
    var variance = 0.0
    for (v in rmsValues) {
        val d = v - mean
        variance += d * d
    }
    return sqrt(variance / nWindows)
}
