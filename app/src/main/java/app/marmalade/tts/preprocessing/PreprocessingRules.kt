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
                val majorN = parts[0].ifEmpty { "0" }.toLong()
                val minorN = parts[1].ifEmpty { "0" }.toLong()
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
                val n = amount.toLong()
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

    private val abbreviationRegex = Regex(
        "\\b(?:[A-Z]\\.){2,}|(?:e\\.g\\.|i\\.e\\.|etc\\.|vs\\.|" +
            "[Mm]r\\.|[Mm]rs\\.|[Mm]s\\.|[Dd]r\\.|[Ss]r\\.|[Jj]r\\.|" +
            "[Ss]t\\.|ft\\.|lb\\.|oz\\.)",
    )
    private val COMMON_ABBREVIATIONS: Map<String, String> = mapOf(
        "e.g." to "for example", "i.e." to "that is", "etc." to "et cetera",
        "vs." to "versus", "mr." to "mister", "mrs." to "missus", "ms." to "miss",
        "dr." to "doctor", "sr." to "senior", "jr." to "junior",
        "st." to "saint", "ft." to "feet", "lb." to "pounds", "oz." to "ounces",
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
    // We use the Unicode property "Emoji" wherever it's recognised, then a
    // codepoint-range fallback covering the broad pictograph blocks used by
    // the CLI: U+1F300–U+1FAFF (symbols, pictographs, transport),
    // U+2600–U+27BF (misc symbols + dingbats),
    // U+1F1E6–U+1F1FF (regional indicators / flag halves), ZWJ, VS16,
    // combining enclosing keycap.

    // Java/Kotlin Pattern syntax: `\x{...}` is the Unicode-aware
    // code-point escape (supports astral plane). `\uXXXX` is BMP-only.
    private val emojiRegex = Regex(
        "[" +
            "\\x{1F300}-\\x{1FAFF}" +
            "\\u2600-\\u27BF" +
            "\\x{1F1E6}-\\x{1F1FF}" +
            "\\u200D" +
            "\\uFE0F" +
            "\\u20E3" +
            "]+",
    )

    private fun stripEmojis(text: String): String =
        emojiRegex.replace(text, " ")

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
            description = "Strip emoji (engines pronounce them as \"loudly crying face\" otherwise)",
            transform = ::stripEmojis,
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
        // 8. Tail end — math / ampersand / hashtag operate on the already-
        //    normalized text. No pronounce rule on Android (no YAML dict).
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
    )

    /** Look up a rule by its stable [PreprocessingRule.name]. */
    fun byName(name: String): PreprocessingRule? =
        ALL.firstOrNull { it.name == name }
}
