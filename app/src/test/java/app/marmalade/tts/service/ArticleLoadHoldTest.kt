package app.marmalade.tts.service

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleLoadHoldTest {

    private val a = "https://example.com/a"
    private val b = "https://example.com/b"

    @Test
    fun `a share starts the host and holds until its load ends`() = runTest {
        var starts = 0
        val hold = ArticleLoadHold({ starts++; true }, backgroundScope)
        assertTrue(hold.begin(a))
        assertEquals(1, starts)
        assertEquals(a, hold.loading.value)
        hold.end(b) // another link's load: not ours
        assertEquals(a, hold.loading.value)
        hold.end(a)
        assertNull(hold.loading.value)
    }

    @Test
    fun `a refused host holds nothing`() = runTest {
        val hold = ArticleLoadHold({ false }, backgroundScope)
        assertFalse(hold.begin(a))
        assertNull(hold.loading.value)
    }

    @Test
    fun `a newer share takes the hold, and an old load's end leaves it`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        hold.begin(a)
        hold.begin(b)
        hold.end(a)
        assertEquals(b, hold.loading.value)
    }

    /** #94: "Loading article…" swiped away — the hold ends and the reader must not autoplay. */
    @Test
    fun `a cancelled load ends the hold and is reported once`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        hold.begin(a)
        hold.cancel(a)
        assertNull(hold.loading.value)
        assertFalse(hold.consumeCancelled(b))
        assertTrue(hold.consumeCancelled(a))
        assertFalse(hold.consumeCancelled(a))
    }

    @Test
    fun `sharing a cancelled link again reads it`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        hold.begin(a)
        hold.cancel(a)
        hold.begin(a)
        assertEquals(a, hold.loading.value)
        assertFalse(hold.consumeCancelled(a))
    }

    /** Onboarding still open, a reader that never opened: no foreground service stays stuck. */
    @Test
    fun `a hold nobody ends times out`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope, timeoutMs = 1_000)
        hold.begin(a)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(a, hold.loading.value)
        advanceTimeBy(2)
        runCurrent()
        assertNull(hold.loading.value)
    }
}
