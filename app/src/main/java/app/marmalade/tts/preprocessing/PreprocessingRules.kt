package app.marmalade.tts.preprocessing

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Preprocessor.apply(text, enabledRules)
//     │
//     ▼
//   for rule in PreprocessingRules.ALL:
//      if rule.name in enabledRules: text = rule.transform(text)
//
//   Each rule is a pure (String) -> String function. Composition order
//   matters — see ALL's ordering rationale. Ports the CLI's rule set in
//   `marmalade_tts/preprocessing.py`, with the same stable rule names so
//   users moving between CLI and Android see the same keys.
//
//   Number-to-words uses a small pure-Kotlin spell-out implementation
//   instead of `android.icu.text.RuleBasedNumberFormat`. The latter is
//   *runtime*-available since API 24 but is NOT in the compileSdk
//   android.jar stub, so referencing it directly is a compile error.
//   Reflection would work, but a 50-line en-US cardinal/ordinal table
//   is simpler and keeps the rule deterministic for tests.
// -----------------------------------------------------------------------------

/**
 * Catalog of named text preprocessing rules.
 *
 * Ported from `marmalade_tts/preprocessing.py` in marmalade-tts-cli. Rule
 * names match the CLI exactly so a user toggling, say, `abbreviation` in
 * the Android Settings sees the same behaviour as `abbreviation` in their
 * CLI config.
 *
 * Order in [ALL] = order of application (priority). The CLI's `priority`
 * list dictates this — emoji-strip first, formatting strippers next,
 * structured patterns (email/url) before generic numbers, currency before
 * numbers (so `$100` doesn't become "100"), abbreviations before
 * filenames (both have dots), bare numbers last.
 */
object PreprocessingRules {

