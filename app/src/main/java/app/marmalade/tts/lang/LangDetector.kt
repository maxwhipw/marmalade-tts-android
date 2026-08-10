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
 * text and is never attempted; [espeakCodeFor] takes the region from the
 * voice's own catalog default instead.
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
class LangDetector(
    lines: List<String>,
    /**
     * The Han-ambiguity tiebreak (Max, 2026-08-08): a short kana-free Han
     * run with no marker characters resolves to the system default
     * language when that default is itself ja or zh — any other default
     * says nothing about Han text. Explicit in tests; the DI default
     * reads the device locale.
     */
    private val systemCjk: String? = systemCjkDefault(),
) {

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
        if (cjk > 0 && cjk >= latin) return if (kana > 0) "ja" else hanDetect(text, han)
        if (deva > 0 && deva >= latin) return "hi"
        if (latin == 0) return null
        return trigramDetect(text)
    }

    /** zh/ja for a kana-free Han run — see the [HAN_ZH]/[HAN_JA] comment. */
    private fun hanDetect(text: String, han: Int): String? {
        if (text.any { it in HAN_ZH }) return "zh"
        if (text.any { it in HAN_JA }) return "ja"
        return if (han >= HAN_ZH_MIN) "zh" else systemCjk
    }

    /**
     * [requested] with the [AUTO] sentinel replaced by a detection on
     * [text]. Any other value passes through untouched — an explicit
     * choice always outranks detection.
     *
     * [voiceDefault] is the voice's own catalog espeak code, which
     * [espeakCodeFor] needs to decide the English region.
     */
    fun resolve(requested: String?, text: String, voiceDefault: String?): String? =
        if (requested != AUTO) requested else espeakCodeFor(detect(text), voiceDefault)

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
        if ((totals[second] - totals[best]).toDouble() / nTri < requiredMargin(nTri)) return null
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
         * Han-only text (no kana) needs a zh/ja tiebreak. Each set holds
         * characters effectively exclusive to one language's modern
         * writing: [HAN_ZH] — PRC simplifications that differ from
         * shinjitai (这≠這, 时≠時, 读≠読 …), Chinese-only grammar/pronoun
         * characters (们 吗 呢 吧 你 她), and traditional forms Japanese
         * writes differently (們 嗎 讓 麼 沒 氣); [HAN_JA] — kokuji
         * (込 働 峠 …) and shinjitai differing from BOTH Chinese forms
         * (図≠图/圖, 気≠气/氣 …). Shared glyphs (国 学 会 写 没 万 …) are
         * deliberately absent. Mirrored in the CLI's langdetect.py —
         * keep the two in lockstep.
         */
        private val HAN_ZH = (
            "这说对时东车书长门问间语读关开见觉认识谁让过还进远边达选们么军动头" +
                "买卖妈红电华个为从发汉现乐你她它吗呢吧哪咱啥們嗎讓麼沒氣"
            ).toSet()
        private val HAN_JA =
            "込働峠畑辻枠匂塀笹図円売読絵駅験単桜気帰歯労楽実徳縄渋択沢変遅剣塩拝恵姫黒悪圧".toSet()

        /**
         * A kana-free Han run this long is Chinese — real Japanese
         * sentences carry kana within a few characters (okurigana,
         * particles). Below it an unmarked run abstains to the fallback.
         */
        private const val HAN_ZH_MIN = 6

        /**
         * Sentinel stored in `VoiceAlias.phonemizationLanguage` meaning
         * "detect the language of each utterance".
         *
         * Auto-detect is the default, so a null column means the same
         * thing on a Kokoro alias — which is what makes the rows written
         * before the sentinel existed behave correctly with no migration.
         * The literal is still written by nothing but the legacy rows of
         * the one build that offered it as a separate dropdown entry.
         */
        const val AUTO = "auto"

        /** Below this many trigrams the trigram guess is noise. */
        const val MIN_TRIGRAMS = 8

        /**
         * Minimum per-trigram cost gap between winner and runner-up.
         *
         * 2.0, not the 4.0 the first table shipped with: at 4 the
         * detector abstained on ordinary Portuguese sentences (es/pt is
         * the closest pair it has to separate). Measured against the
         * retrained table, 2.0 is 100% accurate on decided 25–200-char
         * held-out sentences and 99.95% across the full 40–200 band, at
         * a 0.3% abstain rate.
         */
        const val MIN_MARGIN = 2.0

        /**
         * Short text must be decisive (Max's 2026-08-09 device report:
         * "button" detected as Italian and rerouted a Kitten alias to an
         * accented Kokoro voice). Common English words score junk
         * margins — up to ~15 below 8 trigrams (hence MIN_TRIGRAMS 6→8:
         * nothing separates them there) and up to ~5.7 in the 8–23 band
         * — while genuinely foreign short phrases score 10–45. The
         * required margin ramps linearly from [SHORT_MARGIN] at
         * [MIN_TRIGRAMS] down to [MIN_MARGIN] at [SHORT_TRIGRAMS]; the
         * original 25–200-char tuning above is untouched. An abstention
         * falls back to the alias/request language — the sticky default.
         * Tuning data + the CLI twin: marmalade-tts-cli langdetect.py,
         * ~/coding/scratch/langdetect-short/. LOCKSTEP with the CLI.
         */
        const val SHORT_TRIGRAMS = 24
        const val SHORT_MARGIN = 8.0

        internal fun requiredMargin(nTri: Int): Double {
            if (nTri >= SHORT_TRIGRAMS) return MIN_MARGIN
            val frac = (SHORT_TRIGRAMS - nTri).toDouble() / (SHORT_TRIGRAMS - MIN_TRIGRAMS)
            return MIN_MARGIN + (SHORT_MARGIN - MIN_MARGIN) * frac
        }

        /** "ja"/"zh" when the device locale is one of them, else null. */
        fun systemCjkDefault(): String? =
            when (java.util.Locale.getDefault().language) {
                "ja" -> "ja"
                "zh" -> "zh"
                else -> null
            }

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
         * [voiceDefault] is the voice's own catalog espeak code (for
         * Kokoro, `KokoroDirectVoiceCatalog.espeakVoiceFor`). It answers
         * the one question detection cannot: which English. A British
         * voice reading detected English stays British; every other
         * voice gets American, mirroring the CLI's `to_kokoro_lang`.
         * The region is still never guessed from the text.
         *
         * Detected English and Mandarin must resolve to a real code
         * rather than null, because null means "the voice's own language
         * stands" — and on a Japanese voice that would phonemize English
         * through Japanese G2P, which is the bug this signature exists to
         * fix. Mandarin's code is `en-us` because Han runs go through
         * `lexicon-zh.txt` inside KokoroDirectEngine and only the latin
         * spans reach espeak (the same choice the `z*` voices' catalog
         * default makes); espeak-cmn produces IPA the model can't read.
         *
         * Null in, null out: no detection and no fallback locale is the
         * one true no-information case, and there the voice decides.
         */
        fun espeakCodeFor(lang: String?, voiceDefault: String?): String? = when (lang) {
            "en" -> if (voiceDefault == "en-gb") "en-gb" else "en-us"
            "es" -> "es"
            "fr" -> "fr-fr"
            "hi" -> "hi"
            "it" -> "it"
            "ja" -> "ja"
            "pt" -> "pt-br"
            "zh" -> "en-us"
            else -> null
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
