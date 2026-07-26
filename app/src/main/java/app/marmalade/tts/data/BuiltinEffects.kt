package app.marmalade.tts.data

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

    // Trial presets — seeded for Max to audition, named "<name> - test".
    // Approve → rename and mirror into the CLI. Reject → delete + prune.
    const val CASSETTE_ID = "builtin:test_cassette"
    const val CROONER_ID = "builtin:test_crooner"
    const val DEADPAN_ID = "builtin:test_deadpan"
    const val FINE_PRINT_ID = "builtin:test_fine_print"
    const val NEXT_ROOM_ID = "builtin:test_next_room"
    const val BEDTIME_ID = "builtin:test_bedtime"
    const val TILES_ID = "builtin:test_tiles"

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
            // Android-only stackups (Bitcrush / RingMod — no sox equivalent).
            row(CYBORG_ID, "Cyborg", EffectChain.CYBORG_BLOCKS),
            row(EIGHT_BIT_ID, "8-bit", EffectChain.EIGHT_BIT_BLOCKS),
            row(GLITCH_ID, "Glitch", EffectChain.GLITCH_BLOCKS),
            // Trial batch — auditioning only, see EffectChain's trial section.
            row(CASSETTE_ID, "Cassette - test", EffectChain.CASSETTE_BLOCKS),
            row(CROONER_ID, "Crooner - test", EffectChain.CROONER_BLOCKS),
            row(DEADPAN_ID, "Deadpan - test", EffectChain.DEADPAN_BLOCKS),
            row(FINE_PRINT_ID, "Fine print - test", EffectChain.FINE_PRINT_BLOCKS),
            row(NEXT_ROOM_ID, "Next room - test", EffectChain.NEXT_ROOM_BLOCKS),
            row(BEDTIME_ID, "Bedtime - test", EffectChain.BEDTIME_BLOCKS),
            row(TILES_ID, "Tiles - test", EffectChain.TILES_BLOCKS),
        )
    }

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
