package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.data.db.EffectDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One effect rendered as a card on [EffectsScreen]. [blocks] is the chain
 * decoded to one short label per block (e.g. ["Reverb 80", "Echo"]) for chip
 * display — decoded once in the ViewModel so the composable doesn't re-parse
 * JSON on every recomposition.
 */
data class EffectCard(
    val id: String,
    val name: String,
    val isBuiltin: Boolean,
    val blocks: List<String>,
)

/**
 * ViewModel for [EffectsScreen] — the catalog of effects (the seeded CLI
 * presets + any user-created ones), shown as cards. Read-only for now; the
 * chain builder (create/edit/duplicate/delete) lands in E-F.
 */
@HiltViewModel
class EffectsViewModel @Inject constructor(
    private val effectDao: EffectDao,
) : ViewModel() {

    val effects: StateFlow<List<EffectCard>> = effectDao.getAll()
        .map { rows -> rows.map { it.toCard() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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
    return EffectCard(id = id, name = name, isBuiltin = isBuiltin, blocks = labels)
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
