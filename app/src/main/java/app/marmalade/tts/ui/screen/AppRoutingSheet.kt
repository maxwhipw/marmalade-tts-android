package app.marmalade.tts.ui.screen

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Owner lookup for the "Currently → X" subtitle. Excludes this alias —
    // those rows are ticked, which already says where they belong.
    val otherOwners = remember(mappings, state.aliasId) {
        mappings
            .filter { it.aliasId != state.aliasId }
            .associate { it.packageName to it.aliasId }
    }

    // Row order is fixed when the sheet opens, NOT recomputed per tick —
    // otherwise an app would leap to the top of the list the moment you
    // ticked it, out from under the finger that just tapped it.
    val initiallyRouted = remember(state.aliasId) { state.selected }

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
                text = "Apps that use ${state.aliasId}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Anything you don't tick here falls back to your primary alias.",
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
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconTile(drawable = app.icon, size = 40.dp)
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
        // The whole row is the tap target; the Checkbox is a pure indicator.
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

/**
 * Look up [packageName]'s icon via PackageManager and render it at [size].
 * Falls back to an empty tile when the lookup fails or the package isn't
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
    AppIconTile(drawable = drawable, size = size)
}

/**
 * Launcher labels for [packageNames], in the same order.
 *
 * Icons alone are meaningless to a screen reader, so anywhere a row of app
 * icons stands in for "these apps" the labels carry the meaning instead.
 * A package that's gone falls back to its own name.
 */
@Composable
fun rememberAppLabels(packageNames: List<String>): List<String> {
    val pm = LocalContext.current.packageManager
    val labels by produceState(initialValue = packageNames, packageNames) {
        value = packageNames.map { name ->
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                name
            }
        }
    }
    return labels
}

/**
 * The one launcher-tile look every app icon in this feature wears: rounded
 * square, soft shadow, icon bled to the edges.
 *
 * Icons arrive in two incompatible shapes. A legacy icon is a square bitmap
 * with its own baked background; an `AdaptiveIconDrawable` paints itself
 * through the *device's* mask, which on most launchers is a circle. Rendered
 * side by side they read as two different systems, so we drop the platform
 * mask ([toIconBitmap]) and give every icon the same container instead.
 * ContentScale.Crop fills the tile so a non-square legacy bitmap doesn't
 * letterbox.
 */
@Composable
private fun AppIconTile(drawable: Drawable?, size: Dp) {
    // Rasterise at the tile's real pixel size: the bitmap then lands 1:1 in
    // the Image and needs no rescaling, which is both sharper and the only
    // way the two sizes we draw (30 dp on the card, 40 dp in the sheet) can
    // share one cache-free path.
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val painter = drawable?.let { rememberDrawablePainter(it, sizePx) }
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
 * by rasterising the Drawable to a square [sizePx] Bitmap once and wrapping
 * it — an `AdaptiveIconDrawable` has no useful intrinsic size to defer to,
 * so the caller names the size it wants.
 */
@Composable
private fun rememberDrawablePainter(drawable: Drawable, sizePx: Int): Painter {
    return remember(drawable, sizePx) {
        BitmapPainter(drawable.toIconBitmap(sizePx).asImageBitmap())
    }
}

/**
 * Rasterise a launcher icon to a square [sizePx] bitmap that fills its frame.
 *
 * An adaptive icon's own `draw()` clips both layers to the device's icon mask,
 * which would leave a circle floating inside our square tile — so we paint the
 * two layers ourselves and skip the mask entirely. Setting bounds to the
 * target square puts the 72-dp viewport there: the framework extends each
 * child layer by `getExtraInsetFraction()` (1/4) of the bounds on every side,
 * so the full 108-dp artwork lands 1.5x oversized and centred and the canvas
 * crops the 18-dp bleed. The result is full-bleed and centred, ready for our
 * corners. Legacy icons are just stretched to the square as before.
 */
private fun Drawable.toIconBitmap(sizePx: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null &&
        bitmap.width == sizePx && bitmap.height == sizePx
    ) {
        return bitmap
    }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    if (this is AdaptiveIconDrawable) {
        background?.draw(canvas)
        foreground?.draw(canvas)
    } else {
        draw(canvas)
    }
    return bmp
}
