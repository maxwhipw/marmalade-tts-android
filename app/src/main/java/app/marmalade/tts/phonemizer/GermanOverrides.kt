package app.marmalade.tts.phonemizer

import java.text.Normalizer
import java.util.Locale

// -----------------------------------------------------------------------------
// GermanOverrides — pronunciation-override lexicon for the German Kokoro path.
//
// A small lexicon layer on top of espeak-de. espeak mispronounces English /
// brand / tech terms (GitHub, PyTorch, CUDA, …) and a few foreign-origin
// German words; these overrides supply hand-written phonemes that bypass
// espeak and go straight to the model. Phonemes use the same symbol
// convention KokoroEspeakG2P maps espeak output to (capital A/I/W/O/Y for
// diphthongs, ʦ/ʧ etc.).
//
// The table (90 entries) is ported verbatim from kikiri-tts PR #28
// (author dida-80b) via the semidark/misaki fork, misaki/data/
// de_overrides.json, Apache-2.0. Phoneme values are copied exactly.
// Priority on lookup collisions: brand > en > de_foreign (first writer wins).
// -----------------------------------------------------------------------------

internal object GermanOverrides {

    private val LOOKUP_REPLACEMENTS = mapOf('+' to "plus", '&' to "and", '@' to "at")

    /**
     * Collapse a word to its override-lookup key: casefold, NFKD, drop
     * combining marks (so "Moët" -> "moet"), fold "+"/"&"/"@" to
     * plus/and/at, keep only alphanumerics.
     *
     * Java's [String.lowercase] is NOT full Unicode casefold — it leaves ß
     * intact where Python's str.casefold folds it to "ss". For this table's
     * keys lowercase alone would suffice (none contain ß), but the explicit
     * ß->ss keeps this function faithful to misaki on words like "Straße".
     */
    fun normalizeForLookup(text: String): String {
        val folded = text.lowercase(Locale.ROOT).replace("ß", "ss")
        val decomposed = Normalizer.normalize(folded, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            val repl = LOOKUP_REPLACEMENTS[c]
            when {
                repl != null -> sb.append(repl)
                Character.isLetterOrDigit(c) -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Word-ish tokens for override matching. Captures internal "-"/"'"
     * (espeak-ng, zero-shot) and a trailing "+" (Disney+) so single tokens
     * with joiners match their normalized override key. Space-separated
     * multi-word brands are not matched here by design.
     */
    val OVERRIDE_WORD_RE = Regex("""[0-9A-Za-zÀ-ÖØ-öø-ÿß]+(?:['\-][0-9A-Za-zÀ-ÖØ-öø-ÿß]+)*\+?""")

    private val BRAND = linkedMapOf(
        "bark" to "bˈaːɐk",
        "claude" to "klˈoːt",
        "coqui" to "kˈoːki",
        "cuda" to "kˈuːda",
        "deepseek" to "dˈiːpsiːk",
        "espeak-ng" to "ˈiːspiːk ɛndʒiː",
        "fastpitch" to "fˈaːstpɪtʃ",
        "geforce" to "dʒiːfˈɔɐs",
        "gemini" to "dʒˈɛmɪnaɪ",
        "hifigan" to "haɪfˈaɪɡæn",
        "intellij" to "ˈɪntɛlaɪdʒ",
        "kikiri" to "kɪkˈiːʁi",
        "kokoro" to "kˈoːkoːʁoː",
        "llama" to "lˈaːma",
        "mistral" to "mˈɪstʁal",
        "mixtral" to "mˈɪkstʁal",
        "neovim" to "nˈiːovɪm",
        "phonemizer" to "foːnəmˈaɪzɐ",
        "piper" to "pˈaɪpɐ",
        "pycharm" to "pˈaɪtʃaːɐm",
        "qwen" to "kwˈɛn",
        "radeon" to "ɹˈeɪdɪɔn",
        "ryzen" to "ɹˈaɪzən",
        "tacotron" to "tˈækotʁɔn",
        "tacotron2" to "tˈækotʁɔn tuː",
        "triton" to "tʁˈaɪtɔn",
        "typescript" to "tˈaɪpskʁɪpt",
        "unsloth" to "ˈʌnslɔːθ",
        "vits" to "vˈɪts",
        "vscode" to "viːˈɛs koːt",
    )

    private val EN = linkedMapOf(
        "accelerate" to "ɐksˈɛlɚɹˌAt",
        "amd" to "ˌAˌɛmdˈiː",
        "apache" to "ɐpˈæʧi",
        "api" to "eɪpiːˈaɪ",
        "bert" to "bˈɜːt",
        "checkpoint" to "tʃˈɛkpɔɪnt",
        "cli" to "tseːɛlˈaɪ",
        "debian" to "dˈɛbiən",
        "disneyplus" to "dˈɪzni plˈʌs",
        "dropout" to "dɹˈɑːpWt",
        "fallback" to "fˈɔːlbæk",
        "finetuning" to "fˈIn tˈuːnɪŋ",
        "gan" to "ɡˈæn",
        "github" to "ɡˈɪthab",
        "githubactions" to "ɡˈɪt hˈʌb ˈɛkʃəns",
        "gpu" to "dʒiːpiːjˈuː",
        "https" to "ˌAʧtˌiːtˈiːpˌiːˈɛs",
        "huggingface" to "hˈaɡɪŋfeɪs",
        "ipad" to "ˈI pˈæd",
        "jameswebb" to "ʤˈAmz wˈɛb",
        "json" to "dʒˈeɪsən",
        "kde" to "kˌAdˌiːˈiː",
        "louisvuitton" to "lwˈi vyitˈɔ̃",
        "macos" to "mˈɛk oː ˈɛs",
        "moetchandon" to "mɔˈɛ ʃɑ̃dˈɔ̃",
        "nvidia" to "ɛnˈviːdiːa",
        "ollama" to "olˈaːma",
        "pipeline" to "pˈaɪplaɪn",
        "primevideo" to "pɹˈIm vˈɪdɪO",
        "protocol" to "pʁotokˈɔl",
        "pytorch" to "pˈaɪtɔːɹtʃ",
        "rag" to "ɹˈæɡ",
        "repository" to "ɹᵻpˈɑːzɪtˌɔːɹi",
        "review" to "ɹᵻvjˈuː",
        "rnn" to "ˌɑːɹɹˌɛnˈɛn",
        "runtime" to "ˈɹantaɪm",
        "styletts" to "stˈaɪl tiːtiːˈɛs",
        "styletts2" to "stˈaɪl tiːtiːˈɛs tsvai",
        "surface" to "sˈɜːfɪs",
        "tcp" to "tˌiːsˌiːpˈiː",
        "thread" to "θɹˈɛd",
        "tpu" to "tˌiːpˌiːjˈuː",
        "transformers" to "tɹænsfˈɔːɹmɚz",
        "ubuntu" to "uːbˈuːntuː",
        "ui" to "juːˈaɪ",
        "wavlm" to "wˈɛɪv ɛlˈɛm",
        "wsl" to "dˌʌbəljˌuːˌɛsˈɛl",
        "zero-shot" to "zˈiːɹo ʃˈɔt",
    )

    private val DE_FOREIGN = linkedMapOf(
        "diathese" to "diaˈteːzə",
        "ekstase" to "ɛkstˈaːzə",
        "epiklese" to "epiˈkleːzə",
        "epithese" to "epiˈteːzə",
        "glucose" to "ɡlukˈoːzə",
        "hypnose" to "hˈyːpnoːzə",
        "metamorphose" to "metamɔʁfˈoːzə",
        "oase" to "oˈaːzə",
        "prosthese" to "pʁɔstˈeːzə",
        "prothese" to "pʁotˈeːzə",
        "symbiose" to "zymbɪˈoːzə",
        "synthese" to "zyntˈeːzə",
    )

    private val RAW_ALIASES = mapOf("moetandchandon" to "moetchandon")

    /** Normalized lookup table, brand > en > de_foreign on key collisions. */
    private val LOOKUP: Map<String, String> = buildMap {
        for (section in listOf(BRAND, EN, DE_FOREIGN)) {
            for ((key, value) in section) putIfAbsent(normalizeForLookup(key), value)
        }
    }

    private val ALIASES: Map<String, String> =
        RAW_ALIASES.entries.associate { normalizeForLookup(it.key) to normalizeForLookup(it.value) }

    /** Override phonemes for a single word, or null if not overridden. */
    fun overrideFor(word: String): String? {
        val key = normalizeForLookup(word)
        if (key.isEmpty()) return null
        return LOOKUP[ALIASES[key] ?: key]
    }
}
