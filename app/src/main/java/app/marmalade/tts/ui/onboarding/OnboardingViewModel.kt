package app.marmalade.tts.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.KokoroVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   OnboardingScreen
//     │
//     ├── step           ◄────── OnboardingViewModel.step (StateFlow<OnboardingStep>)
//     ├── engines        ◄────── EngineCatalog.all  (static)
//     ├── selectedIds    ◄────── OnboardingViewModel.selectedEngineIds
//     ├── installStates  ◄────── OnboardingViewModel.installStates (per engine)
//     ├── aliasCreated   ◄────── OnboardingViewModel.aliasCreated
//     │                              ▲
//     │                              │ Flow
//     │                       VoiceAliasDao.getAll().map { it.isNotEmpty() }
//     ├── aliasEditor    ◄────── OnboardingViewModel.aliasEditorState
//     ├── installedVoices ◄───── OnboardingViewModel.installedVoices (per current engine)
//     │
//     └── actions ──► next() / back() / toggle(name) / installSelected()
//                  next()  + onAlias{Name,Engine,Voice,Speed,Effect}Change()
//                  saveAliasAndContinue() / useDefaultsAndContinue() / finish()
//
//   installSelected()
//     │
//     ├── selectedEngineIds.value.forEach { name ->
//     │     ├── installStates.update { it + (name -> Downloading(0,…)) }
//     │     ├── installer.install(name) { progress -> updateState(name, progress) }
//     │     └── installStates.update { it + (name -> Installed | Failed) }
//     │
//     └── (UI calls next() once all-done to advance to CreateAlias)
//
//   saveAliasAndContinue()
//     │
//     ├── validate alias fields
//     ├── voiceAliasDao.upsert(...)
//     ├── settings.setPrimaryAliasName(name)  — first alias is primary
//     └── finish()
//
//   finish()
//     │
//     ├── refuses while aliasCreated == false (no-op)
//     └── settings.setOnboarded(true)
// -----------------------------------------------------------------------------

