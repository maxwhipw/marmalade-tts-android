package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.service.KeepaliveCoordinator
import app.marmalade.tts.service.KeepaliveMode
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
//     ├── themeMode        ◄────── SettingsViewModel.themeMode
//     │                                ▲
//     │                          SettingsRepository.themeMode (Flow<String>)
//     │
//     └── actions ──► setThemePreset(ThemePreset)
//                     setThemeMode(String)
//                          │
//                          ▼
//                     SettingsRepository.set...(value)  (DataStore round-trip)
//
//   `keepEngineLoaded` / `setKeepEngineLoaded` were removed in v0.1.16
//   when the Settings Switch was hidden — the engines never honoured the
//   flag. The storage on SettingsRepository stays; this ViewModel will
//   re-expose it when KittenEngine / KokoroEngine actually wire it up.
//
//   Text preprocessing was hosted here in v0.1.10 and earlier. It moved to
//   the per-engine EngineDetailScreen (EngineDetailViewModel) in v0.1.11 —
//   the rules were already per-engine in storage, so this is purely a UI
//   relocation.
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
    private val keepaliveCoordinator: KeepaliveCoordinator,
    private val synthesizer: SpeechPlayer,
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
            initialValue = ThemePreset.MARMALADE,
        )

    /**
     * The user's dark-mode override — `"system"`, `"light"`, or `"dark"`.
     * Defaults to `"system"` (follow the OS).
     */
    val themeMode: StateFlow<String> = settings.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = "system",
        )

    /**
     * Manual ONNX-Runtime intra-op thread count, or `null` for the auto
     * value supplied by [app.marmalade.tts.perf.CpuClusterDetector]. The
     * setting takes effect on next engine load (cold start, or after a
     * release-and-reload), not on the current synthesis.
     */
    val intraOpThreads: StateFlow<Int?> = settings.intraOpThreads
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    /**
     * The auto-detected perf-cluster size for this device, surfaced to
     * the UI so the "Auto (N)" radio label can show what auto resolves
     * to. Computed once at VM init since CPU topology never changes
     * at runtime.
     */
    val autoIntraOpThreads: Int =
        app.marmalade.tts.perf.CpuClusterDetector.detectPerfCoreCount()

    /**
     * Whether the legacy sherpa engines are shown in the engine lists.
     * Defaults to [BuildConfig.DEBUG] until the first DataStore emission.
     */
    val showDeveloperEngines: StateFlow<Boolean> = settings.showDeveloperEngines
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = BuildConfig.DEBUG,
        )

    /** Persist a new theme preset selection. */
    fun setThemePreset(preset: ThemePreset) {
        viewModelScope.launch {
            settings.setThemePreset(preset.name)
        }
    }

    /** Persist a new dark-mode override. Caller passes one of "system" / "light" / "dark". */
    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            settings.setThemeMode(mode)
        }
    }

    /** Persist a manual thread count, or pass `null` to revert to auto. */
    fun setIntraOpThreads(count: Int?) {
        viewModelScope.launch {
            settings.setIntraOpThreads(count)
            // The thread count is only read when an engine builds its ORT
            // sessions, so a warm engine keeps the old value until the process
            // dies. Release all engines now so the next synth reloads with the
            // new count — otherwise the setting is a silent no-op (re-opening
            // the app keeps the singleton/process alive).
            synthesizer.releaseAll()
        }
    }

    /** Persist the show-developer-engines toggle. */
    fun setShowDeveloperEngines(value: Boolean) {
        viewModelScope.launch {
            settings.setShowDeveloperEngines(value)
        }
    }

    /**
     * P-K — current keepalive mode (Off / Smart / Persistent). The
     * "Smart" default matches the value [SettingsRepository.keepaliveMode]
     * falls back to when nothing's been stored.
     */
    val keepaliveMode: StateFlow<KeepaliveMode> = settings.keepaliveMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = KeepaliveMode.Smart,
        )

    /**
     * Persist a new keepalive mode and immediately apply it (start /
     * stop the keepalive service). Without the explicit apply call, the
     * change wouldn't take effect until the next synth.
     */
    fun setKeepaliveMode(mode: KeepaliveMode) {
        viewModelScope.launch {
            settings.setKeepaliveMode(mode)
            keepaliveCoordinator.applyCurrentMode()
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
