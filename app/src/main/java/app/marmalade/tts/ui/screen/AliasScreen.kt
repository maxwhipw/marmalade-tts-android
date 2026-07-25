package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AliasScreen
//     │
//     ├── reads: AliasViewModel.aliases      ──► LazyColumn of alias cards
//     ├── reads: AliasViewModel.editorState  ──► AliasEditorSheet (modal)
//     ├── reads: AliasViewModel.voicesForSelectedEngine ──► voice dropdown
//     ├── reads: AppRoutingViewModel.mappings   ──► each card's routing strip
//     ├── reads: AppRoutingViewModel.sheetState ──► AppRoutingSheet (modal)
//     │
//     └── actions
//          ├── FAB → openEditor(null)             — create
//          ├── card tap → openEditor(alias)       — edit; delete lives inside
//          │                                        the editor sheet
//          ├── star tap → setPrimary(name)
//          └── routing strip → routing.openSheet(name)
//
//   Hosted by AppRoot: navigates back to SpeakScreen via the back arrow.
//
//   Per-app routing used to be its own screen hanging off Settings. It moved
//   here in the alias-routing redesign: the apps an alias speaks for are a
//   property of that alias, so they show on the alias card and are edited in a
//   sheet scoped to it. Two ViewModels back one screen — see AppRoutingViewModel.
// -----------------------------------------------------------------------------

/**
 * Voice aliases / personas — the user-saved bundle screen, and the home of
 * per-app voice routing.
 *
 * Each alias is a card: name, voice summary, speed/effect chips, and a strip
 * showing which apps speak with it. The card carries no persistent edit or
 * delete icons — tapping it opens the editor sheet, and delete lives inside
 * that sheet next to the fields it destroys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AliasScreen(
    onBack: () -> Unit,
    viewModel: AliasViewModel = hiltViewModel(),
    routingViewModel: AppRoutingViewModel = hiltViewModel(),
) {
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val voices by viewModel.voicesForSelectedEngine.collectAsStateWithLifecycle()
    val primaryAliasName by viewModel.primaryAliasName.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val effects by viewModel.effects.collectAsStateWithLifecycle()

    val mappings by routingViewModel.mappings.collectAsStateWithLifecycle()
    val installedApps by routingViewModel.installedApps.collectAsStateWithLifecycle()
    val routingSheet by routingViewModel.sheetState.collectAsStateWithLifecycle()
    val isPro by routingViewModel.isPro.collectAsStateWithLifecycle()

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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        // Clear the FAB so the last card's routing strip stays
                        // reachable when the list is scrolled to the bottom.
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = aliases, key = { it.name }) { alias ->
                        AliasCard(
                            alias = alias,
                            isPrimary = alias.name == primaryAliasName,
                            effectName = effects.firstOrNull { it.id == alias.effectId }?.name
                                ?: "No effect",
                            routedApps = mappings.filter { it.aliasName == alias.name },
                            onOpenEditor = { viewModel.openEditor(alias) },
                            onSetPrimary = { viewModel.setPrimary(alias.name) },
                            onOpenRouting = { routingViewModel.openSheet(alias.name) },
                        )
                    }
                }
            }
        }
    }

    if (editorState.isOpen) {
        AliasEditorSheet(
            state = editorState,
            engines = engines,
            voices = voices,
            effects = effects,
            // Cannot delete the last remaining alias — the app's data model
            // assumes at least one alias (with one designated primary) exists
            // once any have been created. AliasViewModel.delete also defends
            // this invariant; hiding the button is the primary UX cue.
            canDelete = !editorState.isNew && aliases.size > 1,
            onNameChange = viewModel::onEditorNameChange,
            onEngineChange = viewModel::onEditorEngineChange,
            onVoiceChange = viewModel::onEditorVoiceChange,
            onSpeedChange = viewModel::onEditorSpeedChange,
            onEffectChange = viewModel::onEditorEffectChange,
            onPhonemizationLanguageChange = viewModel::onEditorPhonemizationLanguageChange,
            onDelete = {
                pendingDelete = aliases.firstOrNull { it.name == editorState.originalName }
            },
            onSave = { viewModel.save() },
            onDismiss = viewModel::dismissEditor,
        )
    }

    if (routingSheet.isOpen) {
        AppRoutingSheet(
            state = routingSheet,
            installedApps = installedApps,
            mappings = mappings,
            isPro = isPro,
            onQueryChange = routingViewModel::onQueryChange,
            onToggle = routingViewModel::toggle,
            onSave = routingViewModel::saveRouting,
            onDismiss = routingViewModel::dismissSheet,
        )
    }

    pendingDelete?.let { alias ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${alias.name}\"?") },
            text = { Text("This removes the alias. The underlying voice stays installed.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(alias.name)
                        pendingDelete = null
                        // The editor sheet is still open behind this dialog,
                        // editing the row we just deleted.
                        viewModel.dismissEditor()
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
 * One alias on the list.
 *
 * UX choice for the primary indicator: a filled star, tinted on the primary
 * card and dimmed elsewhere. It's a single tap target with no menu — tapping
 * it on a non-primary card promotes that alias immediately. Picked over an
 * overflow menu because the action is binary (set/unset is implicit — there
 * is always exactly one primary). material-icons-core has no outlined Star,
 * so the dimmed alpha is the "not primary" affordance.
 *
 * Everything else on the card opens the editor. There is deliberately no edit
 * pencil and no trash icon: a card that is entirely tappable doesn't need a
 * pencil, and a destructive action parked permanently beside a tap target on
 * every row invites accidents — delete lives in the editor sheet instead.
 */