/** Five-step onboarding flow. */
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

    /**
     * Create primary alias — the user must save at least one alias
     * (or accept the "Use defaults" affordance) before [finish] is
     * allowed to flip the onboarded flag.
     *
     * Skipped automatically (the wizard jumps straight to a Finish
     * affordance) when the alias table already has rows on entry —
     * fresh-install + sideloaded-data edge case.
     */
    CreateAlias,

    /**
     * Final step — explains that the user has to manually pick
     * Marmalade in system Settings → Languages → Text-to-speech, and
     * provides a button that launches the TTS settings intent. The app
     * being installed isn't enough; until the OS-level default engine
     * is set to ours, none of the system-TTS path actually routes here.
     */
    SystemDefault,
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
    private val aliasDao: VoiceAliasDao,
    private val voiceDao: VoiceMetaDao,
) : ViewModel() {

    /**
     * Clock indirection for tests, mirroring [AliasViewModel.now]. Hilt's
     * `@Inject` constructor uses the default (wall clock); tests stamp a
     * fixed value via direct construction.
     */
    internal var now: () -> Long = { System.currentTimeMillis() }

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

    /** Move forward through the steps. The terminal step is [OnboardingStep.CreateAlias]. */
    fun next() {
        _step.value = when (_step.value) {
            OnboardingStep.Welcome -> OnboardingStep.EnginePick
            OnboardingStep.EnginePick -> OnboardingStep.Installing
            OnboardingStep.Installing -> OnboardingStep.CreateAlias
            OnboardingStep.CreateAlias -> OnboardingStep.SystemDefault
            OnboardingStep.SystemDefault -> OnboardingStep.SystemDefault
        }
    }

    /** Move back. The Welcome step is the back-stop — no-op when already there. */
    fun back() {
        _step.value = when (_step.value) {
            OnboardingStep.Welcome -> OnboardingStep.Welcome
            OnboardingStep.EnginePick -> OnboardingStep.Welcome
            OnboardingStep.Installing -> OnboardingStep.EnginePick
            OnboardingStep.CreateAlias -> OnboardingStep.Installing
            OnboardingStep.SystemDefault -> OnboardingStep.CreateAlias
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

    // -- CreateAlias step state ------------------------------------------------

    /**
     * True once at least one alias exists in the DB. Drives the
     * "Finish" button's enabled state on the CreateAlias step, and is
     * checked defensively inside [finish] so the gate can't be bypassed
     * by a stale UI snapshot.
     */
    val aliasCreated: StateFlow<Boolean> = aliasDao.getAll()
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * In-place editor state for the CreateAlias step. Simplified version
     * of [app.marmalade.tts.ui.screen.EditorState] — no "isOpen" or
     * "isNew" flags because the step is always in create-new mode and
     * always visible while we're on this step.
     */
    data class AliasFields(
        val name: String = "default",
        val engine: String = "",
        val voiceId: String = "",
        val speed: Float = 1.0f,
        val effect: EffectPreset = EffectPreset.NONE,
        val error: String? = null,
    )

    private val _aliasEditorState = MutableStateFlow(AliasFields())
    val aliasEditorState: StateFlow<AliasFields> = _aliasEditorState.asStateFlow()

    /**
     * Voices for the engine currently selected in the alias editor,
     * filtered to "installed = true" rows so the user can only pick
     * voices that will actually synthesize.
     *
     * Falls back to the unfiltered list when nothing is installed yet —
     * the editor needs *something* to show on a no-engine-installed run
     * (e.g. the user skipped the engine-pick step) so they can still
     * scrub through and hit "Use defaults".
     */
    val installedVoices: StateFlow<List<app.marmalade.tts.data.db.VoiceMeta>> =
        combine(
            _aliasEditorState.map { it.engine },
            voiceDao.getAll(),
        ) { engine, all ->
            if (engine.isBlank()) emptyList()
            else all.filter { it.engine == engine }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Seed the editor with sensible defaults. Picks the first installed
     * engine (preferring `kokoro` over `kitten` to match the catalog's
     * recommended order) and the catalog's [DEFAULT_VOICE_ID] for it.
     *
     * Called by the CreateAlias step's UI on first composition. Idempotent —
     * re-seeding after the user has typed clears their edits, so the UI
     * should only invoke it once.
     */
    fun seedAliasDefaults() {
        viewModelScope.launch {
            val installedEngines = _installStates.value
                .filterValues { it is InstallState.Installed }
                .keys
            val engine = when {
                "kokoro" in installedEngines -> "kokoro"
                "kitten" in installedEngines -> "kitten"
                installedEngines.isNotEmpty() -> installedEngines.first()
                else -> EngineCatalog.all.firstOrNull()?.name.orEmpty()
            }
            val voiceId = defaultVoiceIdFor(engine)
            _aliasEditorState.value = AliasFields(
                name = "default",
                engine = engine,
                voiceId = voiceId,
                speed = 1.0f,
                effect = EffectPreset.NONE,
                error = null,
            )
        }
    }

    fun onAliasNameChange(value: String) {
        _aliasEditorState.update { it.copy(name = value, error = null) }
    }

    fun onAliasEngineChange(value: String) {
        // Engine change clears voice — picking a Kitten voice and then
        // flipping engine to Kokoro would otherwise smuggle the wrong
        // voice ID through.
        _aliasEditorState.update {
            it.copy(engine = value, voiceId = defaultVoiceIdFor(value), error = null)
        }
    }

    fun onAliasVoiceChange(voiceId: String) {
        _aliasEditorState.update { it.copy(voiceId = voiceId, error = null) }
    }

    fun onAliasSpeedChange(speed: Float) {
        val clamped = speed.coerceIn(VoiceAlias.MIN_SPEED, VoiceAlias.MAX_SPEED)
        _aliasEditorState.update { it.copy(speed = clamped) }
    }

    fun onAliasEffectChange(effect: EffectPreset) {
        _aliasEditorState.update { it.copy(effect = effect) }
    }

    /**
     * Validate the editor's current state, persist the alias, and mark
     * it as primary. Returns true on success — the UI then calls
     * [finish] + the host's `onComplete`.
     *
     * Returns false on validation failure; the editor stays visible
     * with `state.error` populated. No DB write happens.
     */
    fun saveAliasAndContinue(): Boolean {
        val s = _aliasEditorState.value
        val name = s.name.trim()
        if (!VoiceAlias.isValidName(name)) {
            _aliasEditorState.value = s.copy(
                error = "Use lower-case letters, digits, dash, underscore.",
            )
            return false
        }
        if (s.voiceId.isBlank()) {
            _aliasEditorState.value = s.copy(error = "Pick a voice.")
            return false
        }
        if (s.engine.isBlank()) {
            _aliasEditorState.value = s.copy(error = "Pick an engine.")
            return false
        }
        viewModelScope.launch {
            // Defensive collision handling: if there's an existing alias
            // by this name we replace it (Room's REPLACE strategy on PK).
            // The onboarding wizard only runs pre-onboarded, so this is
            // really just a fresh-install + sideloaded-data edge case.
            aliasDao.upsert(
                VoiceAlias(
                    name = name,
                    engine = s.engine,
                    voiceId = s.voiceId,
                    speed = s.speed,
                    effectPreset = s.effect.name,
                    createdAt = now(),
                ),
            )
            settings.setPrimaryAliasName(name)
            // Advance to SystemDefault step instead of finishing — the user
            // still needs to be told to set Marmalade as their system TTS
            // engine before any external app can route through us.
            _step.value = OnboardingStep.SystemDefault
        }
        return true
    }

    /**
     * Skip the editor and create a baseline alias named "default" with
     * the recommended engine's default voice, speed 1.0, no effect.
     * Sets it as primary. Useful for users who don't want to think about
     * voice configuration on first launch.
     */
    fun useDefaultsAndContinue() {
        viewModelScope.launch {
            val installedEngines = _installStates.value
                .filterValues { it is InstallState.Installed }
                .keys
            val engine = when {
                "kokoro" in installedEngines -> "kokoro"
                "kitten" in installedEngines -> "kitten"
                installedEngines.isNotEmpty() -> installedEngines.first()
                else -> EngineCatalog.all.firstOrNull()?.name.orEmpty()
            }
            val voiceId = defaultVoiceIdFor(engine)
            // Refuse to create a malformed alias if there isn't any
            // engine to pull a default voice from — surface as an error
            // on the editor state so the UI can react.
            if (engine.isBlank() || voiceId.isBlank()) {
                _aliasEditorState.update {
                    it.copy(error = "Install an engine before creating an alias.")
                }
                return@launch
            }
            aliasDao.upsert(
                VoiceAlias(
                    name = "default",
                    engine = engine,
                    voiceId = voiceId,
                    speed = 1.0f,
                    effectPreset = EffectPreset.NONE.name,
                    createdAt = now(),
                ),
            )
            settings.setPrimaryAliasName("default")
            // Advance to SystemDefault step — see saveAliasAndContinue.
            _step.value = OnboardingStep.SystemDefault
        }
    }

    /**
     * Mark onboarding complete and move out of the wizard.
     *
     * Gated on [aliasCreated]: refuses to flip the onboarded flag if no
     * alias has been saved yet. Also self-heals the primary pointer — if
     * an alias exists but no primary is set (existing user, sideloaded
     * DB, etc.), the first alias is auto-promoted to primary before
     * flipping onboarded so downstream consumers always see a valid
     * primary on a freshly-onboarded user.
     *
     * @return true if onboarded was flipped, false if blocked by the
     *   "must create at least one alias" gate.
     */
    /**
     * Sideloaded-data path: an alias already exists when the wizard runs
     * (rare), so the user has skipped through the CreateAlias step via
     * "Finish setup". Self-heal the primary if it's unset, then advance
     * to the SystemDefault step — they still need to be prompted to
     * pick Marmalade as their system TTS engine.
     */
    fun advanceToSystemDefault(): Boolean {
        if (!aliasCreated.value) return false
        viewModelScope.launch {
            if (settings.primaryAliasName.first() == null) {
                aliasDao.getAll().first().firstOrNull()?.let { first ->
                    settings.setPrimaryAliasName(first.name)
                }
            }
        }
        _step.value = OnboardingStep.SystemDefault
        return true
    }

    fun finish(): Boolean {
        if (!aliasCreated.value) return false
        viewModelScope.launch {
            // Self-heal: if there's no primary set but at least one alias
            // exists, auto-promote the first alias to primary.
            if (settings.primaryAliasName.first() == null) {
                aliasDao.getAll().first().firstOrNull()?.let { first ->
                    settings.setPrimaryAliasName(first.name)
                }
            }
            settings.setOnboarded(true)
        }
        return true
    }

    /**
     * Map an engine key to its catalog default voice ID, or `""` when
     * the engine is unknown. Centralised so [seedAliasDefaults] and
     * [useDefaultsAndContinue] stay in sync.
     */
    private fun defaultVoiceIdFor(engine: String): String = when (engine) {
        "kokoro" -> KokoroVoiceCatalog.DEFAULT_VOICE_ID
        "kitten" -> KittenVoiceCatalog.DEFAULT_VOICE_ID
        else -> ""
    }

    private fun updateInstallState(engineName: String, state: InstallState) {
        _installStates.update { current -> current + (engineName to state) }
    }
}
