package app.marmalade.tts.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog

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
    val primaryAliasName by viewModel.primaryAliasName.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val effects by viewModel.effects.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<VoiceAlias?>(null) }

    // Re-probe engine install state every time the screen becomes the active
    // destination. The VM's init does an initial probe; this catches the
    // Aliases → Engines → install → back-to-Aliases flow so a
    // freshly-installed engine appears in the editor's picker without a restart.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Voice aliases") },
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
                            isPrimary = alias.name == primaryAliasName,
                            // Cannot delete the last remaining alias — the
                            // app's data model assumes at least one alias
                            // (with one designated primary) exists once
                            // any have been created. AliasViewModel.delete
                            // also defends this invariant; gating the
                            // button is the primary UX cue.
                            isDeletable = aliases.size > 1,
                            effectName = effects.firstOrNull { it.id == alias.effectId }?.name ?: "No effect",
                            onEdit = { viewModel.openEditor(alias) },
                            onDelete = { pendingDelete = alias },
                            onSetPrimary = { viewModel.setPrimary(alias.name) },
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
            engines = engines,
            voices = voices,
            effects = effects,
            onNameChange = viewModel::onEditorNameChange,
            onEngineChange = viewModel::onEditorEngineChange,
            onVoiceChange = viewModel::onEditorVoiceChange,
            onSpeedChange = viewModel::onEditorSpeedChange,
            onEffectChange = viewModel::onEditorEffectChange,
            onPhonemizationLanguageChange = viewModel::onEditorPhonemizationLanguageChange,
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
                // Error-toned destructive action — matches the red trash
                // icon already shown on each list row.
                Button(
                    onClick = {
                        viewModel.delete(alias.name)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
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

/**
 * One row on the alias list.
 *
 * UX choice for the primary indicator: a leading **filled star** on the
 * primary row, an outlined-star **IconButton** on non-primary rows. The
 * star is a single tap target with no menu — tapping it on a non-primary
 * row calls [onSetPrimary] immediately. Picked over an overflow menu
 * because the action is binary (set/unset is implicit — there is always
 * exactly one primary) and a one-tap affordance reads cleaner than a
 * three-dot menu hiding a single item.
 *
 * The primary row's star is non-interactive (no IconButton wrapper) so
 * tapping a row that is already primary does nothing — matches the
 * "there is always one primary" invariant.
 */
@Composable
private fun AliasRow(
    alias: VoiceAlias,
    isPrimary: Boolean,
    isDeletable: Boolean,
    effectName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetPrimary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Always an IconButton so primary and non-primary rows share the same
        // 48dp leading footprint — a bare 24dp Icon for the primary case made
        // that row shorter and threw off its vertical rhythm. Tint is the only
        // state difference; re-tapping an already-primary star is a harmless
        // no-op. material-icons-core has no outlined Star, so the dimmed alpha
        // is the "not primary" affordance.
        IconButton(onClick = onSetPrimary) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = if (isPrimary) {
                    "Primary alias"
                } else {
                    "Set ${alias.name} as primary"
                },
                tint = if (isPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
            )
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alias.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isPrimary) {
                    AssistChip(
                        onClick = onEdit,
                        label = { Text("Primary") },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // Friendly engine label (matches the picker) + just the voice name —
            // the stored voiceId is "<engine>:<name>", so we strip the redundant
            // engine prefix rather than print the raw id twice.
            val engineLabel = EngineCatalog.byName(alias.engine)?.displayName ?: alias.engine
            val voiceLabel = alias.voiceId.substringAfter(':', alias.voiceId)
            Text(
                text = "$engineLabel · $voiceLabel",
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
                    label = { Text(effectName) },
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${alias.name}")
        }
        IconButton(onClick = onDelete, enabled = isDeletable) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = if (isDeletable) {
                    "Delete ${alias.name}"
                } else {
                    "Cannot delete — at least one alias is required"
                },
                tint = if (isDeletable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasEditorDialog(
    state: EditorState,
    engines: List<EngineOption>,
    voices: List<VoiceMeta>,
    effects: List<Effect>,
    onNameChange: (String) -> Unit,
    onEngineChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEffectChange: (String?) -> Unit,
    onPhonemizationLanguageChange: (String?) -> Unit,
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
                                ?: "Letters, digits, spaces, dashes — up to 50 characters.",
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

                EffectPicker(
                    effects = effects,
                    selectedId = state.effectId,
                    onPick = onEffectChange,
                )

                PhonemizationLanguageDropdown(
                    selected = state.phonemizationLanguage,
                    onPick = onPhonemizationLanguageChange,
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
    engines: List<EngineOption>,
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

/**
 * Picks an effect (built-in or custom) from the DB-backed [effects] list, or
 * "No effect" (writes null). Replaces the old fixed [EffectPreset] dropdown —
 * any of the seeded CLI presets (and future user effects) can be assigned.
 */
@Composable
private fun EffectPicker(
    effects: List<Effect>,
    selectedId: String?,
    onPick: (String?) -> Unit,
) {
    // A modal picker (not a DropdownMenu) because the built-in catalog has
    // grown past 20 entries — the dropdown's popup clipped at the bottom of
    // the dialog and hid items like Walkie-talkie. AlertDialog's scrollable
    // content surface handles arbitrary list length reliably.
    var showPicker by remember { mutableStateOf(false) }
    val selectedLabel = effects.firstOrNull { it.id == selectedId }?.name ?: "No effect"
    OutlinedTextField(
        value = selectedLabel,
        onValueChange = { /* read-only */ },
        readOnly = true,
        label = { Text("Effect") },
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick effect")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Pick effect") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    EffectPickerRow("No effect") { onPick(null); showPicker = false }
                    for (effect in effects) {
                        EffectPickerRow(effect.name) { onPick(effect.id); showPicker = false }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EffectPickerRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/**
 * Per-alias espeak language override. Null = "Auto" (engine decides:
 * KokoroDirect picks per voice prefix; others ignore). Non-null forces
 * espeak's language for the synthesis call.
 *
 * Languages match what `KokoroDirectVoiceCatalog.espeakVoiceFor()` can
 * emit — adding more requires bundling the corresponding espeak-ng-data
 * subdirectory (which we already do, since we ship the full data tree).
 */
@Composable
private fun PhonemizationLanguageDropdown(
    selected: String?,
    onPick: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = phonemizationLanguageDisplayName(selected),
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text("Phonemization language") },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick phonemization language")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((code, label) in PHONEMIZATION_LANGUAGES) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Code-to-label pairs. The first entry's code is `null` — that's the
 * "Auto" sentinel that clears any user override and lets the engine
 * pick (KokoroDirect derives from voice prefix; others ignore).
 *
 * Espeak codes follow espeak-ng-data's directory naming (`af_dict`,
 * `en-us`, `cmn`, etc.) — no abstraction layer here, the strings are
 * what espeak's `SetVoiceByName` expects.
 */
private val PHONEMIZATION_LANGUAGES: List<Pair<String?, String>> = listOf(
    null to "Auto (engine decides)",
    "en-us" to "English (US)",
    "en-gb" to "English (UK)",
    "es" to "Spanish",
    "fr-fr" to "French",
    "hi" to "Hindi",
    "it" to "Italian",
    "ja" to "Japanese",
    "pt-br" to "Portuguese (BR)",
    "cmn" to "Mandarin Chinese",
)

private fun phonemizationLanguageDisplayName(code: String?): String =
    PHONEMIZATION_LANGUAGES.firstOrNull { it.first == code }?.second
        ?: code  // unrecognised override — show the raw code rather than hide it
        ?: "Auto (engine decides)"

private fun errorTextFor(error: SaveError?): String? = when (error) {
    SaveError.InvalidName -> "Letters, digits, spaces, dashes — up to 50 characters."
    SaveError.NameTaken -> "That name is already in use."
    SaveError.MissingVoice -> "Pick a voice for this alias."
    null -> null
}
