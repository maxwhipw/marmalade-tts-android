package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-visible audio effect — an ordered chain of DSP blocks, stored as
 * JSON in [blocksJson] (see [app.marmalade.tts.audio.EffectBlockJson]).
 *
 * Aliases reference an effect by [id] (nullable on the alias = "no effect").
 * The synth path decodes [blocksJson] to `List<EffectBlock>` and runs it
 * through `EffectChain.applyChain`.
 *
 * @property id        Stable identifier. Built-ins use `"builtin:<name>"`
 *                     (see [app.marmalade.tts.data.BuiltinEffects]); custom
 *                     effects use a generated id.
 * @property name      User-facing label, e.g. "Cave".
 * @property isBuiltin True for the seeded CLI presets — shown read-only in the
 *                     UI ("duplicate to edit") and re-seeded on catalog bumps;
 *                     custom effects are false and fully editable/deletable.
 * @property blocksJson The effect chain encoded by [EffectBlockJson.encode].
 * @property createdAt  Epoch ms — list ordering. Built-ins use 0 so they sort
 *                      ahead of custom effects.
 */
@Entity(tableName = "effect")
data class Effect(
    @PrimaryKey val id: String,
    val name: String,
    val isBuiltin: Boolean,
    val blocksJson: String,
    val createdAt: Long,
)
