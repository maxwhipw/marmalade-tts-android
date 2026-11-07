package app.marmalade.tts.ui.onboarding

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.InstalledVoiceAssets
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.pickableVoices
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.data.probeInstalledVoiceAssets
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.perf.DeviceProbeSource
import app.marmalade.tts.perf.EngineFit
import app.marmalade.tts.perf.EngineRecommendation
import app.marmalade.tts.perf.EngineRecommender
import app.marmalade.tts.ui.screen.PreviewState
import app.marmalade.tts.ui.screen.VoicePreviewer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   OnboardingScreen
//     │
//     ├── step           ◄────── OnboardingViewModel.step (StateFlow<OnboardingStep>)
//     ├── probeDone      ◄────── OnboardingViewModel.probeDone (spinner until true)
//     ├── engines        ◄────── OnboardingViewModel.engines
//     │                              ▲
//     │                              │ EngineCatalog.visibleTo(false),
//     │                              │ Recommended first, labelled by
//     │                              │ EngineRecommender.recommend(
//     │                              │     DeviceProbeSource.probe())
//     ├── selectedIds    ◄────── OnboardingViewModel.selectedEngineIds
//     ├── installStates  ◄────── OnboardingViewModel.installStates (per engine)
//     ├── aliasCreated   ◄────── OnboardingViewModel.aliasCreated
//     │                              ▲
//     │                              │ Flow
//     │                       VoiceAliasDao.getAll().map { it.isNotEmpty() }
//     ├── voiceGroups    ◄────── OnboardingViewModel.voiceGroups (voice step)
//     │                              ▲
//     │                              │ firstVoiceGroups(pickable voices, …):
//     │                              │ probeInstalledVoiceAssets, run by
//     │                              │ prepareVoiceStep on entering the step
//     ├── selectedVoice  ◄────── the user's pick, else the default voice
//     ├── previewState   ◄────── VoicePreviewer (the row speakers)
//     │
//     └── actions ──► next() / back() / toggle(name) / installSelected()
//                  retry(name) / removeDownload(name)
//                  pickVoice(id) / togglePreview(voice) / useVoice() / finish()
//
//   installSelected()
//     │
//     ├── selectedEngineIds.value.forEach { name ->
//     │     ├── installStates.update { it + (name -> Downloading(0,…)) }
//     │     ├── installer.install(name) { progress -> updateState(name, progress) }
//     │     └── installStates.update { it + (name -> Installed | Failed) }
//     │
//     └── (UI calls next() once all-done to advance to ChooseVoice)
//
//   useVoice()
//     │
//     ├── voiceAliasDao.upsert(alias named after the voice, 1.0×, no effect)
//     ├── settings.setPrimaryAliasId(id)  — first alias is primary
//     └── step = BackgroundUnrestricted
//
//   finish()
//     │
//     ├── refuses while aliasCreated == false (no-op)
//     └── settings.setOnboarded(true)
// -----------------------------------------------------------------------------

private const val TAG = "Onboarding"

/**
 * Max wait for the first-run baked-default seed to finish before onboarding
 * decides whether to install (download) it. The seed copies ~58 MB from assets
 * — a couple of seconds — so this only ever elapses if the seed genuinely
 * failed, in which case the normal download path takes over.
 */
private const val SEED_WAIT_TIMEOUT_MS = 15_000L

/**
 * Longest the engine step hides its cards behind the phone check, counted
 * from onboarding's start (the check overlaps the Welcome step), seed wait
 * included (issue #76). The real check is the ~58 MB seed copy (a couple of
 * seconds), Kitten's model load and ~2 s of Kitten audio: under a second of
 * synthesis on the Pixel 6a (probe RTF 0.21), about 2 s at the slowest probe
 * that still gets a verdict worth having (0.9). A phone still checking after
 * 20 s renders Kitten at RTF ~5 or worse, where the verdict would be Kitten
 * alone, which is what the static flags already say. The probe's own caps
 * (15 s seed + 30 s benchmark) are too long to stare at a spinner.
 */
internal const val PROBE_WAIT_MS = 20_000L

