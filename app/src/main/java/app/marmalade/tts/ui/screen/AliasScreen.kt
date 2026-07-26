package app.marmalade.tts.ui.screen

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.VoicePath
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
//          ├── editor → setPrimary(name) / delete
//          └── routing strip → routing.openSheet(name)
//
//   Hosted by AppRoot: navigates back to SpeakScreen via the back arrow.
//
//   Per-app routing used to be its own screen hanging off Settings. It moved
//   here in the alias-routing redesign: the apps an alias speaks for are a
//   property of that alias, so they show on the alias card and are edited in a
//   sheet scoped to it. Two ViewModels back one screen — see AppRoutingViewModel.
// -----------------------------------------------------------------------------

/** Fully-rounded shape shared by this screen's chips, badges and action buttons. */
private val PillShape = RoundedCornerShape(999.dp)

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
    val primaryAliasName by viewModel.primaryAliasName.collectAsStateWithLifecycle()
    val effects by viewModel.effects.collectAsStateWithLifecycle()
    val voiceTree by viewModel.voiceTree.collectAsStateWithLifecycle()
    val pickerState by viewModel.pickerState.collectAsStateWithLifecycle()

    val mappings by routingViewModel.mappings.collectAsStateWithLifecycle()
    val installedApps by routingViewModel.installedApps.collectAsStateWithLifecycle()
    val routingSheet by routingViewModel.sheetState.collectAsStateWithLifecycle()
    val isPro by routingViewModel.isPro.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<VoiceAlias?>(null) }

    // Dismissing the voice picker returns focus to whatever held it before
    // the sheet opened — the Name field — which pops the keyboard and drops
    // a cursor in it as if you'd asked to rename the alias. You didn't; you
    // picked a voice. Drop focus instead so the editor is just sitting there.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(pickerState.isOpen) {
        if (!pickerState.isOpen) focusManager.clearFocus(force = true)
    }

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
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openEditor(null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
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
                            voicePath = viewModel.voicePathFor(alias),
                            routedApps = mappings.filter { it.aliasName == alias.name },
                            onOpenEditor = { viewModel.openEditor(alias) },
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
            voicePath = editorState.voiceId.takeIf { it.isNotBlank() }
                ?.let { viewModel.voicePathFor(it, editorState.engine) },
            effects = effects,
            // Cannot delete the last remaining alias — the app's data model
            // assumes at least one alias (with one designated primary) exists
            // once any have been created. AliasViewModel.delete also defends
            // this invariant; hiding the button is the primary UX cue.
            canDelete = !editorState.isNew && aliases.size > 1,
            // Promoting is only meaningful for a saved alias that isn't
            // already primary. The star that used to do this on the card was
            // the only control there that wasn't "open the editor".
            canSetPrimary = !editorState.isNew &&
                editorState.originalName != null &&
                editorState.originalName != primaryAliasName,
            fallbackCandidates = viewModel.fallbackCandidates(),
            onSetPrimary = {
                editorState.originalName?.let { viewModel.setPrimary(it) }
            },
            onNameChange = viewModel::onEditorNameChange,
            onOpenVoicePicker = viewModel::openVoicePicker,
            onFallbackChange = viewModel::onEditorFallbackChange,
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

    if (pickerState.isOpen) {
        VoicePickerSheet(
            state = pickerState,
            tree = voiceTree,
            selectedVoiceId = editorState.voiceId,
            onQueryChange = viewModel::onPickerQueryChange,
            onSelectSource = viewModel::selectPickerSource,
            onSelectModel = viewModel::selectPickerModel,
            onPick = viewModel::pickVoice,
            onBack = viewModel::pickerBack,
            onDismiss = viewModel::dismissVoicePicker,
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
            title = { Text("Delete ${alias.name}?") },
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
 * The card is entirely a tap target that opens the editor — no edit pencil,
 * no trash icon, and no primary star. The star was a second way of saying
 * what the PRIMARY pill already says, and it was the only control on the
 * card that did something other than open the editor; promoting an alias
 * now lives in the editor alongside Delete, next to the rest of its
 * settings.
 */
@Composable
private fun AliasCard(
    alias: VoiceAlias,
    isPrimary: Boolean,
    effectName: String,
    voicePath: VoicePath,
    routedApps: List<AppAliasMapping>,
    onOpenEditor: () -> Unit,
    onOpenRouting: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenEditor),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            // The star used to sit in its own leading column, which left a
            // 48dp empty gutter down the left of every card and made the
            // whole row read lopsided. Inline with the name it reads as what
            // it is — a property of the alias, not a separate control column.
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                // Pill sits hard right rather than trailing the name:
                // it's a status badge, not part of the title, and a fixed
                // corner keeps it in the same place regardless of how long
                // the alias name is.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = alias.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (isPrimary) PrimaryPill()
                }
                Text(
                    text = voicePath.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Whether an alias needs the network decides whether it
                    // can fail, so it leads — and it's the only filled chip,
                    // which is what makes it readable at a glance in a row
                    // of otherwise identical outlines.
                    MetaChip(
                        text = if (voicePath.isCloud) "Cloud" else "On device",
                        filled = true,
                    )
                    MetaChip(text = "%.2f×".format(alias.speed))
                    MetaChip(text = effectName)
                }
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
            // The primary gets a synthetic "every app" tile in the same
            // slot real app icons occupy. It reads as a member of the same
            // family instead of a bare line of text, and says "all apps"
            // more directly than a chevron did.
            if (routedApps.isEmpty() && isPrimary) {
                AllAppsIcon(size = 30.dp)
                Spacer(Modifier.size(10.dp))
            }
            // Cap the icon stack — past four the count carries the meaning and
            // more icons just crowd the label out of the row.
            for (mapping in routedApps.take(MAX_STRIP_ICONS)) {
                AppIcon(packageName = mapping.packageName, size = 30.dp)
                Spacer(Modifier.size(5.dp))
            }
            if (routedApps.isNotEmpty()) {
                Spacer(Modifier.size(5.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        routedApps.isEmpty() -> "All unrouted apps"
                        routedApps.size == 1 -> "1 app"
                        else -> "${routedApps.size} apps"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (isPrimary && routedApps.isNotEmpty()) {
                    Text(
                        text = "+ all unrouted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // No chevron on the primary's "all unrouted" state: there the
            // strip is stating a fact about how routing works, not offering
            // a list to drill into. Where routes exist, the chevron is a
            // real affordance.
            if (routedApps.isNotEmpty()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Choose apps for $aliasName",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_STRIP_ICONS = 4

/**
 * Stand-in tile for "every app" on the primary alias's routing strip.
 *
 * Deliberately the same size and shape as a real launcher icon so it reads
 * as one more entry in the icon row rather than decoration.
 */
@Composable
private fun AllAppsIcon(size: Dp) {
    Surface(
        shape = RoundedCornerShape(size / 4),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 2.dp,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

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
    voicePath: VoicePath?,
    effects: List<Effect>,
    canDelete: Boolean,
    canSetPrimary: Boolean,
    fallbackCandidates: List<VoiceAlias>,
    onSetPrimary: () -> Unit,
    onNameChange: (String) -> Unit,
    onOpenVoicePicker: () -> Unit,
    onFallbackChange: (String?) -> Unit,
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
                text = if (state.isNew) "Create alias" else "Edit ${state.originalName}",
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

            // One row for both kinds of voice. The engine and voice used to
            // be separate dropdowns, which let them drift out of step and
            // forced the user to know which engine owned a voice before
            // they could pick it. Now the row opens the drill-down picker
            // and the two move together — see AliasViewModel.pickVoice.
            VoiceRowField(
                path = voicePath,
                isError = state.error is SaveError.MissingVoice,
                onClick = onOpenVoicePicker,
            )

            // Only cloud voices can fail for want of a network, so the
            // fallback only appears for them. Hidden entirely otherwise
            // rather than shown disabled — a permanently-greyed control on
            // every on-device alias would be noise.
            if (voicePath?.isCloud == true) {
                FallbackPicker(
                    selected = state.fallbackAliasName,
                    candidates = fallbackCandidates,
                    onPick = onFallbackChange,
                )
            }

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

            // Phonemization is an on-device concept: the text is turned
            // into phonemes locally by espeak before inference. A cloud
            // provider does its own text processing server-side and the
            // engine never sends this field, so offering it would be a
            // control that silently does nothing.
            if (voicePath?.isCloud != true) {
                PhonemizationLanguageDropdown(
                    selected = state.phonemizationLanguage,
                    onPick = onPhonemizationLanguageChange,
                )
            }

            // Save is the unconditional action and gets the full-width pill;
            // the two conditional ones share the row below it, equally
            // weighted so "Make primary" can't crowd Delete off a narrow
            // screen. There is no Cancel: the sheet already dismisses on
            // swipe, scrim tap and back.
            Button(
                onClick = onSave,
                shape = PillShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }

            if (canSetPrimary || canDelete) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canSetPrimary) {
                        OutlinedButton(
                            onClick = onSetPrimary,
                            shape = PillShape,
                            modifier = Modifier.weight(1f),
                        ) { Text("Make primary") }
                    }
                    if (canDelete) {
                        OutlinedButton(
                            onClick = onDelete,
                            shape = PillShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }
}

/**
 * The alias editor's single voice field.
 *
 * Shows the voice name with its collapsed path underneath — "Kitten Mini"
 * for an on-device voice, "Venice › ElevenLabs Turbo v2.5" for a cloud one.
 * Identical for both kinds: the editor deliberately does not branch on
 * whether a voice needs the network, which is what keeps one code path for
 * a hierarchy that is two levels deep on device and three in the cloud.
 */
@Composable
private fun VoiceRowField(
    path: VoicePath?,
    isError: Boolean,
    onClick: () -> Unit,
) {
    val border = when {
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Column {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = path?.voice ?: "Choose a voice",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (path == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (path != null) {
                        Text(
                            text = path.collapsed,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isError) {
            Text(
                text = "Pick a voice for this alias",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
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

/**
 * Picks the alias a cloud voice falls back to when the network is gone.
 *
 * Candidates are on-device aliases only — a cloud alias can't rescue
 * another cloud alias from the same dead network. "Don't fall back" stays
 * available for someone who would rather hear an error than an unexpected
 * voice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FallbackPicker(
    selected: String?,
    candidates: List<VoiceAlias>,
    onPick: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        if (candidates.isEmpty()) {
            Text(
                text = "No on-device alias to fall back to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected ?: "None",
                onValueChange = {},
                readOnly = true,
                label = { Text("Offline fallback") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (alias in candidates) {
                    DropdownMenuItem(
                        text = { Text(alias.name) },
                        onClick = { onPick(alias.name); expanded = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = { onPick(null); expanded = false },
                )
            }
        }
    }
}

/**
 * The "PRIMARY" badge. A small caps pill rather than an [AssistChip] —
 * AssistChip is a 32dp-tall interactive control, and using one for a
 * non-interactive label made it compete with the alias name it sits next to.
 */
@Composable
private fun PrimaryPill() {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = "PRIMARY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

/**
 * A compact metadata chip for the alias card.
 *
 * Hand-rolled rather than [AssistChip] for the same reason as [PrimaryPill]:
 * these are labels, not buttons (the whole card is the tap target), and the
 * Material chip's minimum height made three of them dominate the card.
 * [filled] marks the one chip that carries real information.
 */
@Composable
private fun MetaChip(text: String, filled: Boolean = false) {
    Surface(
        shape = PillShape,
        color = if (filled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        border = if (filled) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
            color = if (filled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        )
    }
}
