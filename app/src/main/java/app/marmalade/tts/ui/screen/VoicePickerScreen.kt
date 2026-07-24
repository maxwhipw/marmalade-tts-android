package app.marmalade.tts.ui.screen

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.install.EngineCatalog

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoiceMetaDao.getAll() (Flow<List<VoiceMeta>>)
//     │   ──► filtered by VoicePickerViewModel against the installed
//     │       engine set (see v0.1.18 — pre-fix this screen hardcoded
//     │       getByEngine("kitten") and showed those rows even when the
//     │       Kitten engine wasn't installed).
//     ▼
//   VoicePickerViewModel.voices  ───► LazyColumn rows
//   VoicePickerViewModel.selectedId ► check icon on the matching row
//   VoicePickerViewModel.previewState ► row Preview button state
//
//   User taps a row
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
// -----------------------------------------------------------------------------

/**
 * Voice picker — lists the 8 Kitten voices with per-row preview.
 *
 * Tapping a row persists the selection via [SettingsRepository] and
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
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val installedEngines by viewModel.installedEngines.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val expandedEngines by viewModel.expandedEngines.collectAsStateWithLifecycle()

    val modelMissingState = previewState as? PreviewState.ModelMissing
    val modelMissing = modelMissingState != null

    // Re-probe engine install state every time the screen becomes the
    // active destination. The init block in the VM does an initial probe;
    // this catches the Voices → Engines → install → back-to-Voices flow.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // Engine-scoped mode (voices?engine=<name>, reached from the engine's
    // detail page): title names the engine and the list drops the
    // per-engine section headers — every row belongs to the same engine.
    val engineFilter = viewModel.engineFilter

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(engineFilter?.let { "${displayNameForEngine(it)} voices" } ?: "Voices")
                },
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
            if (voices.isEmpty() && installedEngines.isEmpty()) {
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

            // Group by engine so the user can see at a glance which engine
            // each voice belongs to — and so they can pick "any kokoro
            // voice" vs "any kitten voice" without rummaging a flat list.
            // remember(voices): the grouping/ordering shouldn't recompute
            // on every preview-chip recomposition.
            val (groupedByEngine, orderedEngines) = remember(voices) {
                val grouped = voices.groupBy { it.engine }
                // Recommended-first ordering, from the catalog's real engine
                // ids (a previous hardcoded listOf("kokoro", "kitten") never
                // matched the versioned ids, so everything silently fell to
                // alphabetical — Kitten above the recommended Kokoro).
                val engineOrder = EngineCatalog.all.map { it.name }
                val ordered = engineOrder.filter { it in grouped.keys } +
                    grouped.keys.filter { it !in engineOrder }.sorted()
                grouped to ordered
            }

            // Seed initial expansion: open the engine that owns the
            // currently-selected voice; everything else starts collapsed
            // so a user with all engines installed isn't scrolling past
            // 240+ voices.
            LaunchedEffect(groupedByEngine, selectedId) {
                viewModel.setInitialExpansion(groupedByEngine, selectedId)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                orderedEngines.forEach { engineName ->
                    val engineVoices = groupedByEngine[engineName].orEmpty()
                    // Scoped mode has a single engine whose name is already
                    // in the top bar — skip the header + collapse machinery.
                    val isExpanded = engineFilter != null || engineName in expandedEngines
                    if (engineFilter == null) {
                        item(key = "header-$engineName") {
                            EngineSectionHeader(
                                name = displayNameForEngine(engineName),
                                voiceCount = engineVoices.size,
                                isExpanded = isExpanded,
                                onClick = { viewModel.toggleEngineExpanded(engineName) },
                            )
                        }
                    }
                    if (isExpanded) {
                        items(items = engineVoices, key = { it.id }) { voice ->
                            VoiceRow(
                                voice = voice,
                                isSelected = voice.id == selectedId,
                                isPreviewing = (previewState as? PreviewState.Playing)?.voiceId == voice.id,
                                // Only disable preview for rows of the engine
                                // that actually failed — a Kitten ModelMissing
                                // shouldn't grey out installed Kokoro rows.
                                previewEnabled = modelMissingState?.engineName != voice.engine,
                                onClick = {
                                    viewModel.selectVoice(voice.id)
                                    onVoiceSelected()
                                },
                                onPreview = { viewModel.preview(voice) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tappable engine section header. Shows the engine's display name + voice
 * count and toggles the section's expanded state on click. Uses a Row so
 * the chevron icon can sit at the trailing edge without separate gravity
 * hacks.
 */
@Composable
private fun EngineSectionHeader(
    name: String,
    voiceCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$name ($voiceCount)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            // Filled.ArrowDropDown is in the default icon set (Filled.ExpandLess/More
            // require the material-icons-extended artifact, which we don't pull in).
            // Rotated 180° via Modifier when collapsed → arrow points right ("tap
            // to expand"); pointed-down when expanded ("tap to collapse").
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = if (isExpanded) "Collapse $name" else "Expand $name",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { rotationZ = if (isExpanded) 180f else 0f },
        )
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceMeta,
    isSelected: Boolean,
    isPreviewing: Boolean,
    previewEnabled: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading gender glyph — unicode is fine for v0.1 per the spec.
        Text(
            text = genderGlyph(voice.gender),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center,
        )

        Column(modifier = Modifier
            .weight(1f)
            .padding(horizontal = 8.dp)) {
            Text(
                text = voice.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = supportingText(voice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                contentDescription = if (isPreviewing) "Previewing ${voice.displayName}" else "Preview ${voice.displayName}",
                tint = if (isPreviewing) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun supportingText(voice: VoiceMeta): String {
    val gender = voice.gender ?: "—"
    // Engine is omitted here — each voice already sits under its engine
    // section header, so repeating it (and the raw engine id) on every row
    // is redundant clutter.
    return "$gender · ${voice.languageCode}"
}

/**
 * Engine section-header label — the catalog's user-facing display name
 * (e.g. "Kitten Mini (v0.8)") so the header matches the rest of the app
 * instead of the raw engine id. Falls back to a title-cased id for any
 * engine not present in [EngineCatalog].
 */
private fun displayNameForEngine(engineName: String): String = when (engineName) {
    // Not in EngineCatalog — hosted engine with no installable bundle.
    CloudApiVoiceCatalog.ENGINE -> "Cloud API (Venice)"
    else -> EngineCatalog.byName(engineName)?.displayName
        ?: engineName.replaceFirstChar { it.uppercase() }
}
