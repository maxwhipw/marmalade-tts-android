package app.marmalade.tts.ui

import app.marmalade.tts.service.SpeakDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [ReaderRequest.of] — the gate on reader extras arriving
 * at the exported MainActivity, which any app can send.
 */
class ReaderRequestTest {

    @Test
    fun `an http or https url makes a request`() {
        assertEquals(
            ReaderRequest("https://example.com/a", "Title https://example.com/a"),
            ReaderRequest.of("https://example.com/a", "Title https://example.com/a"),
        )
        assertEquals(
            ReaderRequest("HTTP://example.com/b", ""),
            ReaderRequest.of("HTTP://example.com/b", null),
        )
    }

    @Test
    fun `no url is no request`() {
        assertNull(ReaderRequest.of(null, "some text"))
    }

    @Test
    fun `non web schemes are refused`() {
        assertNull(ReaderRequest.of("file:///sdcard/Download/page.html", ""))
        assertNull(ReaderRequest.of("content://app.provider/page", ""))
        assertNull(ReaderRequest.of("javascript:alert(1)", ""))
        assertNull(ReaderRequest.of("example.com/no-scheme", ""))
    }

    @Test
    fun `shared text is capped like every other speak entry point`() {
        val long = "a".repeat(SpeakDispatcher.MAX_TEXT_LENGTH + 500)

        val request = ReaderRequest.of("https://example.com/a", long)!!

        assertEquals(SpeakDispatcher.MAX_TEXT_LENGTH, request.sharedText.length)
    }
}
