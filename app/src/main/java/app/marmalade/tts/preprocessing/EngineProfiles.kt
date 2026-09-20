package app.marmalade.tts.preprocessing

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   First launch / "Reset to defaults" tap on Settings → Text preprocessing
//     │
//     ▼
//   EngineProfiles.DEFAULT_PROFILES[engineName] ──► Set<String>
//     │
//     ▼
//   SettingsRepository.setEnabledRules(engineName, set)
//                     │
//                     ▼
//                  DataStore writes JSON-y CSV under "preprocessing_rules_<engineName>"
//
//   On read:
//     SettingsRepository.enabledRules(engineName)
//        falls back to DEFAULT_PROFILES[engineName] when no row stored,
//        and to DEFAULT_PROFILES["kitten"] as a last resort for unknown
//        engines (matches the CLI's behaviour).
// -----------------------------------------------------------------------------

/**
 * Per-engine default sets of preprocessing rules to apply.
 *
 * Ported verbatim from the CLI's `ENGINE_PROFILES` dict in
 * `marmalade_tts/preprocessing.py`. Engines that handle some patterns
 * natively skip those rules (e.g. Kokoro doesn't need `number` because
 * misaki normalizes digits internally; EmojiVoice skips `emoji` because
 * it consumes the emoji as a speaker-id signal).
 *
 * Only engines currently in [app.marmalade.tts.install.EngineCatalog] need
 * to appear here — but we keep the full CLI list so the Settings UI can
 * be extended when future engines ship without revisiting this file.
 */
object EngineProfiles {

    /**
     * Engine name → default set of enabled rule names.
     *
     * The set is unordered; rule *application* order is driven by
     * [PreprocessingRules.ALL] (CLI's `priority` list) and ignores the
     * order the user toggles them in Settings. Storing as a Set instead
     * of a List avoids the question "do duplicates matter?" — they
     * don't.
     */
    /**
     * Kitten family default rules — applies to both Kitten Direct variants.
     *
     * Kitten ships no native text normalization, so this is the full rule set
     * (every rule in [PreprocessingRules.ALL]). It includes the espeak-specific
     * `heteronym` and `respell` fixups: Kitten phonemizes English through
     * espeak, so it needs them.
     */
    private val KITTEN_DEFAULTS: Set<String> = setOf(
        "linebreaks", "markdown", "html", "separators", "parens",
        "currency", "percentage", "ordinal", "time", "date",
        "email", "url", "filename", "abbreviation", "number",
        "heteronym", "respell",
        "math", "ampersand", "hashtag", "emoji",
        "repeated_punctuation", "terminal_punctuation",
    )

    /**
     * Kokoro family default rules. Kokoro (via misaki upstream) handles
     * numbers, abbreviations, and some symbols natively — skip those rules.
     *
     * NOTE the deliberate divergence from the CLI's `kokoro` profile: the CLI
     * omits BOTH `heteronym` and `respell` for kokoro, because CLI-kokoro
     * phonemizes with misaki, which disambiguates heteronyms via POS tags and
     * has its own lexicon. Android's KokoroDirect instead routes English
     * through espeak (untied espeak + EnPhonemeFixups — see the
     * KokoroEspeakG2P.kt header), so it needs the same espeak fixups as Kitten.
     * Hence `heteronym` and `respell` ARE included here.
     */
    private val KOKORO_DEFAULTS: Set<String> = setOf(
        "linebreaks", "markdown", "html", "separators", "parens",
        "currency", "percentage", "time", "date",
        "email", "url", "filename",
        "heteronym", "respell",
        "math", "ampersand", "hashtag", "emoji",
        "repeated_punctuation", "terminal_punctuation",
    )

    /**
     * Pocket TTS default rules. Pocket does its own phonemization upstream of
     * ORT — no espeak anywhere in the pipeline (see the comment in
     * PocketEngine.kt `synthesize`). Every respelling in `respell` and every
     * heteronym context in `heteronym` is probe-verified against espeak
     * specifically, so those two rules would only corrupt Pocket's readings.
     * Pocket DOES get the engine-agnostic `linebreaks` and `parens`, plus the
     * rest of the generic normalization it has no native handling for.
     */
    private val POCKET_DEFAULTS: Set<String> = setOf(
        "linebreaks", "markdown", "html", "separators", "parens",
        "currency", "percentage", "ordinal", "time", "date",
        "email", "url", "filename", "abbreviation", "number",
        "math", "ampersand", "hashtag", "emoji",
        "repeated_punctuation", "terminal_punctuation",
    )

    /**
     * Default preprocessing rule sets per engine name. Kitten Direct uses the
     * Kitten defaults; Kokoro Direct uses the Kokoro defaults. Pocket TTS uses
     * [POCKET_DEFAULTS] (no espeak, so no heteronym/respell).
     */
    val DEFAULT_PROFILES: Map<String, Set<String>> = mapOf(
        "kitten-direct-v0_8" to KITTEN_DEFAULTS,
        "kokoro-direct-v1_0" to KOKORO_DEFAULTS,
        "pocket-tts-en-v2026_04" to POCKET_DEFAULTS,
        // Developer-only clean-room Pocket engine — same profile as production Pocket.
        "pocket-tts-en-v2026_04-dev" to POCKET_DEFAULTS,
        // VITS Marmalade: the checkpoints normalize nothing upstream — espeak
        // gets the text as-is — so every rule applies, same as Kitten
        // (including the espeak heteronym/respell fixups).
        "vits-marmalade-v1" to KITTEN_DEFAULTS,
        "piper" to setOf(
            // Piper does almost nothing natively — apply everything.
            "linebreaks", "markdown", "html", "separators", "parens",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "heteronym", "respell",
            "math", "ampersand", "hashtag", "emoji",
        "terminal_punctuation",
        ),
        "coqui" to setOf(
            // Coqui handles basic numbers natively but not much else.
            "linebreaks", "markdown", "html", "separators", "parens",
            "currency", "percentage", "time", "date",
            "email", "url", "filename", "abbreviation",
            "heteronym", "respell",
            "math", "ampersand", "hashtag", "emoji",
        "terminal_punctuation",
        ),
        "pocket" to setOf(
            // Legacy PocketSphinx-derived espeak engine; no native text
            // normalization (distinct from the pocket-tts-* ORT engines above,
            // which do their own phonemization and get no espeak fixups).
            "linebreaks", "markdown", "html", "separators", "parens",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "heteronym", "respell",
            "math", "ampersand", "hashtag", "emoji",
        "terminal_punctuation",
        ),
        "matcha" to setOf(
            // Matcha-TTS phonemizes only — normalize everything upstream.
            "linebreaks", "markdown", "html", "separators", "parens",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "heteronym", "respell",
            "math", "ampersand", "hashtag", "emoji",
        "terminal_punctuation",
        ),
        "emojivoice" to setOf(
            // EmojiVoice runs on Matcha-TTS — also no native normalization.
            // The "emoji" rule is INTENTIONALLY omitted: emojivoice
            // consumes the emoji itself (it maps to the speaker id and
            // strips it inside the engine). Stripping early would force
            // every utterance to the neutral speaker.
            "linebreaks", "markdown", "html", "separators", "parens",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "heteronym", "respell",
            "math", "ampersand", "hashtag",
        ),
    )

    /**
     * Look up the default rule set for [engineName], falling back to the
     * Kitten Nano profile for any unknown engine.
     */
    fun defaultsFor(engineName: String): Set<String> =
        DEFAULT_PROFILES[engineName] ?: KITTEN_DEFAULTS
}
