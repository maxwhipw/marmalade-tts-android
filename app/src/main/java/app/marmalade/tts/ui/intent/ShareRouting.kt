package app.marmalade.tts.ui.intent

import android.content.Intent
import app.marmalade.tts.reader.SharedUrlDetector
import app.marmalade.tts.service.SpeakDispatcher

// -----------------------------------------------------------------------------
// ShareRouting — the one decision ShareIntentActivity makes.
//
// Split out from the activity so it can be unit-tested on the JVM: the
// activity itself is a trampoline whose only other job is talking to the
// Android framework.
//
// The rule is deliberately narrow. ACTION_SEND carrying a link is a "here's a
// page" share, which is what reader mode is for. Everything else — a share
// with no link, and every ACTION_PROCESS_TEXT selection (that's prose the user
// highlighted, not a link share, even on the rare occasion it contains a URL)
// — keeps the original speak-it-now behaviour exactly.
// -----------------------------------------------------------------------------

/** Where an incoming share intent should go. */
internal sealed interface ShareRoute {

    /** A link share: open reader mode on [url], carrying [sharedText] for fallback. */
    data class Reader(val url: String, val sharedText: String) : ShareRoute

    /** Everything else: speak [text] via the existing dispatcher path. */
    data class Speak(val text: String?) : ShareRoute
}

internal object ShareRouting {

    /**
     * Decide what to do with a share. [action] and [text] come straight off
     * the intent; both may be null.
     */
    fun routeFor(action: String?, text: String?): ShareRoute {
        if (action == Intent.ACTION_SEND) {
            val url = SharedUrlDetector.findUrl(text)
            if (url != null) {
                return ShareRoute.Reader(
                    url = url,
                    // The share text rides along as a navigation argument, so
                    // cap it at the length the speak path would clamp it to
                    // anyway rather than putting an unbounded string on the
                    // back stack.
                    sharedText = text.orEmpty().take(SpeakDispatcher.MAX_TEXT_LENGTH),
                )
            }
        }
        return ShareRoute.Speak(text)
    }
}
