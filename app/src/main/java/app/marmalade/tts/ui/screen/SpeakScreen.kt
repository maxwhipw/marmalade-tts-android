package app.marmalade.tts.ui.screen

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.ui.MarmaladeIcons
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
//   SpeakViewModel.applyAlias(id)          (alias UUID, not display name)
//     │
//     ├── VoiceAliasDao.findById ─► alias row
//     ├── SettingsRepository.setDefaultVoiceId(alias.voiceId)
//     └── activeAlias = id  ──► the row's name + the sheet's tick
//
//   User taps a voice manually in the picker
//     │
//     ▼
//   defaultVoiceId emits a value ≠ the one applyAlias set
//     │
//     ▼
//   SpeakViewModel clears activeAlias ──► row falls back to the voice
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
 *  - Top app bar: "marmalade tts" wordmark. No trailing action.
 *  - Mascot (~64dp) — `mascot_speaking` while a load or playback is in
 *    flight, `mascot_happy` otherwise.
 *  - OutlinedTextField, multi-line (~5 lines visible).
 *  - CurrentVoiceRow — names the persona in play; opens PersonaSheet.
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
    val personas by viewModel.personas.collectAsStateWithLifecycle()
    val currentPersona by viewModel.currentPersona.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    // Re-entering the screen (e.g. back from Settings → Engines after an
    // install) re-probes a stuck ModelMissing banner — see the kdoc on
    // onScreenEntered for why no flow catches this case.
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // Loading (cold engine paging in) is Speaking's twin for every control:
    // work the user asked for is in flight, and Stop must stay reachable so
    // a slow load can be abandoned. Only the status line tells them apart.
    val isSpeaking = playbackState is PlaybackState.Speaking ||
        playbackState is PlaybackState.Loading
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
                // lowercase, Momo Trust Display 600, orange in light / cream in
                // dark (LocalWordmarkColor carries the mode-aware swap).
                title = {
                    Text(
                        text = stringResource(R.string.speak_wordmark),
                        fontFamily = Wordmark,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = LocalWordmarkColor.current,
                        // Brand wordmark, not a screen title — cosmetic like
                        // the mascot, so no TalkBack stop (Max, 2026-08-09).
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                },
                windowInsets = WindowInsets(0),
                // No trailing action. The voice row below reaches the picker
                // and names what it will do; an unlabelled list glyph in the
                // bar was a second route to the same place that looked like
                // a menu.
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Landscape (and large font scales) leave less height than the
                // column needs; without this the Speak button and status line
                // sit below the fold with no way to reach them.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // Small animated mascot — drawable switches with playback state.
            val mascotRes = if (isSpeaking) R.drawable.mascot_speaking else R.drawable.mascot_happy
            Image(
                painter = painterResource(id = mascotRes),
                // Decorative: the status line below says whether we're
                // speaking, so the mascot is a redundant first swipe stop.
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )

            Spacer(Modifier.height(16.dp))

            // The semantics description names the field's purpose for
            // TalkBack — the visible floating label alone read bare
            // (Max, 2026-08-09). TalkBack announces it as the field's
            // name, then the current value, then "edit box".
            val speakFieldCd = stringResource(R.string.speak_text_field_cd)
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::onTextChanged,
                label = { Text(stringResource(R.string.speak_text_field_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .semantics { contentDescription = speakFieldCd },
                minLines = 5,
                maxLines = 8,
            )

            Spacer(Modifier.height(16.dp))

            // One row naming what will be heard; tap opens the sheet. This
            // replaced a voice chip plus a wrapping row of alias chips —
            // two chip languages doing one job, whose height grew with the
            // number of personas. This row's height never changes.
            CurrentVoiceRow(
                persona = currentPersona,
                onClick = { sheetOpen = true },
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
                Text(
                    stringResource(
                        if (isSpeaking) R.string.speak_button_stop else R.string.speak_button_speak,
                    ),
                )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            text = installCta,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // Composed only when there is something to say: the Idle
                    // state's empty string used to leave an empty-but-focusable
                    // TalkBack target under the Speak button (Max, 2026-08-09).
                    // Appearing with liveRegion still announces the new status.
                    val status = statusText(playbackState, installCta)
                    if (status.isNotEmpty()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (playbackState) {
                                is PlaybackState.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        PersonaSheet(
            personas = personas,
            activeId = currentPersona?.id,
            onPick = viewModel::applyAlias,
            onPickVoice = onNavigateToVoices,
            onCreate = onNavigateToAliases,
            onDismiss = { sheetOpen = false },
        )
    }
}

/**
 * The single control that names the current persona and opens the picker.
 *
 * 56 dp, one tap target, reads left to right: who, then what they sound
 * like, then whether it needs the network. Fixed height regardless of how
 * many personas exist — the property the chip row did not have.
 */
@Composable
private fun CurrentVoiceRow(
    persona: SpeakViewModel.Persona?,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            // fillMaxWidth, not fillMaxSize: the Surface's height is now a
            // minimum, so a filling Row would stretch to the whole column.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A speaker, not a person avatar: an avatar implies personas
            // can carry an image (they can't) and says nothing about what
            // the row does. No colored circle behind it either.
            Icon(
                imageVector = MarmaladeIcons.Speak,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Null only on the very first frame, before either flow
                    // has emitted. An em dash beats an empty row jumping.
                    text = persona?.name ?: stringResource(R.string.speak_persona_unresolved),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = persona.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (persona.isCloud) {
                            Spacer(Modifier.width(5.dp))
                            CloudMark()
                        }
                    }
                }
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.speak_change_voice),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The cloud glyph. One composable so the Speak row and the Aliases
 * screen's "Cloud" chip cannot drift into meaning different things.
 */
@Composable
fun CloudMark(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** Null where neighbouring text already says "Cloud". */
    contentDescription: String? = stringResource(R.string.speak_cloud_voice_desc),
) {
    Icon(
        imageVector = MarmaladeIcons.Cloud,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(14.dp),
    )
}

/**
 * Persona picker. Every saved persona with the same three facts the row
 * shows, plus the escape hatches: hand-pick a voice, or make a new
 * persona. "New persona" lives here rather than as a permanent chip on
 * the main screen, where it competed with the personas themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaSheet(
    personas: List<SpeakViewModel.Persona>,
    activeId: String?,
    onPick: (String) -> Unit,
    onPickVoice: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.speak_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(personas, key = { it.id }) { persona ->
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(persona.name)
                            if (persona.isPrimary) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.speak_persona_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = persona.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (persona.isCloud) {
                                Spacer(Modifier.width(5.dp))
                                CloudMark()
                            }
                        }
                    },
                    trailingContent = {
                        if (persona.id == activeId) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.speak_persona_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onPick(persona.id)
                        onDismiss()
                    },
                )
            }
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.speak_pick_voice_directly)) },
            leadingContent = {
                Icon(MarmaladeIcons.Speak, contentDescription = null)
            },
            modifier = Modifier.clickable {
                onPickVoice()
                onDismiss()
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.speak_new_persona)) },
            leadingContent = {
                Icon(Icons.Filled.Add, contentDescription = null)
            },
            modifier = Modifier.clickable {
                onCreate()
                onDismiss()
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

// Idle says nothing. "Ready" was permanent furniture that carried no
// information — the enabled Speak button already says the app is ready,
// and a line that never changes stops being read, which costs the states
// that DO matter their glance value. The Box keeps its height reserved so
// nothing below shifts when a real message arrives.
@Composable
private fun statusText(state: PlaybackState, installCta: String): String = when (state) {
    is PlaybackState.Idle -> ""
    is PlaybackState.Loading -> stringResource(R.string.speak_status_loading, state.engineDisplayName)
    is PlaybackState.Speaking -> stringResource(R.string.speak_status_speaking)
    is PlaybackState.ModelMissing -> installCta
    is PlaybackState.Error -> state.message ?: stringResource(state.fallbackRes)
}

/**
 * Build the "Tap to install <engine>" call-to-action for the model-missing
 * state. Resolves the engine from the currently-selected voice via
 * [EngineCatalog] so the copy stays correct when the user switches between
 * Kokoro and Kitten voices. Falls back to a neutral "a TTS engine" label
 * when no voice is resolved yet (initial-load flicker) or the engine
 * isn't in the catalog (would be a bug, but the neutral copy is safe).
 */
@Composable
private fun installCtaFor(voice: VoiceMeta?): String {
    val displayName = voice?.engine
        ?.let { EngineCatalog.byName(it)?.displayName }
    return if (displayName != null) {
        stringResource(R.string.speak_install_cta_engine, displayName)
    } else {
        stringResource(R.string.speak_install_cta_generic)
    }
}
