package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 103 Kokoro v1.1 voices that ship with Sherpa-ONNX's
 * `kokoro-multi-lang-v1_1` port (fp32, ~348 MB compressed, ~407 MB on-disk).
 *
 * v1.1 is the Mandarin-specialist variant: only 3 English voices
 * (af_maple, af_sol, bf_vale) but 100 Mandarin voices (zf_001..zm_100).
 * Audio quality on English content is noticeably lower than v1.0 per the
 * pre-ship A/B test, so v1.0 stays the default for English-primary use.
 * v1.1 installs alongside v1.0 — they're separate engines, not a swap.
 *
 * The Mandarin speaker handles are anonymous (zf_NNN, zm_NNN) with gaps
 * in the numbering — these aren't sequential. The list below matches the
 * exact order sherpa-onnx's v1.1 voices.bin packs them in.
 */
object KokoroV11VoiceCatalog {

    const val ENGINE = "kokoro-v1_1"
    const val SAMPLE_RATE = 24000

    /** Default voice when "any" is requested — first English voice in the catalog. */
    const val DEFAULT_VOICE_ID = "kokoro-v1_1:af_maple"

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /**
     * The 103 Kokoro v1.1 voices in voices.bin order. First three are
     * English; rest are Mandarin (female 3-57, male 58-102). See class
     * doc for caveats. Must stay in lockstep with
     * [app.marmalade.tts.engine.KokoroV11Engine]'s SPEAKER_ID_BY_NAME.
     */
    val voices: List<VoiceMeta> = buildList {
        // 0-2  English
        add(seed("af_maple"))
        add(seed("af_sol"))
        add(seed("bf_vale"))
        // 3-57  Mandarin female (zf_NNN, non-sequential)
        listOf(
            "001", "002", "003", "004", "005", "006", "007", "008",
            "017", "018", "019", "021", "022", "023", "024", "026",
            "027", "028", "032", "036", "038", "039", "040", "042",
            "043", "044", "046", "047", "048", "049", "051", "059",
            "060", "067", "070", "071", "072", "073", "074", "075",
            "076", "077", "078", "079", "083", "084", "085", "086",
            "087", "088", "090", "092", "093", "094", "099",
        ).forEach { add(seed("zf_$it")) }
        // 58-102  Mandarin male (zm_NNN, non-sequential)
        listOf(
            "009", "010", "011", "012", "013", "014", "015", "016",
            "020", "025", "029", "030", "031", "033", "034", "035",
            "037", "041", "045", "050", "052", "053", "054", "055",
            "056", "057", "058", "061", "062", "063", "064", "065",
            "066", "068", "069", "080", "081", "082", "089", "091",
            "095", "096", "097", "098", "100",
        ).forEach { add(seed("zm_$it")) }
    }

    /** Gender derivation — same convention as v1.0 (second char). */
    fun genderFor(voiceKey: String): String? = when {
        voiceKey.length < 2 -> null
        voiceKey[1] == 'f' -> "female"
        voiceKey[1] == 'm' -> "male"
        else -> null
    }

    /** Natural BCP-47 language from voice-key first character. */
    fun languageFor(voiceKey: String): String? {
        if (voiceKey.isEmpty()) return null
        return when (voiceKey[0]) {
            'a' -> "en-US"
            'b' -> "en-GB"
            'z' -> "zh-CN"
            else -> null
        }
    }

    private fun seed(name: String): VoiceMeta = VoiceMeta(
        id = voiceId(name),
        engine = ENGINE,
        displayName = name,
        languageCode = languageFor(name)
            ?: error("Unsupported v1.1 voice key prefix: '$name'"),
        sampleRate = SAMPLE_RATE,
        gender = genderFor(name),
        isInstalled = false,
    )
}
