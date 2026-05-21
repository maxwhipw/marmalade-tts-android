package app.marmalade.tts.ui.screen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   SpeakScreen
//     │
//     ├── text state ◄────── SpeakViewModel.text  (MutableStateFlow)
//     ├── current voice ◄─── SpeakViewModel.currentVoice
//     │                          ▲
//     │                          │ flatMapLatest
//     │                          │
//     │            SettingsRepository.defaultVoiceId
//     │                          │
//     │                          ▼
//     │                  VoiceMetaDao.findById(id) (suspend → flow shim)
//     │
//     ├── playback ◄──────── SpeakViewModel.playbackState
//     │                          ▲
//     │                          │
//     │                  Synthesizer.speak(text, voiceId)
//     │
//     ├── aliases   ◄──────── SpeakViewModel.aliases (VoiceAliasDao.getAll)
//     ├── activeAlias ◄────── SpeakViewModel.activeAlias
//     │                          ▲
//     │                          │ set by applyAlias(name); cleared when
//     │                          │ defaultVoiceId emits a value that doesn't
//     │                          │ match the voice we last applied.
//     │
//     ├── currentEffect ◄──── SpeakViewModel.currentEffect (set by applyAlias)
//     │   TODO: route currentEffect through SpeechPlayer in a follow-up commit.
//     │   v0.1 stores it for UI/future-use only; Synthesizer.speak doesn't
//     │   take an EffectPreset yet, and threading one through would require
//     │   widening SpeechPlayer + updating MarmaladeSynthService + test fakes.
//     │
//     └── actions ──► onTextChanged / speak() / cancel() / applyAlias(name)
// -----------------------------------------------------------------------------

/** Coarse UI state for the playback area on the Speak screen. */
sealed class PlaybackState {
    /** Nothing playing. Default. */
    object Idle : PlaybackState()

    /** Synthesis or playback is in flight. UI shows the speaking mascot. */
    object Speaking : PlaybackState()

    /** Engine assets aren't bundled — see STUBS.md (P0). */
    object ModelMissing : PlaybackState()

    /** Anything else — synth/JNI/audiotrack error. `message` is user-facing. */
    data class Error(val message: String) : PlaybackState()
}