    // -- Number spell-out -----------------------------------------------------
    //
    // Pure-Kotlin en-US cardinal/ordinal spell-out for integers up to one
    // less than a trillion. Output style matches num2words(): hyphenated
    // tens-units ("forty-two"), space-separated scale groups ("one
    // thousand two hundred"), no commas, no "and" before tens.
    //
    // Why hand-rolled instead of android.icu.text.RuleBasedNumberFormat:
    // the ICU class is *runtime*-available since API 24 but is not in the
    // compileSdk android.jar stub, so a direct reference fails to compile.
    // num2words on Python supports arbitrary locales; we only ship English
    // engines for now, so the small table below is good enough — and it
    // keeps tests independent of any Android SDK quirks.

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty",
        "sixty", "seventy", "eighty", "ninety",
    )
    // Cardinal → ordinal substitution for the simple word forms. Anything
    // else gets "th" appended (matches num2words' en behaviour: "forty-two"
    // → "forty-second" handled by suffixing the units word's ordinal form).
    private val CARDINAL_TO_ORDINAL: Map<String, String> = mapOf(
        "zero" to "zeroth",
        "one" to "first",
        "two" to "second",
        "three" to "third",
        "four" to "fourth",
        "five" to "fifth",
        "six" to "sixth",
        "seven" to "seventh",
        "eight" to "eighth",
        "nine" to "ninth",
        "ten" to "tenth",
        "eleven" to "eleventh",
        "twelve" to "twelfth",
        "thirteen" to "thirteenth",
        "fourteen" to "fourteenth",
        "fifteen" to "fifteenth",
        "sixteen" to "sixteenth",
        "seventeen" to "seventeenth",
        "eighteen" to "eighteenth",
        "nineteen" to "nineteenth",
        "twenty" to "twentieth",
        "thirty" to "thirtieth",
        "forty" to "fortieth",
        "fifty" to "fiftieth",
        "sixty" to "sixtieth",
        "seventy" to "seventieth",
        "eighty" to "eightieth",
        "ninety" to "ninetieth",
        "hundred" to "hundredth",
        "thousand" to "thousandth",
        "million" to "millionth",
        "billion" to "billionth",
    )

    /** Spell out a non-negative integer 0..999 as English words. */
    private fun spellUnder1000(n: Long): String {
        require(n in 0L..999L)
        if (n < 20L) return ONES[n.toInt()]
        if (n < 100L) {
            val t = (n / 10).toInt()
            val u = (n % 10).toInt()
            return if (u == 0) TENS[t] else "${TENS[t]}-${ONES[u]}"
        }
        val h = (n / 100).toInt()
        val rest = n % 100
        return if (rest == 0L) "${ONES[h]} hundred"
        else "${ONES[h]} hundred ${spellUnder1000(rest)}"
    }

    /** Spell out a non-negative integer as English words. */
    private fun spellCardinal(n: Long): String {
        if (n < 0L) return "minus ${spellCardinal(-n)}"
        if (n < 1000L) return spellUnder1000(n)
        if (n < 1_000_000L) {
            val thousands = n / 1000L
            val rest = n % 1000L
            val prefix = "${spellUnder1000(thousands)} thousand"
            return if (rest == 0L) prefix else "$prefix ${spellUnder1000(rest)}"
        }
        if (n < 1_000_000_000L) {
            val millions = n / 1_000_000L
            val rest = n % 1_000_000L
            val prefix = "${spellUnder1000(millions)} million"
            return if (rest == 0L) prefix else "$prefix ${spellCardinal(rest)}"
        }
        // Up to one less than a trillion — plenty for TTS use cases.
        val billions = n / 1_000_000_000L
        val rest = n % 1_000_000_000L
        val prefix = "${spellUnder1000(billions)} billion"
        return if (rest == 0L) prefix else "$prefix ${spellCardinal(rest)}"
    }

    /**
     * Spell out a non-negative integer as an English ordinal.
     *
     * Logic mirrors num2words' en behaviour: spell the cardinal, then
     * replace the last word with its ordinal form. For "forty-two" the
     * last *segment* (after the hyphen) is the units word.
     */
    private fun spellOrdinal(n: Long): String {
        if (n < 0L) return "minus ${spellOrdinal(-n)}"
        val cardinal = spellCardinal(n)
        // Split on the last space — the trailing token is what gets the
        // ordinal suffix. Handle the hyphenated tens-units form ("forty-
        // two" → "forty-second") by descending into the last hyphenated
        // segment.
        val lastSpace = cardinal.lastIndexOf(' ')
        val head = if (lastSpace == -1) "" else cardinal.substring(0, lastSpace + 1)
        val tail = if (lastSpace == -1) cardinal else cardinal.substring(lastSpace + 1)
        val ordinalTail = if (tail.contains('-')) {
            val hyphenIdx = tail.lastIndexOf('-')
            val tHead = tail.substring(0, hyphenIdx + 1)
            val tTail = tail.substring(hyphenIdx + 1)
            tHead + (CARDINAL_TO_ORDINAL[tTail] ?: "${tTail}th")
        } else {
            CARDINAL_TO_ORDINAL[tail] ?: "${tail}th"
        }
        return head + ordinalTail
    }

    // -- Currency rule --------------------------------------------------------
    //
    // $100 → 100 dollars; $3.50 → 3 dollars and 50 cents; £42 → 42 pounds.
    // Matches the CLI's _currency exactly.

    private val currencyRegex = Regex("([\$£€¥])(\\d+(?:\\.\\d{1,2})?)")

    private val currencySymbols: Map<String, Pair<String, String>> = mapOf(
        "$" to ("dollar" to "cent"),
        "£" to ("pound" to "penny"),
        "€" to ("euro" to "cent"),
        "¥" to ("yen" to ""), // yen has no fractional unit name
    )

    private fun expandCurrency(text: String): String =
        currencyRegex.replace(text) { m ->
            val sym = m.groupValues[1]
            val amount = m.groupValues[2]
            val (major, minor) = currencySymbols[sym] ?: ("units" to "")
            if ("." in amount) {
                val parts = amount.split(".", limit = 2)
                // toLongOrNull: a >19-digit amount overflows Long; leave the
                // match unchanged rather than throwing out of the pipeline
                // (same policy as expandOrdinal / expandNumber).
                val majorN = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return@replace m.value
                val minorN = parts[1].ifEmpty { "0" }.toLongOrNull() ?: return@replace m.value
                val pieces = mutableListOf<String>()
                if (majorN > 0L) {
                    pieces += "$majorN $major${if (majorN != 1L) "s" else ""}"
                }
                if (minorN > 0L && minor.isNotEmpty()) {
                    // British "penny" -> "pennies" rather than "pennys".
                    val minorPlural = pluralizeMinor(minor, minorN)
                    pieces += "$minorN $minorPlural"
                }
                if (pieces.isEmpty()) amount else pieces.joinToString(" and ")
            } else {
                val n = amount.toLongOrNull() ?: return@replace m.value
                "$n $major${if (n != 1L) "s" else ""}"
            }
        }

    private fun pluralizeMinor(name: String, count: Long): String {
        if (count == 1L) return name
        // Special-case "penny" → "pennies"; default rule is "+s".
        return if (name == "penny") "pennies" else "${name}s"
    }

    // -- Percentage rule ------------------------------------------------------
    //
    // 50% → 50 percent. CLI: _percentage.

    private val percentageRegex = Regex("(\\d+(?:\\.\\d+)?)%")
    private fun expandPercentage(text: String): String =
        percentageRegex.replace(text) { m -> "${m.groupValues[1]} percent" }

    // -- Ordinal rule ---------------------------------------------------------
    //
    // 1st → first, 23rd → twenty-third. CLI: _ordinal.

    private val ordinalRegex = Regex("\\b(\\d+)(?:st|nd|rd|th)\\b")
    private fun expandOrdinal(text: String): String =
        ordinalRegex.replace(text) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            spellOrdinal(n)
        }

    // -- Time rule ------------------------------------------------------------
    //
    // 10:30 → ten thirty, 3:00 PM → three PM, 14:00 → fourteen hundred.
    // CLI: _time.

    private val timeRegex = Regex(
        "\\b(\\d{1,2}):(\\d{2})\\s*(AM|PM|am|pm|a\\.m\\.|p\\.m\\.)?\\b",
    )
    private fun expandTime(text: String): String =
        timeRegex.replace(text) { m ->
            val hour = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val minute = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val suffix = m.groupValues[3].trim()
            val h = spellCardinal(hour)
            when {
                minute == 0L && suffix.isNotEmpty() -> "$h $suffix"
                minute == 0L && hour >= 13L -> "$h hundred"
                minute == 0L -> "$h o'clock"
                else -> {
                    val mi = if (minute < 10L) "oh ${spellCardinal(minute)}"
                    else spellCardinal(minute)
                    if (suffix.isNotEmpty()) "$h $mi $suffix" else "$h $mi"
                }
            }
        }

    // -- Date rule ------------------------------------------------------------
    //
    // 01/15/2025 → January fifteenth, 2025. CLI: _date_slash.

    private val dateRegex = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private fun expandDate(text: String): String =
        dateRegex.replace(text) { m ->
            val month = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val day = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val year = m.groupValues[3] // keep year literal so the number rule
            // doesn't verbalize it (we explicitly preserve years 1900-2099)
            if (month in 1L..12L) {
                "${MONTHS[(month - 1L).toInt()]} ${spellOrdinal(day)}, $year"
            } else {
                m.value
            }
        }

    // -- Email rule -----------------------------------------------------------
    //
    // user@example.com → user at example dot com. CLI: _email.

    private val emailRegex = Regex(
        "\\b([a-zA-Z0-9_.+-]+)@([a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+)\\b",
    )
    private fun expandEmail(text: String): String =
        emailRegex.replace(text) { m ->
            val user = m.groupValues[1]
            val domainParts = m.groupValues[2].split(".")
            "$user at ${domainParts.joinToString(" dot ")}"
        }

    // -- URL rule -------------------------------------------------------------
    //
    // https://example.com/foo → example dot com. CLI: _url.

    private val urlRegex = Regex(
        "https?://(www\\.)?([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})[^\\s]*",
    )
    private fun expandUrl(text: String): String =
        urlRegex.replace(text) { m ->
            val domain = m.groupValues[2]
            domain.split(".").joinToString(" dot ")
        }

    // -- Filename rule --------------------------------------------------------
    //
    // example.txt → example dot T X T. Only fires for known extensions to
    // avoid mangling decimals / version strings. CLI: _filename.

    private val filenameRegex = Regex("\\b(\\w+)\\.([a-zA-Z]{1,5})\\b")
    private val KNOWN_EXTS = setOf(
        "txt", "pdf", "doc", "docx", "xls", "xlsx", "csv", "json", "yaml", "yml",
        "xml", "html", "htm", "css", "js", "ts", "py", "rb", "go", "rs", "java",
        "cpp", "hpp", "c", "h", "sh", "bash", "zsh", "fish", "bat", "ps1",
        "md", "rst", "tex", "log", "conf", "cfg", "ini", "toml",
        "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico",
        "mp3", "wav", "ogg", "flac", "aac", "m4a",
        "mp4", "mkv", "avi", "mov", "webm",
        "zip", "tar", "gz", "bz2", "xz", "rar", "7z",
        "exe", "msi", "deb", "rpm", "apk", "dmg",
        "sql", "db", "sqlite",
        "onnx", "pt", "bin", "safetensors",
    )

    private fun expandFilename(text: String): String =
        filenameRegex.replace(text) { m ->
            val name = m.groupValues[1]
            val ext = m.groupValues[2]
            if (name.all { it.isDigit() }) return@replace m.value
            if (ext.lowercase() !in KNOWN_EXTS) return@replace m.value
            val spelled = ext.uppercase().toCharArray().joinToString(" ")
            "$name dot $spelled"
        }

    // -- Abbreviation rule ----------------------------------------------------
    //
    // U.S.A. → U S A, e.g. → for example, Dr. → doctor. CLI: _abbreviation.

    // The \b before the second group is load-bearing (and matches the CLI
    // pattern): without it the short abbreviations matched mid-word, so any
    // word ending in "st." at a sentence boundary — "smoke test. This" —
    // became "te saint This", swallowing the period ("left." hit ft. the
    // same way).
    private val abbreviationRegex = Regex(
        "\\b(?:[A-Z]\\.){2,}|\\b(?:e\\.g\\.|i\\.e\\.|etc\\.|vs\\.|" +
            "[Mm]r\\.|[Mm]rs\\.|[Mm]s\\.|[Dd]r\\.|[Ss]r\\.|[Jj]r\\.|" +
            "[Ss]t\\.|ft\\.|lb\\.|oz\\.|[Ee]x\\.)",
    )
    private val COMMON_ABBREVIATIONS: Map<String, String> = mapOf(
        "e.g." to "for example", "i.e." to "that is", "etc." to "et cetera",
        "vs." to "versus", "mr." to "mister", "mrs." to "missus", "ms." to "miss",
        "dr." to "doctor", "sr." to "senior", "jr." to "junior",
        "st." to "saint", "ft." to "feet", "lb." to "pounds", "oz." to "ounces",
        "ex." to "for example",
    )
    private fun expandAbbreviation(text: String): String =
        abbreviationRegex.replace(text) { m ->
            val matched = m.value
            val lower = matched.lowercase()
            COMMON_ABBREVIATIONS[lower]?.let { return@replace it }
            // Spell out dot-separated abbreviations: U.S.A. → U S A.
            val letters = matched.replace(".", "")
            if (letters.all { it.isUpperCase() } && letters.length <= 6) {
                letters.toCharArray().joinToString(" ")
            } else {
                matched
            }
        }

    // -- Number rule ----------------------------------------------------------
    //
    // 42 → forty-two; 99.5 → ninety-nine point five. 4-digit years 1900-2099
    // are left as digits (the CLI special-cases this to avoid "nineteen
    // eighty-five" mid-sentence — speech engines say "1985" more naturally).
    // CLI: _number_to_words.

    private val numberRegex = Regex("\\b\\d+(?:\\.\\d+)?\\b")
    private fun expandNumber(text: String): String =
        numberRegex.replace(text) { m ->
            val raw = m.value
            try {
                if ("." in raw) {
                    val parts = raw.split(".", limit = 2)
                    val whole = parts[0].ifEmpty { "0" }.toLong()
                    val frac = parts[1]
                    val wholeWords = spellCardinal(whole)
                    val fracWords = frac.map { digit ->
                        spellCardinal((digit - '0').toLong())
                    }.joinToString(" ")
                    "$wholeWords point $fracWords"
                } else {
                    val n = raw.toLong()
                    // CLI year-protection band: 1900-2099 stays as digits.
                    if (n in 1900L..2099L) raw else spellCardinal(n)
                }
            } catch (_: Exception) {
                raw
            }
        }

    // -- Math rule ------------------------------------------------------------
    //
    // " + " → " plus " etc. Only between spaces, so "ninety-nine" doesn't
    // lose its hyphen. CLI: _math_symbols.

    private val mathRegex = Regex("(?<=\\s)([+×÷=≠<>≤≥±])(?=\\s)")
    private val MATH_SYMBOLS: Map<String, String> = mapOf(
        "+" to "plus", "×" to "times", "÷" to "divided by",
        "=" to "equals", "≠" to "not equal to",
        "<" to "less than", ">" to "greater than",
        "≤" to "less than or equal to", "≥" to "greater than or equal to",
        "±" to "plus or minus",
    )
    private fun expandMath(text: String): String =
        mathRegex.replace(text) { m ->
            MATH_SYMBOLS[m.groupValues[1]] ?: m.value
        }

    // -- Ampersand rule -------------------------------------------------------
    //
    // " & " → " and ". CLI: _ampersand.

    private val ampersandRegex = Regex("\\s&\\s")
    private fun expandAmpersand(text: String): String =
        ampersandRegex.replace(text, " and ")

    // -- Hashtag rule ---------------------------------------------------------
    //
    // #100 → number 100, #hello → hashtag hello. CLI: _hashtag.

    private val hashtagRegex = Regex("#(\\w+)")
    private fun expandHashtag(text: String): String =
        hashtagRegex.replace(text) { m ->
            val body = m.groupValues[1]
            if (body.all { it.isDigit() }) "number $body" else "hashtag $body"
        }

    // -- HTML rule ------------------------------------------------------------
    //
    // Strip tags, decode entities. CLI: _html_strip + _html.

    private val htmlTagRegex = Regex("<[^>]+>")
    private val HTML_ENTITIES: Map<String, String> = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&nbsp;" to " ",
    )
    private val numericEntityRegex = Regex("&#(\\d+);")
    private val hexEntityRegex = Regex("&#x([0-9a-fA-F]+);")

    private fun stripHtml(text: String): String {
        // Tag-strip first (kills real tags), THEN entity-decode so literal
        // &lt; in remaining text isn't re-eaten by a second strip.
        var out = htmlTagRegex.replace(text, " ")
        // Named entities (small fixed set covers the high-value cases).
        for ((entity, replacement) in HTML_ENTITIES) {
            out = out.replace(entity, replacement)
        }
        // Numeric and hex character references.
        out = numericEntityRegex.replace(out) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && cp in 0..0x10FFFF) {
                String(Character.toChars(cp))
            } else m.value
        }
        out = hexEntityRegex.replace(out) { m ->
            val cp = m.groupValues[1].toIntOrNull(16)
            if (cp != null && cp in 0..0x10FFFF) {
                String(Character.toChars(cp))
            } else m.value
        }
        return out
    }

    // -- Markdown rule --------------------------------------------------------
    //
    // Strip the high-value markdown syntax. Not a full parser. CLI: _markdown.

    private val mdImageRegex = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val mdLinkRegex = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val mdFenceRegex = Regex("```[^\\n`]*\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val mdCodeRegex = Regex("`([^`\\n]+?)`")
    private val mdBoldStarRegex = Regex("\\*\\*([^*\\n]+?)\\*\\*")
    private val mdStrikeRegex = Regex("~~([^~\\n]+?)~~")
    private val mdItalStarRegex = Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)")
    private val mdItalUnderRegex = Regex("(?<!\\w)_([^_\\n]+?)_(?!\\w)")
    private val mdBoldUnderRegex = Regex("(?<!\\w)__([^_\\n]+?)__(?!\\w)")
    private val mdHeadingRegex = Regex("(?m)^[ \\t]*#{1,6}[ \\t]+")
    private val mdBlockquoteRegex = Regex("(?m)^[ \\t]*>[ \\t]?")
    private val mdBulletRegex = Regex("(?m)^[ \\t]*[-*+][ \\t]+")

    // Python dunder denylist isn't relevant on Android (Kotlin doesn't have
    // `__init__` style names) — drop it, the `__name__` shape just gets
    // unwrapped to `name` like any other bold-underscore. If the markdown
    // rule starts being applied to mixed-language source listings later, we
    // can port the denylist.

    private fun stripMarkdown(text: String): String {
        var out = text
        // Images before links so ![alt](url) doesn't keep the leading "!".
        out = mdImageRegex.replace(out, "$1")
        out = mdLinkRegex.replace(out, "$1")
        // Fenced code before inline code.
        out = mdFenceRegex.replace(out, "$1")
        out = mdCodeRegex.replace(out, "$1")
        out = mdBoldStarRegex.replace(out, "$1")
        out = mdBoldUnderRegex.replace(out, "$1")
        out = mdStrikeRegex.replace(out, "$1")
        out = mdItalStarRegex.replace(out, "$1")
        out = mdItalUnderRegex.replace(out, "$1")
        out = mdHeadingRegex.replace(out, "")
        out = mdBlockquoteRegex.replace(out, "")
        out = mdBulletRegex.replace(out, "")
        return out
    }

    // -- Emoji rule -----------------------------------------------------------
    //
    // Strip emoji characters. Without this, espeak-backed engines verbalize
    // them as their Unicode names ("loudly crying face"). Replaced with a
    // single space; final whitespace-collapse pass tidies up. CLI: _emoji.
    //
    // EmojiVoice intentionally omits this rule (it consumes the emoji to
    // select the speaker id and strips it inside the engine).
    //
    // Codepoint ranges mirroring the CLI's emoji rule:
    // U+1F300–U+1FAFF (symbols, pictographs, transport),
    // U+2500–U+27BF (box drawing, block elements, geometric shapes,
    // misc symbols + dingbats — geometric shapes like ■ are the scene-break
    // markers light novels use; engines read them as "black square", #9),
    // U+1F1E6–U+1F1FF (regional indicators / flag halves), the four emoji
    // outside those ranges (⬛ ⬜ ⭐ ⭕), ※ (reference mark, another common
    // scene break), ZWJ, VS16, combining enclosing keycap.

    // Java/Kotlin Pattern syntax: `\x{...}` is the Unicode-aware
    // code-point escape (supports astral plane). `\uXXXX` is BMP-only.
    private val emojiRegex = Regex(
        "[" +
            "\\x{1F300}-\\x{1FAFF}" +
            "\\u2500-\\u27BF" +
            "\\x{1F1E6}-\\x{1F1FF}" +
            "\\u2B1B\\u2B1C\\u2B50\\u2B55" +
            "\\u203B" +
            "\\u200D" +
            "\\uFE0F" +
            "\\u20E3" +
            "]+",
    )

    private fun stripEmojis(text: String): String =
        emojiRegex.replace(text, " ")

    /**
     * Ensure the input ends with prosodic punctuation (`.!?,;:`).
     * Without it, TTS models don't know it's an end-of-utterance and
     * tend to cut the final word short or skip the trained sentence-end
     * pause prosody. Append `.` if missing. Empty / whitespace-only
     * inputs pass through unchanged.
     */
    private fun ensureTerminalPunctuation(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return text
        return if (trimmed.last() in TERMINAL_PUNCT) text else "$trimmed."
    }

    /**
     * Characters that count as "already terminal" so we don't append a
     * spurious `.`. Covers ASCII (`.!?,;:`), the Unicode ellipsis, CJK
     * sentence/clause punctuation (。、！？, ideographic + fullwidth), and the
     * fullwidth ASCII forms Open JTalk / CJK input methods emit (．？！，：；).
     * Spanish ¿¡ are sentence-INITIAL, not terminal, so they're intentionally
     * absent — a "¿Cómo estás?" still ends in ASCII `?` and is recognised.
     */
    private const val TERMINAL_PUNCT = ".!?,;:…。、！？．，：；"

    /**
     * Collapse runs of terminal punctuation to their canonical form:
     *   3+ dots  → "…" (single Unicode ellipsis; espeak phonemizes this
     *              as a deliberate pause, where "..." gets read as
     *              three separate dots in some configs)
     *   2+ bangs → "!"
     *   2+ ?s    → "?"
     *
     * Mixed runs like `?!` / `!?` / `!?!` are left alone — these are
     * intentional rhetorical patterns and the model handles them
     * reasonably. Splitting hairs on emphasis ordering isn't worth the
     * complexity.
     *
     * Runs of `.` inside URLs / emails / IPv4 / version strings are
     * never `...` (those are dotted-segment patterns, not ellipses),
     * and URL/email rules already ran upstream and expanded them — so
     * the regex can't false-positive on real-world inputs.
     */
    private fun collapseRepeatedPunctuation(text: String): String {
        var out = text
        out = REPEATED_DOTS.replace(out, "…")
        out = REPEATED_BANGS.replace(out, "!")
        out = REPEATED_QUESTIONS.replace(out, "?")
        return out
    }

    private val REPEATED_DOTS = Regex("\\.{3,}")
    private val REPEATED_BANGS = Regex("!{2,}")
    private val REPEATED_QUESTIONS = Regex("\\?{2,}")

    // -- Separators rule ------------------------------------------------------
    //
    // Scene-break dinkuses ("***", "* * *", "====="), stray asterisks
    // (rating stars, censored words, unbalanced emphasis), and superscript
    // footnote digits. espeak verbalizes all of these ("asterisk asterisk
    // asterisk", "equals equals equals", "cool¹ note" → "cool one note").
    // Runs AFTER markdown/html so paired emphasis (**bold**) is consumed as
    // formatting first. Follow-up to the ■ scene-break fix (#9). CLI:
    // `separators` in ts/src/preprocessing.ts — keep the two in sync.

    private val ASTERISK_RUN = Regex("\\*+")
    private val EQUALS_TILDE_RUN = Regex("[=~]{2,}")
    // A lone ~ between whitespace/line edges ("~ ~ ~" scene breaks). Tildes
    // glued to text (/~user, "~5") are left alone.
    private val LONE_TILDE = Regex("(?<=^|\\s)~(?=\\s|$)")
    // Superscript footnote digits, deleted outright (no space) so
    // "note¹ here" becomes "note here".
    private val SUPERSCRIPT_DIGITS = Regex("[¹²³⁰-⁹]+")
    // A period left alone on its own line (what a spaced dinkus can reduce
    // to) is meaningless to TTS — drop it.
    private val LONE_PERIOD_LINE = Regex("(?<=^|\\n)[ \\t]*\\.[ \\t]*(?=\\n|$)")

    private fun stripSeparators(text: String): String {
        var out = text
        out = ASTERISK_RUN.replace(out, " ")
        out = EQUALS_TILDE_RUN.replace(out, " ")
        out = LONE_TILDE.replace(out, " ")
        out = SUPERSCRIPT_DIGITS.replace(out, "")
        out = LONE_PERIOD_LINE.replace(out, "")
        return out
    }

    // -- Line breaks ----------------------------------------------------------
    //
    // Ported from `linebreaks` in ts/src/preprocessing.ts — keep the two in
    // sync. espeak does NOT treat a newline as a sentence end ("Title\nFirst
    // line" reads as one run-on clause), and the chunkers only cut at a newline
    // that survives to them. So line breaks are resolved here, in text: a line
    // that ends without punctuation gets a period when the next line plainly
    // starts something new (blank line, list/heading marker, capital, digit,
    // opening quote); otherwise the break is a soft wrap (hard-wrapped prose)
    // and becomes a space. Lines already ending in punctuation keep their
    // newline. Runs BEFORE markdown stripping so bullet/heading markers can
    // still be seen.
    //
    // The CLI uses Python-exact strip/space semantics (pyRstrip/pyStrip). Here
    // the fixtures are ASCII, so Kotlin's trimEnd()/trimStart()/isBlank() are a
    // faithful-enough simplification. The `\u0000` soft-wrap sentinel +
    // join("\n").replace(" \u0000\n", " ") mechanism and the fence-tracking
    // passthrough mirror the TS exactly.

    private val LINE_MARKER = Regex("^[ \\t]*(?:[-*+]|#{1,6}|>|\\p{Nd}+[.)])[ \\t]")
    private val LINE_ENDS_PUNCT = Regex("[.!?;:,\u2014\u2026][\"'\u201d\u2019)\\]]*\$")
    private val SOFT_WRAP_NEXT = Regex("^[a-z]")
    // A triple-backtick fence delimiter line (with or without a language tag).
    private val FENCE_LINE = Regex("^[ \\t]*```")

    private fun linebreaks(text: String): String {
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        var inFence = false
        for (i in lines.indices) {
            val cur = lines[i].trimEnd()
            // Fence delimiters and everything between them pass through
            // verbatim: no period injection, no soft-wrap gluing.
            if (FENCE_LINE.containsMatchIn(cur)) {
                inFence = !inFence
                out.add(cur)
                continue
            }
            if (inFence) {
                out.add(cur)
                continue
            }
            var nxt: String? = null
            for (j in i + 1 until lines.size) {
                if (lines[j].isNotBlank()) { nxt = lines[j]; break }
            }
            if (cur.isBlank() || nxt == null || LINE_ENDS_PUNCT.containsMatchIn(cur)) {
                out.add(cur)
                continue
            }
            val paragraph = lines[i + 1].isBlank()   // blank line follows
            val newItem = LINE_MARKER.containsMatchIn(nxt) || LINE_MARKER.containsMatchIn(cur)
            if (paragraph || newItem || !SOFT_WRAP_NEXT.containsMatchIn(nxt.trimStart())) {
                out.add("$cur.")
            } else {
                out.add("$cur \u0000")   // soft wrap: joined below
            }
        }
        return out.joinToString("\n").replace(" \u0000\n", " ")
    }

    // -- Parentheses ----------------------------------------------------------
    //
    // Ported from `parens` in ts/src/preprocessing.ts — keep the two in sync. A
    // parenthetical is an aside the speaker sets off with a pause on each side,
    // but espeak-backed vocab has no brackets (they vanish, no pause) and the
    // chunkers never cut at them. Rewrite "(aside)" as a semicolon-delimited
    // clause: `;` is a real render boundary with a clause gap and a natural
    // pause. Tiny groups — "item(s)", "f(x)", "(a)" markers — just lose their
    // brackets.

    private val PAREN_GROUP = Regex("[ \\t]*\\(([^()\\n]*)\\)")
    private const val PAREN_MAX_GLUE = 2   // inner text this short is glued, not set off
    private val TRAILING_HTAB = Regex("[ \\t]+\$")
    private val LEADING_HTAB = Regex("^[ \\t]+")

    private fun parens(text: String): String =
        PAREN_GROUP.replace(text) { m ->
            val whole = m.value
            val inner = m.groupValues[1].trim()
            if (inner.length <= PAREN_MAX_GLUE) {
                // Tiny group: drop the brackets. "item(s)"/"f(x)" glue with no
                // space (there was none before the paren); "See (a) first" keeps
                // the space the source had, so words don't collide ("Seea"). The
                // regex's leading [ \t]* is part of `whole`, so a leading space
                // there means the source separated the paren from the prev word.
                val spaced = whole.isNotEmpty() && (whole[0] == ' ' || whole[0] == '\t')
                (if (spaced) " " else "") + inner
            } else {
                val before = TRAILING_HTAB.replace(text.substring(0, m.range.first), "")
                val after = LEADING_HTAB.replace(text.substring(m.range.last + 1), "")
                val lead = when {
                    before.isEmpty() || before.endsWith("\n") -> ""
                    ".!?;:,\u2014-".contains(before.last()) -> " "
                    else -> "; "
                }
                val tail = if (after.isNotEmpty() && !".!?;:,)\n".contains(after[0])) ";" else ""
                lead + inner + tail
            }
        }

    // -- Built-in respellings -------------------------------------------------
    //
    // Ported from `respell` (RESPELL table) in ts/src/preprocessing.ts — keep
    // the two in sync. Words espeak's letter-to-sound fallback gets wrong,
    // respelled so every espeak-backed engine reads them right. Keys lowercase;
    // a capitalized match keeps its capital. Verified with tools/ph_probe.py in
    // the CLI — do not add, drop, or "improve" any entry here without the
    // probe's before/after in hand.

    private val RESPELL: Map<String, String> = mapOf(
        // The bi- prefix before a w/m/y stem: /bɪ/ instead of /baɪ/.
        "biweekly" to "bi-weekly",
        "bimonthly" to "bi-monthly",
        "biyearly" to "bi-yearly",
        "bilayer" to "bi-layer",
        "bimorph" to "bi-morph",
        // Other prefix/compound boundaries the fallback mis-syllabifies.
        "triennial" to "tri-ennial",
        "coworking" to "co-working",
        "cosign" to "co-sign",
        "deescalate" to "de-escalate",
        "reupload" to "re-upload",
        "smarthome" to "smart-home",
        "macrophage" to "macro-phage",
        "triglyceride" to "try-glyceride",
        // Tech vocabulary
        "regex" to "reg-ex",
        "cli" to "C-L-I",
        "mkdir" to "makedir",
        "async" to "aysync",
        "numpy" to "numpie",
        "scipy" to "sci-pie",
        "jupyter" to "jupiter",
        // Names, food, medicine
        "huawei" to "wahway",
        "renault" to "renoh",
        "quinoa" to "keen-wah",
        "penne" to "pennay",
        "linguine" to "lin-gweenee",
        "feta" to "fetta",
        "miso" to "meeso",
        "kimchi" to "kimchee",
        "mochi" to "mohchee",
        "edema" to "ee-deema",
    )

    // Escape the regex metacharacters that are special outside a character
    // class. `-` is intentionally NOT escaped (mirrors the CLI's reEscape).
    private val RE_SPECIAL = Regex("[.*+?^\$(){}|\\[\\]\\\\/]")
    private fun reEscape(s: String): String = RE_SPECIAL.replace(s) { "\\" + it.value }

    // Alternation sorted longest-key-first, whole-word boundaries via Unicode
    // letter/number lookarounds, case-insensitive.
    private val RESPELL_RE = Regex(
        "(?<![\\p{L}\\p{N}_])(?:" +
            RESPELL.keys.sortedByDescending { it.length }.joinToString("|") { reEscape(it) } +
            ")(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    )

    private fun respell(text: String): String =
        RESPELL_RE.replace(text) { m ->
            val word = m.value
            val rep = RESPELL[word.lowercase()]!!
            if (word[0].isUpperCase() || word[0].isTitleCase()) {
                rep[0].uppercaseChar() + rep.substring(1)
            } else {
                rep
            }
        }

    // -- Context-gated heteronym respellings ----------------------------------
    //
    // Ported from `heteronym` (HETERONYMS table) in ts/src/preprocessing.ts —
    // keep the two in sync. Heteronyms (same spelling, two pronunciations)
    // can't go in RESPELL: the right reading depends on the sentence. espeak
    // has a weak POS heuristic and gets many right on its own — it reads "have
    // read", "will record", "can wind", "to tear" correctly. What it reliably
    // fails on is the IMPERATIVE ("Close the door"), where it picks the
    // noun/adjective reading. Each entry fires only in a context the probe
    // showed espeak getting wrong, and leaves the word alone otherwise: a wrong
    // flip is worse than no flip.
    //
    // Word list inspired by the public heteronym inventories in Google's
    // WikipediaHomographData (Apache-2.0) and the AmEPD pronunciation
    // dictionary (BSD-2-Clause). No data was copied from either — every entry
    // here is an original context rule with its own probe-verified respelling.

    private val NOUN_CUE = listOf(
        "the", "a", "an", "this", "that", "these", "those", "my",
        "your", "his", "her", "its", "our", "their", "some", "any", "no",
        "each", "every", "another",
    )
    private val OBJ_PRON = listOf("me", "us", "him", "her", "them", "it")
    private val SUBJ_PRON = listOf("i", "he", "she", "we", "they", "you")
    private val PAST_TIME_CUE = listOf(
        "yesterday", "last night", "last week", "last month",
        "last year", "ago", "earlier", "this morning", "this afternoon",
    )

    private fun alt(words: List<String>): String = words.joinToString("|")

    // Start of an imperative clause: string start, after sentence-final
    // punctuation, or after "please"/"let's".
    private const val IMPERATIVE_PRE =
        "(?:^\\s*|(?<=[.!?;:\\n])\\s*|\\blet['\u2019]?s\\s+|\\bplease\\s+)"
    // A direct object right after the verb — determiner/possessive or object
    // pronoun. This separates "Close the door" (verb) from "Close to the
    // station" or "a close call" (adjective), so those need no extra guard.
    private val OBJECT_AFTER = "\\s+(?:${alt(NOUN_CUE)}|${alt(OBJ_PRON)})\\b"

    // [compiled context pattern with the word in group "w", respelling]. IPA in
    // the comments is the espeak output, before → after.
    private val HETERONYM_RULES: List<Pair<Regex, String>> = listOf(
        // "He read the report yesterday."  ɹˈiːd → ɹˈɛd
        // ("have/was read" is already ɹˈɛd in espeak, so no aux rule is needed.)
        Regex(
            "\\b(?:${alt(SUBJ_PRON)})\\s+(?<w>read)\\b" +
                "(?=[^.!?\\n]*\\b(?:${alt(PAST_TIME_CUE)})\\b)",
            RegexOption.IGNORE_CASE,
        ) to "red",
        // "Wind the clock before bed."  wˈɪnd → wˈaɪnd
        Regex("$IMPERATIVE_PRE(?<w>wind)$OBJECT_AFTER", RegexOption.IGNORE_CASE) to "wined",
        // "She wound the bandage around his arm."  wˈuːnd → wˈaʊnd
        // Object pronouns are excluded after the verb so "you wound me" (injure,
        // wˈuːnd) can't match; "you" is excluded as a subject for the same reason.
        Regex(
            "\\b(?:i|he|she|we|they|it)\\s+(?<w>wound)" +
                "\\s+(?:the|a|an|its|their|my|your|our|up|around|down|through)\\b",
            RegexOption.IGNORE_CASE,
        ) to "wowned",
        // "Record the meeting please."  ɹˈɛkɚd → ɹˌiːkˈɔːɹd
        Regex("$IMPERATIVE_PRE(?<w>record)$OBJECT_AFTER", RegexOption.IGNORE_CASE) to "re-cord",
        // "Close the door."  klˈoʊs → klˈoʊz
        Regex("$IMPERATIVE_PRE(?<w>close)$OBJECT_AFTER", RegexOption.IGNORE_CASE) to "cloze",
        // "Tear the page out."  tˈɪɹ → tˈɛɹ
        Regex("$IMPERATIVE_PRE(?<w>tear)$OBJECT_AFTER", RegexOption.IGNORE_CASE) to "tair",
        // "A minute amount of dust remained."  mˈɪnɪt → maɪnˈuːt
        Regex(
            "(?<![\\w-])(?<w>minute)\\s+(?:amounts?|quantit(?:y|ies)|details?|" +
                "particles?|traces?|differences?|changes?|fractions?)\\b",
            RegexOption.IGNORE_CASE,
        ) to "my-newt",
        // "The differences are minute."  mˈɪnɪt → maɪnˈuːt
        Regex(
            "\\b(?:are|is|were|was|seems?|seemed|appears?|appeared|remains?|" +
                "remained|so|very|extremely|quite)\\s+(?<w>minute)(?=\\s*[.,;:!?]|\\s*\$)",
            RegexOption.IGNORE_CASE,
        ) to "my-newt",
        // "Send me your resume."  ɹᵻzˈuːm → ɹˈɛzˈuːmˈeɪ
        Regex("\\b(?:${alt(NOUN_CUE)})\\s+(?<w>resume)\\b", RegexOption.IGNORE_CASE) to "rez-oo-may",
        // "The lead pipe was corroded."  lˈiːd → lˈɛd
        Regex(
            "(?<![\\w-])(?<w>lead)\\s+(?:pipes?|paint|poisoning|pencils?|acid|" +
                "shot|bullets?|weights?|solder)\\b",
            RegexOption.IGNORE_CASE,
        ) to "led",
        // "He dove into the pool."  dˈʌv → dˈoʊv
        // Needs a pronoun subject: "put the dove into the cage" is the bird.
        Regex(
            "\\b(?:${alt(SUBJ_PRON)})\\s+(?<w>dove)" +
                "\\s+(?:into|in|under|down|off|headfirst|straight|through|beneath)\\b",
            RegexOption.IGNORE_CASE,
        ) to "dohv",
        // "The sow had six piglets."  sˈoʊ → sˈaʊ
        Regex("\\b(?:${alt(NOUN_CUE)})\\s+(?<w>sow)\\b", RegexOption.IGNORE_CASE) to "sau",
        // "Excuse me for a moment."  ɛkskjˈuːs → ɛkskjˈuːz
        Regex("(?<![\\w-])(?<w>excuse)\\s+(?:me|us)\\b", RegexOption.IGNORE_CASE) to "excuze",
        // "Please excuse the delay."  ɛkskjˈuːs → ɛkskjˈuːz
        Regex("$IMPERATIVE_PRE(?<w>excuse)$OBJECT_AFTER", RegexOption.IGNORE_CASE) to "excuze",
    )

    /**
     * Respell heteronyms whose context identifies the reading espeak gets
     * wrong. Only the matched word is rewritten — the surrounding context the
     * pattern consumed is put back verbatim. Mirrors the CLI: for each pattern,
     * replace only the `w` group within each non-overlapping match, preserving
     * a leading capital.
     */
    private fun heteronym(text: String): String {
        var current = text
        for ((pattern, respelling) in HETERONYM_RULES) {
            val sb = StringBuilder()
            var last = 0
            for (m in pattern.findAll(current)) {
                val whole = m.value
                val base = m.range.first
                val wGroup = m.groups["w"]!!
                val word = wGroup.value
                val ws = wGroup.range.first
                val we = wGroup.range.last + 1
                val rep = if (word[0].isUpperCase() || word[0].isTitleCase()) {
                    respelling[0].uppercaseChar() + respelling.substring(1)
                } else {
                    respelling
                }
                val rebuilt = whole.substring(0, ws - base) + rep + whole.substring(we - base)
                sb.append(current, last, base)
                sb.append(rebuilt)
                last = base + whole.length
            }
            sb.append(current, last, current.length)
            current = sb.toString()
        }
        return current
    }

    // -- Whole catalog --------------------------------------------------------
    //
    // ORDER MATTERS. This is the CLI's `priority` list in
    // `preprocess()`. Don't reshuffle without re-reading the comment block
    // in `marmalade_tts/preprocessing.py` above it.

    val ALL: List<PreprocessingRule> = listOf(
        // 1. Strip emojis first (disjoint codepoint range, but stripping
        //    early keeps later debug output readable).
        PreprocessingRule(
            name = "emoji",
            description = "Strip emoji and decorative symbols (engines pronounce them as \"loudly crying face\", \"black square\" otherwise)",
            transform = ::stripEmojis,
        ),
        // 1b. Resolve line breaks BEFORE markdown so bullet/heading markers
        //     are still visible (mirrors the CLI's priority order).
        PreprocessingRule(
            name = "linebreaks",
            description = "Unpunctuated line ends become sentence ends; soft wraps join",
            transform = ::linebreaks,
        ),
        // 2. Strip markdown + HTML before URL/number rules see syntax noise.
        PreprocessingRule(
            name = "markdown",
            description = "Strip markdown formatting (bold/italic/code/link/heading/list/quote)",
            transform = ::stripMarkdown,
        ),
        PreprocessingRule(
            name = "html",
            description = "Strip HTML tags and decode entities (&amp; → &)",
            transform = ::stripHtml,
        ),
        PreprocessingRule(
            name = "separators",
            description = "Strip decorative separators: dinkus lines (*** / =====), stray asterisks, superscript footnote digits",
            transform = ::stripSeparators,
        ),
        // 2b. Rewrite parentheticals AFTER markdown (link syntax uses parens)
        //     and separators, BEFORE the structured/number rules.
        PreprocessingRule(
            name = "parens",
            description = "Parentheticals become ;-delimited clauses (pause + chunk seam)",
            transform = ::parens,
        ),
        // 3. Capture structured patterns (email, url) before the number /
        //    filename rules eat their dots.
        PreprocessingRule(
            name = "email",
            description = "Expand emails: user@example.com → user at example dot com",
            transform = ::expandEmail,
        ),
        PreprocessingRule(
            name = "url",
            description = "Expand URLs: https://example.com → example dot com",
            transform = ::expandUrl,
        ),
        // 4. Money / percent before generic numbers.
        PreprocessingRule(
            name = "currency",
            description = "Expand currency: \$100 → 100 dollars",
            transform = ::expandCurrency,
        ),
        PreprocessingRule(
            name = "percentage",
            description = "Expand percent: 50% → 50 percent",
            transform = ::expandPercentage,
        ),
        // 5. Time / date / ordinal before generic numbers.
        PreprocessingRule(
            name = "time",
            description = "Expand times: 10:30 → ten thirty",
            transform = ::expandTime,
        ),
        PreprocessingRule(
            name = "date",
            description = "Expand dates: 01/15/2025 → January fifteenth, 2025",
            transform = ::expandDate,
        ),
        PreprocessingRule(
            name = "ordinal",
            description = "Expand ordinals: 1st → first",
            transform = ::expandOrdinal,
        ),
        // 6. Abbreviations before filename (both have dots).
        PreprocessingRule(
            name = "abbreviation",
            description = "Expand abbreviations: U.S.A. → U S A, e.g. → for example",
            transform = ::expandAbbreviation,
        ),
        PreprocessingRule(
            name = "filename",
            description = "Expand filenames: example.txt → example dot T X T",
            transform = ::expandFilename,
        ),
        // 7. Bare numbers last so currency / dates / ordinals get first crack.
        //    Years 1900-2099 stay as digits.
        PreprocessingRule(
            name = "number",
            description = "Numbers to words: 42 → forty-two (years 1900-2099 left as digits)",
            transform = ::expandNumber,
        ),
        // 7b. Heteronyms (context-gated) then flat respellings, AFTER number
        //     and BEFORE math (mirrors the CLI's priority order). Both are
        //     espeak-specific fixups; profiles gate them to espeak-backed
        //     engines only. No pronounce rule on Android (no YAML dict).
        PreprocessingRule(
            name = "heteronym",
            description = "Context-gated heteronym respellings (Close the door → Cloze the door)",
            transform = ::heteronym,
        ),
        PreprocessingRule(
            name = "respell",
            description = "Built-in respellings for words espeak misreads (biweekly → bi-weekly)",
            transform = ::respell,
        ),
        // 8. Tail end — math / ampersand / hashtag operate on the already-
        //    normalized text.
        PreprocessingRule(
            name = "math",
            description = "Math symbols to words: + → plus (only when standalone)",
            transform = ::expandMath,
        ),
        PreprocessingRule(
            name = "ampersand",
            description = "Ampersand: & → and",
            transform = ::expandAmpersand,
        ),
        PreprocessingRule(
            name = "hashtag",
            description = "Hashtags: #100 → number 100, #hello → hashtag hello",
            transform = ::expandHashtag,
        ),
        // 9. Collapse repeated terminal punctuation BEFORE the final
        //    terminal-punctuation backstop runs, so the backstop sees
        //    canonical single-glyph endings and doesn't double-stamp.
        PreprocessingRule(
            name = "repeated_punctuation",
            description = "Collapse runs: \"...\" → \"…\", \"!!!\" → \"!\", \"???\" → \"?\" (prosody cleanup)",
            transform = ::collapseRepeatedPunctuation,
        ),
        // 10. Final touch-up: ensure the input ends with prosodic
        //    punctuation. Without it, TTS models cut the final word
        //    short or skip the sentence-end pause prosody. Must run
        //    LAST so any earlier rule's punctuation expansion isn't
        //    masked by our appended period.
        PreprocessingRule(
            name = "terminal_punctuation",
            description = "Append \".\" if the input doesn't end with .!?,;: (avoids cut-off final words)",
            transform = ::ensureTerminalPunctuation,
        ),
    )

    /** Look up a rule by its stable [PreprocessingRule.name]. */
    fun byName(name: String): PreprocessingRule? =
        ALL.firstOrNull { it.name == name }
}
