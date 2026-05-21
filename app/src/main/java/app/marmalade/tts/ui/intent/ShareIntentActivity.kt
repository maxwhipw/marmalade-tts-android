package app.marmalade.tts.ui.intent

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.marmalade.tts.service.SpeakDispatcher
import dagger.hilt.android.AndroidEntryPoint

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
//     ├── if text is blank:  Toast "Nothing to speak"
//     │
//     └── else:  SpeakDispatcher.dispatch(this, text)
//                 │
//                 ▼
//               MarmaladeSynthService is started (foreground), validation
//               and clamping live inside the dispatcher so every entry
//               point applies the same rules.
//
//   finish() is called unconditionally so this transparent trampoline
//   never sticks around in the task stack.
// -----------------------------------------------------------------------------

/**
 * Transparent trampoline that lets Marmalade appear as a share-sheet
 * target and as a "Process text" item in the system text-selection menu.
 *
 * Themed translucent + no title bar (set in the manifest) so the user
 * never sees a flash of UI — we read the intent, dispatch to
 * [app.marmalade.tts.service.MarmaladeSynthService], and finish.
 */
@AndroidEntryPoint
class ShareIntentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = extractSpeakableText(intent)
        when (val result = SpeakDispatcher.dispatch(this, text)) {
            SpeakDispatcher.DispatchResult.Blank -> {
                Toast.makeText(this, "Nothing to speak", Toast.LENGTH_SHORT).show()
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
        finish()
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
