package app.marmalade.tts.perf

import app.marmalade.tts.data.db.VoiceAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers the pure speed-up warning trigger (SpeedPerfWarning) and the rolling
// RTF math (RollingRtf). The resolution order — measured RTF beats predicted
// RTF beats the static speed fallback — is pinned here; the ViewModel tests
// only verify the wiring pulls the right inputs.
// -----------------------------------------------------------------------------

class SpeedPerfWarningTest {

    // -- Resolution order -----------------------------------------------------

    @Test
    fun `measured RTF is preferred over predicted`() {
        // Measured says "too slow" (0.9 × 1.0 > 0.8); predicted would say
        // "fine" (0.1). Measured wins → warn.
        assertTrue(
            SpeedPerfWarning.shouldWarn(
                measuredRtf = 0.9,
                predictedRtf = 0.1,
                effectiveSpeed = 1.0f,
            ),
        )
        // And the reverse: measured "fine" overrides predicted "too slow".
        assertFalse(
            SpeedPerfWarning.shouldWarn(
                measuredRtf = 0.1,
                predictedRtf = 0.9,
                effectiveSpeed = 1.0f,
            ),
        )
    }

    @Test
    fun `predicted RTF is used when there is no measured value`() {
        assertTrue(
            SpeedPerfWarning.shouldWarn(
                measuredRtf = null,
                predictedRtf = 0.9,
                effectiveSpeed = 1.0f,
            ),
        )
        assertFalse(
            SpeedPerfWarning.shouldWarn(
                measuredRtf = null,
                predictedRtf = 0.3,
                effectiveSpeed = 1.0f,
            ),
        )
    }

    @Test
    fun `RTF path multiplies by effective speed against the 0_8 threshold`() {
        // 0.5 RTF: fine at 1.5× (0.75), warned at 1.7× (0.85).
        assertFalse(SpeedPerfWarning.shouldWarn(null, 0.5, 1.5f))
        assertTrue(SpeedPerfWarning.shouldWarn(null, 0.5, 1.7f))
    }

    @Test
    fun `exactly at the threshold does not warn`() {
        // 0.8 × 1.0 == 0.8, and the check is strictly greater-than.
        assertFalse(SpeedPerfWarning.shouldWarn(null, 0.8, 1.0f))
    }

    @Test
    fun `falls back to the static speed rule when there is no RTF signal`() {
        // With neither measured nor predicted RTF, the old rule applies:
        // effective speed past VoiceAlias.SPEED_PERF_WARNING_THRESHOLD (1.35).
        assertFalse(SpeedPerfWarning.shouldWarn(null, null, 1.3f))
        assertTrue(SpeedPerfWarning.shouldWarn(null, null, 1.4f))
        // Exactly at the static threshold does not warn either.
        assertFalse(
            SpeedPerfWarning.shouldWarn(
                null,
                null,
                VoiceAlias.SPEED_PERF_WARNING_THRESHOLD,
            ),
        )
    }

    // -- Rolling RTF math -----------------------------------------------------

    @Test
    fun `first sample becomes the average outright`() {
        assertEquals(0.5, RollingRtf.update(previous = null, sample = 0.5), 1e-9)
    }

    @Test
    fun `subsequent samples fold in at ALPHA weight`() {
        // previous 0.5, sample 1.0, alpha 0.3 → 0.5 + 0.3 × 0.5 = 0.65.
        assertEquals(0.65, RollingRtf.update(previous = 0.5, sample = 1.0), 1e-9)
    }

    @Test
    fun `a run of identical samples converges to that value`() {
        var avg: Double? = null
        repeat(50) { avg = RollingRtf.update(avg, 0.42) }
        assertEquals(0.42, avg!!, 1e-6)
    }

    @Test
    fun `a single outlier moves the average only partway`() {
        // Warm at 0.4, one spike to 1.4: EMA lands at 0.4 + 0.3 × 1.0 = 0.7,
        // not the spike itself — the whole point of smoothing.
        val after = RollingRtf.update(previous = 0.4, sample = 1.4)
        assertEquals(0.7, after, 1e-9)
    }
}
