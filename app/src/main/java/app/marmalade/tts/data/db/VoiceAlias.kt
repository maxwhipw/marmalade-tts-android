package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.Index
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
//   SpeakViewModel.applyAlias(id)
//     │
//     ▼
//   VoiceAliasDao.findById(id) ──► VoiceAlias
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
 * Identity is [id], an opaque UUID assigned once at creation and never
 * shown. [name] is display only and free to change.
 *
 * It was the other way round until db v10, and that was the source of a
 * family of bugs: renaming an alias meant deleting one row and inserting
 * another, so every `app_alias_mapping.aliasName`, every other alias's
 * fallback pointer, and the primary-alias setting were all left naming a
 * row that no longer existed. The user saw their per-app routing vanish
 * while the picker still insisted those apps were "routed by" the old
 * name. Nothing about a display string should be load-bearing.
 *
 * @property id            Opaque, immutable, never displayed. Generated
 *                         by [newId] at creation.
 * @property name          Display label, e.g. `"narrator"`. Mutable.
 *                         Must satisfy [NAME_REGEX]; validate at the call
 *                         site via [isValidName] before constructing.
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
@Entity(
    tableName = "voice_alias",
    // Names stay unique — the editor's collision check relies on it and a
    // duplicate would make the picker ambiguous. It is a constraint on a
    // column now, not on identity.
    indices = [Index(value = ["name"], unique = true)],
)
data class VoiceAlias(
    @PrimaryKey val id: String,
    val name: String,
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
     * The literal `"auto"` (`LangDetector.AUTO`) is a third state:
     * detect each utterance's language from its text and phonemize
     * accordingly. The column is free-form TEXT, so the sentinel needed
     * no migration.
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
    val fallbackAliasId: String? = null,
) {
    companion object {
        /** A fresh opaque id. Only creation calls this; renames never do. */
        fun newId(): String = java.util.UUID.randomUUID().toString()

        /**
         * Allowed alias names: letters (any case, incl. unicode), digits,
         * spaces, dashes, underscores, and apostrophes. 1–50 chars after
         * trimming. Leading/trailing whitespace is stripped at validate
         * time, so `"  Max Warren  "` ends up as `"Max Warren"`.
         *
         * Originally restricted to lower-case-only no-spaces tokens for
         * supposed CLI round-trip parity. Relaxed because users (rightly)
         * want to name personas after people, with capitals and spaces.
         * Since db v10 the name is not an identifier at all, so the only
         * job left for this rule is keeping labels sane and unambiguous.
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
