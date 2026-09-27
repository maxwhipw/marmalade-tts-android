package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.install.VoicePackLanguageGroup
import app.marmalade.tts.install.VoicePackSummary
import app.marmalade.tts.install.voicePackGroups
import app.marmalade.tts.install.isInFlight
import app.marmalade.tts.install.voicePackSummary
import app.marmalade.tts.preprocessing.EngineProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
//     ├── reads packGroups       ◄── EngineDetailViewModel.packGroups
//     │   reads packSummary            ▲
//     │                                │ map { voicePackGroups(engine, states) }
//     │                          _packStates (Map<packId, InstallState>)
//     │                                ▲
//     │                          EngineInstaller.packState (every pack, for the
//     │                          VM's lifetime) + verifyPack probes
//     │
//     └── actions
//          ├── toggleRule(rule, enabled) → settings.setEnabledRules(...)
//          ├── resetRules()              → settings.setEnabledRules(name, defaults)
//          ├── refreshPacks()            → installer.verifyPack(each pack)
//          ├── installPack(packId)       → installer.installPack(packId)
//          ├── uninstallPack(packId)     → installer.uninstallPack(packId)
//          ├── removePackDownload(id)    → installer.discardPackDownload(id)
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

    // -- Voice packs ----------------------------------------------------------
    //
    // Only a pack-based engine (EngineDescriptor.isPackBased — VITS Marmalade
    // today) has any of this. For every other engine the map stays empty and
    // the screen renders no pack section.

    private val _packStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())

    init {
        // Mirror the installer's live state for every pack for as long as this
        // VM lives. A download outlives the screen that started it (the
        // installer's blocking I/O ignores viewModelScope's cancellation), so
        // a user who leaves mid-download and comes back gets a fresh VM — and
        // without this it would show a plain Install on a pack that is still
        // downloading, and a tap would queue a second download behind it.
        for (pack in VoicePackCatalog.forEngine(engineName)) {
            viewModelScope.launch {
                installer.packState(pack.id).collect { s ->
                    _packStates.update { it + (pack.id to s) }
                }
            }
        }
    }

    /**
     * This engine's catalog packs grouped by language, each row carrying its
     * live install state. Derived by [voicePackGroups] so the grouping and the
     * per-row action rules are unit-testable without a device.
     *
     * The initial value is the full list at [InstallState.NotInstalled] rather
     * than an empty list: the packs are static catalog data, so the list can
     * draw before [refreshPacks] has probed anything, and only the per-row
     * state changes underneath.
     */
    val packGroups: StateFlow<List<VoicePackLanguageGroup>> = combine(
        _packStates,
        settings.showDeveloperEngines,
    ) { states, showDeveloper ->
        voicePackGroups(engineName, states, VoicePackCatalog.showsUnreleased(showDeveloper))
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = voicePackGroups(
                engineName,
                emptyMap(),
                VoicePackCatalog.showsUnreleased(BuildConfig.DEBUG),
            ),
        )

    /** Counts for the pack section's "N packs · M languages · K installed" line. */
    val packSummary: StateFlow<VoicePackSummary> = combine(
        _packStates,
        settings.showDeveloperEngines,
    ) { states, showDeveloper ->
        voicePackSummary(engineName, states, VoicePackCatalog.showsUnreleased(showDeveloper))
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = voicePackSummary(
                engineName,
                emptyMap(),
                VoicePackCatalog.showsUnreleased(BuildConfig.DEBUG),
            ),
        )

    /**
     * Probe every pack of this engine. Called when the screen composes, so a
     * pack removed behind the app's back (Android's storage screen) doesn't
     * keep reading as installed.
     *
     * An in-flight result is not written: the probe returned a snapshot of a
     * running install, which the [init] collector is already mirroring — and
     * writing that snapshot late could land after the install finished,
     * parking the row on a stale progress bar.
     */
    fun refreshPacks() {
        viewModelScope.launch {
            for (pack in VoicePackCatalog.forEngine(engineName)) {
                val state = installer.verifyPack(pack.id)
                if (!state.isInFlight) _packStates.update { it + (pack.id to state) }
            }
        }
    }

    /**
     * Download and install one voice pack.
     *
     * The optimistic `Downloading(0, 0)` closes the gap before the installer's
     * first state emission; from there the [init] collector carries every
     * transition, Extracting included. The terminal state from the result is
     * a fallback for an installer whose flow didn't move — on failure the
     * installer's own Failed wins, since it carries the failure kind and the
     * partial-download size the row's actions key off.
     */
    fun installPack(packId: String) {
        _packStates.update { it + (packId to InstallState.Downloading(0L, 0L, "")) }
        viewModelScope.launch {
            val result = installer.installPack(packId) { /* state flow handles updates */ }
            val terminal = result.fold(
                onSuccess = { InstallState.Installed },
                onFailure = { err ->
                    installer.packState(packId).value as? InstallState.Failed
                        ?: InstallState.Failed.from(err)
                },
            )
            _packStates.update { it + (packId to terminal) }
        }
    }

    /**
     * "Remove download" on a failed row: delete the partial archive kept for
     * resume. The installer re-probes and the [init] collector carries the
     * row back to its real state.
     */
    fun removePackDownload(packId: String) {
        viewModelScope.launch {
            installer.discardPackDownload(packId)
        }
    }

    /**
     * Delete one pack's files, leaving the engine's other packs alone. The
     * optimistic flip to NotInstalled keeps the row responsive; a failure puts
     * the reason back on the row rather than silently pretending it worked.
     */
    fun uninstallPack(packId: String) {
        _packStates.update { it + (packId to InstallState.NotInstalled) }
        viewModelScope.launch {
            val result = installer.uninstallPack(packId)
            _packStates.update {
                it + (packId to result.fold(
                    onSuccess = { InstallState.NotInstalled },
                    onFailure = { err -> InstallState.Failed.from(err) },
                ))
            }
        }
    }

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
