package app.marmalade.tts.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.LatencyBucket
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.latencyKeyFor
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.ui.MarmaladeIcons
import app.marmalade.tts.ui.components.languageDisplayName
import app.marmalade.tts.ui.components.qualityGradeLabelRes
import app.marmalade.tts.ui.theme.MarmaladeMotion
import app.marmalade.tts.ui.theme.depth

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoiceMetaDao.getAll() (Flow<List<VoiceMeta>>)
//     │   ──► filtered by VoicePickerViewModel against the installed
//     │       engine set, then grouped into the source › model › voice tree
//     │       shared with the alias editor's sheet (VoiceTree.kt).
//     ▼
//   VoicePickerViewModel.voiceTree    ──► drill-down lists
//   VoicePickerViewModel.pickerState  ──► which level is showing
//   VoicePickerViewModel.selectedId   ──► check icon on the matching row
//   VoicePickerViewModel.previewState ──► row sample button state
//
//   User taps a voice row
//     │
//     ▼
//   VoicePickerViewModel.selectVoice(id) ─► SettingsRepository.setDefaultVoiceId
//     │
//     ▼
//   onVoiceSelected() callback → MainActivity pops back to SpeakScreen
//
//   User taps a row's speaker (on-device voices only: a cloud sample would
//   spend provider credit unasked, #88)
//     │
//     ▼
//   VoicePickerViewModel.togglePreview(voice) ─► VoicePreviewer.toggle: play
//     "Hello, I'm <name>.", or stop it if that voice is the one playing
//
// This screen used to be one flat list grouped by engine with collapsible
// headers. That worked while every engine was on-device and had 8-53 voices;
// with a cloud provider configured, one header held ~186 voices from a dozen
// unrelated models. It now drills down exactly like the alias editor's picker,
// and keeps the two things that picker doesn't have: per-row audition and the
// engine-not-installed banner.
// -----------------------------------------------------------------------------

/** The full screen's horizontal gutter, tighter than the sheet's. */
private val ScreenGutter = 16.dp

