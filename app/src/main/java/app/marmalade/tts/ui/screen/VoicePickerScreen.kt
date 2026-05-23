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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoiceMetaDao.getByEngine("kitten") (Flow<List<VoiceMeta>>)
//     │
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
    val selectedId by viewModel.selectedId.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()

    val modelMissingState = previewState as? PreviewState.ModelMissing
    val modelMissing = modelMissingState != null

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Voices") },
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
                    text = "$engineDisplay engine not installed yet — install it from Settings → Engines to enable previews.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Group by engine so the user can see at a glance which engine
            // each voice belongs to — and so they can pick "any kokoro
            // voice" vs "any kitten voice" without rummaging a flat list.
            val groupedByEngine = voices.groupBy { it.engine }
            // Sort engines so the most-installed-likely (kokoro, then kitten)
            // surfaces first. Anything else falls in alphabetical order.
            val engineOrder = listOf("kokoro", "kitten")
            val orderedEngines = engineOrder.filter { it in groupedByEngine.keys } +
                groupedByEngine.keys.filter { it !in engineOrder }.sorted()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                orderedEngines.forEach { engineName ->
                    val engineVoices = groupedByEngine[engineName].orEmpty()
                    item(key = "header-$engineName") {
                        Text(
                            text = displayNameForEngine(engineName),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(items = engineVoices, key = { it.id }) { voice ->
                        VoiceRow(
                            voice = voice,
                            isSelected = voice.id == selectedId,
                            isPreviewing = (previewState as? PreviewState.Playing)?.voiceId == voice.id,
                            previewEnabled = !modelMissing,
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
    return "${voice.engine} · $gender · ${voice.languageCode}"
}

/** Title-case the engine name for the section header. */
private fun displayNameForEngine(engineName: String): String = when (engineName) {
    "kokoro" -> "Kokoro"
    "kitten" -> "Kitten"
    else -> engineName.replaceFirstChar { it.uppercase() }
}
