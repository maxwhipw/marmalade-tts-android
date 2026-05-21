package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineDescriptor

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AliasScreen
//     │
//     ├── reads: AliasViewModel.aliases  ──► LazyColumn rows
//     ├── reads: AliasViewModel.editorState ──► AliasEditorDialog (modal)
//     ├── reads: AliasViewModel.voicesForSelectedEngine ──► voice dropdown
//     │
//     └── actions
//          ├── FAB → openEditor(null)        — create
//          ├── row → openEditor(alias)       — edit existing
//          ├── delete icon → delete(name)    — confirm dialog before deleting
//          └── editor save → save(); on success the editor closes itself
//
//   Hosted by AppRoot: navigates back to SpeakScreen via the back arrow.
// -----------------------------------------------------------------------------

/**
 * Voice aliases / personas — the user-saved bundle screen.
 *
 * Lists every saved alias with a per-row edit + delete affordance, and
 * a FAB that opens the editor in create-new mode. The editor is a
 * Material 3 `AlertDialog` rather than a bottom sheet — simpler, fewer
 * moving parts, and matches the existing install-confirm dialog idiom
 * already used in [EnginesScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasScreen(
    onBack: () -> Unit,
    viewModel: AliasViewModel = hiltViewModel(),
) {
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val voices by viewModel.voicesForSelectedEngine.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<VoiceAlias?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Voice aliases") },
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
            FloatingActionButton(onClick = { viewModel.openEditor(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "Create alias")
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (aliases.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = aliases, key = { it.name }) { alias ->
                        AliasRow(
                            alias = alias,
                            onEdit = { viewModel.openEditor(alias) },
                            onDelete = { pendingDelete = alias },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (editorState.isOpen) {
        AliasEditorDialog(
            state = editorState,
            engines = viewModel.engines,
            voices = voices,
            onNameChange = viewModel::onEditorNameChange,
            onEngineChange = viewModel::onEditorEngineChange,
            onVoiceChange = viewModel::onEditorVoiceChange,
            onSpeedChange = viewModel::onEditorSpeedChange,
            onEffectChange = viewModel::onEditorEffectChange,
            onSave = { viewModel.save() },
            onDismiss = viewModel::dismissEditor,
        )
    }

    pendingDelete?.let { alias ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${alias.name}\"?") },
            text = { Text("This removes the alias. The underlying voice stays installed.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(alias.name)
                    pendingDelete = null
                }) { Text("Delete") }
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
        Text(
            text = "No aliases yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Aliases save a voice + speed + effect under a name like " +
                "\"narrator\" or \"dramatic\". Tap + to create one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AliasRow(
    alias: VoiceAlias,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = alias.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${alias.engine} · ${alias.voiceId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = onEdit,
                    label = { Text("%.2f×".format(alias.speed)) },
                )
                AssistChip(
                    onClick = onEdit,
                    label = { Text(alias.effectPreset.lowercase()) },
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${alias.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete ${alias.name}",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasEditorDialog(
    state: EditorState,
    engines: List<EngineDescriptor>,
    voices: List<VoiceMeta>,
    onNameChange: (String) -> Unit,
    onEngineChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEffectChange: (EffectPreset) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isNew) "Create alias" else "Edit \"${state.originalName}\"") },
        text = {
            // The editor has six fields stacked vertically. Keep it in a
            // plain Column rather than a LazyColumn — AlertDialog scrolls
            // its content automatically when it overflows.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = errorTextFor(state.error)
                                ?: "Lower-case letters, digits, dash, underscore.",
                            color = if (state.error != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    isError = state.error is SaveError.InvalidName || state.error is SaveError.NameTaken,
                    modifier = Modifier.fillMaxWidth(),
                )

                EngineDropdown(
                    selected = state.engine,
                    engines = engines,
                    onPick = onEngineChange,
                )

                VoiceDropdown(
                    selected = state.voiceId,
                    voices = voices,
                    onPick = onVoiceChange,
                    isError = state.error is SaveError.MissingVoice,
                )

                Column {
                    Text(
                        text = "Speed: %.2f×".format(state.speed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = state.speed,
                        onValueChange = onSpeedChange,
                        valueRange = VoiceAlias.MIN_SPEED..VoiceAlias.MAX_SPEED,
                        // 15 steps between 0.5 and 2.0 = 0.1x increments
                        // (Slider's `steps` excludes the endpoints).
                        steps = 14,
                    )
                }

                EffectDropdown(
                    selected = state.effect,
                    onPick = onEffectChange,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineDropdown(
    selected: String,
    engines: List<EngineDescriptor>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = engines.firstOrNull { it.name == selected }?.displayName ?: selected,
            onValueChange = { /* read-only — picker writes via menu items */ },
            readOnly = true,
            label = { Text("Engine") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (engine in engines) {
                DropdownMenuItem(
                    text = { Text(engine.displayName) },
                    onClick = {
                        onPick(engine.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(
    selected: String,
    voices: List<VoiceMeta>,
    onPick: (String) -> Unit,
    isError: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val installed = voices.filter { it.isInstalled }
    // Fall back to the full list if no voice has flipped `isInstalled` yet
    // (true on fresh installs before KittenEngine.ensureModelLoaded()
    // succeeds). The CLI's behaviour is "let the user pick; the next speak
    // call will fail loudly with a model-missing message" — match that
    // rather than blocking the editor entirely.
    val choices = installed.ifEmpty { voices }
    val selectedLabel = choices.firstOrNull { it.id == selected }?.displayName
        ?: if (selected.isBlank()) "Select a voice" else selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = { /* read-only */ },
            readOnly = true,
            isError = isError,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (choices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No voices available — install an engine first.") },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                for (voice in choices) {
                    DropdownMenuItem(
                        text = { Text(voice.displayName) },
                        onClick = {
                            onPick(voice.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectDropdown(
    selected: EffectPreset,
    onPick: (EffectPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selected.name,
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text("Effect") },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick effect")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (preset in EffectPreset.entries) {
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    onClick = {
                        onPick(preset)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun errorTextFor(error: SaveError?): String? = when (error) {
    SaveError.InvalidName -> "Use lower-case letters, digits, dash, underscore — no spaces."
    SaveError.NameTaken -> "That name is already in use."
    SaveError.MissingVoice -> "Pick a voice for this alias."
    null -> null
}
