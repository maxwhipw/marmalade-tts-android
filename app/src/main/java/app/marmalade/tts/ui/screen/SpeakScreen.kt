package app.marmalade.tts.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.data.db.VoiceAlias

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   User types in the OutlinedTextField
//     │
//     ▼
//   SpeakViewModel.onTextChanged(value)
//     │
//     ▼
//   SpeakViewModel.text (StateFlow<String>) ──► OutlinedTextField value param
//
//   User taps "Speak"
//     │
//     ▼
//   SpeakViewModel.speak()
//     │
//     ├── Synthesizer.speak(text, voiceId)
//     │     │
//     │     └── KittenEngine.synthesize + AudioTrack playback
//     │
//     ▼
//   SpeakViewModel.playbackState ──► mascot drawable, status text, button enable
//
//   User taps voice chip OR top-bar Voices icon
//     │
//     ▼
//   onNavigateToVoices()  ──► MainActivity swaps to VoicePickerScreen
//
//   User taps an alias chip
//     │
//     ▼
//   SpeakViewModel.applyAlias(name)
//     │
//     ├── VoiceAliasDao.findByName ─► alias row
//     ├── SettingsRepository.setDefaultVoiceId(alias.voiceId)
//     └── activeAlias = name  ──► FilterChip.selected = true
//
//   User taps a voice manually in the picker
//     │
//     ▼
//   defaultVoiceId emits a value ≠ the one applyAlias set
//     │
//     ▼
//   SpeakViewModel clears activeAlias ──► FilterChip selection clears
//
//   User taps "Create alias" trailing chip
//     │
//     ▼
//   onNavigateToAliases()  ──► alias editor screen
// -----------------------------------------------------------------------------

/**
 * Primary screen — the user's "type something, hear it spoken" surface.
 *
 * Layout (top to bottom):
 *  - Top app bar: title "marmalade-tts", trailing IconButton → voices.
 *  - Mascot (~64dp) — `mascot_speaking` while audio plays, `mascot_happy` otherwise.
 *  - OutlinedTextField, multi-line (~5 lines visible).
 *  - AssistChip showing the current voice — tap to navigate to picker.
 *  - LazyRow of FilterChips, one per saved alias, with a trailing
 *    "Create alias" AssistChip. Empty alias list ⇒ only the create chip.
 *  - "Speak" Button — disabled when text is blank or model isn't installed.
 *  - Status line below the button mirroring the ViewModel state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakScreen(
    onNavigateToVoices: () -> Unit,
    onNavigateToEngines: () -> Unit,
    onNavigateToAliases: () -> Unit,
    viewModel: SpeakViewModel = hiltViewModel(),
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentVoice by viewModel.currentVoice.collectAsStateWithLifecycle()
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val activeAlias by viewModel.activeAlias.collectAsStateWithLifecycle()

    val isSpeaking = playbackState is PlaybackState.Speaking
    val isModelMissing = playbackState is PlaybackState.ModelMissing

    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("marmalade-tts") },
                actions = {
                    IconButton(onClick = onNavigateToVoices) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Voices",
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More",
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Engines") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToEngines()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Voice aliases") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToAliases()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // Small animated mascot — drawable switches with playback state.
            val mascotRes = if (isSpeaking) R.drawable.mascot_speaking else R.drawable.mascot_happy
            Image(
                painter = painterResource(id = mascotRes),
                contentDescription = if (isSpeaking) "Mascot speaking" else "Mascot",
                modifier = Modifier.size(64.dp),
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = viewModel::onTextChanged,
                label = { Text("Text to speak") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                minLines = 5,
                maxLines = 8,
            )

            Spacer(Modifier.height(16.dp))

            // Voice chip — opens the picker. Falls back to "Voice…" while
            // the StateFlow is resolving on first launch.
            AssistChip(
                onClick = onNavigateToVoices,
                label = {
                    Text(text = currentVoice?.displayName ?: "Voice…")
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )

            Spacer(Modifier.height(8.dp))

            // Alias chip row. One FilterChip per saved alias + a trailing
            // "Create alias" AssistChip that always shows (so the user can
            // add more even when they already have some). LazyRow gives
            // horizontal scroll for free on narrow screens.
            AliasChipRow(
                aliases = aliases,
                activeAlias = activeAlias,
                onApplyAlias = viewModel::applyAlias,
                onCreateAlias = onNavigateToAliases,
            )

            Spacer(Modifier.height(16.dp))

            val canSpeak = text.isNotBlank() && !isSpeaking && !isModelMissing
            Button(
                onClick = { viewModel.speak() },
                enabled = canSpeak,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSpeaking) "Speaking…" else "Speak")
            }

            Spacer(Modifier.height(12.dp))

            // Status line — single source of truth for "what's going on."
            Box(modifier = Modifier.fillMaxWidth()) {
                if (isModelMissing) {
                    // Make the missing-engine state actionable: a text button
                    // that routes the user straight to the Engines screen.
                    TextButton(
                        onClick = onNavigateToEngines,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Tap to install Kitten engine",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        text = statusText(playbackState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (playbackState) {
                            is PlaybackState.Error -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Horizontally scrollable row of alias FilterChips with a trailing
 * "Create alias" AssistChip. Extracted so the SpeakScreen body stays
 * legible and so the chip layout is easy to swap later.
 */
@Composable
private fun AliasChipRow(
    aliases: List<VoiceAlias>,
    activeAlias: String?,
    onApplyAlias: (String) -> Unit,
    onCreateAlias: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(aliases, key = { it.name }) { alias ->
            FilterChip(
                selected = alias.name == activeAlias,
                onClick = { onApplyAlias(alias.name) },
                label = { Text(alias.name) },
                leadingIcon = if (alias.name == activeAlias) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }

        // Trailing "Create alias" chip — always present so the user can
        // add more aliases regardless of how many already exist.
        item(key = "__create_alias__") {
            AssistChip(
                onClick = onCreateAlias,
                label = { Text("Create alias") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

private fun statusText(state: PlaybackState): String = when (state) {
    is PlaybackState.Idle -> "Ready"
    is PlaybackState.Speaking -> "Speaking…"
    is PlaybackState.ModelMissing -> "Tap to install Kitten engine"
    is PlaybackState.Error -> state.message
}
