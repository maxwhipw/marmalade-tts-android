package app.marmalade.tts.ui.screen

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VoicePath
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.lang.LangDetector
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
    val primaryAliasId by viewModel.primaryAliasId.collectAsStateWithLifecycle()
    val effects by viewModel.effects.collectAsStateWithLifecycle()
    val voiceTree by viewModel.voiceTree.collectAsStateWithLifecycle()
    val pickerState by viewModel.pickerState.collectAsStateWithLifecycle()
    val voiceLatency by viewModel.voiceLatency.collectAsStateWithLifecycle()

    val mappings by routingViewModel.mappings.collectAsStateWithLifecycle()
    val installedApps by routingViewModel.installedApps.collectAsStateWithLifecycle()
    val aliasNames by routingViewModel.aliasNames.collectAsStateWithLifecycle()
    val routingSheet by routingViewModel.sheetState.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<VoiceAlias?>(null) }

    // Card order: primary first, then aliases that route at least one app, then
    // the unrouted ones. The DAO's createdAt ASC order breaks ties inside each
    // group (sortedWith is stable), so promoting or routing an alias moves it
    // without reshuffling its neighbours.
    val orderedAliases = remember(aliases, mappings, primaryAliasId) {
        val routedNames = mappings.mapTo(mutableSetOf()) { it.aliasId }
        aliases.sortedWith(
            compareBy(
                { it.name != primaryAliasId },
                { it.name !in routedNames },
            ),
        )
    }

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
                title = { Text(stringResource(R.string.alias_title)) },
                windowInsets = WindowInsets(0),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.openEditor(null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.alias_create))
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
                    items(items = orderedAliases, key = { it.name }) { alias ->
                        AliasCard(
                            alias = alias,
                            isPrimary = alias.id == primaryAliasId,
                            effectName = effects.firstOrNull { it.id == alias.effectId }?.name
                                ?: stringResource(R.string.alias_no_effect),
                            voicePath = viewModel.voicePathFor(alias),
                            routedApps = mappings.filter { it.aliasId == alias.id },
                            onOpenEditor = { viewModel.openEditor(alias) },
                            onOpenRouting = { routingViewModel.openSheet(alias.id, alias.name) },
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
                editorState.originalName != primaryAliasId,
            fallbackCandidates = viewModel.fallbackCandidates(),
            onSetPrimary = {
                editorState.editingId?.let { viewModel.setPrimary(it) }
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
            latency = voiceLatency,
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
            aliasNames = aliasNames,
            onQueryChange = routingViewModel::onQueryChange,
            onToggle = routingViewModel::toggle,
            onSave = routingViewModel::saveRouting,
            onDismiss = routingViewModel::dismissSheet,
        )
    }

    pendingDelete?.let { alias ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.alias_delete_title, alias.name)) },
            text = { Text(stringResource(R.string.alias_delete_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(alias.id)
                        pendingDelete = null
                        // The editor sheet is still open behind this dialog,
                        // editing the row we just deleted.
                        viewModel.dismissEditor()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.alias_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.alias_cancel))
                }
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
            text = stringResource(R.string.alias_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.alias_empty_body),
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
                    // The name takes all the slack so the pill is pushed to
                    // the edge. It used to be weight(fill = false) followed by
                    // a weighted Spacer, which made two weighted siblings
                    // split the leftover evenly — landing the pill mid-card.
                    Text(
                        text = alias.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
                        text = stringResource(
                            if (voicePath.isCloud) {
                                R.string.alias_chip_cloud
                            } else {
                                R.string.alias_chip_on_device
                            },
                        ),
                        filled = true,
                        leadingCloud = voicePath.isCloud,
                    )
                    MetaChip(text = stringResource(R.string.alias_speed_chip, alias.speed))
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
                text = stringResource(R.string.alias_route_apps_to, aliasName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Which apps are routed is otherwise conveyed by launcher icons alone —
    // nothing a screen reader can read. The names replace the icon row and
    // the "3 apps" count in a single announcement.
    val appNames = rememberAppLabels(routedApps.map { it.packageName })
    val routingSummary = when {
        appNames.isEmpty() -> stringResource(R.string.alias_routed_apps_unrouted_only)
        isPrimary -> stringResource(
            R.string.alias_routed_apps_with_unrouted,
            appNames.joinToString(", "),
        )
        else -> stringResource(R.string.alias_routed_apps, appNames.joinToString(", "))
    }

    // The primary's strip is a statement, not a control. Routing an app to
    // the primary changes nothing — it is already the fallback for every app
    // the user hasn't routed elsewhere — so opening the app picker from here
    // could only ever produce a no-op assignment. Non-primary aliases keep
    // the affordance.
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (isPrimary) Modifier else Modifier.clickable(onClick = onClick))
            .semantics(mergeDescendants = true) { contentDescription = routingSummary },
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
                    text = if (routedApps.isEmpty()) {
                        stringResource(R.string.alias_all_unrouted_apps)
                    } else {
                        pluralStringResource(
                            R.plurals.alias_app_count,
                            routedApps.size,
                            routedApps.size,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (isPrimary && routedApps.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.alias_plus_all_unrouted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // No chevron on the primary at all — nothing to drill into when
            // the strip isn't tappable. Elsewhere it marks a real affordance,
            // which an alias with no routes doesn't have either.
            if (routedApps.isNotEmpty() && !isPrimary) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(
                        R.string.alias_choose_apps_for,
                        aliasName,
                    ),
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
                text = if (state.isNew) {
                    stringResource(R.string.alias_create)
                } else {
                    stringResource(R.string.alias_edit_title, state.originalName.orEmpty())
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.alias_name_label)) },
                singleLine = true,
                supportingText = {
                    Text(
                        text = stringResource(
                            errorTextFor(state.error) ?: R.string.alias_name_help,
                        ),
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
                    selected = state.fallbackAliasId,
                    candidates = fallbackCandidates,
                    onPick = onFallbackChange,
                )
            }

            Column {
                val speedText = stringResource(R.string.alias_speed_chip, state.speed)
                val speedLabel = stringResource(R.string.alias_speed)
                Text(
                    text = stringResource(R.string.alias_speed_label, speedText),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.speed,
                    onValueChange = onSpeedChange,
                    valueRange = VoiceAlias.MIN_SPEED..VoiceAlias.MAX_SPEED,
                    // 15 steps between 0.5 and 2.0 = 0.1x increments
                    // (Slider's `steps` excludes the endpoints).
                    steps = 14,
                    modifier = Modifier.semantics {
                        contentDescription = speedLabel
                        stateDescription = speedText
                    },
                )
            }

            EffectPicker(
                effects = effects,
                selectedId = state.effectId,
                onPick = onEffectChange,
            )

            // Kokoro is multilingual and phonemizes through espeak, so
            // every entry changes its audio: it gets the full list.
            //
            // Kitten's model is trained on English, but it phonemizes
            // through espeak too, so it gets the two entries that mean
            // something for it — English, or auto-detect, which reads a
            // non-English utterance with that language's espeak rules in
            // the Kitten voice (accented) unless an installed Kokoro
            // voice of the language can take it instead.
            //
            // Pocket doesn't phonemize through espeak at all, so its
            // control is disabled at English rather than removed: the
            // fact that the language is fixed is itself worth showing,
            // and a control that vanishes between voices reads as a
            // missing feature.
            //
            // Cloud voices have no control at all: the provider does its
            // own text processing server-side, so there is no language of
            // ours to state.
            if (state.engine in PHONEMIZATION_ENGINES) {
                val kokoro = state.engine == KokoroDirectVoiceCatalog.ENGINE
                val kitten = state.engine == KittenDirectVoiceCatalog.ENGINE
                PhonemizationLanguageDropdown(
                    selected = when {
                        kokoro -> state.phonemizationLanguage
                        // A null column is English on Kitten, not
                        // auto-detect: detection is opt-in there.
                        kitten -> state.phonemizationLanguage ?: "en-us"
                        else -> "en-us"
                    },
                    onPick = onPhonemizationLanguageChange,
                    enabled = kokoro || kitten,
                    entries = if (kokoro) PHONEMIZATION_LANGUAGES else KITTEN_PHONEMIZATION_LANGUAGES,
                    note = when {
                        kokoro -> null
                        kitten -> R.string.alias_lang_engine_accented
                        else -> R.string.alias_lang_engine_english_only
                    },
                )
            }

            // No Cancel: the sheet already dismisses on swipe, scrim tap and
            // back, so it was a fourth control earning nothing.
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
                    ) { Text(stringResource(R.string.alias_delete)) }
                }
                Spacer(Modifier.weight(1f))
                if (canSetPrimary) {
                    TextButton(onClick = onSetPrimary) {
                        Text(stringResource(R.string.alias_make_primary))
                    }
                }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onSave) { Text(stringResource(R.string.alias_save)) }
            }
        }
    }
}

