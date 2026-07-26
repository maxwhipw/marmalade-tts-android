package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.VoicePath
import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.EffectDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AliasScreen
//     │
//     ├── aliases   ◄────── AliasViewModel.aliases
//     │                          ▲
//     │                          │ Flow
//     │                  VoiceAliasDao.getAll()
//     │
//     ├── editor    ◄────── AliasViewModel.editorState
//     │                          ▲
//     │                          │ MutableStateFlow
//     │                          │
//     │            openEditor(existing?) / dismissEditor()
//     │            onEditor{Name,Engine,Voice,Speed,Effect}Change(...)
//     │
//     ├── engines   ◄────── AliasViewModel.engines (EngineCatalog, dev-filtered)
//     ├── voicesFor ◄────── AliasViewModel.voicesForSelectedEngine
//     │                          ▲
//     │                          │ flatMapLatest(editorState.engine)
//     │                  VoiceMetaDao.getByEngine(engine)
//     │
//     ├── primaryAliasName ◄────── AliasViewModel.primaryAliasName
//     │                                  ▲
//     │                                  │ Flow
//     │                          SettingsRepository.primaryAliasName
//     │
//     └── actions
//          ├── save()    → validate → VoiceAliasDao.upsert(...)
//          │                + auto-promote first alias to primary if none set,
//          │                + retarget primary on rename of current primary.
//          ├── delete(n) → VoiceAliasDao.delete(n) + clear primary if it was n.
//          └── setPrimary(n) → SettingsRepository.setPrimaryAliasName(n)
// -----------------------------------------------------------------------------

/** Why an attempted save was rejected. UI shows this inline under the name field. */
sealed class SaveError {
    /** Name fails the `^[a-z][a-z0-9_-]*$` regex (blank, has spaces, etc). */
    object InvalidName : SaveError()

    /**
     * Editing-mode is creating a new alias whose name collides with an
     * existing one. Editing an existing alias never triggers this.
     */
    object NameTaken : SaveError()

    /** The selected voice ID is missing or not present in the catalog for the engine. */
    object MissingVoice : SaveError()
}

/**
 * Working state of the alias editor dialog/sheet.
 *
 * `isNew` distinguishes "creating a fresh alias" from "editing one that
 * already exists" — only the former is blocked by name collisions.
 *
 * `error` is the most recent rejection (cleared on the next field edit
 * so the user gets immediate feedback when they fix the offending field).
 */
data class EditorState(
    val isOpen: Boolean = false,
    val isNew: Boolean = true,
    val originalName: String? = null,
    val name: String = "",
    val engine: String = "",
    val voiceId: String = "",
    val speed: Float = 1.0f,
    /** Selected effect's [Effect.id], or null for "No effect" (dry). */
    val effectId: String? = null,
    /**
     * espeak language code (e.g. "en-us", "ja"). Null = "Auto" — engines
     * decide (KokoroDirect auto-derives from voice prefix; others ignore).
     */
    val phonemizationLanguage: String? = null,
    val error: SaveError? = null,
)

