package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AliasScreen / AliasViewModel
//     │
//     ├── reads:  VoiceAliasDao.getAll() ──► Flow<List<VoiceAlias>>
//     │             ▲
//     │             │
//     │           Room (table `voice_alias`)
//     │
//     └── writes: VoiceAliasDao.upsert(alias) / delete(name)
//                   ▲
//                   │
//                 AliasViewModel.save(...) — after validating
//                 the user-typed name against [VoiceAlias.NAME_REGEX].
//
//   SpeakViewModel.applyAlias(name)
//     │
//     ▼
//   VoiceAliasDao.findByName(name) ──► VoiceAlias
//     │
//     └─► SettingsRepository.setDefaultVoiceId(voiceId)
//         + in-memory speed / effect state on SpeakViewModel.
// -----------------------------------------------------------------------------

/**
 * A user-saved bundle of `engine + voice + speed + effect` under a
 * friendly name. Mirrors the CLI's `aliases:` config block (see README
 * → "Voice aliases / personas") so a `narrator` on the phone behaves
 * the same way as `marmalade-tts narrator "..."` on the desktop.
 *
 * The primary key is the user-chosen [name] (alphanumeric +
 * dash/underscore, lower-case, no spaces) — collisions are intentional:
 * editing an alias is just an `upsert` with the same name.
 *
 * @property name          User-facing key, e.g. `"narrator"`. Must satisfy
 *                         [NAME_REGEX]; validate at the call site
 *                         via [isValidName] before constructing.
 * @property engine        Matches `EngineDescriptor.name` (e.g. `"kitten"`).
 *                         Stored as a string rather than a foreign key so
 *                         engines coming and going in the catalog don't
 *                         break stored aliases.
 * @property voiceId       Matches [VoiceMeta.id], e.g. `"kitten:Bella"`.
 * @property speed         Playback speed multiplier, clamped 0.5f..2.0f
 *                         in the editor UI.
 * @property effectPreset  Name of the `EffectPreset` enum value, e.g.
 *                         `"NONE"`, `"CAVE"`. Stored as a string so the
 *                         enum can grow without a schema migration.
 *                         Default `"NONE"`.
 * @property createdAt     Epoch ms — used only for stable list ordering
 *                         in the UI.
 */
@Entity(tableName = "voice_alias")
data class VoiceAlias(
    @PrimaryKey val name: String,
    val engine: String,
    val voiceId: String,
    val speed: Float,
    val effectPreset: String,
    val createdAt: Long,
) {
    companion object {
        /**
         * Allowed alias names: lower-case letters, digits, dash, underscore.
         * No leading digit (matches CLI convention so a name like `42`
         * can't shadow an engine command), and at least one character.
         *
         * Rejects spaces — names are positional CLI-style tokens in the
         * CLI, and we want the same expectation here so persona names
         * round-trip across the two surfaces.
         */
        val NAME_REGEX: Regex = Regex("^[a-z][a-z0-9_-]*$")

        /** True iff [candidate] is a syntactically valid alias name. */
        fun isValidName(candidate: String): Boolean =
            candidate.isNotBlank() && NAME_REGEX.matches(candidate)

        /** Minimum / maximum speed multipliers allowed by the editor UI. */
        const val MIN_SPEED: Float = 0.5f
        const val MAX_SPEED: Float = 2.0f
    }
}
