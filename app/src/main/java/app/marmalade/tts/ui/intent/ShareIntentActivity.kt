package app.marmalade.tts.ui.intent

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.marmalade.tts.MainActivity
import app.marmalade.tts.R
import app.marmalade.tts.service.SpeakDispatcher

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//
//   User shares text from any app  ─►  Android share sheet
//     │
//     │  Intent { ACTION_SEND, EXTRA_TEXT = "…" }
//     │  ── or ──
//     │  Intent { ACTION_PROCESS_TEXT, EXTRA_PROCESS_TEXT = "…" }
//     │  (text-selection floating menu on Android 6+)
//     ▼
//   ShareIntentActivity.onCreate(savedInstanceState)
//     │
//     ├── extractSpeakableText(intent)
//     │     ├── ACTION_SEND          ──► EXTRA_TEXT
//     │     ├── ACTION_PROCESS_TEXT  ──► EXTRA_PROCESS_TEXT (CharSequence)
//     │     └── anything else        ──► null
//     │
//     ├── ShareRouting.routeFor(action, text)
//     │
//     ├── ShareRoute.Reader ──► MainActivity + EXTRA_READER_URL
//     │                          │
//     │                          ▼
//     │                        Routes.Reader — fetch, extract, render
//     │
//     └── ShareRoute.Speak  ──► SpeakDispatcher.dispatch(this, text)
//           │                    │
//           │                    ▼
//           │                  MarmaladeSynthService is started (foreground),
//           │                  validation and clamping live inside the
//           │                  dispatcher so every entry point applies the
//           │                  same rules.
//           │
//           └── if text is blank:  Toast "Nothing to speak"
//
//   finish() is called unconditionally so this transparent trampoline
//   never sticks around in the task stack.
// -----------------------------------------------------------------------------

/**
 * Transparent trampoline that lets Marmalade appear as a share-sheet
 * target and as a "Process text" item in the system text-selection menu.
 *
 * Themed translucent + no title bar (set in the manifest) so the user never
 * sees a flash of UI — we read the intent and either dispatch to
 * [app.marmalade.tts.service.MarmaladeSynthService] or hand a shared link to
 * MainActivity's reader screen, then finish.
 */
class ShareIntentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = extractSpeakableText(intent)
        when (val route = ShareRouting.routeFor(intent?.action, text)) {
            is ShareRoute.Reader -> openReader(route)
            is ShareRoute.Speak -> speak(route.text)
        }
        finish()
    }

    /** Hand the shared link to the reader screen in MainActivity. */
    private fun openReader(route: ShareRoute.Reader) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_READER_URL, route.url)
                putExtra(MainActivity.EXTRA_READER_SHARED_TEXT, route.sharedText)
                // CLEAR_TOP + SINGLE_TOP so a second share reuses the running
                // MainActivity (arriving at its onNewIntent) instead of
                // stacking reader screens.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
    }

    /** Original trampoline behaviour: speak the shared text immediately. */
    private fun speak(text: String?) {
        when (val result = SpeakDispatcher.dispatch(this, text)) {
            SpeakDispatcher.DispatchResult.Blank -> {
                Toast.makeText(
                    this,
                    R.string.speak_share_nothing_to_speak,
                    Toast.LENGTH_SHORT,
                ).show()
                Log.d(TAG, "Share intent had no usable text; action=${intent?.action}")
            }
            is SpeakDispatcher.DispatchResult.Dispatched -> {
                if (result.clamped) {
                    Log.i(
                        TAG,
                        "Share text clamped to ${result.length} chars before dispatch.",
                    )
                }
            }
        }
    }

    /**
     * Pull the text payload out of the incoming intent. Returns null if
     * the intent shape isn't one we understand (we don't crash — the
     * caller will treat null the same as blank).
     */
    private fun extractSpeakableText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
    }

    private companion object {
        const val TAG = "ShareIntentActivity"
    }
}