/**
 * ViewModel for [AliasScreen].
 *
 * Owns the list of saved aliases (read from Room as a Flow) and the
 * working state of the create/edit dialog. Validation lives here — see
 * [VoiceAlias.isValidName] for the syntactic rule and [save] for the
 * uniqueness check.
 *
 * No injection of [SpeakViewModel] — the speak screen's `applyAlias`
 * does its own lookup. Keeping the two ViewModels independent means a
 * future "Try this alias" preview button on the alias screen can live
 * here without coupling to playback state.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AliasViewModel @Inject constructor(
    private val aliasDao: VoiceAliasDao,
    private val voiceDao: VoiceMetaDao,
    private val settings: SettingsRepository,
    private val installer: EngineInstaller,
    private val voicePaths: VoicePathResolver,
    effectDao: EffectDao,
) : ViewModel() {

    /**
     * Where [alias]'s voice sits in the source › model › voice hierarchy.
     *
     * Resolved on demand rather than cached: it reads the in-memory provider
     * descriptor, and an alias list is a handful of rows.
     */
    fun voicePathFor(alias: VoiceAlias): VoicePath =
        voicePaths.resolve(alias.voiceId, alias.engine)

    /**
     * All effects (built-in + custom), for the editor's effect picker. The
     * picker writes the chosen [Effect.id] into the alias's `effectId`; the
     * synth path resolves it to a chain. A null selection = "No effect" (dry).
     */
    val effects: StateFlow<List<Effect>> = effectDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Clock indirection for tests. The Hilt-injected constructor uses
     * the default (wall clock); tests construct directly with a stub.
     * Kept out of the `@Inject` constructor so Hilt doesn't need a
     * binding for `() -> Long`.
     */
    internal var now: () -> Long = { System.currentTimeMillis() }

    val aliases: StateFlow<List<VoiceAlias>> = aliasDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * The currently designated primary alias name (or null when none is
     * set). Sourced verbatim from [SettingsRepository.primaryAliasName] —
     * callers that need a "resolved" primary (i.e. fall back to null when
     * the named alias has been deleted) should cross-check against
     * [aliases] before consuming.
     */
    val primaryAliasName: StateFlow<String?> = settings.primaryAliasName
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Engines whose on-disk layout currently passes [EngineInstaller.verify].
     * Seeded by [refresh] (init + when the screen becomes active). Same
     * source of truth the voice picker uses — [VoiceMeta.isInstalled] is
     * never flipped in production, so disk verification is the only honest
     * "can the user actually pick this" signal.
     */
    private val _installedEngines = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Engines offered in the alias editor's engine picker: installed engines
     * only, further filtered by the "show developer engines" setting. We hide
     * uninstalled engines so the user can't create an alias pointing at an
     * engine they can't run — the bug that produced a stuck "Tap to install"
     * banner with no obvious cause. An alias already saved against a
     * now-hidden engine (uninstalled or dev-only) still works (routing via
     * [EngineCatalog.byName] is unfiltered, and [EngineDropdown] falls back to
     * the raw name); it just won't appear as a fresh pick.
     */
    val engines: StateFlow<List<EngineOption>> = combine(
        settings.showDeveloperEngines,
        _installedEngines,
    ) { showDeveloper, installed ->
        val options = EngineCatalog.visibleTo(showDeveloper)
            .filter { it.name in installed }
            .map { EngineOption(it.name, it.displayName) }
        // Cloud API engine lives outside EngineCatalog (no bundle);
        // offer it when its key is configured, after the local engines.
        if (CloudApiVoiceCatalog.ENGINE in installed) {
            options + EngineOption(CloudApiVoiceCatalog.ENGINE, CloudApiVoiceCatalog.DISPLAY_NAME)
        } else {
            options
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _editorState = MutableStateFlow(EditorState())
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    /**
     * Installed voices for the engine currently selected in the editor.
     * Recomputes when the editor's engine field changes — closes the
     * UX hole where switching engine leaves a stale voice list visible.
     *
     * `distinctUntilChanged` prevents the flatMap from re-subscribing
     * on every keystroke in the name field.
     */
    val voicesForSelectedEngine: StateFlow<List<VoiceMeta>> = _editorState
        .map { it.engine }
        .distinctUntilChanged()
        .flatMapLatest { engine ->
            if (engine.isBlank()) flowOf(emptyList())
            else voiceDao.getByEngine(engine)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    init {
        refresh()
    }

    /**
     * Re-probe each catalog engine's on-disk install state and update
     * [_installedEngines], so the engine picker only offers engines the user
     * can actually run. Mirrors [VoicePickerViewModel.refresh] — the screen
     * calls this when it becomes active so installing an engine elsewhere and
     * returning surfaces it without an app restart.
     */
    fun refresh() {
        viewModelScope.launch {
            val installed = mutableSetOf<String>()
            for (engine in EngineCatalog.all) {
                if (installer.verify(engine.name) is InstallState.Installed) {
                    installed += engine.name
                }
            }
            // Cloud API engine: any configured provider key == installed (no bundle).
            if (settings.anyCloudApiKeySet.firstOrNull() == true) {
                installed += CloudApiVoiceCatalog.ENGINE
            }
            _installedEngines.value = installed
        }
    }

    /**
     * Open the editor.
     *
     * @param existing  null  ⇒ create-new mode. Defaults to the first
     *                        engine in the catalog so the user only has
     *                        to pick a voice + name to save.
     *                  non-null ⇒ edit mode — fields prefilled, name is
     *                            still editable but uniqueness check
     *                            ignores its own old row.
     */
    fun openEditor(existing: VoiceAlias? = null) {
        _editorState.value = if (existing == null) {
            val defaultEngine = engines.value.firstOrNull()?.name.orEmpty()
            EditorState(
                isOpen = true,
                isNew = true,
                engine = defaultEngine,
            )
        } else {
            EditorState(
                isOpen = true,
                isNew = false,
                originalName = existing.name,
                name = existing.name,
                engine = existing.engine,
                voiceId = existing.voiceId,
                speed = existing.speed,
                effectId = existing.effectId,
                phonemizationLanguage = existing.phonemizationLanguage,
            )
        }
    }

    /** Close the editor without saving — discards any unsaved field edits. */
    fun dismissEditor() {
        _editorState.value = EditorState()
    }

    fun onEditorNameChange(value: String) {
        val current = _editorState.value
        _editorState.value = current.copy(
            name = value,
            error = clearIfRelatedTo(current.error, NameField),
        )
    }

    fun onEditorEngineChange(value: String) {
        // Switching engine clears the voice selection — picking a Kitten
        // voice and then flipping the engine to "piper" would otherwise
        // silently keep the now-invalid voice ID.
        val current = _editorState.value
        _editorState.value = current.copy(engine = value, voiceId = "", error = null)
    }

    fun onEditorVoiceChange(voiceId: String) {
        val current = _editorState.value
        _editorState.value = current.copy(
            voiceId = voiceId,
            error = clearIfRelatedTo(current.error, VoiceField),
        )
    }

    fun onEditorSpeedChange(speed: Float) {
        val clamped = speed.coerceIn(VoiceAlias.MIN_SPEED, VoiceAlias.MAX_SPEED)
        val current = _editorState.value
        _editorState.value = current.copy(speed = clamped)
    }

    /** Pass null to clear the effect (= "No effect" — dry). */
    fun onEditorEffectChange(effectId: String?) {
        val current = _editorState.value
        _editorState.value = current.copy(effectId = effectId)
    }

    /** Pass null to clear the user override (= "Auto" — engine decides). */
    fun onEditorPhonemizationLanguageChange(language: String?) {
        val current = _editorState.value
        _editorState.value = current.copy(phonemizationLanguage = language)
    }

    /**
     * Persist the editor's current state. Returns true on success so the
     * caller can dismiss the sheet; on failure the editor stays open with
     * `state.error` populated.
     */
    fun save(): Boolean {
        val state = _editorState.value
        val name = state.name.trim()

        if (!VoiceAlias.isValidName(name)) {
            _editorState.value = state.copy(error = SaveError.InvalidName)
            return false
        }
        if (state.voiceId.isBlank()) {
            _editorState.value = state.copy(error = SaveError.MissingVoice)
            return false
        }
        // Collision check — only applies to new aliases, or to edits that
        // changed the name to something already taken by another row.
        val collidesWithExisting = aliases.value.any { existing ->
            existing.name == name && existing.name != state.originalName
        }
        if (collidesWithExisting) {
            _editorState.value = state.copy(error = SaveError.NameTaken)
            return false
        }

        val createdAt = if (state.isNew) {
            now()
        } else {
            findExisting(state.originalName)?.createdAt ?: now()
        }

        val alias = VoiceAlias(
            name = name,
            engine = state.engine,
            voiceId = state.voiceId,
            speed = state.speed,
            // effectId (chosen in the picker) is the source of truth the synth
            // path reads. effectPreset is the retired legacy column — kept
            // non-null ("NONE") only to satisfy the schema; nothing reads it.
            effectPreset = "NONE",
            createdAt = createdAt,
            phonemizationLanguage = state.phonemizationLanguage,
            effectId = state.effectId,
        )

        viewModelScope.launch {
            // If the user renamed an alias, delete the old row first so
            // we don't leave a duplicate behind under the previous PK.
            val oldName = state.originalName
            if (!state.isNew && oldName != null && oldName != name) {
                aliasDao.delete(oldName)
            }
            aliasDao.upsert(alias)
            // First-alias-becomes-primary rule. We auto-promote whenever
            // there is no primary set yet (null pointer in settings) —
            // covers fresh installs and the "primary was deleted, next
            // created alias inherits" recovery path. Renames of the
            // current primary keep the pointer in sync by re-writing it
            // to the new name.
            val currentPrimary = settings.primaryAliasName.first()
            when {
                currentPrimary == null -> settings.setPrimaryAliasName(name)
                !state.isNew && oldName != null && oldName != name && currentPrimary == oldName ->
                    settings.setPrimaryAliasName(name)
                else -> Unit
            }
        }
        _editorState.value = EditorState()
        return true
    }

    /**
     * Remove the alias with [name]. No-op if it doesn't exist.
     *
     * Invariants enforced here:
     *  - The last remaining alias cannot be deleted — every install with
     *    at least one alias must always have at least one. UI gates the
     *    button too; this is a defense-in-depth check.
     *  - If the deleted alias was the primary, auto-promote the oldest
     *    remaining alias (createdAt ASC, matching the DAO sort) so we
     *    never sit in a "aliases exist but no primary" state. Pair with
     *    the auto-promote rule in [save] which handles the fresh-install
     *    + recovery cases.
     */
    fun delete(name: String): Boolean {
        if (aliases.value.size <= 1) return false
        viewModelScope.launch {
            val wasPrimary = settings.primaryAliasName.first() == name
            aliasDao.delete(name)
            if (wasPrimary) {
                val successor = aliases.value.firstOrNull { it.name != name }?.name
                settings.setPrimaryAliasName(successor)
            }
        }
        return true
    }

    /**
     * Explicitly designate [name] as the primary alias. The caller is
     * responsible for passing the name of an existing alias — this method
     * does not cross-check against [aliases] (the UI only exposes the
     * action via context menus on already-rendered rows, so the row's
     * existence is implicit at call time).
     */
    fun setPrimary(name: String) {
        viewModelScope.launch {
            settings.setPrimaryAliasName(name)
        }
    }

    // -- internals -------------------------------------------------------------

    private fun findExisting(name: String?): VoiceAlias? =
        aliases.value.firstOrNull { it.name == name }

    // Field tags so a successful name edit clears a "name invalid" error
    // but not a "voice missing" error, and vice versa. Keeps the UI from
    // jumping if the user fixes one of two simultaneous problems.
    private object NameField
    private object VoiceField

    private fun clearIfRelatedTo(error: SaveError?, field: Any): SaveError? = when {
        error == null -> null
        field === NameField && (error is SaveError.InvalidName || error is SaveError.NameTaken) -> null
        field === VoiceField && error is SaveError.MissingVoice -> null
        else -> error
    }
}

/**
 * Engine choice for the alias editor's picker — decoupled from
 * [app.marmalade.tts.install.EngineDescriptor] so engines without an installable bundle (the Cloud
 * API engine) can be offered alongside catalog engines.
 */
data class EngineOption(val name: String, val displayName: String)
