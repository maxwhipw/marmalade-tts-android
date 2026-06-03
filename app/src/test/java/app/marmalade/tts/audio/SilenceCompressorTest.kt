package app.marmalade.tts.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SilenceCompressor]. The key contract after alpha.10.X: only the
 * TRAILING silence run is compressed; internal pauses (between sentences) are
 * preserved verbatim.
 */
class SilenceCompressorTest {

    private val loud: Short = 8000
    private val silent: Short = 0

    @Test
    fun trailingSilenceIsCompressed() {
        // 100 loud samples + 1000 trailing-silent. minRun=500, scale=0.2 →
        // trailing 1000 → 200. Result length = 100 + 200 = 300.
        val pcm = ShortArray(1100) { if (it < 100) loud else silent }
        val out = SilenceCompressor.compress(pcm, scale = 0.2f, threshold = 200, minRunSamples = 500)
        assertEquals(300, out.size)
        // Loud prefix preserved.
        assertEquals(loud, out[0])
        assertEquals(loud, out[99])
    }

    @Test
    fun internalPauseIsPreserved() {
        // loud(100) + silence(1000 internal) + loud(100) + silence(50 trailing).
        // Internal 1000-silence must stay; trailing 50 < minRun so untouched.
        val pcm = ShortArray(1250) { i ->
            when {
                i < 100 -> loud
                i < 1100 -> silent     // internal pause — KEEP
                i < 1200 -> loud
                else -> silent          // 50-sample trailing — below minRun
            }
        }
        val out = SilenceCompressor.compress(pcm, scale = 0.2f, threshold = 200, minRunSamples = 500)
        // Nothing compressed: internal run preserved, trailing too short.
        assertEquals(1250, out.size)
        // The internal silence is still full length (sample 600 still silent,
        // sample 1150 still loud).
        assertEquals(silent, out[600])
        assertEquals(loud, out[1150])
    }

    @Test
    fun internalPausePreserved_trailingCompressed() {
        // loud(100) + internal silence(1000) + loud(100) + trailing silence(1000).
        // Internal kept (1000); trailing 1000 → 200. Total = 100+1000+100+200=1400.
        val pcm = ShortArray(2200) { i ->
            when {
                i < 100 -> loud
                i < 1100 -> silent  // internal — keep
                i < 1200 -> loud
                else -> silent       // trailing — compress
            }
        }
        val out = SilenceCompressor.compress(pcm, scale = 0.2f, threshold = 200, minRunSamples = 500)
        assertEquals(1400, out.size)
        assertEquals(silent, out[600])   // internal pause intact
        assertEquals(loud, out[1150])    // speech after internal pause intact
    }

    @Test
    fun scaleOneIsPassThrough() {
        val pcm = ShortArray(1000) { if (it < 100) loud else silent }
        val out = SilenceCompressor.compress(pcm, scale = 1.0f)
        assertEquals(pcm.size, out.size)
    }

    @Test
    fun allSilenceCompressesToScaledTail() {
        val pcm = ShortArray(1000) { silent }
        val out = SilenceCompressor.compress(pcm, scale = 0.2f, threshold = 200, minRunSamples = 500)
        assertEquals(200, out.size)
        assertTrue(out.all { it == silent })
    }

    @Test
    fun emptyInputIsEmpty() {
        assertEquals(0, SilenceCompressor.compress(ShortArray(0)).size)
    }
}
