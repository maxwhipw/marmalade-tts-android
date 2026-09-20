package app.marmalade.tts.phonemizer

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern

// -----------------------------------------------------------------------------
// GermanTextNormalizer — faithful port of misaki.de.normalize_text_de.
//
// Expands numbers, dates, times, currency and abbreviations so espeak-ng
// receives clean spelled-out German. Ported from semidark's misaki fork
// (misaki/de.py, Apache-2.0); the executable spec this mirrors is
// scratch/kokoro-german-lab/fixtures/gen_fixtures_de.py and the golden
// vectors in that lab's fixtures.json.
//
// Java-regex parity note: Python's `re` treats `\b`/`\w`/`\s` as
// Unicode-aware, but java.util.regex is ASCII-only for those unless
// UNICODE_CHARACTER_CLASS is set — without it ä/ö/ü/ß count as non-word
// characters and manufacture false word boundaries, and \s would miss the
// non-breaking space this normalizer folds. Every pattern below is
// compiled through [ure], which sets that flag (plus UNICODE_CASE
// alongside CASE_INSENSITIVE), so the lookarounds and boundaries behave
// exactly as the Python source does.
// -----------------------------------------------------------------------------

internal object GermanTextNormalizer {

    // ── cardinal numbers ────────────────────────────────────────────────────

    private val ONES = arrayOf(
        "", "ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht",
        "neun", "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn",
        "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val TENS = arrayOf(
        "", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig",
        "siebzig", "achtzig", "neunzig",
    )

