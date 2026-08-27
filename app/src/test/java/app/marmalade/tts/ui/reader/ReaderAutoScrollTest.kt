package app.marmalade.tts.ui.reader

import app.marmalade.tts.ui.reader.ReaderAutoScroll.USER_SCROLL_GRACE_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "follow the highlight, but never fight the reader" rule. */
class ReaderAutoScrollTest {

    @Test
    fun `follows the highlight when the user has never scrolled`() {
        assertTrue(
            ReaderAutoScroll.shouldAutoScroll(
                nowMillis = 10_000,
                lastUserScrollMillis = 0,
                userIsScrolling = false,
            ),
        )
    }

    @Test
    fun `never scrolls out from under a drag in progress`() {
        assertFalse(
            ReaderAutoScroll.shouldAutoScroll(
                nowMillis = 10_000,
                lastUserScrollMillis = 0,
                userIsScrolling = true,
            ),
        )
    }

    @Test
    fun `stays put just after the user scrolled`() {
        assertFalse(
            ReaderAutoScroll.shouldAutoScroll(
                nowMillis = 10_000,
                lastUserScrollMillis = 10_000 - (USER_SCROLL_GRACE_MS - 1),
                userIsScrolling = false,
            ),
        )
    }

    @Test
    fun `resumes following once the grace period has passed`() {
        assertTrue(
            ReaderAutoScroll.shouldAutoScroll(
                nowMillis = 10_000,
                lastUserScrollMillis = 10_000 - USER_SCROLL_GRACE_MS,
                userIsScrolling = false,
            ),
        )
    }
}
