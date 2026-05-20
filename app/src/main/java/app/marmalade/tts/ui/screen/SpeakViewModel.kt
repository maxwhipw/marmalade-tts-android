package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.SettingsRepository
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
//     └── actions ──► onTextChanged / speak() / cancel()
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
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * The voice the user has chosen as default. Re-resolved whenever
     * [SettingsRepository.defaultVoiceId] emits, so picking a new voice in
     * the picker shows up here without an extra signal.
     *
     * Emits `null` only transiently — before the first lookup completes
     * or if the persisted ID points at a voice that's been removed from
     * the catalog (shouldn't happen with the v0.1 static catalog).
     */
    val currentVoice: StateFlow<VoiceMeta?> = settings.defaultVoiceId
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

    /** UI hook for the text field's onValueChange. */
    fun onTextChanged(value: String) {
        _text.value = value
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
}
