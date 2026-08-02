package app.marmalade.tts.ui.screen

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.EffectDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One effect rendered as a card on [EffectsScreen]. [chain] is the decoded
 * block chain — it drives both the chip row (the screen formats one label per
 * block) and the card's play button. Decoded once in the ViewModel so the
 * composable doesn't re-parse JSON on every recomposition.
 */
data class EffectCard(
    val id: String,
    val name: String,
    val isBuiltin: Boolean,
    val chain: List<EffectBlock>,
)

/**
 * Which card is previewing, plus the failure from the last attempt (rendered
 * under the card that raised it). At most one preview runs at a time — starting
 * another stops the first.
 *
 * [errorRes] names the failure; [errorDetail] carries the engine's own message
 * when there is one, which has no resource to come from.
 */
data class EffectsPreviewState(
    val playingId: String? = null,
    val errorId: String? = null,
    @StringRes val errorRes: Int? = null,
    val errorDetail: String? = null,
)

/**
 * ViewModel for [EffectsScreen] — the catalog of effects (the seeded CLI
 * presets + any user-created ones), shown as cards. Owns delete and the
 * per-card audio preview; create/edit/duplicate route to the editor.
 */
@HiltViewModel
class EffectsViewModel @Inject constructor(
    private val effectDao: EffectDao,
    private val settings: SettingsRepository,
    private val synthesizer: SpeechPlayer,
) : ViewModel() {

    val effects: StateFlow<List<EffectCard>> = effectDao.getAll()
        .map { rows -> rows.map { it.toCard() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _preview = MutableStateFlow(EffectsPreviewState())
    val preview: StateFlow<EffectsPreviewState> = _preview.asStateFlow()

    /**
     * Monotonic token for the latest preview/stop. A stopped preview's
     * coroutine still completes ([SpeechPlayer.speak] returns success on
     * cancellation) and must not overwrite the state a newer action set.
     * Main-thread only (UI callbacks + viewModelScope), so a plain var.
     */
    private var previewGeneration = 0L

    /**
     * Speak [EFFECT_PREVIEW_TEXT] with the default voice through [card]'s
     * chain. Tapping play on a second card supersedes the first rather than
     * layering two voices over each other.
     */
    fun preview(card: EffectCard) {
        val generation = ++previewGeneration
        synthesizer.cancel()
        _preview.value = EffectsPreviewState(playingId = card.id)
        viewModelScope.launch {
            val voiceId = settings.defaultVoiceId.first()
            if (voiceId.isBlank()) {
                if (generation != previewGeneration) return@launch
                _preview.value = EffectsPreviewState(
                    errorId = card.id,
                    errorRes = R.string.effects_preview_pick_default_voice,
                )
                return@launch
            }
            val result = synthesizer.speak(EFFECT_PREVIEW_TEXT, voiceId, 1.0f, card.chain, null)
            if (generation != previewGeneration) return@launch // superseded
            _preview.value = result.fold(
                onSuccess = { EffectsPreviewState() },
                onFailure = {
                    EffectsPreviewState(
                        errorId = card.id,
                        errorRes = R.string.effects_preview_failed,
                        errorDetail = it.message,
                    )
                },
            )
        }
    }

    fun stopPreview() {
        // Orphan the in-flight coroutine's terminal write (see previewGeneration).
        previewGeneration++
        synthesizer.cancel()
        _preview.value = EffectsPreviewState()
    }

    override fun onCleared() {
        super.onCleared()
        synthesizer.cancel()
    }

    /**
     * Delete a custom effect. The DAO's WHERE-guard refuses built-ins, so a
     * stray call on a seeded preset is a harmless no-op. Aliases that pointed
     * at this effect keep their (now-dangling) effectId; the resolver maps a
     * missing id to the dry chain, so synthesis degrades to "no effect"
     * rather than crashing.
     */
    fun delete(id: String) {
        viewModelScope.launch { effectDao.deleteCustom(id) }
    }
}

private fun app.marmalade.tts.data.db.Effect.toCard(): EffectCard = EffectCard(
    id = id,
    name = name,
    isBuiltin = isBuiltin,
    chain = runCatching { EffectBlockJson.decode(blocksJson) }.getOrDefault(emptyList()),
)