/**
 * Voice picker — browse every installed voice and set the default.
 *
 * Tapping a voice persists the selection via `SettingsRepository` and
 * triggers [onVoiceSelected] so the host navigation can pop back to the
 * Speak screen.
 *
 * The sample button is disabled when the engine assets aren't installed
 * yet (model-missing state); the row itself stays tappable so users can
 * pick a default voice even before audio works end-to-end. Cloud voices have
 * no sample button at all (#88).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerScreen(
    onBack: () -> Unit,
    onVoiceSelected: () -> Unit,
    viewModel: VoicePickerViewModel = hiltViewModel(),
) {
    val tree by viewModel.voiceTree.collectAsStateWithLifecycle()
    val installedEngines by viewModel.installedEngines.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val pickerState by viewModel.pickerState.collectAsStateWithLifecycle()
    val latency by viewModel.voiceLatency.collectAsStateWithLifecycle()
    val contentReady by viewModel.contentReady.collectAsStateWithLifecycle()

    val modelMissingState = previewState as? PreviewState.ModelMissing

    // Re-probe engine install state every time the screen becomes the
    // active destination. The init block in the VM does an initial probe;
    // this catches the Voices → Engines → install → back-to-Voices flow.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    // A sample still playing stops when the screen goes.
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }

    // Opens on the source list; engine-scoped mode drills past it.
    LaunchedEffect(tree) {
        viewModel.setInitialDrill(tree)
    }

    // Engine-scoped mode (voices?engine=<name>, reached from the engine's
    // detail page): the tree is already filtered to that engine by the VM.
    val engineFilter = viewModel.engineFilter

    val source = tree.firstOrNull { it.name == pickerState.source }
    val model = source?.models?.firstOrNull { it.name == pickerState.model }

    // System back unwinds the drill-down before it leaves the screen —
    // otherwise three taps in, Back throws away all of them at once.
    // drillBack() returns false at its floor (top level, or the level
    // engine-scoped mode auto-drilled to) — fall through to onBack() there so
    // the gesture leaves the screen instead of being swallowed.
    BackHandler(enabled = !pickerState.atTopLevel()) {
        if (!viewModel.drillBack()) onBack()
    }

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            engineFilter?.let {
                                stringResource(
                                    R.string.voices_title_engine,
                                    displayNameForEngine(it),
                                )
                            } ?: stringResource(R.string.voices_title),
                            modifier = Modifier.semantics { heading() },
                        )
                        // Breadcrumb — always says where in the tree you are,
                        // so two voices with the same name stay distinguishable.
                        // `model` is resolved out of `source`, so a non-null
                        // model implies a non-null source.
                        val crumb = when {
                            pickerState.searching ->
                                stringResource(R.string.voices_crumb_searching)
                            model != null && source!!.models.size > 1 ->
                                stringResource(R.string.voices_crumb_path, source.name, model.name)
                            source != null -> source.name
                            else -> stringResource(R.string.voices_crumb_all_sources)
                        }
                        Text(
                            text = crumb,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.drillBack()) onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.voices_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Hold the body until the install probe and (in engine-scoped
            // mode) the auto-drill have settled — see
            // VoicePickerViewModel.contentReady. Rendering early flashed the
            // "no engines installed" empty state and the source list on the
            // way in. The top bar stays, so this reads as a normal transition.
            if (!contentReady) return@Column

            // Inline status hint when the engine signals missing assets.
            if (modelMissingState != null) {
                val engineDisplay = EngineCatalog.byName(modelMissingState.engineName)
                    ?.displayName
                    ?: stringResource(R.string.voices_engine_generic)
                Text(
                    text = stringResource(R.string.voices_engine_not_installed, engineDisplay),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Empty state: no engines installed at all (which is what the
            // v0.1.18 fix exposes — pre-fix the screen showed Kitten voices
            // regardless of install state, hiding this condition).
            if (tree.isEmpty() && installedEngines.isEmpty()) {
                Text(
                    text = stringResource(R.string.voices_no_engines_installed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                )
                return@Column
            }

            // Placeholder text disappears the moment the user types, so it
            // can't be the field's name — spell the name out for a screen
            // reader without adding a visible label.
            val searchLabel = stringResource(R.string.voices_search_placeholder)
            OutlinedTextField(
                value = pickerState.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(searchLabel) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenGutter, vertical = 8.dp)
                    .semantics { contentDescription = searchLabel },
            )

            val onPick: (VoiceMeta) -> Unit = { voice ->
                viewModel.selectVoice(voice.id)
                onVoiceSelected()
            }
            // A ModelMissing from one engine must not grey out another
            // engine's rows, so the check is per-voice rather than global.
            val previewEnabled: (VoiceMeta) -> Boolean =
                { modelMissingState?.engineName != it.engine }
            val playingId = (previewState as? PreviewState.Playing)?.voiceId
            val previewPhrase = stringResource(R.string.voices_preview_phrase)
            val onPreview: (VoiceMeta) -> Unit = { voice ->
                // spokenName, not displayName — espeak reads a Kokoro flag
                // emoji out loud ("United States Adam").
                viewModel.togglePreview(voice, previewPhrase.format(voice.spokenName))
            }
            val canPreview: (VoiceMeta) -> Boolean = viewModel::canPreview

            VoiceDrillLevels(pickerState, Modifier.fillMaxSize()) { shown ->
                // Resolved from [shown], not the title's source/model: a list
                // fading out keeps drawing its own level.
                val shownSource = tree.firstOrNull { it.name == shown.source }
                val shownModel = shownSource?.models?.firstOrNull { it.name == shown.model }
                when {
                    shown.searching -> SearchResults(
                        hits = remember(tree, shown.query) {
                            searchVoiceTree(tree, shown.query)
                        },
                        query = shown.query,
                        selectedId = selectedId,
                        playingId = playingId,
                        latency = latency,
                        canPreview = canPreview,
                        previewEnabled = previewEnabled,
                        onPick = onPick,
                        onPreview = onPreview,
                    )
                    tree.isEmpty() -> VoicePickerEmpty(
                        stringResource(R.string.voices_empty_none_installed),
                        ScreenGutter,
                    )
                    shownModel != null -> VoiceList(
                        voices = shownModel.voices,
                        selectedId = selectedId,
                        playingId = playingId,
                        canPreview = canPreview,
                        previewEnabled = previewEnabled,
                        onPick = onPick,
                        onPreview = onPreview,
                    )
                    shownSource != null -> VoiceModelList(
                        shownSource, latency, ScreenGutter, viewModel::selectModel,
                    )
                    else -> VoiceSourceList(tree, latency, ScreenGutter, viewModel::selectSource)
                }
            }
        }
    }
}

@Composable
private fun VoiceList(
    voices: List<VoiceMeta>,
    selectedId: String,
    playingId: String?,
    canPreview: (VoiceMeta) -> Boolean,
    previewEnabled: (VoiceMeta) -> Boolean,
    onPick: (VoiceMeta) -> Unit,
    onPreview: (VoiceMeta) -> Unit,
) {
    if (voices.isEmpty()) {
        VoicePickerEmpty(stringResource(R.string.voices_empty_model), ScreenGutter)
        return
    }
    // Language sections for a multi-language model (Kokoro's 9 locales, VITS
    // Marmalade's 6 languages); a single-language engine renders one unlabelled
    // section, i.e. exactly the flat list it had before.
    val sections = remember(voices) { groupVoicesByLanguage(voices) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for (section in sections) {
            if (sections.size > 1) {
                item(key = "lang-${section.languageCode}") {
                    VoiceLanguageHeader(section.languageCode, ScreenGutter)
                }
            }
            items(items = section.voices, key = { it.id }) { voice ->
                VoiceRow(
                    voice = voice,
                    isSelected = voice.id == selectedId,
                    isPreviewing = playingId == voice.id,
                    canPreview = canPreview(voice),
                    previewEnabled = previewEnabled(voice),
                    subtitle = supportingText(voice),
                    badge = null,
                    onClick = { onPick(voice) },
                    onPreview = { onPreview(voice) },
                )
                HorizontalDivider(color = MaterialTheme.depth.line)
            }
        }
    }
}

/**
 * Flat cross-level results. Each row carries its full `source › model` path,
 * which is what disambiguates a name that exists under several models.
 */
