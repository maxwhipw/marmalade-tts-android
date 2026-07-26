package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * One effect rendered as a card on [EffectsScreen]. [blocks] is the chain
 * decoded to one short label per block (e.g. ["Reverb 80", "Echo"]) for chip
 * display, and [chain] is the same decode kept in block form so the card's
 * play button can preview it. Decoded once in the ViewModel so the composable
 * doesn't re-parse JSON on every recomposition.
 */
data class EffectCard(
    val id: String,
    val name: String,
    val isBuiltin: Boolean,
    val blocks: List<String>,
    val chain: List<EffectBlock>,
)

/**
 * Which card is previewing, plus the failure from the last attempt (rendered
 * under the card that raised it). At most one preview runs at a time — starting
 * another stops the first.
 */
data class EffectsPreviewState(
    val playingId: String? = null,
    val errorId: String? = null,
    val errorMessage: String? = null,
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
                    errorMessage = "Pick a default voice on the Speak screen first.",
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
                        errorMessage = it.message ?: "Preview failed",
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

private fun app.marmalade.tts.data.db.Effect.toCard(): EffectCard {
    val decoded = runCatching { EffectBlockJson.decode(blocksJson) }.getOrDefault(emptyList())
    val labels = if (decoded.isEmpty()) listOf("No effect") else decoded.map { it.label() }
    return EffectCard(
        id = id,
        name = name,
        isBuiltin = isBuiltin,
        blocks = labels,
        chain = decoded,
    )
}

private fun EffectBlock.label(): String = when (this) {
    is EffectBlock.Reverb -> "Reverb ${reverberance.toInt()}"
    is EffectBlock.Echo -> "Echo"
    is EffectBlock.Overdrive -> "Overdrive ${gainDb.toInt()}"
    is EffectBlock.Pitch -> "Pitch ${if (cents >= 0) "+" else ""}${cents.toInt()}"
    is EffectBlock.Tempo -> "Tempo ${tempoLabel(factor)}"
    is EffectBlock.Bandpass -> "Band ${lowHz.toInt()}–${highHz.toInt()}"
    is EffectBlock.Vol -> "Vol ${"%.1f".format(factor)}×"
    is EffectBlock.Treble -> "Treble ${if (db >= 0) "+" else ""}${db.toInt()}"
    is EffectBlock.Bass -> "Bass ${if (db >= 0) "+" else ""}${db.toInt()}"
    is EffectBlock.Mid -> "Mid ${freqHz.toInt()}Hz ${if (gainDb >= 0) "+" else ""}${gainDb.toInt()}"
    is EffectBlock.Lowpass -> "LP ${freqHz.toInt()}Hz"
    is EffectBlock.Highpass -> "HP ${freqHz.toInt()}Hz"
    is EffectBlock.Tremolo -> "Tremolo ${"%.1f".format(speedHz)}Hz"
    is EffectBlock.Flanger -> "Flanger ${"%.2f".format(speedHz)}Hz"
    is EffectBlock.Chorus -> "Chorus ${"%.2f".format(speedHz)}Hz"
    is EffectBlock.Phaser -> "Phaser ${"%.2f".format(speedHz)}Hz"
    is EffectBlock.Compressor -> "Comp ${thresholdDb.toInt()}dB ${"%.0f".format(ratio)}:1"
    is EffectBlock.Bitcrush -> "Crush ${bits.toInt()}-bit ${downsample.toInt()}×"
    is EffectBlock.RingMod -> "Ring ${freqHz.toInt()}Hz"
    is EffectBlock.Monotone -> "Monotone ${targetHz.toInt()}Hz"
}

private fun tempoLabel(factor: Float): String = "%.2f×".format(factor)
