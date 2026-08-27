package app.marmalade.tts.ui.intent

import android.content.Intent
import app.marmalade.tts.service.SpeakDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers ShareRouting: which shares open reader mode and which keep the
// original speak-immediately trampoline behaviour.
//
// Plain JVM — Intent.ACTION_SEND and friends are compile-time string constants,
// so no Android runtime is involved.
// -----------------------------------------------------------------------------

class ShareRoutingTest {

    @Test
    fun `send with a bare url routes to the reader`() {
        val route = ShareRouting.routeFor(Intent.ACTION_SEND, "https://example.com/article")

        assertEquals(
            ShareRoute.Reader(
                url = "https://example.com/article",
                sharedText = "https://example.com/article",
            ),
            route,
        )
    }

    @Test
    fun `send with title and url keeps the whole payload for the fallback`() {
        val shared = "Marmalade Ships\nhttps://example.com/article"

        assertEquals(
            ShareRoute.Reader(url = "https://example.com/article", sharedText = shared),
            ShareRouting.routeFor(Intent.ACTION_SEND, shared),
        )
    }

    @Test
    fun `send without a url keeps the speak behaviour`() {
        assertEquals(
            ShareRoute.Speak("Just some prose to read aloud."),
            ShareRouting.routeFor(Intent.ACTION_SEND, "Just some prose to read aloud."),
        )
    }

    @Test
    fun `blank and null shares stay on the speak path`() {
        assertEquals(ShareRoute.Speak(null), ShareRouting.routeFor(Intent.ACTION_SEND, null))
        assertEquals(ShareRoute.Speak("   "), ShareRouting.routeFor(Intent.ACTION_SEND, "   "))
    }

    /** Selected text is prose the user highlighted, not a link share. */
    @Test
    fun `process text with a url still speaks it`() {
        val selection = "See https://example.com/article for the details."

        assertEquals(
            ShareRoute.Speak(selection),
            ShareRouting.routeFor(Intent.ACTION_PROCESS_TEXT, selection),
        )
    }

    @Test
    fun `unknown action stays on the speak path`() {
        assertEquals(ShareRoute.Speak("text"), ShareRouting.routeFor(Intent.ACTION_VIEW, "text"))
    }

    @Test
    fun `reader shared text is capped at the dispatcher's limit`() {
        val long = "https://example.com/a " + "word ".repeat(SpeakDispatcher.MAX_TEXT_LENGTH)

        val route = ShareRouting.routeFor(Intent.ACTION_SEND, long)

        assertEquals(
            SpeakDispatcher.MAX_TEXT_LENGTH,
            (route as ShareRoute.Reader).sharedText.length,
        )
    }
}
