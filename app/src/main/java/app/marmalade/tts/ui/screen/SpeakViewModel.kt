package app.marmalade.tts.ui.screen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectResolver
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.install.EngineCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   SpeakScreen
//     │
//     ├── text state ◄────── SpeakViewModel.text  (MutableStateFlow)
//     ├── current voice ◄─── SpeakViewModel.currentVoice
//     │                          ▲
//     │                          │ combine(id, voices) { voices.firstOrNull(...) }
//     │                          │
//     │            SettingsRepository.defaultVoiceId  +  VoiceMetaDao.getAll()
//     │
//     │   (combining the two flows means once the seed lands and getAll()
//     │    re-emits, the lookup re-runs — no stale null past initial seed)
//     │
//     ├── playback ◄──────── SpeakViewModel.playbackState
//     │                          ▲
//     │                          │
//     │                  Synthesizer.speak(text, voiceId, speed, effect)
//     │
//     ├── aliases   ◄──────── SpeakViewModel.aliases (VoiceAliasDao.getAll)
//     ├── activeAlias ◄────── SpeakViewModel.activeAlias
//     │                          ▲
//     │                          │ set by applyAlias(name); also auto-set on
//     │                          │ VM init from settings.primaryAliasName so
//     │                          │ effects/speed configured on the primary
//     │                          │ alias fire on first Speak. Cleared when
//     │                          │ defaultVoiceId emits a value that doesn't
//     │                          │ match the voice we last applied (manual
//     │                          │ voice pick wins over auto-applied primary).
//     │
//     ├── currentEffectBlocks ◄ SpeakViewModel.currentEffectBlocks (resolved
//     │                       from alias.effectId by applyAlias; passed
//     │                       through to Synthesizer on speak())
//     ├── currentSpeed  ◄──── SpeakViewModel.currentSpeed  (set by applyAlias;
//     │                       passed through to Synthesizer on speak())
//     │
//     └── actions ──► onTextChanged / speak() / cancel() / applyAlias(name)
// -----------------------------------------------------------------------------

/** Coarse UI state for the playback area on the Speak screen. */
sealed class PlaybackState {
    /** Nothing playing. Default. */
    object Idle : PlaybackState()

    /** Synthesis or playback is in flight. UI shows the speaking mascot. */
    object Speaking : PlaybackState()

    /** Engine assets aren't bundled — see STUBS.md (P0). */
    object ModelMissing : PlaybackState()

    /** Anything else — synth/JNI/audiotrack error. `message` is user-facing. */
    data class Error(val message: String) : PlaybackState()
}

/**
 * ViewModel for [SpeakScreen].
 *
 * Holds the text field state, resolves the currently selected voice from
 * `SettingsRepository`, and routes the "Speak" button through [Synthesizer].
 *
 * The text field state lives here (not in the composable) so it survives
 * configuration changes — typing a paragraph and rotating shouldn't lose
 * the draft.
 *
 * `onCleared()` cancels any in-flight playback so leaving the app or
 * navigating away (resulting in ViewModel teardown) doesn't leave audio
 * playing in the background.
 */
