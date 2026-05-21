package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
//     ├── engines   ◄────── AliasViewModel.engines (EngineCatalog.all)
//     ├── voicesFor ◄────── AliasViewModel.voicesForSelectedEngine
//     │                          ▲
//     │                          │ flatMapLatest(editorState.engine)
//     │                  VoiceMetaDao.getByEngine(engine)
//     │
//     └── actions
//          ├── save()    → validate → VoiceAliasDao.upsert(...)
//          └── delete(n) → VoiceAliasDao.delete(n)
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
    val effect: EffectPreset = EffectPreset.NONE,
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
) : ViewModel() {

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

    /** Static list — v0.1 only ships Kitten but other engines plug in later. */
    val engines: List<EngineDescriptor> = EngineCatalog.all

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
            val defaultEngine = engines.firstOrNull()?.name.orEmpty()
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
                effect = decodeEffect(existing.effectPreset),
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

    fun onEditorEffectChange(effect: EffectPreset) {
        val current = _editorState.value
        _editorState.value = current.copy(effect = effect)
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
            effectPreset = state.effect.name,
            createdAt = createdAt,
        )

        viewModelScope.launch {
            // If the user renamed an alias, delete the old row first so
            // we don't leave a duplicate behind under the previous PK.
            val oldName = state.originalName
            if (!state.isNew && oldName != null && oldName != name) {
                aliasDao.delete(oldName)
            }
            aliasDao.upsert(alias)
        }
        _editorState.value = EditorState()
        return true
    }

    /** Remove the alias with [name]. No-op if it doesn't exist. */
    fun delete(name: String) {
        viewModelScope.launch {
            aliasDao.delete(name)
        }
    }

    // -- internals -------------------------------------------------------------

    private fun findExisting(name: String?): VoiceAlias? =
        aliases.value.firstOrNull { it.name == name }

    private fun decodeEffect(raw: String): EffectPreset =
        EffectPreset.entries.firstOrNull { it.name == raw } ?: EffectPreset.NONE

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