/**
 * The alias editor's single voice field.
 *
 * Shows the voice name with its collapsed path underneath — "Kitten Nano"
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
    PickerField(
        label = stringResource(R.string.alias_voice_label),
        value = path?.voice ?: stringResource(R.string.alias_voice_placeholder),
        supporting = path?.collapsed,
        isPlaceholder = path == null,
        isError = isError,
        errorText = stringResource(R.string.alias_voice_error),
        onClick = onClick,
    )
}

/**
 * A field that only looks like one: label, current value, chevron, and the
 * whole surface as the tap target.
 *
 * Every choice in the editor that opens its own picker uses this instead of a
 * read-only [OutlinedTextField]. A read-only text field still takes focus and
 * pops the keyboard, and only its trailing icon answers a tap — so the field
 * you obviously meant to press does nothing.
 */
@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    supporting: String? = null,
    isPlaceholder: Boolean = false,
    isError: Boolean = false,
    errorText: String? = null,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        // The label sits outside the tappable Surface, so a screen reader
        // landing on the control would otherwise announce the value alone
        // ("Aria", not "Voice: Aria"). Name the control explicitly.
        val spoken = if (supporting != null) "$value, $supporting" else value
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label: $spoken" },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isPlaceholder) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (supporting != null) {
                        Text(
                            text = supporting,
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
        if (isError && errorText != null) {
            Text(
                text = errorText,
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
    val noEffect = stringResource(R.string.alias_no_effect)
    val selectedLabel = effects.firstOrNull { it.id == selectedId }?.name ?: noEffect
    PickerField(
        label = stringResource(R.string.alias_effect_label),
        value = selectedLabel,
        isPlaceholder = selectedId == null,
        onClick = { showPicker = true },
    )
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.alias_pick_effect)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    EffectPickerRow(noEffect) { onPick(null); showPicker = false }
                    for (effect in effects) {
                        EffectPickerRow(effect.name) { onPick(effect.id); showPicker = false }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.alias_cancel))
                }
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
 * Per-alias espeak language override. The default is auto-detect: each
 * utterance's own language decides how it is phonemized, without moving
 * the voice. A stored null means the same thing, so the rows written
 * before detection existed need no migration.
 *
 * A specific code forces espeak's language for every synthesis call on
 * the alias, whatever the voice — a Japanese voice reading en-us is
 * accented English, which is a legitimate thing to ask for, so the list
 * is never filtered by the voice's own language.
 *
 * [enabled] false renders the standard disabled field: greyed, no focus,
 * menu unreachable. That's Pocket, which is pinned to English (US).
 *
 * [entries] is the offered subset — the full list for Kokoro, English
 * and auto-detect for Kitten. [note] is the supporting line under the
 * field, absent on Kokoro where the entries speak for themselves.
 *
 * The offered codes are the espeak languages Kokoro's voice set covers,
 * which is not the same list `espeakVoiceFor()` returns. Adding a
 * language requires the matching espeak-ng-data subdirectory, which we
 * always have — we ship the full data tree.
 */
