package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.preprocessing.EngineProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EngineDetailScreen("kokoro")
//     │
//     ├── reads engineName       ◄── SavedStateHandle["name"]
//     │
//     ├── reads enabledRules     ◄── EngineDetailViewModel.enabledRules
//     │                                ▲
//     │                                │ stateIn(viewModelScope)
//     │                          SettingsRepository.enabledRules(engineName)
//     │
//     ├── reads installState     ◄── EngineDetailViewModel.installState
//     │                                ▲
//     │                                │ stateIn(viewModelScope)
//     │                          EngineInstaller.state(engineName)
//     │
//     └── actions
//          ├── toggleRule(rule, enabled) → settings.setEnabledRules(...)
//          ├── resetRules()              → settings.setEnabledRules(name, defaults)
//          └── install()                 → installer.install(engineName)
//                                          (used by the in-page "Install"
//                                          affordance when the user lands on
//                                          a not-yet-installed engine page)
// -----------------------------------------------------------------------------

/**
 * ViewModel backing [EngineDetailScreen].
 *
 * One instance per visit — Hilt scopes by the NavBackStackEntry and the
 * engine name is read from the route arg via [SavedStateHandle]. Holds the
 * install-state flow for the status header and the per-engine
 * preprocessing rule set for the body.
 *
 * Why a separate ViewModel instead of folding into [SettingsViewModel]:
 * - It scopes lifetime to the detail screen rather than the (long-lived)
 *   Settings tab.
 * - It only needs the rules + install-state for ONE engine, not the cross-
 *   product the global Settings screen used to combine.
 * - The nav-arg pattern (`SavedStateHandle["name"]`) keeps the screen
 *   reusable across engines without rebuilding the VM manually.
 */
@HiltViewModel
class EngineDetailViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val installer: EngineInstaller,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Engine name from the route arg (e.g. `"kokoro"`). A missing arg is
     * a programming error — the nav graph wires `navArgument("name") { type
     * = NavType.StringType }` so this can only be null if someone navigates
     * without the param. We use empty string as a defensive fallback to
     * avoid a hard crash; the screen renders gracefully because every
     * downstream lookup tolerates an unknown engine.
     */
    val engineName: String = savedStateHandle.get<String>(NAV_ARG_NAME).orEmpty()

    /**
     * Current install state for [engineName]. Cached as a StateFlow so the
     * screen can render synchronously after the first emission.
     *
     * Initial value is [InstallState.NotInstalled] — the installer's own
     * state flow emits the actual on-disk state on first subscription.
     */
    val installState: StateFlow<InstallState> =
        installer.state(engineName)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = InstallState.NotInstalled,
            )

    /**
     * Set of enabled preprocessing rule names for this engine, sourced from
     * [SettingsRepository.enabledRules]. Falls back to
     * [EngineProfiles.defaultsFor] on a fresh install (the repository's
     * Flow already encodes that fallback — we mirror it here as the
     * StateFlow's initial value so the UI never flashes "no rules" before
     * the Flow emits).
     */
    val enabledRules: StateFlow<Set<String>> =
        settings.enabledRules(engineName)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = EngineProfiles.defaultsFor(engineName),
            )

    /**
     * Tracks an in-flight install kicked off from this screen so the UI
     * can flip its affordance to a spinner. The installer's own state flow
     * also reports Downloading/Extracting/Failed, but it doesn't capture
     * the brief window before our first onProgress callback arrives —
     * setting this immediately on tap removes that gap. The screen reads
     * [installState] for everything else.
     *
     * Exposed primarily for the "Install this engine to see its settings"
     * call-to-action on the detail page; the EnginesScreen still drives
     * its own install flow through [EnginesViewModel].
     */
    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling

    /**
     * Toggle one preprocessing [rule] on or off.
     *
     * Reads the latest stored set with `.first()` rather than the cached
     * StateFlow so two rapid taps on different rules don't both compute
     * against the same stale snapshot and clobber each other's write.
     */
    fun toggleRule(rule: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.enabledRules(engineName).first()
            val next = if (enabled) current + rule else current - rule
            settings.setEnabledRules(engineName, next)
        }
    }

    /** Restore the engine's preprocessing rules to [EngineProfiles.DEFAULT_PROFILES]. */
    fun resetRules() {
        viewModelScope.launch {
            settings.setEnabledRules(engineName, EngineProfiles.defaultsFor(engineName))
        }
    }

    /**
     * Install the engine from this page. Used by the "Install this engine"
     * affordance shown when the user lands on a detail page for an engine
     * they haven't installed yet. Progress is reflected via the installer's
     * own state flow (which [installState] mirrors).
     */
    fun install() {
        if (engineName.isBlank()) return
        _isInstalling.value = true
        viewModelScope.launch {
            try {
                installer.install(engineName) { /* progress reported via state flow */ }
            } finally {
                _isInstalling.value = false
            }
        }
    }

    companion object {
        /** Key used by [AppRoot] when wiring the `engine/{name}` route. */
        const val NAV_ARG_NAME = "name"

        // Same 5s grace period the other ViewModels use. Keeps the StateFlow
        // warm across a config change (rotation) without leaking past the
        // last observer's disposal.
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
