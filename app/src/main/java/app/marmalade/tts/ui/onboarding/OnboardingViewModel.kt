package app.marmalade.tts.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.SettingsRepository
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
//   OnboardingScreen
//     │
//     ├── step          ◄────── OnboardingViewModel.step (StateFlow<OnboardingStep>)
//     ├── engines       ◄────── EngineCatalog.all  (static)
//     ├── selectedIds   ◄────── OnboardingViewModel.selectedEngineIds
//     ├── installStates ◄────── OnboardingViewModel.installStates (per engine)
//     │
//     └── actions ──► next() / back() / toggle(name) / installSelected() / finish()
//
//   installSelected()
//     │
//     ├── selectedEngineIds.value.forEach { name ->
//     │     ├── installStates.update { it + (name -> Downloading(0,…)) }
//     │     ├── installer.install(name) { progress -> updateState(name, progress) }
//     │     └── installStates.update { it + (name -> Installed | Failed) }
//     │
//     └── on all-done: step = Done
// -----------------------------------------------------------------------------

/** Three-step onboarding flow plus a terminal "done" state. */
enum class OnboardingStep {
    /** Welcome screen — mascot + pitch + "Get started" CTA. */
    Welcome,

    /** Engine picker — list cards, multi-select, "Install selected" CTA. */
    EnginePick,

    /**
     * Per-engine progress + "Continue" once everything has either
     * completed or terminally failed.
     */
    Installing,
}

/**
 * UI model for one engine card shown on the EnginePick step. Snapshots the
 * descriptor's display fields next to the user's selection state so the
 * composable doesn't have to thread two collections through.
 */
data class EngineCardState(
    val descriptor: EngineDescriptor,
    val isSelected: Boolean,
)

/**
 * ViewModel for the onboarding flow.
 *
 * Holds:
 *  - the current step (Welcome / EnginePick / Installing),
 *  - the set of engine names the user has ticked,
 *  - the live install state per engine (driven by [EngineInstaller]).
 *
 * Why not split into one VM per step: the steps share state (selected
 * engines, install progress) and the lifecycle of the install operation
 * outlives the EnginePick step. Keeping them in one VM avoids re-creating
 * state when the user taps "Install selected" and transitions to the
 * progress step.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val installer: EngineInstaller,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _step = MutableStateFlow(OnboardingStep.Welcome)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /**
     * Initial selection: every engine flagged `isRecommended` in the
     * catalog is pre-checked. For v0.1 that's just Kitten.
     */
    private val _selectedEngineIds = MutableStateFlow<Set<String>>(
        EngineCatalog.all.filter { it.isRecommended }.map { it.name }.toSet(),
    )
    val selectedEngineIds: StateFlow<Set<String>> = _selectedEngineIds.asStateFlow()

    /**
     * Per-engine install state, keyed by engine name. Only present for
     * engines that have been at least once nominated for install in this
     * session — the Installing step iterates over this map.
     */
    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    /** Convenience: the cards rendered on the EnginePick step. */
    val engines: StateFlow<List<EngineCardState>> = MutableStateFlow(
        EngineCatalog.all.map { d ->
            EngineCardState(descriptor = d, isSelected = _selectedEngineIds.value.contains(d.name))
        },
    ).asStateFlow()

    /** Toggle the selection state of [engineName] in the picker. */
    fun toggle(engineName: String) {
        _selectedEngineIds.update { current ->
            if (current.contains(engineName)) current - engineName else current + engineName
        }
    }

    /** Move forward through the steps. The terminal step is [OnboardingStep.Installing]. */
    fun next() {
        _step.value = when (_step.value) {
            OnboardingStep.Welcome -> OnboardingStep.EnginePick
            OnboardingStep.EnginePick -> OnboardingStep.Installing
            OnboardingStep.Installing -> OnboardingStep.Installing
        }
    }

    /** Move back. The Welcome step is the back-stop — no-op when already there. */
    fun back() {
        _step.value = when (_step.value) {
            OnboardingStep.Welcome -> OnboardingStep.Welcome
            OnboardingStep.EnginePick -> OnboardingStep.Welcome
            OnboardingStep.Installing -> OnboardingStep.EnginePick
        }
    }

    /**
     * Kick off installs for every currently-selected engine.
     *
     * - Each engine's install runs in its own coroutine on
     *   `viewModelScope`, so failures isolated to one engine don't block
     *   the others.
     * - State updates flow through the per-engine StateFlow plus our local
     *   [_installStates] map (the UI reads the latter so it can render the
     *   full list at once).
     *
     * If no engines are selected, this is a no-op — the user reaches the
     * "Continue" affordance immediately on the next step.
     */
    fun installSelected() {
        _step.value = OnboardingStep.Installing
        val toInstall = _selectedEngineIds.value.toList()
        if (toInstall.isEmpty()) return

        for (name in toInstall) {
            updateInstallState(name, InstallState.Downloading(0L, 0L, ""))
            viewModelScope.launch {
                val result = installer.install(name) { progress ->
                    updateInstallState(name, progress)
                }
                val terminal: InstallState = result.fold(
                    onSuccess = { InstallState.Installed },
                    onFailure = { err ->
                        InstallState.Failed(err.message ?: "Install failed")
                    },
                )
                updateInstallState(name, terminal)
            }
        }
    }

    /**
     * Retry an install that failed on the progress screen. Reuses the
     * existing [installSelected] machinery but for a single engine.
     */
    fun retry(engineName: String) {
        updateInstallState(engineName, InstallState.Downloading(0L, 0L, ""))
        viewModelScope.launch {
            val result = installer.install(engineName) { progress ->
                updateInstallState(engineName, progress)
            }
            updateInstallState(
                engineName,
                result.fold(
                    onSuccess = { InstallState.Installed },
                    onFailure = { err ->
                        InstallState.Failed(err.message ?: "Install failed")
                    },
                ),
            )
        }
    }

    /**
     * Mark onboarding complete and move out of the wizard. Called by the
     * Installing screen's "Continue" button. The user is considered
     * onboarded even if they installed zero engines — they can install
     * later from Settings → Engines, and the speak screen will guide them
     * there via the "Model not installed yet" copy.
     */
    fun finish() {
        viewModelScope.launch {
            settings.setOnboarded(true)
        }
    }

    private fun updateInstallState(engineName: String, state: InstallState) {
        _installStates.update { current -> current + (engineName to state) }
    }
}
