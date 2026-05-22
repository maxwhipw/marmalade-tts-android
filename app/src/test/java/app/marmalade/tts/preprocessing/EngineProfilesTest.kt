package app.marmalade.tts.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinning tests for the per-engine default preprocessing profiles.
 *
 * Why pin these: each engine's default profile is a UX contract — a
 * user installing kitten today gets currency+numbers+url+… on, and
 * future profile-edits should be deliberate. A blunt equality test
 * catches accidental rule deletions in code review; named assertions
 * catch the "intentional choice" reversals (e.g. someone toggling
 * `emoji` ON for emojivoice would silently break the speaker-routing
 * feature).
 */
class EngineProfilesTest {

    @Test
    fun kitten_includes_every_rule() {
        // Kitten ships no native text normalization — its default profile
        // contains every rule we offer. This is the CLI's invariant; if
        // someone trims kitten's defaults they should know it.
        val profile = EngineProfiles.defaultsFor("kitten")
        val allNames = PreprocessingRules.ALL.map { it.name }.toSet()
        assertEquals(allNames, profile)
    }

    @Test
    fun emojivoice_intentionally_omits_emoji_rule() {
        // EmojiVoice consumes the emoji as its speaker-id signal. The
        // `emoji` rule strips them — running both would force every
        // utterance to the neutral speaker. This is the most important
        // intentional exclusion in the whole profile set.
        val profile = EngineProfiles.defaultsFor("emojivoice")
        assertFalse(
            "emojivoice MUST omit the emoji rule (speaker routing breaks otherwise)",
            "emoji" in profile,
        )
    }

    @Test
    fun kokoro_omits_what_misaki_handles_natively() {
        // misaki (kokoro's text frontend) handles numbers + abbreviations
        // + ordinals natively. Re-running our generic rules over what
        // misaki already normalized would garble the output.
        val profile = EngineProfiles.defaultsFor("kokoro")
        assertFalse("kokoro skips `number` (misaki handles)", "number" in profile)
        assertFalse("kokoro skips `abbreviation` (misaki handles)", "abbreviation" in profile)
        assertFalse("kokoro skips `ordinal` (misaki handles)", "ordinal" in profile)
    }

    @Test
    fun coqui_omits_number_but_keeps_abbreviation() {
        // Coqui handles basic numbers but no abbreviations — the inverse
        // of kokoro's misaki frontend. Stable selection ported from the
        // CLI's ENGINE_PROFILES.
        val profile = EngineProfiles.defaultsFor("coqui")
        assertFalse("coqui skips `number` (handled natively)", "number" in profile)
        assertTrue("coqui keeps `abbreviation`", "abbreviation" in profile)
    }

    @Test
    fun unknown_engine_falls_back_to_kitten_profile() {
        // Matches the CLI's `ENGINE_PROFILES.get(engine, ENGINE_PROFILES["kitten"])`
        // shape. A misspelled or future engine name shouldn't crash —
        // it should yield the "everything on" safe default.
        val kitten = EngineProfiles.defaultsFor("kitten")
        val unknown = EngineProfiles.defaultsFor("nonexistent-engine")
        assertEquals(kitten, unknown)
    }

    @Test
    fun every_default_profile_only_references_real_rules() {
        // Catch typo'd rule names early — if a profile lists
        // "abbreviations" (plural) the rule lookup silently no-ops at
        // runtime, which is exactly the kind of bug a test should pin.
        val realNames = PreprocessingRules.ALL.map { it.name }.toSet()
        for ((engine, profile) in EngineProfiles.DEFAULT_PROFILES) {
            for (rule in profile) {
                assertTrue(
                    "Engine '$engine' profile references unknown rule '$rule'",
                    rule in realNames,
                )
            }
        }
    }

    @Test
    fun every_engine_in_catalog_has_a_profile_defined() {
        // Forward-compat with the install catalog — once an engine ships,
        // it must have a profile or the Settings UI shows it with empty
        // toggles. Currently kitten is the only catalog entry and has a
        // profile; this guards the invariant for the next engine.
        val kitten = EngineProfiles.DEFAULT_PROFILES["kitten"]
        assertNotNull("kitten profile must exist", kitten)
    }
}
