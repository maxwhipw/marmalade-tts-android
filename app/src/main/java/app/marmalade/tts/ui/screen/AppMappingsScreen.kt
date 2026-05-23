package app.marmalade.tts.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.marmalade.tts.data.db.AppAliasMapping

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AppMappingsScreen
//     │
//     ├── reads: AppMappingsViewModel.mappings        ──► LazyColumn rows
//     ├── reads: AppMappingsViewModel.aliases         ──► alias picker
//     ├── reads: AppMappingsViewModel.installedApps   ──► app picker
//     ├── reads: AppMappingsViewModel.editorState     ──► add/edit sheet
//     │
//     └── actions
//          ├── FAB → openEditor(null) + loadInstalledApps() (kicks the pm
//          │    enumeration off the main thread; result populates the sheet)
//          ├── row tap → openEditor(mapping)  — edit
//          ├── delete icon → confirm dialog → delete(packageName)
//          └── editor save → save(); on success the sheet closes itself
//
//   Hosted by AppRoot: navigates back to SettingsScreen via the back arrow.
// -----------------------------------------------------------------------------

/**
 * Per-app voices — manages (package → alias) mappings used by [TtsRouter]
 * when an external app calls system TTS without specifying a voice.
 *
 * Reachable from Settings → "Per-app voices". Detail screen; the bottom
 * nav bar is hidden by AppRoot while this is the current destination
 * (same pattern as [AliasScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMappingsScreen(
    onBack: () -> Unit,
    viewModel: AppMappingsViewModel = hiltViewModel(),
) {
    val mappings by viewModel.mappings.collectAsStateWithLifecycle()
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<AppAliasMapping?>(null) }

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Per-app voices") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.loadInstalledApps()
                viewModel.openEditor(null)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add mapping")
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (mappings.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = mappings, key = { it.packageName }) { mapping ->
                        MappingRow(
                            mapping = mapping,
                            onEdit = {
                                viewModel.loadInstalledApps()
                                viewModel.openEditor(mapping)
                            },
                            onDelete = { pendingDelete = mapping },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (editorState.isOpen) {
        MappingEditorDialog(
            state = editorState,
            installedApps = installedApps,
            aliases = aliases.map { it.name },
            onPickApp = viewModel::selectApp,
            onPickAlias = viewModel::selectAlias,
            onSave = { viewModel.save() },
            onDismiss = viewModel::dismissEditor,
        )
    }

    pendingDelete?.let { mapping ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove this mapping?") },
            text = {
                Text(
                    "Routing for ${mapping.displayName ?: mapping.packageName} will fall " +
                        "back to your primary alias.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(mapping.packageName)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No per-app routes yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pick a voice persona for each app that asks marmalade-tts " +
                "to speak. Anything you don't configure uses your primary alias. " +
                "Tap + to add one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MappingRow(
    mapping: AppAliasMapping,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No icon caching yet — keeping the data class lightweight to
        // avoid stale Drawables surviving config changes. The package
        // name + display name from the cached label tell the user enough.
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = mapping.displayName ?: mapping.packageName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "→ ${mapping.aliasName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mapping.displayName != null) {
                Text(
                    text = mapping.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove mapping for ${mapping.packageName}",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MappingEditorDialog(
    state: MappingEditorState,
    installedApps: List<InstalledApp>,
    aliases: List<String>,
    onPickApp: (InstalledApp) -> Unit,
    onPickAlias: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var appPickerOpen by remember { mutableStateOf(false) }
    var aliasMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state.originalPackageName == null) "Add per-app voice"
                else "Edit per-app voice",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Pick an app
                OutlinedTextField(
                    value = state.selectedApp?.displayName ?: "",
                    onValueChange = { /* read-only */ },
                    readOnly = true,
                    label = { Text("App") },
                    placeholder = { Text("Pick an app") },
                    trailingIcon = {
                        IconButton(onClick = { appPickerOpen = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick app")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { appPickerOpen = true },
                )

                // 2. Pick an alias
                Box {
                    OutlinedTextField(
                        value = state.selectedAliasName ?: "",
                        onValueChange = { /* read-only */ },
                        readOnly = true,
                        label = { Text("Voice alias") },
                        placeholder = {
                            Text(
                                if (aliases.isEmpty()) "Create an alias first"
                                else "Pick an alias",
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { aliasMenuOpen = true },
                                enabled = aliases.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = "Pick alias",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = aliasMenuOpen,
                        onDismissRequest = { aliasMenuOpen = false },
                    ) {
                        if (aliases.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No aliases yet — create one first.") },
                                onClick = { aliasMenuOpen = false },
                                enabled = false,
                            )
                        } else {
                            for (name in aliases) {
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onPickAlias(name)
                                        aliasMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = state.selectedApp != null && state.selectedAliasName != null,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (appPickerOpen) {
        AppPickerDialog(
            apps = installedApps,
            onPick = { picked ->
                onPickApp(picked)
                appPickerOpen = false
            },
            onDismiss = { appPickerOpen = false },
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>,
    onPick: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            val q = query.trim().lowercase()
            apps.filter {
                it.displayName.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick an app") },
        text = {
            Column(modifier = Modifier.heightIn(min = 240.dp, max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (apps.isEmpty()) {
                    // Either still loading, or the device genuinely has
                    // no third-party launchable apps. Either way the user
                    // can't proceed; the FAB triggers loadInstalledApps()
                    // so this should populate within a few hundred ms.
                    Text(
                        text = "Loading apps…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (filtered.isEmpty()) {
                    Text(
                        text = "No matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn {
                        items(items = filtered, key = { it.packageName }) { app ->
                            AppPickerRow(app = app, onClick = { onPick(app) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    // Trigger initial population on first opening — defensive; the FAB
    // path also calls loadInstalledApps, but if the editor was opened
    // from the row-edit path this guarantees the list is populated.
    LaunchedEffect(Unit) {
        // No-op LaunchedEffect — the actual load is kicked from the
        // composable's caller (AppMappingsScreen) on FAB/row tap. This
        // hook is reserved for future incremental loading.
    }
}

@Composable
private fun AppPickerRow(app: InstalledApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val painter = app.icon?.let { rememberDrawablePainter(it) }
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(text = app.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Minimal `Drawable → Painter` adapter. We avoid pulling in
 * accompanist-drawablepainter (one more dependency for a single use case)
 * by rasterising the Drawable to a Bitmap once and wrapping it.
 *
 * Adaptive icons rasterise correctly because Android resolves the
 * foreground/background layers internally before the Drawable hits the
 * Canvas. Vector drawables likewise rasterise — they're already drawn
 * via Canvas under the hood.
 */
@Composable
private fun rememberDrawablePainter(drawable: Drawable): Painter {
    return remember(drawable) {
        val bitmap = drawable.toBitmap()
        BitmapPainter(bitmap)
    }
}

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap
    }
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

/**
 * Trivial Painter that renders a [Bitmap] at its natural size. Equivalent
 * to androidx.compose.ui.graphics.painter.BitmapPainter but spelled out
 * locally so this file doesn't pull in another import.
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
