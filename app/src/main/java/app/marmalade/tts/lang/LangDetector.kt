package app.marmalade.tts.lang

import android.content.Context
import java.util.Base64

/**
 * Per-utterance language detection: which language is this text written
 * in, so the phonemizer can be pointed at the right rules.
 *
 * Two stages, mirroring the CLI's `tools/langdetect-train/detector_ref.py`
 * exactly (same table file, same normalisation, same thresholds — the two
 * implementations must not drift):
 *
 *  1. Script check. Kana → `ja`, Han without kana → `zh`, Devanagari →
 *     `hi`. Only when the non-Latin script is at least as common as Latin
 *     letters, so "Play 東京 for me" stays English.
 *  2. Char-trigram naive Bayes over the five Latin languages Kokoro
 *     speaks (`en es fr it pt`), from the trained table in
 *     `assets/langdetect.tab`.
 *
 * Returns null when it doesn't know — too little text, or two languages
 * too close to call. Callers fall back rather than guess.
 *
 * **Language is not region.** `en-US` vs `en-GB` is not inferable from
 * text and is never attempted; English resolves to null in
 * [espeakCodeFor] so the voice's own catalog default decides.
 *
 * Constructed from the table's lines rather than a Context so plain-JVM
 * unit tests can build one straight off the file. Format (line-based;
 * deliberately not JSON — `org.json` is a throwing stub on plain JVM):
 *
 * ```
 * 1: "marmalade-langdetect 1"
 * 2: language codes, space-separated
 * 3: cost scale (provenance only)
 * 4: per-language floor cost for an unseen trigram
 * 5: every trigram key concatenated, 3 chars each, space stored as "_"
 * 6: base64 uint8 costs, trigram-major then language-minor
 * ```
 */
class LangDetector(lines: List<String>) {

    private val langs: List<String>

    /** Cost charged per language for a trigram that isn't in the table. */
    private val floor: IntArray

    /** Trigram → its offset into [costs] (already multiplied by `langs.size`). */
    private val offsets: HashMap<String, Int>

    /** Flat uint8 cost matrix, trigram-major then language-minor. */
    private val costs: ByteArray

    init {
        require(lines.size >= 6 && lines[0] == HEADER) {
            "Not a marmalade-langdetect table (header was '${lines.firstOrNull()}')"
        }
        langs = lines[1].trim().split(' ')
        floor = lines[3].trim().split(' ').map { it.toInt() }.toIntArray()
        val keys = lines[4].replace('_', ' ')
        costs = Base64.getDecoder().decode(lines[5])
        val n = langs.size
        val count = keys.length / 3
        offsets = HashMap(count * 2)
        for (i in 0 until count) {
            offsets[keys.substring(i * 3, i * 3 + 3)] = i * n
        }
    }

    /**
     * Language of [text], or null when the guess would be noise.
     *
     * One of `en es fr it pt ja zh hi`, i.e. the languages Marmalade can
     * actually phonemize.
     */
    fun detect(text: String): String? {
        var kana = 0
        var han = 0
        var deva = 0
        var latin = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isKana(cp) -> kana++
                isHan(cp) -> han++
                isDevanagari(cp) -> deva++
                Character.isLetter(cp) -> latin++
            }
        }
        val cjk = kana + han
        if (cjk > 0 && cjk >= latin) return if (kana > 0) "ja" else "zh"
        if (deva > 0 && deva >= latin) return "hi"
        if (latin == 0) return null
        return trigramDetect(text)
    }

    /**
     * [requested] with the [AUTO] sentinel replaced by a detection on
     * [text]. Any other value (including null, "engine decides") passes
     * through untouched — an explicit choice always outranks detection.
     */
    fun resolve(requested: String?, text: String): String? =
        if (requested != AUTO) requested else espeakCodeFor(detect(text))

    private fun trigramDetect(text: String): String? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        val padded = " $norm "
        val nTri = padded.length - 2
        if (nTri < MIN_TRIGRAMS) return null

        val n = langs.size
        val totals = LongArray(n)
        for (t in 0 until nTri) {
            val at = offsets[padded.substring(t, t + 3)]
            if (at == null) {
                for (li in 0 until n) totals[li] += floor[li]
            } else {
                for (li in 0 until n) totals[li] += (costs[at + li].toInt() and 0xFF)
            }
        }
        var best = 0
        for (li in 1 until n) if (totals[li] < totals[best]) best = li
        var second = -1
        for (li in 0 until n) {
            if (li != best && (second == -1 || totals[li] < totals[second])) second = li
        }
        if (second == -1) return null
        if ((totals[second] - totals[best]).toDouble() / nTri < MIN_MARGIN) return null
        return langs[best]
    }

    /** Lowercase, non-letters to space, space runs collapsed, trimmed. */
    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text.lowercase()) {
            if (ch.isLetter()) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(ch)
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    companion object {
        private const val HEADER = "marmalade-langdetect 1"
        private const val ASSET = "langdetect.tab"

        /**
         * Sentinel stored in `VoiceAlias.phonemizationLanguage` meaning
         * "detect the language of each utterance". Distinct from null,
         * which means "the engine decides" (Kokoro derives the language
         * from the voice's key prefix).
         */
        const val AUTO = "auto"

        /** Below this many trigrams the trigram guess is noise. */
        const val MIN_TRIGRAMS = 6

        /** Minimum per-trigram cost gap between winner and runner-up. */
        const val MIN_MARGIN = 4.0

        @Volatile
        private var cached: LangDetector? = null

        /** The shared detector, parsed from assets on first use. */
        fun load(context: Context): LangDetector = cached ?: synchronized(this) {
            cached ?: context.applicationContext.assets.open(ASSET).use { input ->
                LangDetector(input.bufferedReader().readLines())
            }.also { cached = it }
        }

        /**
         * Detected language → the espeak voice code to phonemize with.
         *
         * English (and an undetected language) map to null: the region a
         * voice reads English in is the voice's own business, and
         * guessing en-US vs en-GB from text is not a thing detection can
         * do. Mandarin maps to null too — Han runs go through
         * `lexicon-zh.txt` inside KokoroDirectEngine whatever the espeak
         * voice is, and espeak-cmn produces IPA the model can't read.
         */
        fun espeakCodeFor(lang: String?): String? = when (lang) {
            "es" -> "es"
            "fr" -> "fr-fr"
            "hi" -> "hi"
            "it" -> "it"
            "ja" -> "ja"
            "pt" -> "pt-br"
            else -> null // en, zh, unknown
        }

        private fun isKana(cp: Int): Boolean =
            cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F

        private fun isHan(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0xF900..0xFAFF ||
                cp in 0x20000..0x2A6DF

        private fun isDevanagari(cp: Int): Boolean = cp in 0x0900..0x097F
    }
}
