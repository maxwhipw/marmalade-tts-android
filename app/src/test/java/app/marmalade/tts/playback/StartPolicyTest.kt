package app.marmalade.tts.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPolicyTest {

    private fun input(
        banked: Double = 3_000.0,
        chunks: Int = 1,
        complete: Boolean = false,
        rtf: Double? = 0.5,
        speed: Float = 1f,
        remaining: Double = 12_000.0,
    ) = StartPolicy.Input(banked, chunks, complete, rtf, speed, remaining)

    @Test
    fun `nothing banked holds unless there is nothing left to render`() {
        assertFalse(StartPolicy.shouldStart(input(banked = 0.0, chunks = 0)))
        assertFalse(StartPolicy.shouldStart(input(banked = 0.0, chunks = 0, complete = true)))
    }

    @Test
    fun `faster than realtime starts after chunk 0`() {
        assertTrue(StartPolicy.shouldStart(input(rtf = 0.55)))
        // 1.5x of 0.55 = 0.83, still under the 0.9 margin.
        assertTrue(StartPolicy.shouldStart(input(rtf = 0.55, speed = 1.5f)))
    }

    @Test
    fun `a deficit holds until the banked audio covers it`() {
        // 2.0x of 0.6 = 1.2 played RTF, deficit 0.3; 4 chunks of 3 s left
        // (12 s) → stall 3.6 s: chunk 0 alone (3 s) does not cover it.
        assertFalse(StartPolicy.shouldStart(input(rtf = 0.6, speed = 2f)))
        // …but the cap starts it at 2 chunks.
        assertTrue(StartPolicy.shouldStart(input(banked = 6_000.0, chunks = 2, rtf = 0.6, speed = 2f)))
    }

    @Test
    fun `a small deficit rounds down like PrerollGate`() {
        // Throttled 8a at 1x: RTF 0.91 over a 5-chunk paragraph → shortfall
        // 4 × 0.01 = 0.04 chunk-lengths → K = 1.
        assertTrue(StartPolicy.shouldStart(input(rtf = 0.91, remaining = 12_000.0)))
    }

    @Test
    fun `audio already banked counts - a prepared block needs no hold`() {
        assertTrue(
            StartPolicy.shouldStart(input(banked = 20_000.0, chunks = 5, complete = false, rtf = 1.2, remaining = 2_000.0)),
        )
        assertTrue(StartPolicy.shouldStart(input(complete = true, rtf = 3.0)))
    }

    @Test
    fun `no measurement yet holds`() {
        assertFalse(StartPolicy.shouldStart(input(rtf = null)))
    }

    @Test
    fun `the EWMA follows a step within about three chunks`() {
        val e = RtfEstimator()
        assertNull(e.value)
        e.add(500.0, 1_000.0)
        assertEquals(0.5, e.value!!, 1e-9)
        repeat(3) { e.add(1_200.0, 1_000.0) }
        // 0.5 → 1.2: after three samples within 12.5 % of the step.
        assertEquals(1.2 - 0.7 * 0.125, e.value!!, 1e-9)
    }
}
