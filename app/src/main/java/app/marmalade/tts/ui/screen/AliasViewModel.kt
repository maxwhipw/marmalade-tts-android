package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.LatencyBucket
import app.marmalade.tts.data.VoiceLatencySource
import app.marmalade.tts.data.VoicePath
import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.EffectDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.lang.LangDetector
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
//     ├── primaryAliasId ◄────── AliasViewModel.primaryAliasId
//     │                                  ▲
//     │                                  │ Flow
//     │                          SettingsRepository.primaryAliasId
//     │
//     └── actions
//          ├── save()    → validate → VoiceAliasDao.upsert(...)
//          │                + auto-promote first alias to primary if none set,
//          │                + retarget primary on rename of current primary.
//          ├── delete(id) → VoiceAliasDao.delete(id) + promote a successor.
//          └── setPrimary(n) → SettingsRepository.setPrimaryAliasId(n)
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
    /**
     * Id of the alias being edited, null when creating. Distinct from
     * [originalName], which is only kept so the uniqueness check can
     * exempt the row from colliding with itself.
     */
    val editingId: String? = null,
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
    /**
     * Alias to speak with when a cloud voice can't be reached. Only
     * surfaced in the editor for cloud voices; see [VoiceAlias.fallbackAliasId].
     */
    val fallbackAliasId: String? = null,
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
    private val mappingDao: AppAliasMappingDao,
    private val voiceDao: VoiceMetaDao,
    private val settings: SettingsRepository,
    private val installer: EngineInstaller,
    private val voicePaths: VoicePathResolver,
    latencySource: VoiceLatencySource,
    effectDao: EffectDao,
) : ViewModel() {

    /**
     * How long each model makes you wait, keyed by
     * [app.marmalade.tts.data.latencyKeyFor]. Feeds the picker's speed
     * badge; a model with no seed and too few measurements is simply
     * absent, and the picker shows nothing rather than a guess.
     */
    val voiceLatency: StateFlow<Map<String, LatencyBucket>> = latencySource.buckets()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    /**
     * Where [alias]'s voice sits in the source › model › voice hierarchy.
     *
     * Resolved on demand rather than cached: it reads the in-memory provider
     * descriptor, and an alias list is a handful of rows.
     */
    fun voicePathFor(alias: VoiceAlias): VoicePath =
        voicePaths.resolve(alias.voiceId, alias.engine)

    /** Same, for the editor's in-progress selection (not yet an alias). */
    fun voicePathFor(voiceId: String, engine: String): VoicePath =
        voicePaths.resolve(voiceId, engine)

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
     * set). Sourced verbatim from [SettingsRepository.primaryAliasId] —
     * callers that need a "resolved" primary (i.e. fall back to null when
     * the named alias has been deleted) should cross-check against
     * [aliases] before consuming.
     */
    val primaryAliasId: StateFlow<String?> = settings.primaryAliasId
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

    // -- Voice picker ---------------------------------------------------------
    //
    // The tree, the level-skipping and the search live in VoiceTree.kt, shared
    // with the full-screen picker so the two surfaces browse identically.

    /** Installed voices grouped into the drill-down tree. */
    val voiceTree: StateFlow<List<VoiceSource>> = combine(
        voiceDao.getAll(),
        _installedEngines,
    ) { voices, installed ->
        buildVoiceTree(voices.filter { it.engine in installed }, voicePaths)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _pickerState = MutableStateFlow(VoicePickerState())
    val pickerState: StateFlow<VoicePickerState> = _pickerState.asStateFlow()

    /** Open the picker at the top level (or inside the current voice's source). */
    fun openVoicePicker() {
        val current = _editorState.value.voiceId
        val path = current.takeIf { it.isNotBlank() }
            ?.let { voicePaths.resolve(it, _editorState.value.engine) }
        _pickerState.value = VoicePickerState(
            isOpen = true,
            // Land where the user already is rather than making them
            // re-navigate to the voice they're about to change.
            source = path?.source,
            model = path?.model,
        )
    }

    fun dismissVoicePicker() {
        _pickerState.value = VoicePickerState()
    }

    fun onPickerQueryChange(query: String) {
        _pickerState.value = _pickerState.value.copy(query = query)
    }

    fun selectPickerSource(source: String) {
        _pickerState.value = _pickerState.value.selectSourceIn(voiceTree.value, source)
    }

    fun selectPickerModel(model: String) {
        _pickerState.value = _pickerState.value.copy(model = model)
    }

    /** Step back one level; at the top level, closes the picker. */
    fun pickerBack() {
        _pickerState.value = _pickerState.value.back(voiceTree.value)
    }

    /**
     * Commit a voice choice. Sets the editor's engine *and* voiceId together
     * — they must move as a pair, which is exactly what the old two-dropdown
     * editor couldn't guarantee.
     */
    fun pickVoice(voice: VoiceMeta) {
        val state = _editorState.value
        val isCloud = voicePaths.resolve(voice.id, voice.engine).isCloud
        _editorState.value = state.copy(
            engine = voice.engine,
            voiceId = voice.id,
            // Choosing a cloud voice arms the fallback automatically. The
            // protection is worth nothing if it depends on the user knowing
            // to configure it, and "speaks in a different voice" beats
            // "says nothing" when the network drops mid-sentence.
            fallbackAliasId = when {
                !isCloud -> null
                state.fallbackAliasId != null -> state.fallbackAliasId
                else -> defaultFallbackAlias()
            },
            error = null,
        )
        _pickerState.value = VoicePickerState()
    }

    fun onEditorFallbackChange(aliasName: String?) {
        _editorState.value = _editorState.value.copy(fallbackAliasId = aliasName)
    }

    /**
     * Aliases eligible as a fallback: on-device only (a cloud alias can't
     * rescue another cloud alias from a dead network) and never the alias
     * being edited.
     */
    fun fallbackCandidates(): List<VoiceAlias> {
        val editing = _editorState.value.originalName
        return aliases.value.filter {
            it.name != editing && !voicePaths.resolve(it.voiceId, it.engine).isCloud
        }
    }

    /** Primary on-device alias if there is one, else any on-device alias. */
    private fun defaultFallbackAlias(): String? {
        val candidates = fallbackCandidates()
        val primary = primaryAliasId.value
        return candidates.firstOrNull { it.name == primary }?.name
            ?: candidates.firstOrNull()?.name
    }

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
                editingId = existing.id,
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

    /**
     * Pass [app.marmalade.tts.lang.LangDetector.AUTO] (or null, which
     * means the same) to clear the user override back to auto-detect.
     */
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

        val existing = state.editingId?.let { id -> aliases.value.firstOrNull { it.id == id } }
        val createdAt = existing?.createdAt ?: now()

        val alias = VoiceAlias(
            // An edit keeps its id, which is the entire point: a rename is
            // now an UPDATE of one column, and everything pointing at this
            // alias keeps pointing at it.
            id = existing?.id ?: VoiceAlias.newId(),
            name = name,
            engine = state.engine,
            voiceId = state.voiceId,
            speed = state.speed,
            // effectId (chosen in the picker) is the source of truth the synth
            // path reads. effectPreset is the retired legacy column — kept
            // non-null ("NONE") only to satisfy the schema; nothing reads it.
            effectPreset = "NONE",
            createdAt = createdAt,
            // Kokoro acts on every code. Kitten acts on auto-detect, and
            // on nothing else — it renders English, so a specific
            // non-English code is not a preference it can hold. Pocket
            // never phonemizes through espeak. So anything a voice change
            // left behind on an engine that can't act on it is normalized
            // away rather than persisted for a later read to ignore — or
            // worse, to bring a stale "ja" back the moment the alias is
            // pointed at Kokoro again.
            phonemizationLanguage = state.phonemizationLanguage?.takeIf {
                when (state.engine) {
                    KokoroDirectVoiceCatalog.ENGINE -> true
                    KittenDirectVoiceCatalog.ENGINE -> it == LangDetector.AUTO
                    else -> false
                }
            },
            effectId = state.effectId,
            // Only cloud voices can fail for lack of a network, so only they
            // carry a fallback. Persisting one on an on-device alias would be
            // dead data that later reads have to remember to ignore.
            fallbackAliasId = state.fallbackAliasId
                ?.takeIf { voicePaths.resolve(state.voiceId, state.engine).isCloud },
        )

        viewModelScope.launch {
            // A rename is now a plain upsert on the same id — one UPDATE,
            // no delete-and-reinsert. Per-app routing, other aliases'
            // fallback pointers and the primary pointer all reference the
            // id, so none of them need touching and none of them can be
            // left naming a row that no longer exists.
            aliasDao.upsert(alias)

            // First-alias-becomes-primary: promote whenever nothing is set
            // yet. Covers fresh installs and the "primary was deleted, next
            // created alias inherits" recovery path. There is deliberately
            // no rename branch any more — there is nothing to retarget.
            if (settings.primaryAliasId.first() == null) {
                promoteToPrimary(alias.id)
            }
        }
        _editorState.value = EditorState()
        return true
    }

    /**
     * Remove the alias with [id]. No-op if it doesn't exist.
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
    fun delete(id: String): Boolean {
        if (aliases.value.size <= 1) return false
        viewModelScope.launch {
            val wasPrimary = settings.primaryAliasId.first() == id
            aliasDao.delete(id)
            if (wasPrimary) {
                val successor = aliases.value.firstOrNull { it.id != id }?.id
                // Null can't happen behind the size<=1 guard, but a null
                // pointer is also the documented "no primary" state, so
                // write it through rather than inventing a successor.
                if (successor != null) promoteToPrimary(successor)
                else settings.setPrimaryAliasId(null)
            }
        }
        return true
    }

    /**
     * Explicitly designate [id] as the primary alias. The caller is
     * responsible for passing the name of an existing alias — this method
     * does not cross-check against [aliases] (the UI only exposes the
     * action via context menus on already-rendered rows, so the row's
     * existence is implicit at call time).
     */
    fun setPrimary(id: String) {
        viewModelScope.launch {
            promoteToPrimary(id)
        }
    }

    /**
     * Point the primary at [name] and release any apps routed to it.
     *
     * The release is not tidiness, it is the fix for a trap: the primary
     * is already the fallback for every caller without a rule of its own,
     * so per-app rows naming it change nothing — but the alias card makes
     * the primary's routing strip inert, so once promoted those rows can
     * no longer be edited and the apps are pinned to it permanently.
     * Dropping them leaves every app reachable again.
     */
    private suspend fun promoteToPrimary(id: String) {
        settings.setPrimaryAliasId(id)
        mappingDao.releaseAppsRoutedTo(id)
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

