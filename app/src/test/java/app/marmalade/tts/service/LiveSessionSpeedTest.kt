package app.marmalade.tts.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader ↔ service seam for live session-speed changes: requests on a
 * time-stretching engine follow a change; one with the speed baked in (cloud,
 * batched emoji path) makes the change report false so the reader re-enqueues.
 */
class LiveSessionSpeedTest {

    private val speeds = LiveSessionSpeed()

    @Test
    fun `before the reader sets anything the request's own speed applies`() {
        assertEquals(1.25f, speeds.resolve(1L, fallback = 1.25f, liveCapable = true), 0f)
        assertEquals(1.25f, speeds.current(fallback = 1.25f), 0f)
    }

    @Test
    fun `live requests follow a change, and playback reads it`() {
        speeds.set(2.0f)
        assertEquals(2.0f, speeds.resolve(1L, fallback = 2.0f, liveCapable = true), 0f)
        assertEquals(2.0f, speeds.resolve(2L, fallback = 2.0f, liveCapable = true), 0f)

        assertTrue(speeds.change(listOf(1L, 2L, 3L), 1.0f))
        assertEquals(1.0f, speeds.current(fallback = 2.0f), 0f)
    }

    @Test
    fun `a queued request not yet resolved picks up the changed speed`() {
        speeds.set(2.0f)
        // Request 3 was sent at 2× but the service hasn't reached it yet.
        assertTrue(speeds.change(listOf(3L), 1.0f))

        assertEquals(1.0f, speeds.resolve(3L, fallback = 2.0f, liveCapable = false), 0f)
    }

    @Test
    fun `a request with the speed baked in makes the change fall back`() {
        speeds.set(2.0f)
        speeds.resolve(1L, fallback = 2.0f, liveCapable = true)
        speeds.resolve(2L, fallback = 2.0f, liveCapable = false) // cloud

        assertFalse(speeds.change(listOf(1L, 2L), 1.0f))
        // Requests the reader no longer holds don't count.
        assertTrue(speeds.change(listOf(1L, 3L), 1.5f))
    }

    @Test
    fun `a released request no longer blocks a live change`() {
        speeds.resolve(2L, fallback = 1.0f, liveCapable = false)
        speeds.release(2L)

        assertTrue(speeds.change(listOf(2L), 1.5f))
    }

    @Test
    fun `a new article's speed replaces the last one's`() {
        speeds.set(2.0f)
        speeds.set(0.9f)

        assertEquals(0.9f, speeds.resolve(7L, fallback = 2.0f, liveCapable = true), 0f)
    }
}
