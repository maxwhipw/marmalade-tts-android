package app.marmalade.tts.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.ui.MarmaladeFilterChip
import app.marmalade.tts.ui.theme.LocalWordmarkColor
import app.marmalade.tts.ui.theme.Wordmark

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
//   onNavigateToVoices()  ──► AppRoot routes to VoicePickerScreen
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
//   onNavigateToAliases()  ──► alias editor screen (AliasScreen)
//
//   User taps "Tap to install Kitten engine" when the model is missing
//     │
//     ▼
//   onNavigateToEngines()  ──► engine installer screen (EnginesScreen)
//
//   v0.1.8 note: Engines / Aliases / Settings are top-level destinations
//   on the bottom NavigationBar; the old overflow DropdownMenu in this
//   top app bar is gone. The Voices IconButton and these inline nav
//   affordances stay — they're action-in-context, not duplicate nav.
// -----------------------------------------------------------------------------

/**
 * Primary screen — the user's "type something, hear it spoken" surface.
 *
 * Layout (top to bottom):
 *  - Top app bar: "marmalade tts" wordmark, trailing IconButton → voices.
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

    Scaffold(
        // Nested-Scaffold inset handoff: AppRoot's outer Scaffold already
        // consumes the status-bar inset, so this inner Scaffold and its
        // TopAppBar must opt out — otherwise the top app bar adds a second
        // status-bar's worth of padding inside itself and renders at ~2×
        // the expected height. Same pattern marmalade-android uses on every
        // per-screen Scaffold. See AppRoot.kt for the outer Scaffold.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                // Brand wordmark per marmalade-design-scheme-v0: always
                // lowercase, Fredoka 600, orange in light / cream in dark
                // (LocalWordmarkColor carries the mode-aware swap).
                title = {
                    Text(
                        text = "marmalade tts",
                        fontFamily = Wordmark,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = LocalWordmarkColor.current,
                    )
                },
                windowInsets = WindowInsets(0),
                actions = {
                    // The bottom nav exposes Voices as a tab; this in-bar
                    // shortcut stays for now because it's the single
                    // most-used action from Speak (post v0.1.7 user data).
                    // Revisit in v0.2 once we have nav-bar usage telemetry.
                    IconButton(onClick = onNavigateToVoices) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "Voices",
                        )
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
            // the StateFlow is resolving on first launch. No leading icon
            // by design: the alias chips below use Icons.Filled.Person on
            // the selected one, and having a Person here too made it hard
            // to tell which chip you'd just selected. Reserved for aliases.
            AssistChip(
                onClick = onNavigateToVoices,
                label = {
                    Text(text = currentVoice?.displayName ?: "Voice…")
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

            // While speaking, the same button becomes a Stop affordance so
            // the user can interrupt long synth + playback (Pocket Bible
            // chapter, slow chunk). When idle, requires non-blank text and
            // a present engine; while speaking it stays enabled regardless
            // so cancel is always reachable.
            val canSpeak = isSpeaking || (text.isNotBlank() && !isModelMissing)
            Button(
                onClick = { if (isSpeaking) viewModel.cancel() else viewModel.speak() },
                enabled = canSpeak,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isSpeaking) "Stop" else "Speak")
            }

            Spacer(Modifier.height(12.dp))

            // Status line — single source of truth for "what's going on."
            val installCta = installCtaFor(currentVoice)
            Box(modifier = Modifier.fillMaxWidth()) {
                if (isModelMissing) {
                    // Make the missing-engine state actionable: a text button
                    // that routes the user straight to the Engines screen.
                    TextButton(
                        onClick = onNavigateToEngines,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = installCta,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        text = statusText(playbackState, installCta),
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AliasChipRow(
    aliases: List<VoiceAlias>,
    activeAlias: String?,
    onApplyAlias: (String) -> Unit,
    onCreateAlias: () -> Unit,
) {
    // FlowRow lets long alias lists wrap onto multiple lines instead of
    // disappearing off the side of the screen — the horizontal scroll was
    // discoverability-hostile (the chips looked like they could fit but
    // some were just hidden). 24 chars of alias name fits comfortably 2
    // per line on a phone-width screen.
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (alias in aliases) {
            MarmaladeFilterChip(
                selected = alias.name == activeAlias,
                onClick = { onApplyAlias(alias.name) },
                label = { Text(alias.name) },
                leadingIconWhenSelected = Icons.Filled.Person,
            )
        }
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

private fun statusText(state: PlaybackState, installCta: String): String = when (state) {
    is PlaybackState.Idle -> "Ready"
    is PlaybackState.Speaking -> "Speaking…"
    is PlaybackState.ModelMissing -> installCta
    is PlaybackState.Error -> state.message
}

/**
 * Build the "Tap to install <engine>" call-to-action for the model-missing
 * state. Resolves the engine from the currently-selected voice via
 * [EngineCatalog] so the copy stays correct when the user switches between
 * Kokoro and Kitten voices. Falls back to a neutral "a TTS engine" label
 * when no voice is resolved yet (initial-load flicker) or the engine
 * isn't in the catalog (would be a bug, but the neutral copy is safe).
 */
private fun installCtaFor(voice: VoiceMeta?): String {
    val displayName = voice?.engine
        ?.let { EngineCatalog.byName(it)?.displayName }
    return if (displayName != null) {
        "Tap to install $displayName"
    } else {
        "Tap to install a TTS engine"
    }
}
