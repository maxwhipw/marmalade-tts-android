package app.marmalade.tts.data

import androidx.annotation.StringRes
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.audio.EffectChain
import app.marmalade.tts.data.db.Effect

/**
 * The seeded, read-only effects that mirror the CLI's `BUILTIN_PRESETS`
 * (marmalade_tts/effects.py). Each is the matching [EffectChain] block list
 * encoded to JSON, so a seeded built-in reproduces the CLI preset's sound.
 *
 * Seeded into the `effect` table on catalog bumps (see
 * [app.marmalade.tts.MarmaladeTtsApplication]) with REPLACE-on-conflict, so
 * shipping a tweak to a built-in chain re-seeds it. Custom effects (different
 * ids) are never touched. Built-ins are read-only in the UI — users duplicate
 * one to customize — so re-seeding can't clobber user edits.
 */
object BuiltinEffects {

    const val CAVE_ID = "builtin:cave"
    const val TELEPHONE_ID = "builtin:telephone"
    const val CHIPMUNK_ID = "builtin:chipmunk"
    const val DEEP_ID = "builtin:deep"
    const val STADIUM_ID = "builtin:stadium"
    const val MEGAPHONE_ID = "builtin:megaphone"

    // Curated voice stackups (E-K).
    const val BROADCASTER_ID = "builtin:broadcaster"
    const val PODCAST_ID = "builtin:podcast"
    const val TRAILER_ID = "builtin:trailer"
    const val AUDIOBOOK_ID = "builtin:audiobook"
    const val WALKIE_TALKIE_ID = "builtin:walkie_talkie"
    const val VINTAGE_RADIO_ID = "builtin:vintage_radio"
    const val INTERCOM_ID = "builtin:intercom"
    const val UNDERWATER_ID = "builtin:underwater"
    const val AI_ID = "builtin:ai"
    const val ETHEREAL_ID = "builtin:ethereal"
    const val DRAGON_ID = "builtin:dragon"

    // Stackups using the Android-only blocks (E-L) — no CLI equivalent.
    const val CYBORG_ID = "builtin:cyborg"
    const val EIGHT_BIT_ID = "builtin:eight_bit"
    const val GLITCH_ID = "builtin:glitch"

    /** Monotone has no sox equivalent, so these two are Android-only. */
    const val MONOTONE_FEMALE_ID = "builtin:monotone_female"
    const val MONOTONE_MALE_ID = "builtin:monotone_male"

    /**
     * The seeded CLI presets, in the CLI's listing order. Built-ins sort ahead
     * of custom effects (createdAt 0).
     *
     * Lazy on purpose: the initializer calls [EffectBlockJson.encode], which
     * touches `org.json`. `org.json` is a throwing stub on the plain JVM, so
     * an *eager* `val` would crash the class's static initializer the instant
     * any JVM unit test references another member of this object (e.g.
     * [idForLegacyPreset] from AliasViewModel). Deferring to first access keeps
     * the encode on the on-device / Robolectric paths that have real org.json.
     */
    val seedRows: List<Effect> by lazy {
        fun row(id: String, name: String, blocks: List<app.marmalade.tts.audio.EffectBlock>) =
            Effect(id, name, isBuiltin = true, blocksJson = EffectBlockJson.encode(blocks), createdAt = 0L)
        listOf(
            row(CAVE_ID, "Cave", EffectChain.CAVE_BLOCKS),
            row(CHIPMUNK_ID, "Chipmunk", EffectChain.CHIPMUNK_BLOCKS),
            row(DEEP_ID, "Deep", EffectChain.DEEP_BLOCKS),
            row(TELEPHONE_ID, "Telephone", EffectChain.TELEPHONE_BLOCKS),
            row(STADIUM_ID, "Stadium", EffectChain.STADIUM_BLOCKS),
            row(MEGAPHONE_ID, "Megaphone", EffectChain.MEGAPHONE_BLOCKS),
            // Curated voice stackups (E-K).
            row(BROADCASTER_ID, "Broadcaster", EffectChain.BROADCASTER_BLOCKS),
            row(PODCAST_ID, "Podcast", EffectChain.PODCAST_BLOCKS),
            row(TRAILER_ID, "Trailer", EffectChain.TRAILER_BLOCKS),
            row(AUDIOBOOK_ID, "Audiobook", EffectChain.AUDIOBOOK_BLOCKS),
            row(WALKIE_TALKIE_ID, "Walkie-talkie", EffectChain.WALKIE_TALKIE_BLOCKS),
            row(VINTAGE_RADIO_ID, "Vintage radio", EffectChain.VINTAGE_RADIO_BLOCKS),
            row(INTERCOM_ID, "Intercom", EffectChain.INTERCOM_BLOCKS),
            row(UNDERWATER_ID, "Underwater", EffectChain.UNDERWATER_BLOCKS),
            row(AI_ID, "AI", EffectChain.AI_BLOCKS),
            row(ETHEREAL_ID, "Ethereal", EffectChain.ETHEREAL_BLOCKS),
            row(DRAGON_ID, "Dragon", EffectChain.DRAGON_BLOCKS),
            // Android-only stackups (Bitcrush / RingMod / Monotone — no sox equivalent).
            row(CYBORG_ID, "Cyborg", EffectChain.CYBORG_BLOCKS),
            row(EIGHT_BIT_ID, "8-bit", EffectChain.EIGHT_BIT_BLOCKS),
            row(GLITCH_ID, "Glitch", EffectChain.GLITCH_BLOCKS),
            row(MONOTONE_FEMALE_ID, "Monotone female", EffectChain.MONOTONE_FEMALE_BLOCKS),
            row(MONOTONE_MALE_ID, "Monotone male", EffectChain.MONOTONE_MALE_BLOCKS),
        )
    }