@Composable
private fun SearchResults(
    hits: List<VoiceSearchHit>,
    query: String,
    selectedId: String,
    playingId: String?,
    latency: Map<String, LatencyBucket>,
    canPreview: (VoiceMeta) -> Boolean,
    previewEnabled: (VoiceMeta) -> Boolean,
    onPick: (VoiceMeta) -> Unit,
    onPreview: (VoiceMeta) -> Unit,
) {
    if (hits.isEmpty()) {
        VoicePickerEmpty(stringResource(R.string.voices_empty_no_match, query), ScreenGutter)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = hits, key = { it.voice.id }) { (voice, path) ->
            // Hits glide into place as the query narrows them; the row and
            // its divider move as one.
            Column(
                Modifier.animateItem(
                    fadeInSpec = MarmaladeMotion.base(),
                    placementSpec = MarmaladeMotion.base(),
                    fadeOutSpec = MarmaladeMotion.fast(),
                ),
            ) {
                VoiceRow(
                    voice = voice,
                    isSelected = voice.id == selectedId,
                    isPreviewing = playingId == voice.id,
                    canPreview = canPreview(voice),
                    previewEnabled = previewEnabled(voice),
                    subtitle = path,
                    // Search is the one place the leaf rows come from different
                    // models, so it's the one place a per-voice badge says
                    // something the row above it didn't already.
                    badge = latency[latencyKeyFor(voice.id, voice.engine)],
                    onClick = { onPick(voice) },
                    onPreview = { onPreview(voice) },
                )
                HorizontalDivider(color = MaterialTheme.depth.line)
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceMeta,
    isSelected: Boolean,
    isPreviewing: Boolean,
    canPreview: Boolean,
    previewEnabled: Boolean,
    subtitle: String,
    badge: LatencyBucket?,
    onClick: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A choice of one, as in setup's voice step: TalkBack reads
            // "selected" on the picked voice, the tick stays silent.
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            // Without a sample button the row's end padding keeps the check
            // on the gutter; the button brings its own 48 dp target.
            .padding(start = ScreenGutter, end = if (canPreview) 4.dp else ScreenGutter)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading gender glyph — unicode is fine for v0.1 per the spec. A
        // minimum, not a fixed width, so a large font scale can't clip it.
        Text(
            text = genderGlyph(voice.gender),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.widthIn(min = 40.dp),
            textAlign = TextAlign.Center,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = voice.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VoiceCreditLine(voice)
        }

        VoiceLatencyChip(badge)

        // Trailing check icon for the currently-selected voice.
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 4.dp),
            )
        }

        // Sample button: a separate tap target from the row (hearing a voice
        // never picks it), play/stop, one at a time. Never on a cloud voice:
        // the sample would spend the user's provider credit unasked (#88).
        if (canPreview) {
            IconButton(
                onClick = onPreview,
                enabled = previewEnabled,
            ) {
                Icon(
                    // A speaker, as in the alias editor's picker (#86).
                    imageVector = if (isPreviewing) StopIcon else MarmaladeIcons.Speak,
                    // spokenName keeps TalkBack from announcing the flag emoji.
                    contentDescription = if (isPreviewing) {
                        stringResource(R.string.effects_stop_card, voice.spokenName)
                    } else {
                        stringResource(R.string.voices_preview, voice.spokenName)
                    },
                    tint = when {
                        isPreviewing -> MaterialTheme.colorScheme.primary
                        previewEnabled -> MaterialTheme.depth.faint
                        else -> MaterialTheme.depth.ghost
                    },
                )
            }
        }
    }
}

