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
    /**
     * espeak voice/language code used for phonemization (e.g. `"en-us"`,
     * `"ja"`, `"cmn"`). Null means "auto-derive from the voice's natural
     * language" — for KokoroDirect this maps via `KokoroDirectVoiceCatalog.
     * espeakVoiceFor(voiceKey)` (af_*→en-us, jf_*→ja, zf_*→cmn, etc.).
     *
     * Non-null lets the user force a language different from the voice's
     * default — e.g. running an English voice through Spanish espeak rules
     * for accent experimentation. Engines that don't need phonemization
     * (sherpa-Kokoro/Kitten, Pocket) ignore this field.
     *
     * Added in db v5 (alpha.10.L).
     */
    val phonemizationLanguage: String? = null,
    /**
     * Reference to an [Effect] row's id (e.g. `"builtin:cave"` or a custom
     * effect's id). Null means "no effect" (dry). Replaces [effectPreset] as
     * the source of truth: db v7 back-fills it from the old preset string
     * (CAVE→builtin:cave, etc.; NONE→null). [effectPreset] is retained for the
     * back-fill and is otherwise unused going forward.
     *
     * Added in db v6→v7 (E-D).
     */
    val effectId: String? = null,
    /**
     * Name of another alias to speak with when this one's voice can't be
     * reached. Null means "no fallback — fail".
     *
     * Only meaningful for cloud voices, which are the one thing in the app
     * that can simply stop working: a dead network used to mean an error
     * and silence, which for an engine backing a screen reader is the worst
     * possible outcome. The editor offers this only for cloud voices and
     * defaults it to the primary on-device alias, so the protection exists
     * without the user configuring anything.
     *
     * Not a foreign key, matching [AppAliasMapping.aliasName]: if the
     * referenced alias is deleted the reference lingers harmlessly and the
     * router falls through to its normal resolution.
     *
     * Added in db v8→v9.
     */
    val fallbackAliasName: String? = null,
) {
    companion object {
        /**
         * Allowed alias names: letters (any case, incl. unicode), digits,
         * spaces, dashes, underscores, and apostrophes. 1–50 chars after
         * trimming. Leading/trailing whitespace is stripped at validate
         * time, so `"  Max Warren  "` ends up as `"Max Warren"`.
         *
         * Originally restricted to lower-case-only no-spaces tokens for
         * supposed CLI round-trip parity, but the Android side stores
         * aliases as Room primary keys and never shells them out — and
         * users (rightly) want to name personas after people, with
         * proper capitalization and spaces.
         */
        const val MAX_NAME_LENGTH: Int = 50

        /** True iff [candidate] (after trimming) is a syntactically valid alias name. */
        fun isValidName(candidate: String): Boolean {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return false
            return trimmed.all {
                it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '\''
            }
        }

        /** Minimum / maximum speed multipliers allowed by the editor UI. */
        const val MIN_SPEED: Float = 0.5f
        const val MAX_SPEED: Float = 2.0f
    }
}
