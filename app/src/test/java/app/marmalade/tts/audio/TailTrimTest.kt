package app.marmalade.tts.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for [TailTrim] on synthetic PCM. The contract that matters for
 * issue #8: a sample at or above the silence level is NEVER removed, no
 * matter how quiet, and the kept tail is exactly [TailTrim.TAIL_KEEP_SAMPLES].
 */
class TailTrimTest {

    private val keep = TailTrim.TAIL_KEEP_SAMPLES   // 1800

    /** Waveform of [speech] samples at [level] followed by [silence] zeros. */
    private fun render(speech: Int, level: Float, silence: Int) =
        FloatArray(speech + silence) { if (it < speech) level else 0.0f }

    @Test
    fun loudEndingKeepsExactlyTheTailMargin() {
        val wav = render(speech = 10_000, level = 0.5f, silence = 6000)
        val out = TailTrim.trimTail(wav)
        assertEquals(10_000 + keep, out.size)
        assertEquals(0.5f, out[9_999], 1e-6f)
        assertEquals(0.0f, out[10_000], 1e-6f)
    }

    @Test
    fun quietFricativeDecaySurvives() {
        // A final /s/ decaying at 300–900 int16 (≈0.009–0.027 FS) sits well
        // under the old blind chop's reach but above the silence level, so
        // every one of its samples must survive.
        val fricative = 900
        val wav = FloatArray(10_000 + fricative + 6000) { i ->
            when {
                i < 10_000 -> 0.5f
                i < 10_000 + fricative -> {
                    // linear decay 900 → 300 in int16 terms
                    val amp = 900 - (600 * (i - 10_000)) / fricative
                    amp / 32767.0f
                }
                else -> 0.0f
            }
        }
        val out = TailTrim.trimTail(wav)
        assertEquals(10_000 + fricative + keep, out.size)
        // Last fricative sample intact.
        assertEquals(wav[10_000 + fricative - 1], out[10_000 + fricative - 1], 1e-9f)
    }

    @Test
    fun blindChopWouldHaveCutSpeechHereButTailTrimDoesNot() {
        // The 2.5–3.0x case measured on 2026-09-12: only ~180 ms (4300
        // samples) of rendered tail, so the old 5000-sample chop reached
        // 700 samples into speech. TailTrim must keep all of it.
        val wav = render(speech = 20_000, level = 0.4f, silence = 4300)
        val out = TailTrim.trimTail(wav)
        assertEquals(20_000 + keep, out.size)
        assertEquals(0.4f, out[19_999], 1e-6f)
    }

    @Test
    fun allSilenceIsPassedThroughUntouched() {
        val wav = FloatArray(8000)
        assertSame(wav, TailTrim.trimTail(wav))
    }

    @Test
    fun tailShorterThanKeepIsUntouched() {
        val wav = render(speech = 5000, level = 0.3f, silence = keep - 1)
        assertSame(wav, TailTrim.trimTail(wav))
    }

    @Test
    fun wholeChunkShorterThanKeepIsUntouched() {
        val wav = render(speech = 100, level = 0.3f, silence = 200)
        assertArrayEquals(wav, TailTrim.trimTail(wav), 1e-9f)
        assertEquals(300, TailTrim.trimTail(wav).size)
    }

    @Test
    fun emptyInput() {
        assertEquals(0, TailTrim.trimTail(FloatArray(0)).size)
    }

    @Test
    fun internalSilenceIsNeverTouched() {
        // speech | 5000 silent | speech | 6000 silent — only the trailing
        // run may shrink; the internal pause stays exactly as rendered.
        val wav = FloatArray(2000 + 5000 + 2000 + 6000) { i ->
            when {
                i < 2000 -> 0.5f
                i < 7000 -> 0.0f
                i < 9000 -> 0.5f
                else -> 0.0f
            }
        }
        val out = TailTrim.trimTail(wav)
        assertEquals(9000 + keep, out.size)
        for (i in 0 until 9000) assertEquals(wav[i], out[i], 1e-9f)
    }

    /**
     * The handoff for unit A: with a 75 ms kept tail, [SilenceCompressor]'s
     * 2400-sample gate means it does nothing to a normally trimmed chunk.
     * It stays in the pipeline purely as a safety net for a pathologically
     * long model tail.
     */
    @Test
    fun silenceCompressorIsANoOpOnANormallyTrimmedChunk() {
        val wav = render(speech = 12_000, level = 0.5f, silence = 8000)
        val trimmed = TailTrim.trimTail(wav)
        val pcm = ShortArray(trimmed.size) { (trimmed[it] * 32767.0f).toInt().toShort() }
        assertSame(pcm, SilenceCompressor.compress(pcm))
    }
}
