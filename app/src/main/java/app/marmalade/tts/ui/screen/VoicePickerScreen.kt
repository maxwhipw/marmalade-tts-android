package app.marmalade.tts.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.LatencyBucket
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.latencyKeyFor
import app.marmalade.tts.install.EngineCatalog

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
//   VoicePickerViewModel.previewState ──► row Preview button state
//
//   User taps a voice row
//     │
//     ▼
//   VoicePickerViewModel.selectVoice(id) ─► SettingsRepository.setDefaultVoiceId
//     │
//     ▼
//   onVoiceSelected() callback → MainActivity pops back to SpeakScreen
//
//   User taps Preview
//     │
//     ▼
//   VoicePickerViewModel.preview(voice) ─► Synthesizer.speak("Hello, I'm <name>.")
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
 * The Preview button is disabled when the engine assets aren't installed
 * yet (model-missing state); the row itself stays tappable so users can
 * pick a default voice even before audio works end-to-end.
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

    val modelMissingState = previewState as? PreviewState.ModelMissing

    // Re-probe engine install state every time the screen becomes the
    // active destination. The init block in the VM does an initial probe;
    // this catches the Voices → Engines → install → back-to-Voices flow.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

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
    BackHandler(enabled = !pickerState.atTopLevel()) { viewModel.drillBack() }

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
                            engineFilter?.let { "${displayNameForEngine(it)} voices" } ?: "Voices",
                        )
                        // Breadcrumb — always says where in the tree you are,
                        // so two voices with the same name stay distinguishable.
                        // `model` is resolved out of `source`, so a non-null
                        // model implies a non-null source.
                        val crumb = when {
                            pickerState.searching -> "Searching every voice"
                            model != null && source!!.models.size > 1 ->
                                "${source.name} › ${model.name}"
                            source != null -> source.name
                            else -> "All sources"
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
                            contentDescription = "Back",
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
            // Inline status hint when the engine signals missing assets.
            if (modelMissingState != null) {
                val engineDisplay = EngineCatalog.byName(modelMissingState.engineName)
                    ?.displayName
                    ?: "TTS"
                Text(
                    text = "$engineDisplay engine not installed yet — install it from the Engines tab to enable previews.",
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
                    text = "No engines installed yet. Open the Engines tab to install Kokoro or Kitten — voices will appear here once you do.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                )
                return@Column
            }

            OutlinedTextField(
                value = pickerState.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search all voices") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenGutter, vertical = 8.dp),
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

            when {
                pickerState.searching -> SearchResults(
                    hits = remember(tree, pickerState.query) {
                        searchVoiceTree(tree, pickerState.query)
                    },
                    query = pickerState.query,
                    selectedId = selectedId,
                    playingId = playingId,
                    latency = latency,
                    previewEnabled = previewEnabled,
                    onPick = onPick,
                    onPreview = viewModel::preview,
                )
                tree.isEmpty() -> VoicePickerEmpty("No voices installed yet", ScreenGutter)
                model != null -> VoiceList(
                    voices = model.voices,
                    selectedId = selectedId,
                    playingId = playingId,
                    previewEnabled = previewEnabled,
                    onPick = onPick,
                    onPreview = viewModel::preview,
                )
                source != null -> VoiceModelList(
                    source, latency, ScreenGutter, viewModel::selectModel,
                )
                else -> VoiceSourceList(tree, latency, ScreenGutter, viewModel::selectSource)
            }
        }
    }
}

@Composable
private fun VoiceList(
    voices: List<VoiceMeta>,
    selectedId: String,
    playingId: String?,
    previewEnabled: (VoiceMeta) -> Boolean,
    onPick: (VoiceMeta) -> Unit,
    onPreview: (VoiceMeta) -> Unit,
) {
    if (voices.isEmpty()) {
        VoicePickerEmpty("This model has no installed voices", ScreenGutter)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = voices, key = { it.id }) { voice ->
            VoiceRow(
                voice = voice,
                isSelected = voice.id == selectedId,
                isPreviewing = playingId == voice.id,
                previewEnabled = previewEnabled(voice),
                subtitle = supportingText(voice),
                badge = null,
                onClick = { onPick(voice) },
                onPreview = { onPreview(voice) },
            )
            HorizontalDivider()
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
    previewEnabled: (VoiceMeta) -> Boolean,
    onPick: (VoiceMeta) -> Unit,
    onPreview: (VoiceMeta) -> Unit,
) {
    if (hits.isEmpty()) {
        VoicePickerEmpty("No voices match \"$query\"", ScreenGutter)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = hits, key = { it.voice.id }) { (voice, path) ->
            VoiceRow(
                voice = voice,
                isSelected = voice.id == selectedId,
                isPreviewing = playingId == voice.id,
                previewEnabled = previewEnabled(voice),
                subtitle = path,
                // Search is the one place the leaf rows come from different
                // models, so it's the one place a per-voice badge says
                // something the row above it didn't already.
                badge = latency[latencyKeyFor(voice.id, voice.engine)],
                onClick = { onPick(voice) },
                onPreview = { onPreview(voice) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceMeta,
    isSelected: Boolean,
    isPreviewing: Boolean,
    previewEnabled: Boolean,
    subtitle: String,
    badge: LatencyBucket?,
    onClick: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading gender glyph — unicode is fine for v0.1 per the spec.
        Text(
            text = genderGlyph(voice.gender),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.width(40.dp),
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
        }

        VoiceLatencyChip(badge)

        // Trailing check icon for the currently-selected voice.
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 4.dp),
            )
        }

        // Preview button — separate tap target from the row click.
        IconButton(
            onClick = onPreview,
            enabled = previewEnabled,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = if (isPreviewing) {
                    "Previewing ${voice.displayName}"
                } else {
                    "Preview ${voice.displayName}"
                },
                tint = if (isPreviewing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
 * say: gender and language.
 */
private fun supportingText(voice: VoiceMeta): String =
    "${voice.gender ?: "—"} · ${voice.languageCode}"

/**
 * Engine section-header label — the catalog's user-facing display name
 * (e.g. "Kitten Mini (v0.8)") so the header matches the rest of the app
 * instead of the raw engine id. Falls back to a title-cased id for any
 * engine not present in [EngineCatalog].
 */
private fun displayNameForEngine(engineName: String): String = when (engineName) {
    // Not in EngineCatalog — hosted engine with no installable bundle.
    CloudApiVoiceCatalog.ENGINE -> CloudApiVoiceCatalog.DISPLAY_NAME
    else -> EngineCatalog.byName(engineName)?.displayName
        ?: engineName.replaceFirstChar { it.uppercase() }
}
