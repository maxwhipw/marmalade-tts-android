package app.marmalade.tts.playback

import app.marmalade.tts.playback.RenderPlanner.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlannerTest {

    private fun item(
        segment: Int,
        complete: Boolean = false,
        cloud: Boolean = false,
        before: Double = 0.0,
        own: Double = 0.0,
    ) = Item(sessionId = 1, segment = segment, complete = complete, cloud = cloud,
        segmentsAhead = segment, aheadMsBefore = before, ownAheadMs = own)

    @Test
    fun `the listener's segment is always rendered first`() {
        assertEquals(0, RenderPlanner.next(listOf(item(0), item(1)))!!.segment)
    }

    @Test
    fun `on-device prepares ahead up to about a minute`() {
        val items = listOf(item(0, complete = true, own = 20_000.0), item(1, before = 20_000.0), item(2, before = 50_000.0))
        assertEquals(1, RenderPlanner.next(items)!!.segment)
        val full = listOf(item(0, complete = true, own = 61_000.0), item(1, before = 61_000.0))
        assertNull(RenderPlanner.next(full))
    }

    @Test
    fun `cloud prepares only this segment and the next`() {
        val items = listOf(
            item(0, complete = true, cloud = true, own = 5_000.0),
            item(1, complete = true, cloud = true, before = 5_000.0, own = 5_000.0),
            item(2, cloud = true, before = 10_000.0),
        )
        assertNull(RenderPlanner.next(items))
        assertEquals(1, RenderPlanner.next(listOf(item(0, complete = true, cloud = true), item(1, cloud = true)))!!.segment)
    }

    @Test
    fun `the in-flight render stops taking chunks at the budget`() {
        assertTrue(RenderPlanner.mayContinue(item(0, own = 59_000.0)))
        assertFalse(RenderPlanner.mayContinue(item(0, own = 60_000.0)))
        assertFalse(RenderPlanner.mayContinue(item(3, before = 55_000.0, own = 6_000.0)))
        // Cloud has no ms cap inside its depth — today's behaviour.
        assertTrue(RenderPlanner.mayContinue(item(1, cloud = true, before = 90_000.0)))
        assertFalse(RenderPlanner.mayContinue(item(2, cloud = true)))
    }
}
