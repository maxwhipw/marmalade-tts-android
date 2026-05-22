package app.marmalade.tts.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-rule unit tests for the Android port of marmalade-tts's text
 * preprocessing.
 *
 * Tested behavior:
 *  - Each rule transforms its input the way the CLI does (so a user
 *    moving between CLI and Android sees the same output).
 *  - Composition (full enabled-set) handles the canonical "long sentence
 *    with currency + numbers + URL + emoji" case end-to-end.
 *  - Disabled rules are no-ops — toggling a rule off must not affect
 *    text the rule would otherwise have matched.
 *  - Year-protection in the `number` rule: 1900-2099 stay as digits.
 *  - Currency edge cases the CLI README calls out specifically (e.g.
 *    `$3.50` → "3 dollars and 50 cents", not "3.50 dollars").
 *
 * Skipped on purpose:
 *  - Integration with DataStore / SettingsRepository (covered by the
 *    SettingsRepositoryTest round-trip).
 *  - The Settings UI wiring (covered by SettingsViewModelTest).
 *  - The engine call path (covered by ViewModel + service tests).
 *
 * Pure JVM — number spell-out is hand-rolled (not ICU-backed) so no
 * Android runtime needed.
 */
class PreprocessorTest {

    private val preprocessor = Preprocessor(
        rulesByName = PreprocessingRules.ALL.associateBy { it.name },
    )

    /** Convenience: run with every rule enabled (the kitten default). */
    private fun all(text: String): String =
        preprocessor.apply(text, EngineProfiles.defaultsFor("kitten"))

    /** Convenience: run with one rule only. */
    private fun only(rule: String, text: String): String =
        preprocessor.apply(text, setOf(rule))

    // ── currency ────────────────────────────────────────────────────

    @Test
    fun currency_dollars_integer() {
        assertEquals("100 dollars", only("currency", "\$100"))
    }

    @Test
    fun currency_one_dollar_is_singular() {
        // The CLI's _currency pluralisation: "1 dollar", not "1 dollars".
        assertEquals("1 dollar", only("currency", "\$1"))
    }

    @Test
    fun currency_dollars_and_cents() {
        // Canonical CLI example called out in the spec.
        assertEquals("3 dollars and 50 cents", only("currency", "\$3.50"))
    }

    @Test
    fun currency_pounds_and_pennies() {
        // British "penny" pluralises irregularly to "pennies".
        assertEquals("3 pounds and 50 pennies", only("currency", "£3.50"))
    }

    // ── percentage ──────────────────────────────────────────────────

    @Test
    fun percentage_integer() {
        assertEquals("50 percent", only("percentage", "50%"))
    }

    @Test
    fun percentage_decimal() {
        assertEquals("99.9 percent", only("percentage", "99.9%"))
    }

    // ── ordinal ─────────────────────────────────────────────────────

    @Test
    fun ordinal_first() {
        assertEquals("first", only("ordinal", "1st"))
    }

    @Test
    fun ordinal_twentythird() {
        // ICU emits "twenty-third" for en-US.
        assertEquals("twenty-third", only("ordinal", "23rd"))
    }

    // ── number (with year protection) ───────────────────────────────

    @Test
    fun number_small_integer() {
        assertEquals("forty-two", only("number", "42"))
    }

    @Test
    fun number_year_left_as_digits() {
        // CLI year-protection: 4-digit numbers 1900-2099 stay as digits
        // so the speech engine pronounces them naturally ("nineteen
        // eighty-five") instead of as a cardinal ("one thousand nine
        // hundred eighty-five").
        assertEquals("1985", only("number", "1985"))
    }

    @Test
    fun number_year_band_boundary() {
        // 1899 is outside the protection band → verbalize as cardinal.
        // 2099 is the upper bound → stays as digits.
        assertEquals("2099", only("number", "2099"))
        assertTrue(
            "1899 should be verbalized",
            "1899" != only("number", "1899"),
        )
    }

    @Test
    fun number_decimal_pointwise() {
        // CLI: 99.5 → "ninety-nine point five".
        assertEquals("ninety-nine point five", only("number", "99.5"))
    }

    // ── abbreviation ────────────────────────────────────────────────

    @Test
    fun abbreviation_doctor() {
        assertEquals("doctor Smith", only("abbreviation", "Dr. Smith"))
    }

    @Test
    fun abbreviation_etcetera() {
        // "etc." → "et cetera" per the CLI common-abbreviations table.
        // Stand-alone tokens — abbreviation regex doesn't eat
        // surrounding whitespace, but we still need it normalised.
        assertEquals("et cetera", only("abbreviation", "etc.").trim())
    }

    @Test
    fun abbreviation_dot_separated_acronym() {
        // U.S.A. → U S A (CLI dot-separated branch). After the
        // unconditional whitespace collapse we expect a single-spaced
        // form.
        assertEquals("U S A", only("abbreviation", "U.S.A."))
    }

    // ── email ───────────────────────────────────────────────────────

    @Test
    fun email_simple() {
        assertEquals(
            "alice at example dot com",
            only("email", "alice@example.com"),
        )
    }

