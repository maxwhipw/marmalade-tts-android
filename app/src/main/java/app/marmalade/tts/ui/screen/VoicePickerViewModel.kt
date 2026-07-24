package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   VoicePickerScreen
//     │
//     ├── voices ◄────────── VoicePickerViewModel.voices
//     │                          ▲
//     │                          │ combine(allVoices, installedEngines) { ... }
//     │                          │
//     │                  VoiceMetaDao.getAll() (Flow)
//     │                  + per-engine EngineInstaller.verify() (probed via refresh)
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
//          ├── preview(voice)  ──► Synthesizer.speak(...)
//          └── refresh()       ──► installer.verify() for each catalog engine
//
//   Engine-install filtering (v0.1.18):
//     The DB is seeded with metadata for every catalog voice at app startup
//     (see MarmaladeTtsApplication) — but those voices can't actually be
//     synthesised unless the engine's model files are on disk. Pre-v0.1.18
//     this screen was hardcoded to getByEngine("kitten") so it showed Kitten
//     voices regardless of install state. Now we list voices only for
//     engines whose installer reports Installed.
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
 * Surfaces the installed voices from Room filtered down to engines whose
 * assets are actually on disk. Writes new selections back through
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
    private val installer: EngineInstaller,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Optional engine scope from the `voices?engine=` route arg (set when
     * the picker is opened from an engine's detail page). Null = the full
     * all-engines picker reached from the Speak screen.
     */
    val engineFilter: String? = savedStateHandle["engine"]

    /**
     * Engines whose on-disk layout currently passes [EngineInstaller.verify].
     * Seeded from `refresh()` (called on init + when the screen becomes
     * active). Used to filter the voice list — voices whose engine isn't
     * installed are hidden so the user can't pick a voice they can't hear.
     */
    private val _installedEngines = MutableStateFlow<Set<String>>(emptySet())
    val installedEngines: StateFlow<Set<String>> = _installedEngines.asStateFlow()

    val voices: StateFlow<List<VoiceMeta>> = combine(
        voiceDao.getAll(),
        _installedEngines,
        settings.showDeveloperEngines,
    ) { allVoices, installed, showDeveloper ->
        allVoices.filter { voice ->
            voice.engine in installed &&
                (engineFilter == null || voice.engine == engineFilter) &&
                (showDeveloper || voice.engine !in EngineCatalog.developerOnlyNames)
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val selectedId: StateFlow<String> = settings.defaultVoiceId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID,
        )

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    /**
     * Per-engine collapsed/expanded state for the engine section headers
     * on the voices list. Persists across navigation within a single VM
     * lifecycle (not across process death — that's the right scope for a
     * UI hint, no DataStore needed).
     *
     * Default policy: the engine that owns the currently-selected voice
     * opens expanded; everything else starts collapsed (so the user
     * doesn't have to scroll past 50+ Kokoro voices to find Kitten).
     * Computed lazily inside [setInitialExpansion] the first time
     * [voices] + [selectedId] have both emitted.
     */
    private val _expandedEngines = MutableStateFlow<Set<String>>(emptySet())
    val expandedEngines: StateFlow<Set<String>> = _expandedEngines.asStateFlow()

    private var initialExpansionSet: Boolean = false

    init {
        refresh()
    }

    /**
     * Toggle expand/collapse for an engine section. Idempotent — calling
     * with the same name twice round-trips the state.
     */
    fun toggleEngineExpanded(engineName: String) {
        val current = _expandedEngines.value
        _expandedEngines.value = if (engineName in current) {
            current - engineName
        } else {
            current + engineName
        }
    }

    /**
     * Seed [_expandedEngines] with "expand the engine that owns the
     * currently-selected voice" the first time both StateFlows have a
     * non-empty value. Idempotent — subsequent calls (e.g. on user
     * navigation refresh) leave the user's manual toggles intact.
     */
    fun setInitialExpansion(voicesByEngine: Map<String, List<VoiceMeta>>, selected: String) {
        if (initialExpansionSet || voicesByEngine.isEmpty()) return
        val ownerEngine = voicesByEngine.entries.firstOrNull { (_, voices) ->
            voices.any { it.id == selected }
        }?.key
        if (ownerEngine != null) {
            _expandedEngines.value = setOf(ownerEngine)
            initialExpansionSet = true
        }
    }

    /**
     * Re-probe each catalog engine's install state and update
     * [installedEngines]. The screen calls this in a [LaunchedEffect] so a
     * user who installs an engine and comes back sees the new voices
     * without restarting the app.
     */
    fun refresh() {
        viewModelScope.launch {
            val installed = mutableSetOf<String>()
            for (engine in EngineCatalog.all) {
                if (installer.verify(engine.name) is InstallState.Installed) {
                    installed += engine.name
                }
            }
            // The Cloud API engine has no bundle — "installed" means an
            // API key is configured (Engines tab → Cloud voices → Configure).
            if (settings.anyCloudApiKeySet.firstOrNull() == true) {
                installed += CloudApiVoiceCatalog.ENGINE
            }
            _installedEngines.value = installed
        }
    }

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
    /**
     * Monotonic token for the latest [preview]. A superseded preview's
     * coroutine still completes (Synthesizer.speak returns success on
     * cancellation) — without the token check it would overwrite the
     * NEW preview's Playing chip with its own terminal state.
     * Main-thread only (UI callback + viewModelScope), so a plain var.
     */
    private var previewGeneration = 0L

    fun preview(voice: VoiceMeta) {
        // Don't stack previews — cancel anything in flight first.
        synthesizer.cancel()
        val generation = ++previewGeneration
        _previewState.value = PreviewState.Playing(voice.id)

        viewModelScope.launch {
            val phrase = "Hello, I'm ${voice.displayName}."
            val result = synthesizer.speak(phrase, voice.id)
            if (generation != previewGeneration) return@launch // superseded
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