@HiltViewModel
class SpeakViewModel @Inject constructor(
    private val synthesizer: SpeechPlayer,
    private val settings: SettingsRepository,
    private val voiceDao: VoiceMetaDao,
    private val aliasDao: VoiceAliasDao,
    private val effectResolver: EffectResolver,
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * The voice ID we most recently applied via [applyAlias]. When the
     * observed `defaultVoiceId` diverges from this (the user manually
     * picked a different voice), [activeAlias] clears — the alias chip
     * stops looking "applied".
     *
     * `null` means "no alias has been applied since launch" — every
     * defaultVoiceId emission in that state is treated as a manual pick
     * and so doesn't clear anything (since there's nothing to clear).
     */
    @Volatile
    private var expectedAliasVoiceId: String? = null

    private val _activeAlias = MutableStateFlow<String?>(null)
    val activeAlias: StateFlow<String?> = _activeAlias.asStateFlow()

    /**
     * Effect chain currently associated with the active alias, resolved from
     * the alias's `effectId` via [EffectResolver]. Defaults to the empty
     * (dry) chain; applyAlias(...) writes to it; speak() passes it through to
     * [SpeechPlayer]. Resets to empty when the user picks a voice manually
     * (alias chip clears).
     */
    private val _currentEffectBlocks = MutableStateFlow<List<EffectBlock>>(emptyList())
    val currentEffectBlocks: StateFlow<List<EffectBlock>> = _currentEffectBlocks.asStateFlow()

    /**
     * Speed multiplier currently associated with the active alias. Defaults
     * to 1.0 (native pace); applyAlias(...) writes to it; speak() passes
     * it through to [SpeechPlayer]. Resets to 1.0 when the user picks a
     * voice manually (alias chip clears).
     *
     * v0.1 has no Speak-screen speed slider — alias application is the only
     * way to set this. The chip subtitle ("Bella · 1.0×") reads from the
     * alias row, not this StateFlow, so no UI binding is needed.
     */
    private val _currentSpeed = MutableStateFlow(1.0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()

    /**
     * espeak language code carried over from the active alias's
     * [app.marmalade.tts.data.db.VoiceAlias.phonemizationLanguage]. Null
     * = engine default (KokoroDirect auto-derives from voice prefix;
     * other engines ignore). Cleared on manual voice change.
     */
    private val _currentPhonemizationLanguage = MutableStateFlow<String?>(null)
    val currentPhonemizationLanguage: StateFlow<String?> = _currentPhonemizationLanguage.asStateFlow()

    /**
     * The voice the user has chosen as default. Composed from two flows so
     * the lookup re-runs both when the user picks a different voice AND
     * when the underlying voice catalog changes (e.g. the first-launch
     * seed lands after the VM was already constructed — see Blocker #2 in
     * the v0.1 whole-project review).
     *
     * Emits `null` only transiently — before either upstream has emitted,
     * or if the persisted ID points at a voice that isn't (yet) in the
     * catalog. Once the seed lands, `voiceDao.getAll()` re-emits and the
     * combine resolves a non-null value.
     *
     * The `onEach` side effect on `defaultVoiceId` (applied BEFORE the
     * combine so it only fires on id changes, not catalog refreshes)
     * breaks out of the alias chip selection when the user picks a voice
     * manually: if a new defaultVoiceId arrives that doesn't match what
     * applyAlias most recently set, activeAlias clears.
     */
    val currentVoice: StateFlow<VoiceMeta?> = combine(
        settings.defaultVoiceId.onEach { id ->
            // Unstick a stale ModelMissing/Error verdict from a PREVIOUS engine
            // when the selected voice changes. A failed speak() on an
            // uninstalled engine sets ModelMissing (disables Speak + shows the
            // "Tap to install <engine>" banner); switching to a different
            // (installed) engine — via an alias chip or a manual pick, both of
            // which change defaultVoiceId — must clear it, or the banner re-renders
            // with the NEW engine's name and falsely claims an installed engine is
            // missing (observed: pick uninstalled Kitten Mini, then switch to
            // installed Pocket → "Tap to install Pocket TTS" + Speak stuck disabled).
            // Mirrors onTextChanged's retry-intent reset.
            val ps = _playbackState.value
            if (ps is PlaybackState.ModelMissing || ps is PlaybackState.Error) {
                _playbackState.value = PlaybackState.Idle
            }
            val expected = expectedAliasVoiceId
            if (expected != null && id != expected) {
                // Manual voice change — drop everything the alias set up so
                // the next speak() doesn't smuggle stale effect/speed onto a
                // voice the user just hand-picked.
                _activeAlias.value = null
                _currentEffectBlocks.value = emptyList()
                _currentSpeed.value = 1.0f
                _currentPhonemizationLanguage.value = null
                expectedAliasVoiceId = null
            }
        },
        voiceDao.getAll(),
    ) { id, voices ->
        voices.firstOrNull { it.id == id }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Saved aliases, observed from Room. Used by the chip row on the
     * Speak screen so adding/editing/deleting an alias on the Alias
     * screen reflects here without an explicit refresh signal.
     */
    val aliases: StateFlow<List<VoiceAlias>> = aliasDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    init {
        // v0.1.18: auto-apply the user's primary alias on first composition
        // so effects/speed configured on that alias actually take effect
        // when the user opens Speak. Previously the chip had to be tapped
        // manually for any alias-bound effect to fire, which made it look
        // like "voice effects don't work."
        //
        // Runs once per VM lifecycle (init). If the user manually picks a
        // different voice later, the onEach side effect on
        // `settings.defaultVoiceId` clears the alias + effect — that
        // override sticks until the next ViewModel construction.
        autoApplyPrimaryAlias()

        // alpha.10.M: re-sync the cached alias snapshot (voice/speed/
        // effect/phonemizationLanguage) when the underlying alias row
        // changes while it's still the active selection. Without this,
        // editing an alias on the Alias screen and returning to Speak
        // would run synthesis with the pre-edit values (chip says alias
        // is active but the cached StateFlows are stale).
        combine(_activeAlias, aliases) { active, rows ->
            if (active == null) null else rows.firstOrNull { it.name == active }
        }
            .onEach { fresh ->
                if (fresh != null) {
                    _currentEffectBlocks.value = effectResolver.blocksFor(fresh.effectId)
                    _currentSpeed.value = fresh.speed
                    _currentPhonemizationLanguage.value = fresh.phonemizationLanguage
                    // The edit may also have changed the alias's VOICE.
                    // Follow it (mirroring applyAlias) or the chip keeps
                    // claiming the alias while synthesis runs the pre-edit
                    // voice until the user re-taps the chip. Guarded on the
                    // catalog lookup like applyAlias's missing-voice branch,
                    // and expectedAliasVoiceId is set first so the
                    // defaultVoiceId emission isn't misread as a manual pick.
                    val expected = expectedAliasVoiceId
                    if (expected != null && fresh.voiceId != expected &&
                        voiceDao.findById(fresh.voiceId) != null
                    ) {
                        expectedAliasVoiceId = fresh.voiceId
                        settings.setDefaultVoiceId(fresh.voiceId)
                    }
                }
            }
            .launchIn(viewModelScope)

        // P-D: eagerly pre-load the engine that backs the current voice so a
        // subsequent Speak tap doesn't pay model-load + warmup as part of
        // TTFA. Fires whenever the selection changes (manual pick or alias
        // application). Synthesizer.preload swallows errors — ModelMissing
        // is fine here, it surfaces on the actual speak().
        currentVoice
            .onEach { voice ->
                if (voice != null) {
                    viewModelScope.launch {
                        val loaded = synthesizer.preload(voice.id)
                        // If the selected voice's engine IS present (preload
                        // succeeded), clear a stale ModelMissing left by an
                        // earlier failed speak on a now-installed engine.
                        // Only fires on voice CHANGE: the WhileSubscribed
                        // flows never actually restart (this VM's own
                        // launchIn collectors keep them permanently
                        // subscribed) and StateFlow dedups an unchanged
                        // voice — screen re-entry with the same voice goes
                        // through [onScreenEntered] instead.
                        if (loaded && _playbackState.value is PlaybackState.ModelMissing) {
                            _playbackState.value = PlaybackState.Idle
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Called by the Speak screen every time it (re-)enters composition.
     * Re-probes the current voice's engine when the last speak() ended in
     * [PlaybackState.ModelMissing]: installing the engine from Settings →
     * Engines and navigating back doesn't change the voice, so no flow
     * re-emits — without this hook the "Tap to install" banner stayed
     * stuck (Speak disabled) until the user typed or switched voice.
     */
    fun onScreenEntered() {
        if (_playbackState.value !is PlaybackState.ModelMissing) return
        val voice = currentVoice.value ?: return
        viewModelScope.launch {
            if (synthesizer.preload(voice.id) &&
                _playbackState.value is PlaybackState.ModelMissing
            ) {
                _playbackState.value = PlaybackState.Idle
            }
        }
    }

    /**
     * UI hook for the text field's onValueChange.
     *
     * Typing also unsticks a previous [PlaybackState.ModelMissing] or
     * [PlaybackState.Error] — those are "the last speak() failed" markers
     * that disable the Speak button, and a fresh edit signals the user
     * intends to retry (with hopefully the engine installed by now, or
     * a different sentence that avoids whatever error path). [Speaking]
     * is not reset — that's a mid-synthesis state where the result is
     * still in flight, so typing shouldn't yank the button out from under
     * the running coroutine.
     */
    fun onTextChanged(value: String) {
        _text.value = value
        val state = _playbackState.value
        if (state is PlaybackState.ModelMissing || state is PlaybackState.Error) {
            _playbackState.value = PlaybackState.Idle
        }
    }

    /**
     * Resolve the primary alias from settings and apply it. No-op if none
     * is set, which is the case right after onboarding for users who
     * skipped the alias step. Logs (not throws) on misconfiguration so a
     * stale primary pointer never blocks the Speak screen from rendering.
     */
    private fun autoApplyPrimaryAlias() {
        viewModelScope.launch {
            val primary = settings.primaryAliasName.first()
            if (primary.isNullOrBlank()) {
                Log.d(TAG, "autoApplyPrimaryAlias: no primary set, leaving defaults")
                return@launch
            }
            applyAlias(primary)
        }
    }

    /**
     * Apply the alias with [name]: switch voice, speed, and effect to the
     * alias's saved values, and mark it as the active alias for chip
     * highlighting.
     *
     * No-ops (with a warn-level log) if the alias isn't found. Engine
     * mismatches (v0.1 only ships Kitten) log a warning but continue —
     * future engines will work without a code change.
     *
     * Voice changes go through [SettingsRepository.setDefaultVoiceId] so
     * the picker, the system-TTS callback path, and this screen all
     * agree on what's selected.
     */
    fun applyAlias(name: String) {
        viewModelScope.launch {
            val alias = aliasDao.findByName(name)
            if (alias == null) {
                Log.w(TAG, "applyAlias($name): no alias by that name; ignoring")
                return@launch
            }

            // Warn only on truly unknown engines. EngineCatalog enumerates
            // every engine the app knows how to install (kokoro + kitten as
            // of v0.1.16); anything outside that set is a stale alias from
            // a future build that downgraded, or junk we shouldn't trust.
            if (EngineCatalog.byName(alias.engine) == null) {
                Log.w(
                    TAG,
                    "applyAlias($name): engine '${alias.engine}' not in catalog; " +
                        "proceeding with voice/speed/effect only",
                )
            }

            val voice = voiceDao.findById(alias.voiceId)
            if (voice == null) {
                Log.w(
                    TAG,
                    "applyAlias($name): voiceId '${alias.voiceId}' not in catalog; " +
                        "skipping voice change",
                )
                // No voice change to predict, so seed `expectedAliasVoiceId`
                // with the *current* voice id. That way the next manual
                // voice change diverges and clears the chip — without this,
                // the chip would stay selected indefinitely (cosmetic in
                // v0.1's static catalog, but matters once engines become
                // dynamic and voices can disappear).
                expectedAliasVoiceId = currentVoice.value?.id
            } else {
                // Record the expected voice BEFORE persisting so the
                // upcoming defaultVoiceId emission isn't misread as a
                // manual voice pick.
                expectedAliasVoiceId = alias.voiceId
                settings.setDefaultVoiceId(alias.voiceId)
            }

            _currentEffectBlocks.value = effectResolver.blocksFor(alias.effectId)
            _currentSpeed.value = alias.speed
            _currentPhonemizationLanguage.value = alias.phonemizationLanguage
            _activeAlias.value = alias.name
        }
    }

    /**
     * Synthesize and play the current text with the current voice. No-op
     * when:
     *   - the text is blank / whitespace, or
     *   - playback is already in flight (state != Idle), or
     *   - no voice is currently resolved (race during initial load).
     */
    fun speak() {
        val currentText = _text.value
        if (currentText.isBlank()) return
        if (_playbackState.value is PlaybackState.Speaking) return
        val voiceId = currentVoice.value?.id ?: return

        val effectBlocks = _currentEffectBlocks.value
        val speed = _currentSpeed.value
        val language = _currentPhonemizationLanguage.value
        _playbackState.value = PlaybackState.Speaking
        viewModelScope.launch {
            val result = synthesizer.speak(currentText, voiceId, speed, effectBlocks, language)
            _playbackState.value = result.fold(
                onSuccess = { PlaybackState.Idle },
                onFailure = { err ->
                    when (err) {
                        is SynthesizerException.ModelMissing -> PlaybackState.ModelMissing
                        is SynthesizerException.SynthesisFailed -> PlaybackState.Error(
                            err.message ?: "Synthesis failed",
                        )
                        else -> PlaybackState.Error(err.message ?: "Unknown error")
                    }
                },
            )
        }
    }

    /** Stop any in-flight playback and return to Idle. Used by the UI's stop affordance. */
    fun cancel() {
        synthesizer.cancel()
        _playbackState.value = PlaybackState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        synthesizer.cancel()
    }

    companion object {
        private const val TAG = "SpeakViewModel"
    }
}
