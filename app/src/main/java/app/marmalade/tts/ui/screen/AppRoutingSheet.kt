package app.marmalade.tts.ui.screen

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marmalade.tts.data.db.AppAliasMapping

// -----------------------------------------------------------------------------
// Per-alias routing sheet — "which apps speak as <alias>?"
//
// Opened from the routing strip on an alias card (see AliasScreen). Replaces
// the old app-centric AppMappingsScreen that hung off Settings: the same
// app_alias_mapping rows, read from the alias end instead of the app end.
//
// The list is scoped to ONE alias. Ticked = routed here. An app resolves to
// exactly one alias (packageName is the PK), so a row owned by a different
// alias is shown disabled and names its owner rather than being tickable:
// ticking used to silently steal the row, which meant the same app looked
// selectable from two aliases and whichever sheet saved last won.
// -----------------------------------------------------------------------------

/**
 * Bottom sheet listing every launchable app with a tick for "uses this alias".
 *
 * Ticked rows float to the top so the alias's current routing is visible
 * without scrolling; the rest keep the roster's alphabetical order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoutingSheet(
    state: RoutingSheetState,
    installedApps: List<InstalledApp>,
    mappings: List<AppAliasMapping>,
    isPro: Boolean,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Owner lookup for the "Currently → X" subtitle. Excludes this alias —
    // those rows are ticked, which already says where they belong.
    val otherOwners = remember(mappings, state.aliasName) {
        mappings
            .filter { it.aliasName != state.aliasName }
            .associate { it.packageName to it.aliasName }
    }

    // Row order is fixed when the sheet opens, NOT recomputed per tick —
    // otherwise an app would leap to the top of the list the moment you
    // ticked it, out from under the finger that just tapped it.
    val initiallyRouted = remember(state.aliasName) { state.selected }

    val visible = remember(installedApps, state.query, initiallyRouted) {
        val query = state.query.trim().lowercase()
        installedApps
            .filter { app ->
                query.isEmpty() ||
                    app.displayName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
            }
            .sortedByDescending { it.packageName in initiallyRouted }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "Apps that use ${state.aliasName}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isPro) {
                    "Anything you don't tick here falls back to your primary alias."
                } else {
                    "Per-app routing is a Marmalade Pro feature. Without it every " +
                        "app uses your primary alias — which is what most people want anyway."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        // Height-capped rather than free-growing so the Save/Cancel footer
        // below stays on screen while the roster scrolls inside.
        Box(modifier = Modifier.heightIn(min = 180.dp, max = 420.dp)) {
            if (installedApps.isEmpty()) {
                Text(
                    text = "Loading apps…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else if (visible.isEmpty()) {
                Text(
                    text = "No apps match \"${state.query}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn {
                    items(items = visible, key = { it.packageName }) { app ->
                        val owner = otherOwners[app.packageName]
                        AppRoutingRow(
                            app = app,
                            checked = app.packageName in state.selected,
                            currentOwner = owner,
                            // An app resolves to exactly one alias, so a row
                            // owned by another one is not tickable here.
                            // Ticking used to silently steal it, which meant
                            // the same app could appear "selectable" from two
                            // aliases and the last save won. Un-route it there
                            // first — the row names where to go.
                            enabled = owner == null,
                            onToggle = { onToggle(app.packageName) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onSave) { Text("Save routing") }
        }
    }
}

@Composable
private fun AppRoutingRow(
    app: InstalledApp,
    checked: Boolean,
    currentOwner: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val painter = app.icon?.let { rememberDrawablePainter(it, sizePx = 96) }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 2.dp,
            modifier = Modifier.size(40.dp),
        ) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(text = app.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                // The owning alias is the more useful subtitle when there is
                // one — it's what makes ticking this row an informed steal.
                text = currentOwner?.let { "Routed to $it — un-route it there first" }
                    ?: app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The whole row is the tap target; the Checkbox is an indicator that
        // forwards its own taps to the same handler.
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onToggle() },
        )
    }
}

/**
 * Look up [packageName]'s icon via PackageManager and render it at [size].
 * Falls back to a neutral circle when the lookup fails or the package isn't
 * installed any more (e.g. a mapping left behind by an uninstalled app).
 */
@Composable
fun AppIcon(packageName: String, size: Dp) {
    val pm = LocalContext.current.packageManager
    val drawable by produceState<Drawable?>(initialValue = null, packageName) {
        value = try {
            pm.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
    val painter = drawable?.let { rememberDrawablePainter(it, sizePx = 96) }
    // Rounded square with a soft shadow, matching the launcher-tile look.
    //
    // Previously this drew the raw drawable with no container, on the theory
    // that AdaptiveIconDrawable.draw() applies the device mask itself. It
    // does — but a *legacy* icon is just a square bitmap with its own baked
    // background, and the two rendered side by side looked like two
    // different systems (and the masked ones read as cropped). Normalising
    // every icon into the same tile makes the row coherent; ContentScale.Crop
    // fills the tile so a non-square legacy bitmap doesn't letterbox.
    Surface(
        shape = RoundedCornerShape(size / 4),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        modifier = Modifier.size(size),
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * Minimal `Drawable → Painter` adapter. We avoid pulling in
 * accompanist-drawablepainter (one more dependency for a single use case)
 * by rasterising the Drawable to a Bitmap once and wrapping it.
 *
 * [sizePx] caps both dimensions so an `AdaptiveIconDrawable`'s 108-dp canvas
 * (which can rasterise at thousands of pixels on hidpi devices) doesn't waste
 * memory for a 40-dp Image. The drawable is rendered SQUARE at [sizePx] ×
 * [sizePx], matching the launcher icon contract.
 */
@Composable
private fun rememberDrawablePainter(drawable: Drawable, sizePx: Int): Painter {
    return remember(drawable, sizePx) {
        BitmapPainter(drawable.toBitmap(sizePx))
    }
}

private fun Drawable.toBitmap(sizePx: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null &&
        bitmap.width == sizePx && bitmap.height == sizePx
    ) {
        return bitmap
    }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

/**
 * Trivial Painter that renders a [Bitmap] at its natural size. Equivalent to
 * androidx.compose.ui.graphics.painter.BitmapPainter but spelled out locally
 * so this file doesn't pull in another import.
 */
private class BitmapPainter(private val bitmap: Bitmap) : Painter() {
    override val intrinsicSize: androidx.compose.ui.geometry.Size =
        androidx.compose.ui.geometry.Size(
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
        )

    override fun DrawScope.onDraw() {
        drawImage(bitmap.asImageBitmap())
    }
}