    /**
     * The localized display name of each built-in (#118 UI-1). The seeded
     * `Effect.name` stays English: it is the stable fallback and what the CLI
     * calls the preset. Show names through `effectDisplayName`, never
     * `Effect.name` directly, so a built-in follows the app language.
     */
    private val nameResById: Map<String, Int> = mapOf(
        CAVE_ID to R.string.effects_preset_cave,
        CHIPMUNK_ID to R.string.effects_preset_chipmunk,
        DEEP_ID to R.string.effects_preset_deep,
        TELEPHONE_ID to R.string.effects_preset_telephone,
        STADIUM_ID to R.string.effects_preset_stadium,
        MEGAPHONE_ID to R.string.effects_preset_megaphone,
        BROADCASTER_ID to R.string.effects_preset_broadcaster,
        PODCAST_ID to R.string.effects_preset_podcast,
        TRAILER_ID to R.string.effects_preset_trailer,
        AUDIOBOOK_ID to R.string.effects_preset_audiobook,
        WALKIE_TALKIE_ID to R.string.effects_preset_walkie_talkie,
        VINTAGE_RADIO_ID to R.string.effects_preset_vintage_radio,
        INTERCOM_ID to R.string.effects_preset_intercom,
        UNDERWATER_ID to R.string.effects_preset_underwater,
        AI_ID to R.string.effects_preset_ai,
        ETHEREAL_ID to R.string.effects_preset_ethereal,
        DRAGON_ID to R.string.effects_preset_dragon,
        CYBORG_ID to R.string.effects_preset_cyborg,
        EIGHT_BIT_ID to R.string.effects_preset_eight_bit,
        GLITCH_ID to R.string.effects_preset_glitch,
        MONOTONE_FEMALE_ID to R.string.effects_preset_monotone_female,
        MONOTONE_MALE_ID to R.string.effects_preset_monotone_male,
    )

    /** The name resource for built-in [id], or null for a custom effect. */
    @StringRes
    fun nameRes(id: String): Int? = nameResById[id]

    /** Ids of the currently-seeded built-ins — used to prune stale built-ins on re-seed. */
    val seedIds: Set<String> by lazy { seedRows.map { it.id }.toSet() }

    /**
     * Map a legacy `EffectPreset` enum name (as stored in the old
     * `voice_alias.effectPreset` column) to the built-in effect id. NONE (and
     * anything unrecognized) maps to null = "no effect". Used by the alias
     * migration in E-D. `ROBOT` is deliberately unmapped — the preset was
     * removed, so an old Robot alias comes back dry rather than pointing at a
     * pruned row.
     */
    fun idForLegacyPreset(preset: String): String? = when (preset.trim().uppercase()) {
        "CAVE" -> CAVE_ID
        "TELEPHONE" -> TELEPHONE_ID
        else -> null
    }
}
