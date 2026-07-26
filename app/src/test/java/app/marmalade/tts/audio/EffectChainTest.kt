package app.marmalade.tts.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the effect presets + whole-buffer [EffectChain.applyChain] adapter.
 *
 * Two kinds of assertion:
 *  - **Recipe pinning** — the preset block lists must equal the CLI's sox
 *    recipes (marmalade_tts/effects.py). This is the guard that the Android
 *    presets stay faithful to the CLI; a drift here is the bug class that
 *    motivated E-I.
 *  - **Property-based DSP** — individual blocks do the right thing in the
 *    broad strokes (gain scales, bandpass keeps the mid-band, reverb rings)
 *    rather than coefficient-pinning, so reasonable filter tweaks don't break
 *    the tests.
 *
 * Pure-JVM — no Robolectric, no Android context. (Pitch/Tempo are passthrough
 * stubs in this pass, so presets using them are length-preserving for now.)
 */
class EffectChainTest {

    private val sampleRate = 24_000

    // ── recipe pinning (must match marmalade_tts/effects.py) ─────────────────

    @Test
    fun `preset recipes match the CLI sox presets`() {
        assertEquals(
            listOf(
                EffectBlock.Reverb(80f),
                EffectBlock.Echo(0.6f, 0.6f, 120f, 0.3f),
            ),
            EffectChain.CAVE_BLOCKS,
        )
        assertEquals(
            listOf(
                EffectBlock.Bandpass(300f, 3400f),
                EffectBlock.Overdrive(5f),
                EffectBlock.Vol(1.3f),
            ),
            EffectChain.TELEPHONE_BLOCKS,
        )
        assertEquals(listOf(EffectBlock.Pitch(900f)), EffectChain.CHIPMUNK_BLOCKS)
        assertEquals(listOf(EffectBlock.Pitch(-400f), EffectBlock.Bass(6f)), EffectChain.DEEP_BLOCKS)
        assertEquals(
            listOf(EffectBlock.Reverb(90f), EffectBlock.Echo(0.8f, 0.7f, 80f, 0.25f)),
            EffectChain.STADIUM_BLOCKS,
        )
        assertEquals(
            listOf(EffectBlock.Bandpass(500f, 4000f), EffectBlock.Overdrive(30f), EffectBlock.Vol(1.1f)),
            EffectChain.MEGAPHONE_BLOCKS,
        )
        assertEquals(
            listOf(
                EffectBlock.Highpass(400f),
                EffectBlock.Lowpass(5000f),
                EffectBlock.Overdrive(25f),
                EffectBlock.Bitcrush(11f, 1f),
                EffectBlock.Compressor(-32f, 6f),
                EffectBlock.Vol(1.5f),
            ),
            EffectChain.WALKIE_TALKIE_BLOCKS,
        )
        assertEquals(
            listOf(
                EffectBlock.Bandpass(450f, 2500f),
                EffectBlock.Overdrive(18f),
                EffectBlock.Mid(1500f, 4f),
                EffectBlock.Reverb(30f),
                EffectBlock.Vol(1.2f),
            ),
            EffectChain.INTERCOM_BLOCKS,
        )
        assertEquals(
            listOf(
                EffectBlock.Reverb(45f),
                EffectBlock.Pitch(-649f),
                EffectBlock.Mid(1058f, -2f),
                EffectBlock.Overdrive(7f),
                EffectBlock.Chorus(0.25f, 2f),
                EffectBlock.Tempo(0.85f),
            ),
            EffectChain.DRAGON_BLOCKS,
        )
        assertEquals(
            listOf(EffectBlock.Lowpass(3446f), EffectBlock.Bitcrush(7f, 8f)),
            EffectChain.EIGHT_BIT_BLOCKS,
        )
    }

    // ── identity paths ───────────────────────────────────────────────────────

    @Test
    fun `NONE preset returns input element-for-element`() {
        val input = sine(1000.0, 100, Short.MAX_VALUE / 2)
        val out = EffectChain.apply(input, sampleRate, EffectPreset.NONE)
        assertTrue("NONE must return the input unchanged", out.contentEquals(input))
    }

