package app.marmalade.tts.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.ui.rememberActivityViewModel

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
// -----------------------------------------------------------------------------

/**
 * Primary screen — the user's "type something, hear it spoken" surface.
 *
 * Layout (top to bottom):
 *  - Top app bar: title "marmalade-tts", trailing IconButton → voices.
 *  - Mascot (~64dp) — `mascot_speaking` while audio plays, `mascot_happy` otherwise.
 *  - OutlinedTextField, multi-line (~5 lines visible).
 *  - AssistChip showing the current voice — tap to navigate to picker.
 *  - "Speak" Button — disabled when text is blank or model isn't installed.
 *  - Status line below the button mirroring the ViewModel state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakScreen(
    onNavigateToVoices: () -> Unit,
    onNavigateToEngines: () -> Unit,
    onNavigateToAliases: () -> Unit,
    viewModel: SpeakViewModel = rememberActivityViewModel(),
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentVoice by viewModel.currentVoice.collectAsStateWithLifecycle()

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

private fun statusText(state: PlaybackState): String = when (state) {
    is PlaybackState.Idle -> "Ready"
    is PlaybackState.Speaking -> "Speaking…"
    is PlaybackState.ModelMissing -> "Tap to install Kitten engine"
    is PlaybackState.Error -> state.message
}