    @Test
    fun email_multipart_domain() {
        assertEquals(
            "bob at mail dot example dot co dot uk",
            only("email", "bob@mail.example.co.uk"),
        )
    }

    // ── url ─────────────────────────────────────────────────────────

    @Test
    fun url_https_with_path() {
        assertEquals(
            "example dot com",
            only("url", "https://example.com/foo/bar"),
        )
    }

    @Test
    fun url_strips_www() {
        // The CLI regex captures the www. into a separate group and only
        // verbalizes the post-www domain.
        assertEquals(
            "example dot com",
            only("url", "https://www.example.com"),
        )
    }

    // ── html ────────────────────────────────────────────────────────

    @Test
    fun html_strips_tags_and_decodes_entities() {
        // Tag-strip + entity-decode in one pass. Whitespace collapse
        // (final step in Preprocessor.apply) tidies the result.
        assertEquals(
            "Hello & welcome",
            only("html", "<p>Hello &amp; welcome</p>"),
        )
    }

    @Test
    fun html_decodes_named_entities() {
        assertEquals(
            "1 < 2 & 3 > 0",
            only("html", "1 &lt; 2 &amp; 3 &gt; 0"),
        )
    }

    // ── markdown ────────────────────────────────────────────────────

    @Test
    fun markdown_strips_bold_and_italic() {
        // Bold/italic markers vanish; inner text survives.
        assertEquals(
            "Hello world!",
            only("markdown", "**Hello** *world*!"),
        )
    }

    @Test
    fun markdown_strips_link_keeps_anchor_text() {
        // [anchor](url) → anchor (CLI's _MD_LINK).
        assertEquals(
            "Click here for more",
            only("markdown", "[Click here](https://example.com) for more"),
        )
    }

    // ── emoji ───────────────────────────────────────────────────────

    @Test
    fun emoji_strips_with_whitespace_collapse() {
        // Emoji codepoints get replaced with a space; the final
        // whitespace-collapse pass yields a single inter-word gap.
        assertEquals("Hello world", only("emoji", "Hello 🤣 world"))
    }

    // ── math, ampersand, hashtag ────────────────────────────────────

    @Test
    fun math_plus_between_spaces() {
        // Only fires when surrounded by spaces; doesn't eat hyphens in
        // compound words.
        assertEquals("2 plus 2", only("math", "2 + 2"))
    }

    @Test
    fun ampersand_with_spaces() {
        assertEquals("you and me", only("ampersand", "you & me"))
    }

    @Test
    fun hashtag_number() {
        assertEquals("number 100", only("hashtag", "#100"))
    }

    @Test
    fun hashtag_word() {
        assertEquals("hashtag hello", only("hashtag", "#hello"))
    }

    // ── composition ─────────────────────────────────────────────────

    @Test
    fun composition_realistic_paragraph() {
        // The CLI's regression-style smoke test — currency + number +
        // url + emoji all in one go. We don't assert character-for-
        // character (locale-specific quirks can shift the exact words),
        // we assert each phrase is present and the noise is gone.
        val input = "Send me \$100 by Friday — see https://example.com 🤣"
        val out = all(input)
        assertTrue("dollars should be expanded: $out", "dollars" in out)
        assertTrue("URL should be expanded: $out", "example dot com" in out)
        // Emoji codepoint must be absent after stripping + final collapse.
        assertTrue("emoji should be stripped: $out", "🤣" !in out)
        // No multi-space runs survive the final collapse.
        assertTrue("no double spaces: $out", "  " !in out)
    }

    @Test
    fun composition_empty_set_is_identity_modulo_whitespace() {
        // With nothing enabled, the only transform is the unconditional
        // whitespace collapse at the end. Input that's already
        // whitespace-clean must round-trip exactly.
        val s = "Plain text, nothing to see here."
        assertEquals(s, preprocessor.apply(s, emptySet()))
    }

    @Test
    fun composition_collapses_internal_whitespace() {
        // The final pass collapses runs of whitespace.
        assertEquals("a b c", preprocessor.apply("a   b\tc", emptySet()))
    }

    @Test
    fun composition_unknown_rule_names_ignored() {
        // Forward-compat: a stored set predating a rule rename must not
        // crash — unknown names are skipped silently.
        val s = "no change"
        assertEquals(s, preprocessor.apply(s, setOf("nope_not_a_rule")))
    }

    // ── disabled rules are no-ops ───────────────────────────────────

    @Test
    fun disabled_currency_leaves_dollar_amount_intact() {
        // Sanity-check: turning a rule off must not implicitly let
        // another rule (e.g. `number`) eat the digits. With only
        // `number` enabled, "$100" should drop to "$" + verbalized
        // "100" because $ isn't in a number-aware position. The
        // currency rule was off, so the $ doesn't get expanded.
        val out = only("number", "\$100")
        // "100" alone would be one hundred; the $ should still be present
        // (we never turned on currency).
        assertTrue("$ should survive when currency rule is off: $out", "$" in out)
        assertTrue(
            "digits should be verbalized when number rule is on: $out",
            "one hundred" in out,
        )
    }
}
