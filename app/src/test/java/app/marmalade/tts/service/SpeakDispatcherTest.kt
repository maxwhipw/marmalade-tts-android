package app.marmalade.tts.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SpeakDispatcher.prepare] — the pure validation/clamp step
 * shared by the share-sheet trampoline and the Quick Settings tile.
 *
 * The dispatch() path also calls ContextCompat.startForegroundService,
 * which needs a real Android Context; that's covered by manual testing
 * on device (see STUBS.md). Here we only exercise the input-validation
 * rules, where bugs would be subtle and hard to spot at runtime
 * (silently dropped speech, off-by-one truncation, etc.).
 */
class SpeakDispatcherTest {

    @Test
    fun `null input returns null prepared`() {
        assertNull(SpeakDispatcher.prepare(null))
    }

    @Test
    fun `empty string returns null prepared`() {
        assertNull(SpeakDispatcher.prepare(""))
    }

    @Test
    fun `whitespace-only string returns null prepared`() {
        assertNull(SpeakDispatcher.prepare("   \n\t  "))
    }

    @Test
    fun `normal text is trimmed and not clamped`() {
        val result = SpeakDispatcher.prepare("  hello world  ")
        assertNotNull(result)
        assertEquals("hello world", result!!.text)
        assertEquals(false, result.clamped)
    }

    @Test
    fun `single character is accepted`() {
        val result = SpeakDispatcher.prepare("a")
        assertNotNull(result)
        assertEquals("a", result!!.text)
        assertEquals(false, result.clamped)
    }

    @Test
    fun `text exactly at MAX_TEXT_LENGTH is not clamped`() {
        val input = "a".repeat(SpeakDispatcher.MAX_TEXT_LENGTH)
        val result = SpeakDispatcher.prepare(input)
        assertNotNull(result)
        assertEquals(SpeakDispatcher.MAX_TEXT_LENGTH, result!!.text.length)
        assertEquals(false, result.clamped)
    }

    @Test
    fun `text longer than MAX_TEXT_LENGTH is clamped`() {
        val input = "b".repeat(SpeakDispatcher.MAX_TEXT_LENGTH + 500)
        val result = SpeakDispatcher.prepare(input)
        assertNotNull(result)
        assertEquals(SpeakDispatcher.MAX_TEXT_LENGTH, result!!.text.length)
        assertTrue(result.clamped)
        // Confirm we kept the head, not some random window.
        assertTrue(result.text.all { it == 'b' })
    }

    @Test
    fun `leading and trailing whitespace counts toward trim, not clamp`() {
        // Whitespace surrounding actual content should be removed before
        // the length check — otherwise users pasting "  ...  " around
        // long content could trip the clamp unnecessarily.
        val padding = "   "
        val body = "c".repeat(SpeakDispatcher.MAX_TEXT_LENGTH)
        val result = SpeakDispatcher.prepare(padding + body + padding)
        assertNotNull(result)
        assertEquals(SpeakDispatcher.MAX_TEXT_LENGTH, result!!.text.length)
        assertEquals(false, result.clamped)
    }
}