/**
 * ViewModel for [SpeakScreen].
 *
 * Holds the text field state, resolves the currently selected voice from
 * `SettingsRepository`, and routes the "Speak" button through [Synthesizer].
 *
 * The text field state lives here (not in the composable) so it survives
 * configuration changes — typing a paragraph and rotating shouldn't lose
 * the draft.
 *
 * `onCleared()` cancels any in-flight playback so leaving the app or
 * navigating away (resulting in ViewModel teardown) doesn't leave audio
 * playing in the background.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakViewModel @Inject constructor(
    private val synthesizer: SpeechPlayer,
    private val settings: SettingsRepository,
    private val voiceDao: VoiceMetaDao,
    private val aliasDao: VoiceAliasDao,
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * The voice ID we most recently applied via [applyAlias]. When the
     * observed `defaultVoiceId` diverges from this (the user manually
     * picked a different voice), [activeAlias] clears — the alias chip
     * stops looking "applied".
     *
     * `null` means "no alias has been applied since launch" — every
     * defaultVoiceId emission in that state is treated as a manual pick
     * and so doesn't clear anything (since there's nothing to clear).
     */
    @Volatile
    private var expectedAliasVoiceId: String? = null

    private val _activeAlias = MutableStateFlow<String?>(null)
    val activeAlias: StateFlow<String?> = _activeAlias.asStateFlow()

    /**
     * Effect preset currently associated with the active alias. Defaults
     * to [EffectPreset.NONE]; applyAlias(...) writes to it.
     *
     * TODO: route currentEffect through Synthesizer in a follow-up commit.
     * The SpeechPlayer interface takes (text, voiceId) only — widening it
     * to take an EffectPreset would touch the fake player in tests and
     * MarmaladeSynthService. Tracked separately so this commit stays scoped
     * to the alias-chip UI.
     */
    private val _currentEffect = MutableStateFlow(EffectPreset.NONE)
    val currentEffect: StateFlow<EffectPreset> = _currentEffect.asStateFlow()

    /**
     * The voice the user has chosen as default. Re-resolved whenever
     * [SettingsRepository.defaultVoiceId] emits, so picking a new voice in
     * the picker shows up here without an extra signal.
     *
     * Emits `null` only transiently — before the first lookup completes
     * or if the persisted ID points at a voice that's been removed from
     * the catalog (shouldn't happen with the v0.1 static catalog).
     *
     * The `onEach` side effect breaks out of the alias chip selection
     * when the user picks a voice manually: if a new defaultVoiceId
     * arrives that doesn't match what applyAlias most recently set,
     * activeAlias clears.
     */
    val currentVoice: StateFlow<VoiceMeta?> = settings.defaultVoiceId
        .onEach { id ->
            val expected = expectedAliasVoiceId
            if (expected != null && id != expected) {
                _activeAlias.value = null
                expectedAliasVoiceId = null
            }
        }
        .flatMapLatest { id ->
            // Convert the suspend point-lookup into a single-emission Flow.
            // findById is cheap (indexed PK lookup), so this is fine to
            // re-run on every change.
            flow { emit(voiceDao.findById(id)) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Saved aliases, observed from Room. Used by the chip row on the
     * Speak screen so adding/editing/deleting an alias on the Alias
     * screen reflects here without an explicit refresh signal.
     */
    val aliases: StateFlow<List<VoiceAlias>> = aliasDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** UI hook for the text field's onValueChange. */
    fun onTextChanged(value: String) {
        _text.value = value
    }

    /**
     * Apply the alias with [name]: switch voice, speed, and effect to the
     * alias's saved values, and mark it as the active alias for chip
     * highlighting.
     *
     * No-ops (with a warn-level log) if the alias isn't found. Engine
     * mismatches (v0.1 only ships Kitten) log a warning but continue —
     * future engines will work without a code change.
     *
     * Voice changes go through [SettingsRepository.setDefaultVoiceId] so
     * the picker, the system-TTS callback path, and this screen all
     * agree on what's selected.
     */
    fun applyAlias(name: String) {
        viewModelScope.launch {
            val alias = aliasDao.findByName(name)
            if (alias == null) {
                Log.w(TAG, "applyAlias($name): no alias by that name; ignoring")
                return@launch
            }

            if (alias.engine != "kitten") {
                Log.w(
                    TAG,
                    "applyAlias($name): engine '${alias.engine}' not supported in v0.1; " +
                        "proceeding with voice/speed/effect only",
                )
            }

            val voice = voiceDao.findById(alias.voiceId)
            if (voice == null) {
                Log.w(
                    TAG,
                    "applyAlias($name): voiceId '${alias.voiceId}' not in catalog; " +
                        "skipping voice change",
                )
            } else {
                // Record the expected voice BEFORE persisting so the
                // upcoming defaultVoiceId emission isn't misread as a
                // manual voice pick.
                expectedAliasVoiceId = alias.voiceId
                settings.setDefaultVoiceId(alias.voiceId)
            }

            // TODO: apply alias.speed once SpeechPlayer threads a speed
            // multiplier through. Stored intent: the alias's speed should
            // win on the next speak() call.
            // For now we just decode the effect so applyAlias has the
            // right intent recorded on the ViewModel.

            _currentEffect.value = decodeEffect(alias.effectPreset)
            _activeAlias.value = alias.name
        }
    }

    /**
     * Synthesize and play the current text with the current voice. No-op
     * when:
     *   - the text is blank / whitespace, or
     *   - playback is already in flight (state != Idle), or
     *   - no voice is currently resolved (race during initial load).
     */
    fun speak() {
        val currentText = _text.value
        if (currentText.isBlank()) return
        if (_playbackState.value is PlaybackState.Speaking) return
        val voiceId = currentVoice.value?.id ?: return

        _playbackState.value = PlaybackState.Speaking
        viewModelScope.launch {
            // TODO: pass currentEffect.value and the alias's speed through
            // to synthesizer.speak once SpeechPlayer's signature is widened.
            val result = synthesizer.speak(currentText, voiceId)
            _playbackState.value = result.fold(
                onSuccess = { PlaybackState.Idle },
                onFailure = { err ->
                    when (err) {
                        is SynthesizerException.ModelMissing -> PlaybackState.ModelMissing
                        is SynthesizerException.SynthesisFailed -> PlaybackState.Error(
                            err.message ?: "Synthesis failed",
                        )
                        else -> PlaybackState.Error(err.message ?: "Unknown error")
                    }
                },
            )
        }
    }

    /** Stop any in-flight playback and return to Idle. Used by the UI's stop affordance. */
    fun cancel() {
        synthesizer.cancel()
        _playbackState.value = PlaybackState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        synthesizer.cancel()
    }

    private fun decodeEffect(raw: String): EffectPreset =
        EffectPreset.entries.firstOrNull { it.name == raw } ?: EffectPreset.NONE

    companion object {
        private const val TAG = "SpeakViewModel"
    }
}
