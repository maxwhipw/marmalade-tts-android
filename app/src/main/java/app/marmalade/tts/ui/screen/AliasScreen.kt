package app.marmalade.tts.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import app.marmalade.tts.data.Expressiveness
import app.marmalade.tts.data.SentencePause
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VoicePath
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.ui.components.MarmaladeCard
import app.marmalade.tts.ui.components.MarmaladeMenu
import app.marmalade.tts.ui.components.MarmaladeSheet
import app.marmalade.tts.ui.components.SpeedSlider
import app.marmalade.tts.ui.components.SteppedSliderTrack
import app.marmalade.tts.ui.theme.MarmaladeMotion
import app.marmalade.tts.ui.theme.MarmaladeToast
import app.marmalade.tts.ui.theme.MarmaladeToastInk
import app.marmalade.tts.ui.theme.depth
import kotlin.math.roundToInt

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
    val showSpeedWarning by viewModel.showSpeedWarning.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()

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

    // Leaving the screen with the picker open (a tab switch) keeps the VM and
    // the open sheet for the way back, but the voice sample stops here.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
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
                            // Keyed by name, so a new alias fades in, a deleted
                            // one fades out and the rest slide into place (as
                            // does a card the ordering above moves).
                            modifier = Modifier.animateItem(
                                fadeInSpec = MarmaladeMotion.base(),
                                placementSpec = MarmaladeMotion.slow(),
                                fadeOutSpec = MarmaladeMotion.fast(),
                            ),
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
            // primaryAliasId is an alias id, so this compares against editingId
            // — comparing it to originalName (a name) never matched, which left
            // "Make primary" showing on the alias that already was.
            canSetPrimary = !editorState.isNew &&
                editorState.editingId != null &&
                editorState.editingId != primaryAliasId,
            showSpeedWarning = showSpeedWarning,
            fallbackCandidates = viewModel.fallbackCandidates(),
            onSetPrimary = {
                editorState.editingId?.let { viewModel.setPrimary(it) }
            },
            onNameChange = viewModel::onEditorNameChange,
            onOpenVoicePicker = viewModel::openVoicePicker,
            onFallbackChange = viewModel::onEditorFallbackChange,
            onSpeedChange = viewModel::onEditorSpeedChange,
            onExpressivenessChange = viewModel::onEditorExpressivenessChange,
            onSentencePauseChange = viewModel::onEditorSentencePauseChange,
            onEffectChange = viewModel::onEditorEffectChange,
            onPhonemizationLanguageChange = viewModel::onEditorPhonemizationLanguageChange,
            onDelete = {
                pendingDelete = aliases.firstOrNull { it.id == editorState.editingId }
            },
            onSave = { viewModel.save() },
            onDismiss = viewModel::dismissEditor,
        )
    }

    if (pickerState.isOpen) {
        val previewPhrase = stringResource(R.string.voices_preview_phrase)
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
            previewState = previewState,
            canPreview = viewModel::canPreview,
            onTogglePreview = { voice ->
                // spokenName, not displayName — espeak reads a Kokoro flag
                // emoji out loud ("United States Adam").
                viewModel.togglePreview(voice, previewPhrase.format(voice.spokenName))
            },
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
    modifier: Modifier = Modifier,
) {
    // Dark mode gets the lab's card (hairline, soft shadow); light mode keeps
    // the plain Material card it had, by Max's pick in the #83 lab (G).
    if (MaterialTheme.depth.isDark) {
        MarmaladeCard(modifier = modifier.fillMaxWidth(), onClick = onOpenEditor) {
            AliasCardBody(alias, isPrimary, effectName, voicePath, routedApps, onOpenRouting)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth().clickable(onClick = onOpenEditor)) {
            AliasCardBody(alias, isPrimary, effectName, voicePath, routedApps, onOpenRouting)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AliasCardBody(
    alias: VoiceAlias,
    isPrimary: Boolean,
    effectName: String,
    voicePath: VoicePath,
    routedApps: List<AppAliasMapping>,
    onOpenRouting: () -> Unit,
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
            // Wraps rather than squeezing the last chip at large font sizes
            // or with a long effect name.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
    val shape = MaterialTheme.shapes.small
    val depth = MaterialTheme.depth

    // A non-primary alias with no routes has nothing to report, so it gets an
    // invitation instead of an empty state. The primary always has something
    // to say — it is the fallback for every unrouted app.
    if (routedApps.isEmpty() && !isPrimary) {
        // Ghost ink in dark, not a hairline: a dash broken into gaps needs a
        // little more weight than a solid edge to read at all. Light keeps
        // its original outline (#83 G).
        val outline = if (depth.isDark) depth.ghost else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
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
    //
    // Dark: the agent-cards lab's "pointer" row, a faint ink wash with a
    // hairline, so it reads as a panel set into the card rather than a second
    // card. Light keeps its original tonal panel (#83 G).
    Surface(
        shape = shape,
        color = if (depth.isDark) depth.hover else MaterialTheme.colorScheme.surfaceVariant,
        border = if (depth.isDark) BorderStroke(1.dp, depth.line) else null,
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
    // Orange with a small lift in light, as it always was; toast in dark,
    // where bright orange is off-brand, flat with a hairline like the real
    // tiles beside it (see AppIconTile).
    val dark = MaterialTheme.depth.isDark
    Surface(
        shape = RoundedCornerShape(size / 4),
        color = if (dark) MarmaladeToast else MaterialTheme.colorScheme.primary,
        shadowElevation = if (dark) 0.dp else 2.dp,
        border = if (dark) BorderStroke(1.dp, MaterialTheme.depth.line) else null,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = if (dark) MarmaladeToastInk else MaterialTheme.colorScheme.onPrimary,
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
    showSpeedWarning: Boolean,
    fallbackCandidates: List<VoiceAlias>,
    onSetPrimary: () -> Unit,
    onNameChange: (String) -> Unit,
    onOpenVoicePicker: () -> Unit,
    onFallbackChange: (String?) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onExpressivenessChange: (Float?) -> Unit,
    onSentencePauseChange: (SentencePause) -> Unit,
    onEffectChange: (String?) -> Unit,
    onPhonemizationLanguageChange: (String?) -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    MarmaladeSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                // Shared with the reader's alias dial (#71).
                SpeedSlider(
                    value = state.speed,
                    onValueChange = onSpeedChange,
                    label = speedLabel,
                    valueText = speedText,
                )
                // Time-stretched playback can outrun the slower engines'
                // rendering — the adaptive pre-roll covers most of it, but the
                // user should know why fast speech starts later and can still
                // hesitate between sentences. Whether to warn is decided per
                // engine-on-this-device by the ViewModel (measured/predicted
                // RTF × speed), not a static threshold — see
                // AliasViewModel.showSpeedWarning. The reader sheet mirrors it.
                if (showSpeedWarning) {
                    // The ⚠️ lead is composed here, not baked into the string,
                    // which the reader's alias dial shares.
                    Text(
                        text = "⚠️ " + stringResource(R.string.alias_speed_perf_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The pause between sentences (#96) is the app's own silence, so it
            // works for every on-device voice. Hidden for cloud voices: their
            // audio comes back a request at a time, and a pause per sentence
            // would mean a paid request per sentence.
            if (voicePath?.isCloud != true) {
                SentencePauseSlider(
                    value = state.sentencePause,
                    onChange = onSentencePauseChange,
                )
            }

            // Pocket samples its speech, so how lively it sounds is a choice
            // (its sampling temperature). The other engines are deterministic
            // and have nothing to set, so the block is hidden for them rather
            // than shown disabled — same reasoning as the fallback picker.
            if (Expressiveness.appliesTo(state.engine)) {
                ExpressivenessSlider(
                    value = state.expressiveness,
                    onChange = onExpressivenessChange,
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
            // ours to state. VITS Marmalade likewise: each voice pack fixes
            // its own language (Jenny is en-GB, the other packs aren't
            // English at all), so an "English only" note would be wrong.
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
 * The alias editor's pause between sentences (#96): four named stops on a
 * slider styled like Speed's (Max picked the slider over chips, 2026-10-23).
 * The label and TalkBack say the stop's name; the ends are named under the
 * track as on Expressiveness. A paragraph break gets 1.5× the stop's pause
 * ([SentencePause.paragraphMs]); the label doesn't spell that out.
 */
@Composable
private fun SentencePauseSlider(
    value: SentencePause,
    onChange: (SentencePause) -> Unit,
) {
    Column {
        val valueText = stringResource(sentencePauseName(value))
        Text(
            text = stringResource(R.string.alias_sentence_pause_label, valueText),
            style = MaterialTheme.typography.bodyMedium,
        )
        val haptics = LocalHapticFeedback.current
        val name = stringResource(R.string.alias_sentence_pause)
        Slider(
            value = value.ordinal.toFloat(),
            onValueChange = {
                val stop = SentencePause.entries[it.roundToInt().coerceIn(0, SentencePause.entries.size - 1)]
                if (stop != value) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(stop)
                }
            },
            valueRange = 0f..(SentencePause.entries.size - 1).toFloat(),
            // Slider's `steps` counts only the stops between the ends.
            steps = SentencePause.entries.size - 2,
            track = { SteppedSliderTrack(it) },
            modifier = Modifier.semantics {
                contentDescription = name
                stateDescription = valueText
            },
        )
        // The slider's state already speaks the stop, so TalkBack skips these.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        ) {
            Text(
                text = stringResource(sentencePauseName(SentencePause.entries.first())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(sentencePauseName(SentencePause.entries.last())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sentencePauseName(pause: SentencePause): Int = when (pause) {
    SentencePause.NATURAL -> R.string.alias_sentence_pause_natural
    SentencePause.SHORT -> R.string.alias_sentence_pause_short
    SentencePause.MEDIUM -> R.string.alias_sentence_pause_medium
    SentencePause.LONG -> R.string.alias_sentence_pause_long
}

/**
 * The alias editor's Expressiveness control (Pocket only).
 *
 * [value] null is "Default": the thumb rests on
 * [Expressiveness.POCKET_DEFAULT] and the label says "Default" rather than a
 * number, so changing the engine default later moves every untouched alias
 * with it. Dragging stores a number; "Reset to default" (shown only once one
 * is stored) hands back null.
 */
@Composable
private fun ExpressivenessSlider(
    value: Float?,
    onChange: (Float?) -> Unit,
) {
    Column {
        val valueText = if (value == null) {
            stringResource(R.string.alias_expressiveness_default)
        } else {
            stringResource(R.string.alias_expressiveness_value, value)
        }
        val steady = stringResource(R.string.alias_expressiveness_steady)
        val lively = stringResource(R.string.alias_expressiveness_lively)
        val description = stringResource(
            R.string.alias_expressiveness_content_description,
            steady,
            lively,
        )
        Text(
            text = stringResource(R.string.alias_expressiveness_label, valueText),
            style = MaterialTheme.typography.bodyMedium,
        )
        val haptics = LocalHapticFeedback.current
        val shown = value ?: Expressiveness.POCKET_DEFAULT
        Slider(
            value = shown,
            onValueChange = {
                if (it != shown) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(it)
            },
            valueRange = Expressiveness.MIN..Expressiveness.MAX,
            // One detent per STEP; Slider's `steps` counts only the ones
            // between the endpoints (0.2 to 0.7 has 4).
            steps = ((Expressiveness.MAX - Expressiveness.MIN) / Expressiveness.STEP).roundToInt() - 1,
            // A clean track, no stop marks (#84).
            track = { SteppedSliderTrack(it) },
            modifier = Modifier.semantics {
                contentDescription = description
                stateDescription = valueText
            },
        )
        // The slider's description already speaks both ends, so TalkBack
        // skips these.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        ) {
            Text(
                text = steady,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = lively,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Below the slider, not beside its label: it appears on the first
        // drag, and above the slider it would push the thumb out from under
        // the finger.
        if (value != null) {
            TextButton(onClick = { onChange(null) }) {
                Text(stringResource(R.string.alias_expressiveness_reset))
            }
        }
    }
}

/**
 * The alias editor's single voice field.
 *
 * Shows the voice name with its collapsed path underneath — "Kitten TTS Nano"
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
        // The raised step of the surface ramp with a hairline: it sits up off
        // the sheet like the lab's voice row. Only an error colours the edge.
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.depth.raised,
            border = BorderStroke(
                width = 1.dp,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.depth.line2
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
        MarmaladeMenu(
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
 * Engines that get a phonemization-language field at all. Kokoro and
 * Kitten can act on it; Pocket shows it disabled at English. Cloud and
 * VITS Marmalade are absent on purpose — see the call site.
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
 *
 * German and Bulgarian are the partial-support tier (Max, 2026-09-20):
 * the multilingual voices phonemize them intelligibly but accented, with
 * no trained voice of their own — hence the "(accented)" qualifier baked
 * into their labels. Detection can't pick them from text (the trigram
 * table is lockstep with the CLI), so they are offered here as an
 * explicit choice only. This list is Kokoro-only in practice: the other
 * phonemization engines use [KITTEN_PHONEMIZATION_LANGUAGES].
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
    "de" to R.string.alias_lang_de,
    "bg" to R.string.alias_lang_bg,
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
    /** [VoiceAlias.id] of the chosen fallback, or null for none. */
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
            // [selected] is an alias id; show that alias's name. An id that
            // matches no candidate (its alias was deleted, or has since become
            // a cloud alias) reads as "Don't fall back" — the router can't
            // fall back to it either.
            OutlinedTextField(
                value = candidates.firstOrNull { it.id == selected }?.name
                    ?: stringResource(R.string.alias_fallback_none),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.alias_offline_fallback)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            // Dressed like MarmaladeMenu (raised step, hairline, no tonal
            // tint), which can't stand in here: the exposed menu anchors to
            // its text field through this box's scope.
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = MaterialTheme.shapes.small,
                containerColor = MaterialTheme.depth.raised,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.depth.line2),
            ) {
                for (alias in candidates) {
                    DropdownMenuItem(
                        text = { Text(alias.name) },
                        onClick = { onPick(alias.id); expanded = false },
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
 *
 * Primary orange in light; toast in dark, where bright orange is off-brand.
 */
@Composable
private fun PrimaryPill() {
    val dark = MaterialTheme.depth.isDark
    Surface(
        shape = PillShape,
        color = if (dark) MarmaladeToast else MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = stringResource(R.string.alias_primary_pill),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (dark) MarmaladeToastInk else MaterialTheme.colorScheme.onPrimary,
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
 *
 * [filled] marks the one chip that carries real information: toast in light
 * (the scheme's primaryContainer), a toast wash with toast text in dark, so
 * it stays quieter than the solid PRIMARY pill above it. The rest are quiet
 * outlines: a hairline edge and dim text.
 */
@Composable
private fun MetaChip(
    text: String,
    filled: Boolean = false,
    /** Cloud glyph ahead of the label. Shared with the Speak screen's row. */
    leadingCloud: Boolean = false,
) {
    val depth = MaterialTheme.depth
    val dark = MaterialTheme.depth.isDark
    Surface(
        shape = PillShape,
        color = when {
            !filled -> Color.Transparent
            dark -> depth.selected
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        border = when {
            filled -> null
            dark -> BorderStroke(1.dp, depth.line2)
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        },
    ) {
        val ink = when {
            !filled -> depth.dim
            dark -> MarmaladeToast
            else -> MaterialTheme.colorScheme.onPrimaryContainer
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
