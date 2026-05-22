package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.ui.theme.ThemePreset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   SettingsScreen
//     │
//     ├── themePreset      ◄────── SettingsViewModel.themePreset
//     │                                ▲
//     │                                │ map { ThemePreset.fromString(it) }
//     │                          SettingsRepository.themePreset (Flow<String>)
//     │
//     ├── keepEngineLoaded ◄────── SettingsViewModel.keepEngineLoaded
//     │                                ▲
//     │                          SettingsRepository.keepEngineLoaded (Flow<Boolean>)
//     │
//     ├── aliasCount       ◄────── SettingsViewModel.aliasCount
//     │                                ▲
//     │                                │ map { it.size }
//     │                          VoiceAliasDao.getAll() (Flow<List<VoiceAlias>>)
//     │
//     └── actions ──► setThemePreset(ThemePreset)
//                     setKeepEngineLoaded(Boolean)
//                          │
//                          ▼
//                     SettingsRepository.set...(value)  (DataStore round-trip)
// -----------------------------------------------------------------------------

/**
 * Backing ViewModel for the single-page [SettingsScreen].
 *
 * Reads three flows from [SettingsRepository] / [VoiceAliasDao] and exposes
 * them as cached StateFlows so the screen can render synchronously after
 * the first emission. Setters fire-and-forget into `viewModelScope` — the
 * downstream DataStore write is async, but the UI doesn't need to wait
 * since the change comes back through the same Flow.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    voiceAliasDao: VoiceAliasDao,
) : ViewModel() {

    /**
     * The currently-selected theme preset, decoded from the persisted
     * string. Defaults to [ThemePreset.SYSTEM] until the first DataStore
     * emission lands (single frame on warm start, ~few frames on cold).
     */
    val themePreset: StateFlow<ThemePreset> = settings.themePreset
        .map { ThemePreset.fromString(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = ThemePreset.SYSTEM,
        )

    /**
     * Whether the engine should remain resident in RAM between utterances.
     * Defaults to `true` (pre-toggle behavior).
     */
    val keepEngineLoaded: StateFlow<Boolean> = settings.keepEngineLoaded
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = true,
        )

    /**
     * Number of saved voice aliases. Drives the subtitle text on the
     * Voice Aliases row ("N aliases saved"). Counted via .size on the
     * existing Flow — Room re-emits when rows change, so this stays in
     * sync without a dedicated COUNT(*) query.
     */
    val aliasCount: StateFlow<Int> = voiceAliasDao.getAll()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = 0,
        )

    /** Persist a new theme preset selection. */
    fun setThemePreset(preset: ThemePreset) {
        viewModelScope.launch {
            settings.setThemePreset(preset.name)
        }
    }

    /** Persist the keep-loaded toggle. */
    fun setKeepEngineLoaded(value: Boolean) {
        viewModelScope.launch {
            settings.setKeepEngineLoaded(value)
        }
    }

    private companion object {
        // Standard 5s grace period after the last observer detaches.
        // Matches the value used in SpeakViewModel / VoicePickerViewModel
        // so all screens share the same "kept warm across config change"
        // semantics.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