@Composable
private fun PhonemizationLanguageDropdown(
    selected: String?,
    onPick: (String?) -> Unit,
    enabled: Boolean = true,
    entries: List<Pair<String?, Int>> = PHONEMIZATION_LANGUAGES,
    @StringRes note: Int? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = phonemizationLanguageDisplayName(selected),
            onValueChange = { /* read-only */ },
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.alias_phonemization_language)) },
            supportingText = note?.let { { Text(stringResource(it)) } },
            trailingIcon = {
                // The text field's `enabled` does not reach its slots, so
                // the icon has to be disabled in its own right or a greyed
                // field would still open the menu.
                IconButton(onClick = { expanded = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(
                            R.string.alias_pick_phonemization_language,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((code, labelRes) in entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
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
 * Engines that get a phonemization-language field at all. Kokoro is the
 * only one that can act on it; the rest show it disabled at English.
 * Cloud is absent on purpose — see the call site.
 */
private val PHONEMIZATION_ENGINES: Set<String> = setOf(
    KokoroDirectVoiceCatalog.ENGINE,
    KittenDirectVoiceCatalog.ENGINE,
    PocketVoiceCatalog.ENGINE,
    PocketDevVoiceCatalog.ENGINE,
)

/**
 * Code-to-label pairs. The first entry is auto-detect, the default: it
 * clears any user override and lets each utterance's own language decide.
 *
 * Espeak codes follow espeak-ng-data's directory naming (`en-us`,
 * `pt-br`, etc.). `EspeakPhonemizer.normalizeVoice` maps the codes
 * whose espeak voice file is named differently (`fr-fr` → `fr`,
 * `en-gb` → `en`) before they reach `SetVoiceByName`.
 *
 * Kokoro's Mandarin voices are absent on purpose: they phonemize Han
 * text through `lexicon-zh.txt`, not espeak, and espeak-cmn produces
 * IPA the model can't read (see KokoroDirectVoiceCatalog.espeakVoiceFor).
 */
private val PHONEMIZATION_LANGUAGES: List<Pair<String?, Int>> = listOf(
    LangDetector.AUTO to R.string.alias_lang_autodetect,
    "en-us" to R.string.alias_lang_en_us,
    "en-gb" to R.string.alias_lang_en_gb,
    "es" to R.string.alias_lang_es,
    "fr-fr" to R.string.alias_lang_fr_fr,
    "hi" to R.string.alias_lang_hi,
    "it" to R.string.alias_lang_it,
    "ja" to R.string.alias_lang_ja,
    "pt-br" to R.string.alias_lang_pt_br,
)

/**
 * Kitten's offered subset. Its model reads en-us IPA, so a specific
 * non-English code would be a preference for permanently accented
 * speech — not a thing to offer. Auto-detect is: it only leaves English
 * for an utterance that is itself not English, and only when no
 * installed Kokoro voice of that language can take it instead.
 */
private val KITTEN_PHONEMIZATION_LANGUAGES: List<Pair<String?, Int>> = listOf(
    "en-us" to R.string.alias_lang_en_us,
    LangDetector.AUTO to R.string.alias_lang_autodetect,
)

/** A stored null is auto-detect, so it displays as auto-detect. */
@Composable
private fun phonemizationLanguageDisplayName(code: String?): String {
    val effective = code ?: LangDetector.AUTO
    return PHONEMIZATION_LANGUAGES.firstOrNull { it.first == effective }
        ?.second?.let { stringResource(it) }
        ?: effective // unrecognised override — show the raw code rather than hide it
}

@StringRes
private fun errorTextFor(error: SaveError?): Int? = when (error) {
    SaveError.InvalidName -> R.string.alias_name_help
    SaveError.NameTaken -> R.string.alias_error_name_taken
    SaveError.MissingVoice -> R.string.alias_error_missing_voice
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
                text = stringResource(R.string.alias_no_fallback_candidates),
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
                value = selected ?: stringResource(R.string.alias_fallback_none),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.alias_offline_fallback)) },
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
                    text = { Text(stringResource(R.string.alias_fallback_none)) },
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
            text = stringResource(R.string.alias_primary_pill),
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
private fun MetaChip(
    text: String,
    filled: Boolean = false,
    /** Cloud glyph ahead of the label. Shared with the Speak screen's row. */
    leadingCloud: Boolean = false,
) {
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
        val ink = if (filled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        ) {
            if (leadingCloud) {
                CloudMark(tint = ink, contentDescription = null)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                color = ink,
            )
        }
    }
}