/** Cheap unicode mapping for gender — replaced by a mascot/icon in v0.2. */
private fun genderGlyph(gender: String?): String = when (gender) {
    "female" -> "👩" // 👩
    "male" -> "👨"   // 👨
    else -> "👤"     // 👤
}

/**
 * Row subtitle inside a model's voice list. The source and model are already
 * in the breadcrumb above, so this carries only what the drill-down didn't
 * say: gender, language, and — for a pack-based engine — the pack's audio
 * quality grade, because on VITS Marmalade the grade varies voice to voice and
 * it is the thing a listener most wants to know before picking one.
 *
 * The language is the localized name where we have one, falling back to the raw
 * tag for a cloud voice whose language we don't have a string for.
 */
@Composable
private fun supportingText(voice: VoiceMeta): String {
    val base = stringResource(
        R.string.voices_row_subtitle,
        genderLabel(voice.gender),
        languageDisplayName(voice.languageCode),
    )
    val quality = VitsVoiceCatalog.packVoiceOf(voice.id)?.quality ?: return base
    return "$base · ${stringResource(qualityGradeLabelRes(quality))}"
}

/**
 * Localized gender word for a row subtitle. The catalogs store the English
 * words `"female"` / `"male"`; anything else is shown as stored rather than
 * hidden, and a missing value reads as a dash.
 */
@Composable
private fun genderLabel(gender: String?): String = when (gender) {
    "female" -> stringResource(R.string.voices_gender_female)
    "male" -> stringResource(R.string.voices_gender_male)
    null -> stringResource(R.string.voices_gender_unknown)
    else -> gender
}

/**
 * Engine section-header label — the catalog's user-facing display name
 * (e.g. "Kitten TTS Nano") so the header matches the rest of the app
 * instead of the raw engine id. Falls back to a title-cased id for any
 * engine not present in [EngineCatalog].
 */
private fun displayNameForEngine(engineName: String): String = when (engineName) {
    // Not in EngineCatalog — hosted engine with no installable bundle.
    CloudApiVoiceCatalog.ENGINE -> CloudApiVoiceCatalog.DISPLAY_NAME
    else -> EngineCatalog.byName(engineName)?.displayName
        ?: engineName.replaceFirstChar { it.uppercase() }
}
