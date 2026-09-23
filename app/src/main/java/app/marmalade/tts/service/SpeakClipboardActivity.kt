package app.marmalade.tts.service

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import app.marmalade.tts.R

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//
//   SpeakClipboardTileService.onClick() ── startActivityAndCollapse ──►
//   SpeakClipboardActivity (translucent, no history, own task)
//     │
//     ▼
//   onWindowFocusChanged(true)   ← the first moment a clipboard read works
//     │
//     ├── ClipboardManager.primaryClip → clipText()
//     │     └── null / no items / non-text MIME ──► null
//     │
//     ├── SpeakDispatcher.dispatch(this, text)
//     │     ├── Blank      → Toast "Clipboard is empty"
//     │     ├── Failed     → Toast "Synthesis failed"
//     │     └── Dispatched → MarmaladeSynthService starts in the foreground
//     │
//     └── finish()
// -----------------------------------------------------------------------------

/**
 * Invisible trampoline that reads the clipboard for the Quick Settings tile.
 *
 * Since Android 10 only the app with input focus (or the default IME) may read
 * the clipboard; a TileService never has focus, so reading it from onClick
 * always came back empty. This activity exists to hold focus for the one
 * moment the read takes. It waits for window focus rather than reading in
 * onCreate/onResume, because focus is what the platform actually checks and
 * it arrives only after the Quick Settings shade has collapsed.
 *
 * Dispatching from a visible activity also makes the synthesis service's
 * startForegroundService an ordinary foreground-app start, which the tile's
 * background context was never guaranteed to be.
 */
class SpeakClipboardActivity : Activity() {

    /** Focus can come and go (a dialog, the shade); speak once. */
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        speak(readClipboardText())
        finish()
    }

    private fun speak(text: String?) {
        // Application context: the toast outlives this activity by design.
        val message = when (val result = SpeakDispatcher.dispatch(this, text)) {
            SpeakDispatcher.DispatchResult.Blank -> R.string.service_clipboard_empty
            is SpeakDispatcher.DispatchResult.Failed -> R.string.speak_error_synthesis_failed
            // The foreground notification is the confirmation.
            is SpeakDispatcher.DispatchResult.Dispatched -> {
                if (result.clamped) Log.i(TAG, "Clipboard text clamped to ${result.length} chars.")
                return
            }
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * The primary clip as text, or null. Defensive against SecurityException
     * — some OEM builds throw rather than return null on a denied read.
     */
    private fun readClipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = try {
            cm.primaryClip
        } catch (t: SecurityException) {
            Log.w(TAG, "Clipboard read denied", t)
            return null
        }
        return clipText(this, clip)
    }

    companion object {
        private const val TAG = "SpeakClipboardActivity"

        /** The tile's launch intent: its own task, so no app screen comes up behind it. */
        fun intent(context: Context): Intent =
            Intent(context, SpeakClipboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * First item of [clip] as text, or null when the clip is empty or not
         * text. Without the MIME guard, `coerceToText` happily turns a
         * content URI for an image (or any non-text item with a uri/intent)
         * into the URI string itself, which would then be spoken aloud
         * literally. text/plain and text/html only; anything else reads as
         * "Clipboard is empty".
         */
        internal fun clipText(context: Context, clip: ClipData?): String? {
            if (clip == null || clip.itemCount == 0) return null
            val description = clip.description
            if (description == null ||
                (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                    !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            ) {
                return null
            }
            return clip.getItemAt(0)?.coerceToText(context)?.toString()
        }
    }
}