    /**
     * Integer to German words. [standalone] = false returns "ein" for 1
     * (composition: einhundert, eintausend); true returns "eins".
     *
     * Long, not Int: grouped inputs reach ~10^12 (Milliarden), which
     * overflows Int. The upstream uses arbitrary-precision Python ints;
     * beyond Long the callers below fall back to leaving the digits
     * unchanged rather than overflowing.
     */
    fun intToDe(n: Long, standalone: Boolean = true): String {
        if (n < 0) return "minus " + intToDe(-n)
        if (n == 0L) return "null"
        if (n == 1L) return if (standalone) "eins" else "ein"
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) {
            val ones = (n % 10).toInt()
            val tens = (n / 10).toInt()
            return if (ones != 0) ONES[ones] + "und" + TENS[tens] else TENS[tens]
        }
        if (n < 1_000) {
            val h = (n / 100).toInt()
            val r = n % 100
            return ONES[h] + "hundert" + (if (r != 0L) intToDe(r, standalone = false) else "")
        }
        if (n < 1_000_000) {
            val t = n / 1_000
            val r = n % 1_000
            val prefix = if (t != 1L) intToDe(t, standalone = false) else "ein"
            return prefix + "tausend" + (if (r != 0L) intToDe(r, standalone = false) else "")
        }
        if (n < 1_000_000_000) {
            val m = n / 1_000_000
            val r = n % 1_000_000
            val word = if (m == 1L) "eine Million" else intToDe(m, standalone = false) + " Millionen"
            return word + (if (r != 0L) " " + intToDe(r, standalone = false) else "")
        }
        val b = n / 1_000_000_000
        val r = n % 1_000_000_000
        val word = if (b == 1L) "eine Milliarde" else intToDe(b, standalone = false) + " Milliarden"
        return word + (if (r != 0L) " " + intToDe(r, standalone = false) else "")
    }

    // ── ordinals ────────────────────────────────────────────────────────────

    private val ORD_IRREG = mapOf(1L to "erst", 2L to "zweit", 3L to "dritt", 7L to "siebt", 8L to "acht")

    /** Ordinal stem without inflection suffix. */
    fun ordinalStem(n: Long): String =
        ORD_IRREG[n] ?: (intToDe(n, standalone = false) + if (n < 20) "t" else "st")

    // ── years ─────────────────────────────────────────────────────────────

    /** German year pronunciation: 1985 -> neunzehnhundertfünfundachtzig. */
    fun yearDe(n: Long): String {
        if (n in 1100..1999) {
            val c = n / 100
            val r = n % 100
            return intToDe(c, standalone = false) + "hundert" + (if (r != 0L) intToDe(r, standalone = false) else "")
        }
        return intToDe(n)
    }

    // ── month names & currency ──────────────────────────────────────────────

    private val MONTHS = arrayOf(
        "", "Januar", "Februar", "März", "April", "Mai", "Juni", "Juli",
        "August", "September", "Oktober", "November", "Dezember",
    )

    private val CURRENCY = mapOf("€" to "Euro", "$" to "Dollar", "£" to "Pfund", "¥" to "Yen")

    private fun currencyRepl(sym: String, num: String): String {
        val word = CURRENCY[sym] ?: sym
        val cleaned = num.replace(".", "").replace(",", ".")
        val value = try {
            BigDecimal(cleaned)
        } catch (e: NumberFormatException) {
            return sym + num
        }
        val centsTotal = value.multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP).toLong()
        val euros = centsTotal / 100
        val cents = centsTotal % 100
        return if (cents == 0L) {
            intToDe(euros) + " " + word
        } else {
            intToDe(euros) + " " + word + " und " + intToDe(cents) + " Cent"
        }
    }

    // ── regex helper ──────────────────────────────────────────────────────

    private fun ure(pattern: String, ignoreCase: Boolean = false): Regex {
        var flags = Pattern.UNICODE_CHARACTER_CLASS
        if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(pattern, flags).toRegex()
    }

    // Abbreviation table, applied in order. Each triple is
    // (pattern, replacement, ignoreCase). Order matches misaki/de.py.
    private data class Abbr(val re: Regex, val to: String)

    private val ABBREVIATIONS: List<Abbr> = buildList {
        fun add(pattern: String, to: String, ignoreCase: Boolean = false) =
            add(Abbr(ure(pattern, ignoreCase), to))

        add("""\bDr\.(?=\s)""", "Doktor")
        add("""\bProf\.(?=\s)""", "Professor")
        add("""\bHr\.(?=\s)""", "Herr ")
        add("""\bFr\.(?=\s[A-ZÄÖÜ])""", "Frau")
        add("""\bDipl\.\s*-?\s*Ing\.""", "Diplom-Ingenieur")
        add("""\bStr\.(?=\s)""", "Straße")
        add("""\bNr\.(?=\s*\d)""", "Nummer")
        add("""\bTel\.(?=\s)""", "Telefon")
        add("""\bAbt\.(?=\s)""", "Abteilung")
        add("""\bGmbH\b""", "Gesellschaft mit beschränkter Haftung")
        add("""\bAG\b(?=[\s,.]|$)""", "Aktiengesellschaft")
        add("""\bz\.\s*B\.""", "zum Beispiel", ignoreCase = true)
        add("""\bd\.\s*h\.""", "das heißt", ignoreCase = true)
        add("""\bu\.\s*a\.""", "unter anderem", ignoreCase = true)
        add("""\bbzw\.""", "beziehungsweise", ignoreCase = true)
        add("""\busw\.""", "und so weiter", ignoreCase = true)
        add("""\betc\.""", "et cetera", ignoreCase = true)
        add("""\bca\.""", "circa", ignoreCase = true)
        add("""\bvgl\.""", "vergleiche", ignoreCase = true)
        add("""\binkl\.""", "inklusive", ignoreCase = true)
        add("""\bexkl\.""", "exklusive", ignoreCase = true)
        add("""\bggf\.""", "gegebenenfalls", ignoreCase = true)
        add("""\bi\.\s*d\.\s*R\.""", "in der Regel", ignoreCase = true)
        add("""\bo\.\s*ä\.""", "oder ähnliches", ignoreCase = true)
        add("""\bu\.\s*U\.""", "unter Umständen", ignoreCase = true)
        // Month abbreviations.
        val months = listOf(
            "Jan" to "Januar", "Feb" to "Februar", "Mär" to "März", "Apr" to "April",
            "Jun" to "Juni", "Jul" to "Juli", "Aug" to "August", "Sep" to "September",
            "Okt" to "Oktober", "Nov" to "November", "Dez" to "Dezember",
        )
        for ((abbr, full) in months) add("""\b$abbr\.(?=\s)""", full)
    }

    private val CURRENCY_BEFORE = ure("""([€$£¥])\s*(\d[\d.,]*)""")
    private val CURRENCY_AFTER = ure("""(\d[\d.,]*)\s*([€$£¥])""")
    private val TIME_RE = ure("""\b(\d{1,2}):(\d{2})\b(?:\s*Uhr\b)?""")
    private val DATE_RE = ure("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""")
    private val ORDINAL_RE = ure("""(?<!\d)(\d{1,2})\.\s""")
    private val YEAR_RE = ure("""\b(\d{4})\b""")
    private val GROUPED_NUM_RE = ure("""\b\d{1,3}(?:\.\d{3})+(?:,\d+)?\b""")
    private val DECIMAL_RE = ure("""\b(\d+),(\d+)\b""")
    private val REMAINING_TIME_RE = ure("""\b\d{1,2}:\d{2}\b(?:\s*Uhr\b)?""")
    private val PLAIN_INT_RE = ure("""\b(\d+)\b""")
    private val MULTISPACE_RE = ure("""[ \t]{2,}""")
    private val MULTINEWLINE_RE = ure("""\n{3,}""")
    private val NON_BREAKING_WS = ure("""[^\S \n]""")

    /** Normalize German text for TTS. */
    fun normalize(input: String): String {
        if (input.isEmpty()) return input
        var text = input

        // 1. Quotes -> ASCII
        text = text.replace('„', '"').replace('“', '"') // „ "
        text = text.replace('‘', '\'').replace('’', '\'') // ' '
        text = text.replace('«', '"').replace('»', '"') // « »
        text = text.replace('‹', '"').replace('›', '"') // ‹ ›

        // 2. Non-breaking whitespace -> space
        text = NON_BREAKING_WS.replace(text, " ")

        // 3. Abbreviations
        for (abbr in ABBREVIATIONS) text = abbr.re.replace(text, abbr.to)

        // 4. Currency (symbol before or after amount)
        text = CURRENCY_BEFORE.replace(text) { m ->
            currencyRepl(m.groupValues[1], m.groupValues[2])
        }
        text = CURRENCY_AFTER.replace(text) { m ->
            currencyRepl(m.groupValues[2], m.groupValues[1])
        }

        // 5. Times (HH:MM), optional trailing "Uhr" consumed
        text = TIME_RE.replace(text) { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h > 23 || mi > 59) m.value
            else intToDe(h.toLong()) + " Uhr" + (if (mi != 0) " " + intToDe(mi.toLong()) else "")
        }

        // 6. Full dates (DD.MM.YYYY)
        text = DATE_RE.replace(text) { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].toLong()
            if (d < 1 || d > 31 || mo < 1 || mo > 12) m.value
            else ordinalStem(d.toLong()) + "e " + MONTHS[mo] + " " + yearDe(y)
        }

        // 7. Ordinals mid-sentence (e.g. "am 3. Mai") — 1-2 digit only
        text = ORDINAL_RE.replace(text) { m ->
            ordinalStem(m.groupValues[1].toLong()) + "e "
        }

        // 8. Standalone years (1100-2099)
        text = YEAR_RE.replace(text) { m ->
            val n = m.groupValues[1].toLong()
            if (n in 1100..2099) yearDe(n) else intToDe(n)
        }

        // 9. German-format numbers: 1.234.567 or 1.234,56
        text = GROUPED_NUM_RE.replace(text) { m ->
            val cleaned = m.value.replace(".", "").replace(",", ".")
            val d = cleaned.toDoubleOrNull() ?: return@replace m.value
            if (d == Math.floor(d) && !d.isInfinite()) {
                val ip = cleaned.substringBefore(".").toLongOrNull() ?: return@replace m.value
                intToDe(ip)
            } else {
                val parts = cleaned.split(".")
                val ip = parts[0].toLongOrNull() ?: return@replace m.value
                intToDe(ip) + " Komma " + parts[1].map { intToDe(it.digitToInt().toLong()) }.joinToString(" ")
            }
        }

        // Decimal comma (3,14)
        text = DECIMAL_RE.replace(text) { m ->
            val ip = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            val fp = m.groupValues[2]
            intToDe(ip) + " Komma " + fp.map { intToDe(it.digitToInt().toLong()) }.joinToString(" ")
        }

        // Plain integers, keeping any invalid HH:MM text that survived the
        // time pass unchanged.
        val timeSpans = REMAINING_TIME_RE.findAll(text).map { it.range }.toList()
        text = PLAIN_INT_RE.replace(text) { m ->
            val covered = timeSpans.any { it.first <= m.range.first && m.range.last <= it.last }
            if (covered) m.value
            else m.groupValues[1].toLongOrNull()?.let { intToDe(it) } ?: m.value
        }

        // 10. Whitespace cleanup
        text = MULTISPACE_RE.replace(text, " ")
        text = MULTINEWLINE_RE.replace(text, "\n\n")
        return text.trim()
    }
}
