package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EnginesScreen
//     │
//     ├── engines       ◄────── EngineCatalog.all (static StateFlow)
//     ├── installStates ◄────── EnginesViewModel.installStates (per engine)
//     │
//     └── actions
//          ├── install(name)   → installer.install(name, ::onProgress)
//          ├── uninstall(name) → installer.uninstall(name)
//          └── refresh()       → installer.verify(name) for every engine
// -----------------------------------------------------------------------------

/**
 * ViewModel for [EnginesScreen].
 *
 * Wraps [EngineInstaller] for the catalog UI. Tracks per-engine install
 * state in a single [MutableStateFlow] of `Map<engineName, InstallState>`
 * so the screen can render the whole list reactively.
 *
 * Unlike [app.marmalade.tts.ui.onboarding.OnboardingViewModel], this
 * screen lives outside the install-wizard flow — install events come from
 * the user tapping per-row buttons, not a single "Install selected"
 * action. The state-update path is otherwise identical.
 */
@HiltViewModel
class EnginesViewModel @Inject constructor(
    private val installer: EngineInstaller,
) : ViewModel() {

    val engines: StateFlow<List<EngineDescriptor>> =
        MutableStateFlow(EngineCatalog.all).asStateFlow()

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    /**
     * Probe each catalog engine to populate the install-state map. Called
     * once on screen composition (via `LaunchedEffect`) and again any
     * time the user returns to this screen.
     */
    fun refresh() {
        viewModelScope.launch {
            for (engine in EngineCatalog.all) {
                val state = installer.verify(engine.name)
                _installStates.update { current -> current + (engine.name to state) }
            }
        }
    }

    /**
     * Start an install for [engineName]. The progress callback updates the
     * state map; the terminal state (Installed/Failed) overwrites it on
     * completion.
     */
    fun install(engineName: String) {
        _installStates.update { it + (engineName to InstallState.Downloading(0L, 0L, "")) }
        viewModelScope.launch {
            val result = installer.install(engineName) { progress ->
                _installStates.update { it + (engineName to progress) }
            }
            _installStates.update {
                it + (engineName to result.fold(
                    onSuccess = { InstallState.Installed },
                    onFailure = { err -> InstallState.Failed(err.message ?: "Install failed") },
                ))
            }
        }
    }

    /**
     * Remove the installed engine bundle. The optimistic UI update flips
     * to NotInstalled immediately and the asynchronous uninstall job
     * keeps it there (or restores to the previous state on the rare
     * uninstall-failed path).
     */
    fun uninstall(engineName: String) {
        viewModelScope.launch {
            val result = installer.uninstall(engineName)
            _installStates.update {
                it + (engineName to result.fold(
                    onSuccess = { InstallState.NotInstalled },
                    onFailure = { err -> InstallState.Failed(err.message ?: "Uninstall failed") },
                ))
            }
        }
    }
}