@Composable
private fun AliasCard(
    alias: VoiceAlias,
    isPrimary: Boolean,
    effectName: String,
    routedApps: List<AppAliasMapping>,
    onOpenEditor: () -> Unit,
    onSetPrimary: () -> Unit,
    onOpenRouting: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenEditor),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = alias.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isPrimary) {
                            AssistChip(
                                onClick = onOpenEditor,
                                label = { Text("Primary") },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    // Friendly engine label (matches the picker) + just the
                    // voice name — the stored voiceId is "<engine>:<name>", so
                    // we strip the redundant engine prefix rather than print
                    // the raw id twice.
                    val engineLabel =
                        EngineCatalog.byName(alias.engine)?.displayName ?: alias.engine
                    val voiceLabel = alias.voiceId.substringAfter(':', alias.voiceId)
                    Text(
                        text = "$engineLabel · $voiceLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = onOpenEditor,
                            label = { Text("%.2f×".format(alias.speed)) },
                        )
                        AssistChip(
                            onClick = onOpenEditor,
                            label = { Text(effectName) },
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            RoutingStrip(
                aliasName = alias.name,
                routedApps = routedApps,
                isPrimary = isPrimary,
                onClick = onOpenRouting,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * "Which apps speak with this alias" — routing state, shown on the alias it
 * belongs to.
 *
 * The primary alias gets an extra line spelling out the fallback rule
 * ("…and everything you haven't routed"). That rule governs most synthesis
 * calls the app serves and had no UI anywhere before this.
 */
@Composable
private fun RoutingStrip(
    aliasName: String,
    routedApps: List<AppAliasMapping>,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)

    // A non-primary alias with no routes has nothing to report, so it gets an
    // invitation instead of an empty state. The primary always has something
    // to say — it is the fallback for every unrouted app.
    if (routedApps.isEmpty() && !isPrimary) {
        val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onClick)
                .drawBehind {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                            ),
                        ),
                    )
                }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Route apps to $aliasName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cap the icon stack — past four the count carries the meaning and
            // more icons just crowd the label out of the row.
            for (mapping in routedApps.take(MAX_STRIP_ICONS)) {
                AppIcon(packageName = mapping.packageName, size = 28.dp)
                Spacer(Modifier.size(4.dp))
            }
            if (routedApps.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        routedApps.isEmpty() -> "Used by every app you haven't routed"
                        routedApps.size == 1 -> "Used by 1 app"
                        else -> "Used by ${routedApps.size} apps"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (isPrimary && routedApps.isNotEmpty()) {
                    Text(
                        text = "…and everything you haven't routed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Choose apps for $aliasName",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_STRIP_ICONS = 4

/**
 * Create/edit an alias.
 *
 * A bottom sheet rather than the AlertDialog this used to be: six fields plus
 * a destructive action is more than a dialog wants to carry, and the sheet
 * gives the effect picker room to breathe.
 *
 * Delete lives here. On the list row it was a permanent hazard beside a tap
 * target; here it sits with the thing it deletes, behind a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AliasEditorSheet(
    state: EditorState,
    engines: List<EngineOption>,
    voices: List<VoiceMeta>,
    effects: List<Effect>,
    canDelete: Boolean,
    onNameChange: (String) -> Unit,
    onEngineChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEffectChange: (String?) -> Unit,
    onPhonemizationLanguageChange: (String?) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.isNew) "Create alias" else "Edit \"${state.originalName}\"",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = errorTextFor(state.error)
                            ?: "Letters, digits, spaces, dashes — up to 50 characters.",
                        color = if (state.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                isError = state.error is SaveError.InvalidName ||
                    state.error is SaveError.NameTaken,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canDelete) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onSave) { Text("Save") }
            }
        }
    }
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
    // its container and hid items like Walkie-talkie. AlertDialog's
    // scrollable content surface handles arbitrary list length reliably.
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
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Pick phonemization language",
                    )
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
        ?: code // unrecognised override — show the raw code rather than hide it
        ?: "Auto (engine decides)"

private fun errorTextFor(error: SaveError?): String? = when (error) {
    SaveError.InvalidName -> "Letters, digits, spaces, dashes — up to 50 characters."
    SaveError.NameTaken -> "That name is already in use."
    SaveError.MissingVoice -> "Pick a voice for this alias."
    null -> null
}