/** The onboarding flow's steps, in order. */
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
     * Pick a voice (#87, Max's concept A): the installed voices grouped by
     * engine, each with a sample button, one already picked. "Use <voice>"
     * saves it as the first alias, named after the voice, and makes it
     * primary; [finish] refuses until an alias exists. Speed and effects
     * are left for the Aliases tab.
     *
     * When the alias table already has rows on entry (sideloaded data, or
     * setup restarted after an alias was saved) a quiet "Finish setup" moves
     * on without saving another.
     */
    ChooseVoice,

    /**
     * P-J — prompt the user to exempt Marmalade from Android's battery
     * optimisations so the foreground synth service isn't paused mid-
     * utterance when the screen sleeps. Skippable — users who decline
     * still get a working app, they just may see synthesis cut off
     * when the screen turns off during long inputs.
     */
    BackgroundUnrestricted,

    /**
     * Ask for the POST_NOTIFICATIONS runtime permission (Android 13+).
     * The "Keep engine loaded" Smart/Always modes (Smart is the default)
     * run a foreground service that posts a small "staying warm"
     * notification; without this grant Android silently suppresses it.
     * Auto-skipped on Android 12 and below, where the permission is
     * granted at install time. Skippable — declining just hides the
     * keepalive/playback notices, the engine still stays warm.
     */
    NotificationPermission,

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
 *
 * @property fit This device's predicted fit for the engine, or null while
 *   the capability probe is still running or when it gave no verdict — the
 *   card then falls back to the catalog's static
 *   [EngineDescriptor.isRecommended] flag.
 * @property isBuiltIn The engine ships inside the APK, so there is nothing
 *   to download and nothing to opt out of.
 */
data class EngineCardState(
    val descriptor: EngineDescriptor,
    val isSelected: Boolean,
    val fit: EngineFit? = null,
    val isBuiltIn: Boolean = false,
) {
    /** The card wears the Recommended pill: this device's verdict, else the catalog's flag. */
    val showsRecommended: Boolean
        get() = if (fit == null) descriptor.isRecommended else fit == EngineFit.RECOMMENDED
}

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
    private val deviceProbe: DeviceProbeSource,
    private val player: SpeechPlayer,
) : ViewModel() {

    /**
     * Clock indirection for tests, mirroring [AliasViewModel.now]. Hilt's
     * `@Inject` constructor uses the default (wall clock); tests stamp a
     * fixed value via direct construction.
     */
    internal var now: () -> Long = { System.currentTimeMillis() }

    /**
     * The notification step asks (Android 13+). Below that it moves straight
     * on, so Back from the last step skips it rather than bouncing off it
     * (#148). A var, like [now], so tests pick either.
     */
    internal var asksNotifications: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val _step = MutableStateFlow(OnboardingStep.Welcome)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    /**
     * Initial selection: the built-in Kitten (the catalog's `isRecommended`
     * flag), which stays selected because it is not a choice ([toggle]) and
     * its install step is what waits for the offline seed. When the probe
     * answers, the engine it recommends is ticked too (issue #63 follow-up,
     * Max 2026-10-12), so on a slow phone, where Kitten is the
     * recommendation, nothing else is. A user who already ticked or unticked
     * something keeps their own selection ([selectionTouched]).
     */
    private val _selectedEngineIds = MutableStateFlow<Set<String>>(
        EngineCatalog.all.filter { it.isRecommended }.map { it.name }.toSet(),
    )
    val selectedEngineIds: StateFlow<Set<String>> = _selectedEngineIds.asStateFlow()

    /** The user has toggled a card, so the probe's verdict no longer ticks one. */
    private var selectionTouched = false

    /**
     * Per-engine install state, keyed by engine name. Only present for
     * engines that have been at least once nominated for install in this
     * session — the Installing step iterates over this map.
     */
    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    /**
     * This device's engine fit + ordering, or null: still checking, or the
     * check gave no verdict ([EngineRecommender.recommend] returned null, or
     * the probe threw). [probeDone] tells the two apart.
     */
    private val _recommendation = MutableStateFlow<EngineRecommendation?>(null)
    val recommendation: StateFlow<EngineRecommendation?> = _recommendation.asStateFlow()

    /**
     * The phone check has finished: a verdict, no verdict, a failure, or
     * [PROBE_WAIT_MS] elapsing. Until then the engine step shows a spinner
     * instead of cards and can't install (issue #76), so the user never acts
     * on a list that is about to re-sort and re-tick.
     */
    private val _probeDone = MutableStateFlow(false)
    val probeDone: StateFlow<Boolean> = _probeDone.asStateFlow()

    /**
     * The cards rendered on the EnginePick step. Production engines only —
     * developer-only engines (e.g. the Pocket clean-reference build) are
     * never offered to new users in onboarding; they're an opt-in via
     * Settings afterward.
     *
     * Labels come from [_recommendation]; with no verdict (none, a failed
     * probe, or [PROBE_WAIT_MS] passing first) the catalog's static
     * `isRecommended` flag stands in. The screen shows these only once
     * [probeDone] (issue #76, Max 2026-10-13: no cards until the check
     * finishes). The card wearing the Recommended pill comes first, the
     * rest keep the catalog's order (issue #63, Max 2026-10-12:
     * "Recommended engine should be boosted to the top"). With the static
     * flag that is the built-in Kitten, already first, so a card only moves
     * when a verdict lands on another engine.
     */
    val engines: StateFlow<List<EngineCardState>> =
        combine(_recommendation, _selectedEngineIds, ::cardsFor)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = cardsFor(null, _selectedEngineIds.value),
            )

    private fun cardsFor(
        recommendation: EngineRecommendation?,
        selected: Set<String>,
    ): List<EngineCardState> {
        return EngineCatalog.visibleTo(showDeveloper = false).map { d ->
            EngineCardState(
                descriptor = d,
                isSelected = selected.contains(d.name),
                fit = recommendation?.fits?.get(d.name),
                isBuiltIn = d.name == KittenDirectVoiceCatalog.ENGINE,
            )
        }.sortedByDescending { it.showsRecommended } // stable: the rest keep catalog order
    }

    // Kick the capability probe off as soon as onboarding starts, so the
    // benchmark overlaps the Welcome step's reading time and the EnginePick
    // step usually has its answer by the time the user gets there. Declared
    // after [_recommendation] and [_probeDone] because the launch body
    // assigns them and viewModelScope dispatches eagerly on an unconfined
    // test dispatcher.
    init {
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val check = async { checkPhone() }
            val inTime = withTimeoutOrNull(PROBE_WAIT_MS) { check.join() } != null
            if (inTime) {
                val recommendation = check.await()
                // Tick before publishing the verdict, and publish it before
                // [probeDone], so the cards appear sorted, labelled and
                // ticked in one frame.
                if (recommendation != null && !selectionTouched) {
                    _selectedEngineIds.update { it + recommendation.recommended }
                }
                _recommendation.value = recommendation
                _probeDone.value = true
                Log.i(TAG, "Phone check done in ${SystemClock.elapsedRealtime() - started} ms: " +
                    (recommendation?.recommended ?: "no verdict"))
            } else {
                // Too slow: show the static list now. A late verdict still
                // relabels and re-sorts it (the screen scrolls back to the
                // top), but no longer ticks anything: the user may be about
                // to tap Install.
                _probeDone.value = true
                Log.i(TAG, "Phone check passed ${PROBE_WAIT_MS} ms; showing the static list")
                _recommendation.value = check.await()
            }
        }
    }

    /** The probe's verdict, or null when it has none or fails. */
    private suspend fun checkPhone(): EngineRecommendation? = try {
        // The Tier 2 benchmark synthesizes on the baked Kitten engine, so
        // it waits on the same first-run asset seed runInstall waits for.
        // If the seed never lands the probe still answers, from CPU
        // topology alone.
        withTimeoutOrNull(SEED_WAIT_TIMEOUT_MS) {
            settings.bakedDefaultSeeded.firstOrNull { it }
        }
        EngineRecommender.recommend(
            deviceProbe.probe(),
            offered = EngineCatalog.visibleTo(showDeveloper = false).map { it.name },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Phone check failed; showing the static list", e)
        null
    }

    /** Toggle the selection state of [engineName] in the picker. */
    fun toggle(engineName: String) {
        // The baked engine is already inside the APK — there is nothing to
        // opt out of, and dropping it would strand a fresh offline install
        // with no usable voice.
        if (engineName == KittenDirectVoiceCatalog.ENGINE) return
        selectionTouched = true
        _selectedEngineIds.update { current ->
            if (current.contains(engineName)) current - engineName else current + engineName
        }
    }

    /** Move forward through the steps. The terminal step is [OnboardingStep.SystemDefault]. */
    fun next() {
        _step.value = when (_step.value) {
            OnboardingStep.Welcome -> OnboardingStep.EnginePick
            OnboardingStep.EnginePick -> OnboardingStep.Installing
            OnboardingStep.Installing -> OnboardingStep.ChooseVoice
            OnboardingStep.ChooseVoice -> OnboardingStep.BackgroundUnrestricted
            OnboardingStep.BackgroundUnrestricted -> OnboardingStep.NotificationPermission
            OnboardingStep.NotificationPermission -> OnboardingStep.SystemDefault
            OnboardingStep.SystemDefault -> OnboardingStep.SystemDefault
        }
    }

    /**
     * Move back: the on-screen Back buttons and system Back (#148) alike. The
     * Welcome step is the back-stop — no-op when already there (system Back
     * leaves the app there instead). The progress step has no Back button and
     * stays put while anything downloads: going back would leave the
     * downloads running behind a picker that could start them again.
     */
    fun back() {
        stopPreview()
        _step.value = when (_step.value) {
            OnboardingStep.Welcome -> OnboardingStep.Welcome
            OnboardingStep.EnginePick -> OnboardingStep.Welcome
            OnboardingStep.Installing -> if (_installStates.value.values.any { it.inFlight }) {
                OnboardingStep.Installing
            } else {
                OnboardingStep.EnginePick
            }
            OnboardingStep.ChooseVoice -> OnboardingStep.Installing
            OnboardingStep.BackgroundUnrestricted -> OnboardingStep.ChooseVoice
            OnboardingStep.NotificationPermission -> OnboardingStep.BackgroundUnrestricted
            OnboardingStep.SystemDefault -> if (asksNotifications) {
                OnboardingStep.NotificationPermission
            } else {
                OnboardingStep.BackgroundUnrestricted
            }
        }
    }

    private val InstallState.inFlight: Boolean
        get() = this is InstallState.Downloading || this is InstallState.Extracting

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
     * "Continue" affordance immediately on the next step. Also a no-op until
     * [probeDone]: the selection isn't final before the verdict.
     */
    fun installSelected() {
        // The verdict still has to tick the recommended engine (issue #76).
        // The screen disables the button until then; this holds if it doesn't.
        if (!_probeDone.value) return
        _step.value = OnboardingStep.Installing
        val toInstall = _selectedEngineIds.value.toList()
        if (toInstall.isEmpty()) return

        for (name in toInstall) {
            updateInstallState(name, InstallState.Downloading(0L, 0L, ""))
            viewModelScope.launch { runInstall(name) }
        }
    }

    /**
     * Skip engine installation entirely — download nothing (regardless
     * of what's pre-selected) and jump straight to [OnboardingStep.ChooseVoice],
     * which then offers the built-in Kitten voices. Engines can always be
     * installed later from the Engines tab.
     */
    fun skipEngineInstall() {
        _step.value = OnboardingStep.ChooseVoice
    }

    /**
     * Retry an install that failed on the progress screen. Reuses the
     * existing [installSelected] machinery but for a single engine.
     */
    fun retry(engineName: String) {
        updateInstallState(engineName, InstallState.Downloading(0L, 0L, ""))
        viewModelScope.launch { runInstall(engineName) }
    }

    /**
     * Drive the installer and mirror every state transition (Downloading,
     * Extracting, Installed/Failed) into [_installStates] so the
     * onboarding progress UI never freezes mid-install. The `onProgress`
     * callback that `installer.install` accepts only fires for Downloading
     * updates — subscribing to the installer's state flow is the only way
     * the Extracting phase reaches the UI.
     *
     * The `result.fold` block at the end is kept as a defensive fallback
     * for the (rare) case where the state-flow collector gets cancelled
     * before the final emission lands.
     */
    private suspend fun runInstall(engineName: String) {
        // The baked default (Kitten) is seeded from APK assets by a
        // fire-and-forget coroutine at app start. If the user reaches this
        // step before that copy finishes, wait for it rather than racing it
        // into a download — otherwise a fresh OFFLINE install would attempt a
        // (failing) network fetch. Bounded so a genuine seed failure still
        // falls through to the normal download path instead of hanging.
        if (engineName == KittenDirectVoiceCatalog.ENGINE) {
            // firstOrNull (not first): production's DataStore flow is infinite
            // so it suspends until `true`; a finite test flow that only ever
            // emits `false` returns null instead of throwing NoSuchElement.
            withTimeoutOrNull(SEED_WAIT_TIMEOUT_MS) {
                settings.bakedDefaultSeeded.firstOrNull { it }
            }
        }
        // Skip the download if the engine is already installed — notably the
        // baked default just seeded above. Re-downloading it would need the
        // network and defeat the "works offline on first run" guarantee.
        if (installer.verify(engineName) is InstallState.Installed) {
            updateInstallState(engineName, InstallState.Installed)
            return
        }
        val stateJob = viewModelScope.launch {
            installer.state(engineName).collect { s ->
                updateInstallState(engineName, s)
            }
        }
        val result = installer.install(engineName) { /* state flow handles updates */ }
        updateInstallState(engineName, installOutcome(result, installer.state(engineName).value))
        stateJob.cancel()
    }

    /**
     * "Remove download" on a failed row: delete the partial archive the failed
     * install kept for resume. The row stays Failed (Retry still offered, and
     * Continue stays enabled) — only the leftover-bytes count drops to zero,
     * which hides the button.
     */
    fun removeDownload(engineName: String) {
        viewModelScope.launch {
            installer.discardDownload(engineName).onSuccess {
                _installStates.update { current ->
                    val failed = current[engineName] as? InstallState.Failed
                        ?: return@update current
                    current + (engineName to failed.copy(partialDownloadBytes = 0L))
                }
            }
        }
    }

    // -- ChooseVoice step state (#87) -------------------------------------------

    /**
     * True once at least one alias exists in the DB. Checked inside [finish]
     * and [skipVoiceStep], so the gate can't be bypassed by a stale UI
     * snapshot.
     */
    val aliasCreated: StateFlow<Boolean> = aliasDao.getAll()
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * The phone's language, which the voice step lists first. Indirection
     * for tests, like [now].
     */
    internal var phoneLocale: () -> Locale = { Locale.getDefault() }

    /**
     * What is on disk, probed by [prepareVoiceStep] when the voice step opens
     * (every install has settled by then); null until that first probe, so
     * the step doesn't flash its empty state. Room is seeded with every
     * catalog voice and [VoiceMeta.isInstalled] is never flipped in
     * production, so this is the only honest "can it speak" signal, the same
     * one the alias editor uses.
     */
    private val _installedAssets = MutableStateFlow<InstalledVoiceAssets?>(null)

    /**
     * The voice step's list: voices that will actually synthesize (on disk,
     * a VITS voice with its own pack; released only, matching the engine
     * cards), grouped and ordered by [firstVoiceGroups]. Null until
     * [prepareVoiceStep] has probed the disk; empty when nothing can speak.
     */
    val voiceGroups: StateFlow<List<VoiceGroup>?> =
        combine(voiceDao.getAll(), _installedAssets, _recommendation) { all, assets, recommendation ->
            assets ?: return@combine null
            firstVoiceGroups(
                voices = all.pickableVoices(assets, showDeveloper = false),
                engineOrder = listOf(preferredEngine(assets, recommendation)) +
                    EngineCatalog.visibleTo(showDeveloper = false).map { it.name },
                phone = phoneLocale(),
                recommendedEngine = recommendation?.recommended
                    ?: EngineCatalog.all.firstOrNull { it.isRecommended }?.name,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /** The voice the user tapped, or null to take the step's default. */
    private val _pickedVoiceId = MutableStateFlow<String?>(null)

    /**
     * The voice "Use …" will save: the user's pick while it is still listed,
     * else the default voice ([defaultVoice]). Null only with nothing to pick.
     */
    val selectedVoice: StateFlow<VoiceMeta?> =
        combine(_pickedVoiceId, voiceGroups) { picked, groups ->
            val listed = groups.orEmpty().flatMap { it.voices }
            listed.firstOrNull { it.id == picked } ?: groups.orEmpty().defaultVoice()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * An alias already existed when the voice step opened (sideloaded data,
     * or setup restarted after an alias was saved, e.g. the app was closed
     * mid-setup), so it offers "Finish setup". Fixed at
     * opening, not live: the alias [useVoice] saves would otherwise flash the
     * button in while the step leaves.
     */
    private val _aliasOnEntry = MutableStateFlow(false)
    val aliasOnEntry: StateFlow<Boolean> = _aliasOnEntry.asStateFlow()

    /**
     * Probe the disk for [voiceGroups]. Called by the voice step each time it
     * opens (again after Back to the downloads and Continue, keeping the
     * user's pick while it is still listed).
     */
    fun prepareVoiceStep() {
        viewModelScope.launch {
            _aliasOnEntry.value = aliasDao.getAll().first().isNotEmpty()
            // Cloud stays out: onboarding offers catalog engines only, and a
            // new user has no provider key anyway.
            _installedAssets.value =
                probeInstalledVoiceAssets(installer, anyCloudKeySet = false)
        }
    }

    /** Tapping a row picks its voice. It doesn't stop a sample that is playing. */
    fun pickVoice(voiceId: String) {
        _pickedVoiceId.value = voiceId
    }

    private val previewer = VoicePreviewer(player, viewModelScope)
    val previewState: StateFlow<PreviewState> = previewer.state

    /**
     * Play/stop a row's sample (#86's previewer: one at a time, through
     * MarmaladeSynthService). Every voice here is on-device, so no cloud
     * guard is needed. Hearing a voice never picks it.
     */
    fun togglePreview(voice: VoiceMeta, phrase: String) {
        previewer.toggle(voice, phrase)
    }

    fun stopPreview() {
        previewer.stop()
    }

    /** Guards [useVoice] against a double tap saving two aliases. */
    private var savingVoice = false

    /**
     * Save the selected voice as the first alias and make it primary: named
     * after the voice ([aliasNameFor], Max's pick in the #87 lab), at 1.0×
     * with no effect. Speed, effect and expressiveness stay at their
     * defaults; the Aliases tab has them.
     *
     * Then the battery step, on every path. Before #87, "Use defaults"
     * skipped it and "Save and continue" didn't; with one path left, everyone
     * gets the (skippable) battery ask, because long reads with the screen
     * off are what it protects. The notification ask follows it, as before.
     */
    fun useVoice() {
        val voice = selectedVoice.value ?: return
        if (savingVoice) return
        savingVoice = true
        stopPreview()
        viewModelScope.launch {
            try {
                val name = aliasNameFor(voice)
                // Reuse the id if an alias by this name already exists (Back,
                // then Use again), so it is edited rather than duplicated.
                // Names are unique; ids are what routing points at.
                val aliasId = aliasDao.findByName(name)?.id ?: VoiceAlias.newId()
                aliasDao.upsert(
                    VoiceAlias(
                        name = name,
                        engine = voice.engine,
                        voiceId = voice.id,
                        speed = 1.0f,
                        effectPreset = EffectPreset.NONE.name,
                        createdAt = now(),
                        id = aliasId,
                    ),
                )
                settings.setPrimaryAliasId(aliasId)
                _step.value = OnboardingStep.BackgroundUnrestricted
            } finally {
                savingVoice = false
            }
        }
    }

    /**
     * P-J — advance from the BackgroundUnrestricted step to the
     * NotificationPermission step. Called by both "Continue" (after grant)
     * and "Skip" — the onboarding flow doesn't fail if the user declines,
     * just leaves a less-reliable run.
     */
    fun advancePastBackground() {
        if (_step.value == OnboardingStep.BackgroundUnrestricted) {
            _step.value = OnboardingStep.NotificationPermission
        }
    }

    /**
     * Advance from the NotificationPermission step to SystemDefault.
     * Driven by "Allow" (after grant), "Skip", and the auto-skip on
     * Android 12 and below. Declining never blocks onboarding.
     */
    fun advancePastNotifications() {
        if (_step.value == OnboardingStep.NotificationPermission) {
            _step.value = OnboardingStep.SystemDefault
        }
    }

    /**
     * "Finish setup" on the voice step: an alias already existed as it opened
     * ([aliasOnEntry]), so move on without saving another.
     * Self-heal the primary if it's unset, then go to the battery step: the
     * battery, notification and system-TTS steps still lie ahead.
     */
    fun skipVoiceStep(): Boolean {
        if (!aliasCreated.value) return false
        stopPreview()
        viewModelScope.launch {
            if (settings.primaryAliasId.first() == null) {
                aliasDao.getAll().first().firstOrNull()?.let { first ->
                    settings.setPrimaryAliasId(first.id)
                }
            }
        }
        _step.value = OnboardingStep.BackgroundUnrestricted
        return true
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
    fun finish(): Boolean {
        if (!aliasCreated.value) return false
        viewModelScope.launch {
            // Self-heal: if there's no primary set but at least one alias
            // exists, auto-promote the first alias to primary.
            if (settings.primaryAliasId.first() == null) {
                aliasDao.getAll().first().firstOrNull()?.let { first ->
                    settings.setPrimaryAliasId(first.id)
                }
            }
            settings.setOnboarded(true)
        }
        return true
    }

    /**
     * The engine the voice step leads with, unless another installed engine
     * speaks the phone's language and this one doesn't ([firstVoiceGroups]):
     * the downloadable engine this device was recommended (issue #19: Pocket
     * on most phones), then Kokoro over Kitten. "Installed" is what
     * [prepareVoiceStep] found on disk (the baked Kitten after a skipped
     * engine step).
     */
    private fun preferredEngine(
        assets: InstalledVoiceAssets,
        recommendation: EngineRecommendation?,
    ): String {
        val installedEngines = EngineCatalog.visibleTo(showDeveloper = false)
            .map { it.name }
            .filter { it in assets.engines }
        // The baked Kitten is "recommended" on slow phones only as the floor;
        // a download the user chose there still wins.
        val recommended = recommendation?.recommended
            ?.takeIf { it != KittenDirectVoiceCatalog.ENGINE }
        return installedEngines.firstOrNull { it == recommended }
            ?: installedEngines.firstOrNull { it.startsWith("kokoro") }
            ?: installedEngines.firstOrNull { it.startsWith("kitten") }
            ?: installedEngines.firstOrNull()
            ?: KittenDirectVoiceCatalog.ENGINE
    }

    override fun onCleared() {
        super.onCleared()
        stopPreview()
    }

    private fun updateInstallState(engineName: String, state: InstallState) {
        _installStates.update { current -> current + (engineName to state) }
    }
}

/**
 * The row state an install [result] settles on. A failure prefers the
 * installer's own [InstallState.Failed] ([live], published before `install`
 * returns) because it carries the failure kind the screen localizes and the
 * partial-download size behind "Remove download"; the exception is the
 * fallback. A blank reason makes the screen use its generic failure string.
 */
internal fun installOutcome(result: Result<Unit>, live: InstallState): InstallState =
    result.fold(
        onSuccess = { InstallState.Installed },
        onFailure = { err -> live as? InstallState.Failed ?: InstallState.Failed.from(err, "") },
    )
