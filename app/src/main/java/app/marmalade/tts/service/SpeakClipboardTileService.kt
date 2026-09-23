package app.marmalade.tts.service

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.marmalade.tts.R

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//
//   User taps the Marmalade tile in the Quick Settings panel
//     │
//     ▼
//   SpeakClipboardTileService.onClick()
//     │
//     ├── lock screen showing → unlockAndRun { … }  (the user unlocks first)
//     │
//     └── startActivityAndCollapse(SpeakClipboardActivity)
//           │   (PendingIntent overload on API 34+, where the Intent one
//           │    throws; the Intent overload below that)
//           ▼
//         SpeakClipboardActivity reads the clipboard once it has window
//         focus, dispatches via SpeakDispatcher, and finishes.
//
//   The tile never reads the clipboard itself: since Android 10 only the
//   focused app or the default IME can, and a TileService is neither — the
//   read always came back empty.
//
//   onStartListening() refreshes the tile label + icon every time the
//   panel becomes visible. We don't currently expose an "is speaking"
//   state on the tile — that can come later; the active-tile metadata
//   in the manifest leaves the door open for it.
// -----------------------------------------------------------------------------

/**
 * Quick Settings tile that speaks whatever text is currently on the
 * clipboard, through the [SpeakClipboardActivity] trampoline — which
 * dispatches to [MarmaladeSynthService] via [SpeakDispatcher], the same code
 * path as the share-sheet target.
 */
class SpeakClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.quick_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_marmalade)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openTrampoline() } else openTrampoline()
    }

    private fun openTrampoline() {
        val intent = SpeakClipboardActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
