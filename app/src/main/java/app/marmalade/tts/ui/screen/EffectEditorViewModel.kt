package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.EffectDao
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
//   EffectEditorViewModel
//     │
//     ├── reads nav args from SavedStateHandle:
//     │     editId  → load that custom effect, write back to the SAME id (edit)
//     │     dupeId  → load that effect's blocks, mint a NEW id, name "<n> copy"
//     │     (neither) → blank create
//     │
//     └── state: EffectEditorState(name, blocks, isEdit, …) ──► EffectEditorScreen
//
//   Save encodes [blocks] via EffectBlockJson and upserts a custom (isBuiltin=
//   false) Effect. Preview synthesizes a fixed sample with the default voice and
//   the in-progress chain through the same SpeechPlayer the Speak screen uses.
// -----------------------------------------------------------------------------

/** Coarse state for the editor's Preview button. */
sealed interface EffectPreviewState {
    object Idle : EffectPreviewState
    object Playing : EffectPreviewState
    data class Error(val message: String) : EffectPreviewState
}

/**
 * Editor draft. [blocks] is the live chain being assembled; [isEdit] is true
 * only when we loaded an existing custom effect to overwrite (so the screen
 * titles "Edit" vs "New effect" and Save writes back to the same id).
 *
 * [loaded] gates the first frame: the screen shows a spinner until the initial
 * DB read (edit / duplicate source) resolves, so we never flash an empty editor
 * over a chain that's about to populate.
 */
data class EffectEditorState(
    val name: String = "",
    val blocks: List<EffectBlock> = emptyList(),
    val isEdit: Boolean = false,
    val nameError: Boolean = false,
    val loaded: Boolean = false,
    val preview: EffectPreviewState = EffectPreviewState.Idle,
)

@HiltViewModel
class EffectEditorViewModel @Inject constructor(
    private val effectDao: EffectDao,
    private val settings: SettingsRepository,
    private val synthesizer: app.marmalade.tts.audio.SpeechPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editId: String? = savedStateHandle[ARG_EDIT_ID]
    private val dupeId: String? = savedStateHandle[ARG_DUPE_ID]

    /** The id we'll save under: the edited effect's id, or a freshly minted one. */
    private var saveId: String = newCustomId()

    private val _state = MutableStateFlow(EffectEditorState())
    val state: StateFlow<EffectEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when {
                editId != null -> {
                    val existing = effectDao.findById(editId)
                    if (existing != null) {
                        saveId = existing.id
                        _state.value = EffectEditorState(
                            name = existing.name,
                            blocks = decode(existing.blocksJson),
                            isEdit = true,
                            loaded = true,
                        )
                    } else {
                        // Stale id (deleted out from under us) — fall back to a
                        // blank create rather than getting stuck on the spinner.
                        _state.value = EffectEditorState(loaded = true)
                    }
                }
                dupeId != null -> {
                    val source = effectDao.findById(dupeId)
                    _state.value = EffectEditorState(
                        name = source?.let { "${it.name} copy" } ?: "",
                        blocks = source?.let { decode(it.blocksJson) } ?: emptyList(),
                        isEdit = false,
                        loaded = true,
                    )
                }
                else -> _state.value = EffectEditorState(loaded = true)
            }
        }
    }

    fun onNameChange(value: String) {
        _state.value = _state.value.copy(name = value, nameError = false)
    }

    fun addBlock(block: EffectBlock) {
        _state.value = _state.value.copy(blocks = _state.value.blocks + block)
    }

    fun removeBlock(index: Int) {
        val blocks = _state.value.blocks
        if (index !in blocks.indices) return
        _state.value = _state.value.copy(blocks = blocks.toMutableList().apply { removeAt(index) })
    }

    /** Replace the block at [index] (the per-block sliders call this on change). */
    fun updateBlock(index: Int, block: EffectBlock) {
        val blocks = _state.value.blocks
        if (index !in blocks.indices) return
        _state.value = _state.value.copy(blocks = blocks.toMutableList().apply { this[index] = block })
    }

    fun moveUp(index: Int) = swap(index, index - 1)
    fun moveDown(index: Int) = swap(index, index + 1)

    private fun swap(a: Int, b: Int) {
        val blocks = _state.value.blocks
        if (a !in blocks.indices || b !in blocks.indices) return
        _state.value = _state.value.copy(
            blocks = blocks.toMutableList().apply { this[a] = blocks[b]; this[b] = blocks[a] },
        )
    }

    /**
     * Synthesize a fixed sample with the current default voice and the
     * in-progress chain, then play it. The chip "Playing" state clears when
     * [SpeechPlayer.speak] returns (it suspends until playback drains).
     */
    fun preview() {
        if (_state.value.preview is EffectPreviewState.Playing) return
        _state.value = _state.value.copy(preview = EffectPreviewState.Playing)
        viewModelScope.launch {
            val voiceId = settings.defaultVoiceId.first()
            if (voiceId.isBlank()) {
                _state.value = _state.value.copy(
                    preview = EffectPreviewState.Error("Pick a default voice on the Speak screen first."),
                )
                return@launch
            }
            val result = synthesizer.speak(PREVIEW_TEXT, voiceId, 1.0f, _state.value.blocks, null)
            _state.value = _state.value.copy(
                preview = result.fold(
                    onSuccess = { EffectPreviewState.Idle },
                    onFailure = { EffectPreviewState.Error(it.message ?: "Preview failed") },
                ),
            )
        }
    }

    fun stopPreview() {
        synthesizer.cancel()
        _state.value = _state.value.copy(preview = EffectPreviewState.Idle)
    }

    /**
     * Validate + persist. Blank name is the only hard error (the chain may be
     * empty — that's a valid "dry" effect). Calls [onSaved] after the upsert so
     * the screen can pop back. Custom effects always save with isBuiltin=false.
     */
    fun save(onSaved: () -> Unit) {
        val name = _state.value.name.trim()
        if (name.isBlank()) {
            _state.value = _state.value.copy(nameError = true)
            return
        }
        viewModelScope.launch {
            effectDao.upsert(
                Effect(
                    id = saveId,
                    name = name,
                    isBuiltin = false,
                    blocksJson = EffectBlockJson.encode(_state.value.blocks),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            onSaved()
        }
    }

    override fun onCleared() {
        super.onCleared()
        synthesizer.cancel()
    }

    private fun decode(json: String): List<EffectBlock> =
        runCatching { EffectBlockJson.decode(json) }.getOrDefault(emptyList())

    private fun newCustomId(): String = "custom:${UUID.randomUUID()}"

    companion object {
        const val ARG_EDIT_ID = "editId"
        const val ARG_DUPE_ID = "dupeId"
        private const val PREVIEW_TEXT = "The quick brown fox jumps over the lazy dog."
    }
}
