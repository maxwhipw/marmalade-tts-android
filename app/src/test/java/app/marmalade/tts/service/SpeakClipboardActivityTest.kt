package app.marmalade.tts.service

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The Quick Settings tile's clipboard trampoline.
 *
 * The tile used to read the clipboard from TileService.onClick, which Android
 * 10+ always answers with nothing (only the focused app may read it). These
 * pin the replacement: the read happens once the activity has window focus,
 * the text goes to the synthesis service, and the activity gets out of the way.
 *
 * A plain Application, not the Hilt one — nothing here is injected, and the
 * real application's startup work has no business in these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpeakClipboardActivityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clipboard = app.getSystemService(ClipboardManager::class.java)

    @Test
    fun `window focus speaks the clipboard text and finishes`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "  Hello from the clipboard.  "))

        val activity = Robolectric.buildActivity(SpeakClipboardActivity::class.java).setup().get()
        activity.onWindowFocusChanged(true)

        val started = shadowOf(app).nextStartedService
        assertEquals(MarmaladeSynthService.ACTION_SPEAK, started.action)
        assertEquals(
            "Hello from the clipboard.",
            started.getStringExtra(MarmaladeSynthService.EXTRA_TEXT),
        )
        assertTrue(activity.isFinishing)
    }

    /** Focus can bounce (a dialog, the shade); one tap is one utterance. */
    @Test
    fun `a second focus gain does not speak again`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "Once."))

        val activity = Robolectric.buildActivity(SpeakClipboardActivity::class.java).setup().get()
        activity.onWindowFocusChanged(true)
        shadowOf(app).nextStartedService
        activity.onWindowFocusChanged(false)
        activity.onWindowFocusChanged(true)

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `an empty clipboard toasts and starts nothing`() {
        clipboard.clearPrimaryClip()

        val activity = Robolectric.buildActivity(SpeakClipboardActivity::class.java).setup().get()
        activity.onWindowFocusChanged(true)

        assertNull(shadowOf(app).nextStartedService)
        assertEquals(app.getString(R.string.service_clipboard_empty), ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `plain and html text clips read as text`() {
        assertEquals("plain", SpeakClipboardActivity.clipText(app, ClipData.newPlainText("l", "plain")))
        assertEquals(
            "styled",
            SpeakClipboardActivity.clipText(app, ClipData.newHtmlText("l", "styled", "<b>styled</b>")),
        )
    }

    /**
     * coerceToText would turn a URI clip into the URI string and it would be
     * read aloud — the MIME guard has to keep reporting it as empty.
     */
    @Test
    fun `a non-text clip reads as empty`() {
        val imageClip = ClipData(
            ClipDescription("image", arrayOf("image/png")),
            ClipData.Item(Uri.parse("content://media/external/images/1")),
        )

        assertNull(SpeakClipboardActivity.clipText(app, imageClip))
        assertNull(SpeakClipboardActivity.clipText(app, null))
    }
}
