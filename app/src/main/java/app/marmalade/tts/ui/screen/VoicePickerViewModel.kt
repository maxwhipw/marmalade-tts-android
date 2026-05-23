package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoicePickerScreen
//     │
//     ├── voices ◄────────── VoicePickerViewModel.voices
//     │                          ▲
//     │                          │
//     │                  VoiceMetaDao.getByEngine("kitten") (Flow)
//     │
//     ├── selectedId ◄────── VoicePickerViewModel.selectedId
//     │                          ▲
//     │                          │
//     │                  SettingsRepository.defaultVoiceId (Flow)
//     │
//     ├── previewState ◄──── VoicePickerViewModel.previewState
//     │                          ▲
//     │                          │
//     │                  Synthesizer.speak(previewPhrase, voiceId)
//     │
//     └── actions
//          ├── selectVoice(id) ──► SettingsRepository.setDefaultVoiceId
//          └── preview(voice)  ──► Synthesizer.speak(...)
// -----------------------------------------------------------------------------

/**
 * UI state for the per-row Preview button.
 *
 * Kept separate from the screen-level playback state because previewing
 * is intentionally a transient affordance — tapping Preview should not
 * block the rest of the picker from being interactive.
 */
sealed class PreviewState {
    object Idle : PreviewState()

    /** Indicates which voice is currently being previewed (by voice ID). */
    data class Playing(val voiceId: String) : PreviewState()

    /**
     * Engine assets aren't bundled. Same condition as
     * [PlaybackState.ModelMissing]. [engineName] is the engine the failed
     * preview belonged to (`"kokoro"` / `"kitten"`) so the UI copy can
     * name the missing engine specifically instead of hardcoding one.
     */
    data class ModelMissing(val engineName: String) : PreviewState()

    data class Error(val message: String) : PreviewState()
}

/**
 * ViewModel for [VoicePickerScreen].
 *
 * Surfaces the installed Kitten voices from Room and the currently
 * persisted default. Writes new selections back through
 * [SettingsRepository].
 *
 * Preview audio uses a canned phrase so the user can hear the voice
 * without typing anything — same UX as `marmalade-tts kokoro --list` in
 * the CLI.
 */
@HiltViewModel
class VoicePickerViewModel @Inject constructor(
    voiceDao: VoiceMetaDao,
    private val settings: SettingsRepository,
    private val synthesizer: SpeechPlayer,
) : ViewModel() {

    val voices: StateFlow<List<VoiceMeta>> = voiceDao
        .getByEngine(KittenVoiceCatalog.ENGINE)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val selectedId: StateFlow<String> = settings.defaultVoiceId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = KittenVoiceCatalog.DEFAULT_VOICE_ID,
        )

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    /**
     * Persist [id] as the new default voice. Fire-and-forget — the picker
     * navigates back immediately after calling this; DataStore's edit is
     * fast enough that the next read on the Speak screen will see the new
     * value.
     */
    fun selectVoice(id: String) {
        viewModelScope.launch {
            settings.setDefaultVoiceId(id)
        }
    }

    /**
     * Play a short canned phrase in [voice] so the user can hear what they
     * sound like before committing. Phrasing the sentence with the voice's
     * own name doubles as a small bit of personality.
     */
    fun preview(voice: VoiceMeta) {
        // Don't stack previews — cancel anything in flight first.
        synthesizer.cancel()
        _previewState.value = PreviewState.Playing(voice.id)

        viewModelScope.launch {
            val phrase = "Hello, I'm ${voice.displayName}."
            val result = synthesizer.speak(phrase, voice.id)
            _previewState.value = result.fold(
                onSuccess = { PreviewState.Idle },
                onFailure = { err ->
                    when (err) {
                        is SynthesizerException.ModelMissing ->
                            PreviewState.ModelMissing(voice.engine)
                        is SynthesizerException.SynthesisFailed -> PreviewState.Error(
                            err.message ?: "Preview failed",
                        )
                        else -> PreviewState.Error(err.message ?: "Unknown error")
                    }
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        synthesizer.cancel()
    }
}
