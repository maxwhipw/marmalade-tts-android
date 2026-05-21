package app.marmalade.tts.service

import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import app.marmalade.tts.R
import dagger.hilt.android.AndroidEntryPoint

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//
//   User taps the Marmalade tile in the Quick Settings panel
//     │
//     ▼
//   SpeakClipboardTileService.onClick()
//     │
//     ├── getSystemService(ClipboardManager) → primaryClip
//     │     ├── null / no items / non-text MIME ──► Toast "Clipboard is empty"
//     │     └── primary text                    ──► passes through
//     │
//     └── SpeakDispatcher.dispatch(this, clipboardText)
//           │
//           ├── Blank      → Toast "Clipboard is empty"
//           └── Dispatched → MarmaladeSynthService starts in the foreground
//
//   onStartListening() refreshes the tile label + icon every time the
//   panel becomes visible. We don't currently expose an "is speaking"
//   state on the tile — that can come later; the active-tile metadata
//   in the manifest leaves the door open for it.
//
//   This tile requires the device to be unlocked. We do not declare
//   `UNLOCK_REQUIRED=false` because Android 10+ blocks background
//   clipboard reads from a locked context — the primary clip read would
//   come back empty even if the tile were allowed to fire from the lock
//   screen, so the unlock dance is unavoidable for "speak clipboard."
// -----------------------------------------------------------------------------

/**
 * Quick Settings tile that speaks whatever text is currently on the
 * clipboard. The tile dispatches to
 * [app.marmalade.tts.service.MarmaladeSynthService] via [SpeakDispatcher]
 * — same code path as the share-sheet target.
 */
@AndroidEntryPoint
class SpeakClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.quick_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.mascot_happy)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val text = readClipboardText()
        when (SpeakDispatcher.dispatch(this, text)) {
            SpeakDispatcher.DispatchResult.Blank -> {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
            is SpeakDispatcher.DispatchResult.Dispatched -> {
                // No-op; the foreground notification is the user-facing
                // confirmation that synthesis has started.
            }
        }
    }

    /**
     * Read the primary clipboard text, or null if the clipboard is empty
     * or holds a non-text MIME type. Defensive against
     * SecurityException — some OEM builds throw if the tile fires before
     * the user fully unlocks.
     */
    private fun readClipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = try {
            cm.primaryClip
        } catch (t: SecurityException) {
            Log.w(TAG, "Clipboard read denied", t)
            return null
        }
        if (clip == null || clip.itemCount == 0) return null
        // Without this MIME-type guard, `coerceToText` happily turns a
        // content-URI for an image (or any non-text item with a uri/intent)
        // into the URI string itself, which the dispatcher would then speak
        // aloud literally. Accept text/plain and text/html only; everything
        // else surfaces as the "Clipboard is empty" toast.
        val description = clip.description
        if (description == null ||
            (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
        ) {
            return null
        }
        val item = clip.getItemAt(0) ?: return null
        return item.coerceToText(this)?.toString()
    }

    private companion object {
        const val TAG = "SpeakClipboardTile"
    }
}
