package app.marmalade.tts.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class BugReportUrlTest {

    private val url = BugReportUrl.build(
        versionName = "1.0.0-beta.1",
        flavor = "fdroid",
        androidVersion = "15",
        deviceModel = "Google Pixel 8a",
    )

    @Test
    fun `targets the new-issue endpoint with a body param`() {
        assertTrue(url.startsWith("https://github.com/maxwhipw/marmalade-tts-android/issues/new?body="))
    }

    @Test
    fun `is fully percent-encoded — no raw spaces, newlines, or form plus`() {
        val query = url.substringAfter("?body=")
        assertFalse(query.contains(' '))
        assertFalse(query.contains('\n'))
        assertFalse(query.contains('+'))
    }

    @Test
    fun `body round-trips with prompts and environment block`() {
        val body = URLDecoder.decode(url.substringAfter("?body="), "UTF-8")
        assertTrue(body.contains("**What happened?**"))
        assertTrue(body.contains("sounds wrong"))
        assertTrue(body.contains("Marmalade TTS 1.0.0-beta.1 (fdroid) · Android 15 · Google Pixel 8a"))
    }
}
