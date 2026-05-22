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
    val DEFAULT_PROFILES: Map<String, Set<String>> = mapOf(
        "kitten" to setOf(
            "markdown", "html",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "math", "ampersand", "hashtag", "emoji",
        ),
        "kokoro" to setOf(
            // Kokoro (via misaki) handles numbers, abbreviations, and some
            // symbols natively — skip those rules.
            "markdown", "html",
            "currency", "percentage", "time", "date",
            "email", "url", "filename",
            "math", "ampersand", "hashtag", "emoji",
        ),
        "piper" to setOf(
            // Piper does almost nothing natively — apply everything.
            "markdown", "html",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "math", "ampersand", "hashtag", "emoji",
        ),
        "coqui" to setOf(
            // Coqui handles basic numbers natively but not much else.
            "markdown", "html",
            "currency", "percentage", "time", "date",
            "email", "url", "filename", "abbreviation",
            "math", "ampersand", "hashtag", "emoji",
        ),
        "pocket" to setOf(
            // PocketSphinx-derived engine; no native text normalization.
            "markdown", "html",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "math", "ampersand", "hashtag", "emoji",
        ),
        "matcha" to setOf(
            // Matcha-TTS phonemizes only — normalize everything upstream.
            "markdown", "html",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "math", "ampersand", "hashtag", "emoji",
        ),
        "emojivoice" to setOf(
            // EmojiVoice runs on Matcha-TTS — also no native normalization.
            // The "emoji" rule is INTENTIONALLY omitted: emojivoice
            // consumes the emoji itself (it maps to the speaker id and
            // strips it inside the engine). Stripping early would force
            // every utterance to the neutral speaker.
            "markdown", "html",
            "currency", "percentage", "ordinal", "time", "date",
            "email", "url", "filename", "abbreviation", "number",
            "math", "ampersand", "hashtag",
        ),
    )

    /**
     * Look up the default rule set for [engineName], falling back to the
     * `kitten` profile for any unknown engine. Matches the CLI's
     * `ENGINE_PROFILES.get(engine, ENGINE_PROFILES["kitten"])` fallback
     * behaviour.
     */
    fun defaultsFor(engineName: String): Set<String> =
        DEFAULT_PROFILES[engineName] ?: DEFAULT_PROFILES.getValue("kitten")
}