    @Test
    fun `empty chain returns the input array unchanged`() {
        val input = sine(1000.0, 100, Short.MAX_VALUE / 2)
        assertTrue("empty chain must not copy or alter", EffectChain.applyChain(input, sampleRate, emptyList()) === input)
    }

    @Test
    fun `CAVE preset bridge equals applyChain with CAVE_BLOCKS`() {
        val input = sine(440.0, 120, Short.MAX_VALUE / 2)
        assertTrue(
            EffectChain.apply(input, sampleRate, EffectPreset.CAVE)
                .contentEquals(EffectChain.applyChain(input, sampleRate, EffectChain.CAVE_BLOCKS)),
        )
    }

    // ── block-level DSP sanity ───────────────────────────────────────────────

    @Test
    fun `Vol scales amplitude`() {
        // Quarter-scale so ×2 stays well under full-scale (no clipping).
        val input = sine(1000.0, 100, 8_192)
        val out = EffectChain.applyChain(input, sampleRate, listOf(EffectBlock.Vol(2.0f)))
        val ratio = rms(out) / rms(input)
        assertTrue("Vol(2.0) should roughly double RMS (got ${"%.2f".format(ratio)}×)", ratio in 1.8..2.2)
    }

    @Test
    fun `Bandpass keeps the mid-band and attenuates extremes`() {
        val block = listOf(EffectBlock.Bandpass(300f, 3400f))
        val skip = sampleRate / 100 // 10 ms filter warm-up
        fun atten(freq: Double): Double {
            val input = sine(freq, 150, 8_192)
            val out = EffectChain.applyChain(input, sampleRate, block)
            return 20.0 * log10(rms(out.copyOfRange(skip, out.size)) / rms(input.copyOfRange(skip, input.size)))
        }
        assertTrue("100 Hz below the band should be cut", atten(100.0) <= -6.0)
        assertTrue("8 kHz above the band should be cut", atten(8_000.0) <= -6.0)
        assertTrue("1 kHz in-band should pass within ±4 dB", atten(1_000.0) in -4.0..4.0)
    }

    @Test
    fun `Reverb rings out into originally-silent samples and extends length`() {
        val impulse = sampleRate / 1000          // 1 ms
        val silence = sampleRate / 10            // 100 ms
        val input = ShortArray(impulse + silence)
        for (i in 0 until impulse) input[i] = Short.MAX_VALUE
        val out = EffectChain.applyChain(input, sampleRate, listOf(EffectBlock.Reverb(80f)))
        assertTrue("reverb should extend the buffer with a tail", out.size > input.size)
        val tail = out.copyOfRange(out.size / 2, out.size)
        assertTrue("reverb tail should carry energy (rms=${rms(tail)})", rms(tail) > 1.0)
    }

    @Test
    fun `Overdrive changes the signal`() {
        val input = sine(440.0, 100, 16_384)
        val out = EffectChain.applyChain(input, sampleRate, listOf(EffectBlock.Overdrive(20f)))
        assertEquals("length preserved", input.size, out.size)
        assertTrue("overdrive should alter the waveform", !out.contentEquals(input))
    }

    // ── length contract ──────────────────────────────────────────────────────

    @Test
    fun `TELEPHONE preserves length and CAVE extends it`() {
        val input = sine(440.0, 200, 8_192)
        assertEquals(input.size, EffectChain.apply(input, sampleRate, EffectPreset.TELEPHONE).size)
        assertTrue(
            "CAVE should extend the buffer (reverb + echo tail)",
            EffectChain.apply(input, sampleRate, EffectPreset.CAVE).size > input.size,
        )
    }

    // ── empty input ──────────────────────────────────────────────────────────

    @Test
    fun `presets do not crash on empty input`() {
        for (preset in EffectPreset.entries) {
            val out = EffectChain.apply(ShortArray(0), sampleRate, preset)
            assertTrue("empty in → no negative length for $preset", out.isEmpty() || out.all { it == 0.toShort() })
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun sine(freqHz: Double, durationMs: Int, amplitude: Int): ShortArray {
        val n = (sampleRate.toLong() * durationMs / 1000L).toInt()
        return ShortArray(n) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var acc = 0.0
        for (v in pcm) acc += v.toDouble() * v.toDouble()
        return sqrt(acc / pcm.size)
    }
}
