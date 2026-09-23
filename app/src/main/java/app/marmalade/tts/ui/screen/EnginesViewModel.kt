package app.marmalade.tts.ui.screen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.R
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.install.VoicePackSummary
import app.marmalade.tts.install.isInFlight
import app.marmalade.tts.install.voicePackSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EnginesScreen
//     │
//     ├── engines       ◄────── EngineCatalog.all (static StateFlow)
//     ├── installStates ◄────── EnginesViewModel.installStates (per engine)
//     ├── packSummaries ◄────── EnginesViewModel.packSummaries
//     │                         (pack-based engines: installer.verifyPack per
//     │                          pack → voicePackSummary)
//     │
//     └── actions
//          ├── install(name)   → installer.install(name, ::onProgress)
//          ├── uninstall(name) → installer.uninstall(name)
//          ├── removeDownload(name) → installer.discardDownload(name)
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
    private val settings: SettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * Catalog engines to render, filtered by the "show developer engines"
     * setting — the legacy sherpa engines drop out when it's off. Reactive
     * so toggling the setting refreshes the list without a screen reload.
     */
    val engines: StateFlow<List<EngineDescriptor>> = settings.showDeveloperEngines
        .map { EngineCatalog.visibleTo(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EngineCatalog.visibleTo(BuildConfig.DEBUG),
        )

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    init {
        // Mirror the installer's live state for every engine for as long as
        // this VM lives, so a download started elsewhere (onboarding, the
        // engine page) or by a previous instance of this VM still shows its
        // progress here instead of a plain Install button.
        for (engine in EngineCatalog.all) {
            viewModelScope.launch {
                installer.state(engine.name).collect { s ->
                    _installStates.update { it + (engine.name to s) }
                }
            }
        }
    }

    /**
     * Per-engine, per-pack install state, keyed engine → packId → state. Only
     * pack-based engines appear. Seeded with each such engine at an empty map
     * so the counts draw on first frame; [refresh] fills the per-pack states in
     * after probing the disk.
     */
    private val _packStates = MutableStateFlow(
        EngineCatalog.all
            .filter { it.isPackBased }
            .associate { it.name to emptyMap<String, InstallState>() },
    )

    /**
     * Per-engine voice-pack counts for the card's aggregate line, keyed by
     * engine name. Only pack-based engines appear; the card renders nothing for
     * the others.
     *
     * Derived reactively from [_packStates] and the "show developer engines"
     * setting so the counts reflect exactly the packs the current mode exposes:
     * the released set for ordinary users, the whole staged catalog in
     * developer mode. Toggling the setting refreshes the line without a reload,
     * matching [engines].
     */
    val packSummaries: StateFlow<Map<String, VoicePackSummary>> = combine(
        _packStates,
        settings.showDeveloperEngines,
    ) { states, showDeveloper ->
        states.mapValues { (engineName, packStates) ->
            voicePackSummary(engineName, packStates, includeUnreleased = showDeveloper)
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EngineCatalog.all
                .filter { it.isPackBased }
                .associate {
                    it.name to voicePackSummary(it.name, emptyMap(), includeUnreleased = BuildConfig.DEBUG)
                },
        )

    /**
     * Probe each catalog engine to populate the install-state map. Called
     * once on screen composition (via `LaunchedEffect`) and again any
     * time the user returns to this screen.
     *
     * A pack-based engine is probed twice over: once as an engine (does it have
     * any usable pack at all, which is what its Install/Configure button
     * reflects) and once per pack, so the card can say how many of the
     * available packs are actually on the phone.
     */
    fun refresh() {
        viewModelScope.launch {
            for (engine in EngineCatalog.all) {
                val state = installer.verify(engine.name)
                // An in-flight result is a snapshot of a running install the
                // init collector already mirrors; written late, it could land
                // after the install finished and park the card on a spinner.
                if (!state.isInFlight) {
                    _installStates.update { current -> current + (engine.name to state) }
                }
                if (!engine.isPackBased) continue
                // Probe every catalog pack, released or not: developer mode
                // needs the staged packs' states too, and voicePackSummary
                // filters to the released set when it isn't on.
                val packStates = VoicePackCatalog.forEngine(engine.name)
                    .associate { pack -> pack.id to installer.verifyPack(pack.id) }
                _packStates.update { current ->
                    current + (engine.name to packStates)
                }
            }
        }
    }

    /**
     * Start an install for [engineName]. Every transition (Downloading →
     * Extracting → Installed) reaches the UI map through the [init]
     * collector on the installer's state flow — the `onProgress` callback
     * that `installer.install` accepts only fires for Downloading updates,
     * and relying on it alone left the Extracting phase invisible (the 5–15
     * second tarball unpack looked like a frozen UI). The terminal state from
     * `result.fold` is a fallback for an installer whose flow didn't move —
     * on failure the installer's own Failed wins, since it carries the
     * failure kind and the partial-download size the card's actions key off.
     */
    fun install(engineName: String) {
        _installStates.update { it + (engineName to InstallState.Downloading(0L, 0L, "")) }
        viewModelScope.launch {
            val result = installer.install(engineName) { /* state flow handles updates */ }
            _installStates.update {
                it + (engineName to result.fold(
                    onSuccess = { InstallState.Installed },
                    onFailure = { err ->
                        installer.state(engineName).value as? InstallState.Failed
                            ?: InstallState.Failed.from(
                                err,
                                appContext.getString(R.string.engines_install_failed),
                            )
                    },
                ))
            }
        }
    }

    /**
     * "Remove download" on a failed card: delete the partial archive the
     * failed install kept for resume. The installer re-probes, and the
     * [init] collector carries the card to whatever it finds (Install again,
     * or the old version when an update failed).
     */
    fun removeDownload(engineName: String) {
        viewModelScope.launch { installer.discardDownload(engineName) }
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
                    onFailure = { err ->
                        InstallState.Failed(
                            err.message ?: appContext.getString(R.string.engines_uninstall_failed),
                        )
                    },
                ))
            }
        }
    }
}
