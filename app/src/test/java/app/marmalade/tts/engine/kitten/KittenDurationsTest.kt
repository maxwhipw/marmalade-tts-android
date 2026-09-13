package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [KittenDirectEngine.extractDurations].
 *
 * The contract under test (issue #8): float durations must ROUND. The
 * vocoder renders `600 × round(dur)` samples per token, so truncating a
 * 3.7-frame token lost 600 samples from the predicted total, broke
 * [KittenTrim]'s `wav.size == 600 × Σdur` check, and silently dropped the
 * whole render onto the fallback trim.
 */
class KittenDurationsTest {

    @Test
    fun floatDurationsAreRoundedNotTruncated() {
        val floats = floatArrayOf(3.7f, 2.4f, 0.5f, 1.5f, 9.99f)
        assertArrayEquals(
            longArrayOf(4, 2, 1, 2, 10),
            KittenDirectEngine.extractDurations(floats),
        )
    }

    @Test
    fun roundedTotalMatchesTheWaveformTheVocoderRenders() {
        // What the model predicted per token, and what the vocoder actually
        // emitted for it: 600 × Σround. Truncation would have predicted
        // 9 frames (5400 samples) against a 12-frame (7200-sample) render.
        val floats = floatArrayOf(3.7f, 2.6f, 4.4f, 1.4f)
        val dur = KittenDirectEngine.extractDurations(floats)!!
        assertArrayEquals(longArrayOf(4, 3, 4, 1), dur)
        val rendered = 12 * KittenTrim.FRAME_SAMPLES
        assertArrayEquals(
            longArrayOf(rendered.toLong()),
            longArrayOf(dur.sum() * KittenTrim.FRAME_SAMPLES),
        )
    }

    @Test
    fun int64AndInt32ExportsPassThrough() {
        assertArrayEquals(longArrayOf(1, 2, 3), KittenDirectEngine.extractDurations(longArrayOf(1, 2, 3)))
        assertArrayEquals(longArrayOf(1, 2, 3), KittenDirectEngine.extractDurations(intArrayOf(1, 2, 3)))
    }

    @Test
    fun batchedOutputTakesTheFirstRow() {
        val batched: Array<FloatArray> = arrayOf(floatArrayOf(2.6f, 1.2f))
        assertArrayEquals(longArrayOf(3, 1), KittenDirectEngine.extractDurations(batched))
    }

    @Test
    fun unsupportedShapesFallBack() {
        assertNull(KittenDirectEngine.extractDurations(null))
        assertNull(KittenDirectEngine.extractDurations("not a tensor"))
        assertNull(KittenDirectEngine.extractDurations(doubleArrayOf(1.0, 2.0)))
    }
}
