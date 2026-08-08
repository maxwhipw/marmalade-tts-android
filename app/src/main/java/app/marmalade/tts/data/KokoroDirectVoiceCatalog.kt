package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 53 Kokoro v1.0 voices, served by the direct-ORT
 * `KokoroDirectEngine`.
 *
 * Voice naming convention (upstream `hexgrad/Kokoro-82M` v1.0):
 *   First letter (region/language):
 *     a = American English   b = British English   e = Spanish
 *     f = French             h = Hindi              i = Italian
 *     j = Japanese           p = Brazilian Portuguese  z = Mandarin
 *   Second letter (gender): f = female, m = male
 *   Trailing tag: upstream speaker handle (e.g. `af_bella`, `jm_kumo`)
 */
object KokoroDirectVoiceCatalog {

    const val ENGINE = "kokoro-direct-v1_0"
    const val SAMPLE_RATE = 24000

    /**
     * Voice ID used when the system requests "any voice" for en-US.
     * `af_bella` is the highest-rated American-English voice upstream.
     */
    const val DEFAULT_VOICE_ID = "kokoro-direct-v1_0:af_bella"

    fun voiceId(voiceKey: String): String = "$ENGINE:$voiceKey"

    /**
     * The 53 Kokoro v1.0 voices, in the exact order Sherpa-ONNX's
     * `voices.bin` packs them (see `scripts/kokoro/v1.0/generate_voices_bin.py`
     * in the sherpa-onnx repo). The list index is the speaker ID passed
     * into `voices.bin` row lookup, so this must stay in lockstep with
     * the bundled binary.
     */
    val voices: List<VoiceMeta> = listOf(
        // 0–10  American female
        seed("af_alloy"),
        seed("af_aoede"),
        seed("af_bella"),
        seed("af_heart"),
        seed("af_jessica"),
        seed("af_kore"),
        seed("af_nicole"),
        seed("af_nova"),
        seed("af_river"),
        seed("af_sarah"),
        seed("af_sky"),
        // 11–19  American male
        seed("am_adam"),
        seed("am_echo"),
        seed("am_eric"),
        seed("am_fenrir"),
        seed("am_liam"),
        seed("am_michael"),
        seed("am_onyx"),
        seed("am_puck"),
        seed("am_santa"),
        // 20–23  British female
        seed("bf_alice"),
        seed("bf_emma"),
        seed("bf_isabella"),
        seed("bf_lily"),
        // 24–27  British male
        seed("bm_daniel"),
        seed("bm_fable"),
        seed("bm_george"),
        seed("bm_lewis"),
        // 28–29  Spanish (f + m)
        seed("ef_dora"),
        seed("em_alex"),
        // 30  French female
        seed("ff_siwis"),
        // 31–34  Hindi (f + m)
        seed("hf_alpha"),
        seed("hf_beta"),
        seed("hm_omega"),
        seed("hm_psi"),
        // 35–36  Italian (f + m)
        seed("if_sara"),
        seed("im_nicola"),
        // 37–41  Japanese (f + m)
        seed("jf_alpha"),
        seed("jf_gongitsune"),
        seed("jf_nezumi"),
        seed("jf_tebukuro"),
        seed("jm_kumo"),
        // 42–44  Brazilian Portuguese (f + m)
        seed("pf_dora"),
        seed("pm_alex"),
        seed("pm_santa"),
        // 45–48  Mandarin female
        seed("zf_xiaobei"),
        seed("zf_xiaoni"),
        seed("zf_xiaoxiao"),
        seed("zf_xiaoyi"),
        // 49–52  Mandarin male
        seed("zm_yunjian"),
        seed("zm_yunxi"),
        seed("zm_yunxia"),
        seed("zm_yunyang"),
    ).mapIndexed { i, v -> v.copy(sortOrder = i) }

    /**
     * Map a raw voice key (the `af_bella` part of the voice id — NOT the
     * flagged displayName) to its speaker index in `voices.bin`.
     * O(1) HashMap lookup — the previous linear `indexOfFirst` paid
     * O(53) per `runInference` call. Returns -1 for unknown keys.
     */
    fun speakerIdFor(voiceKey: String): Int =
        SPEAKER_INDEX[voiceKey] ?: -1

    /** Pre-built key→index map; built once at class-load time. */
    private val SPEAKER_INDEX: Map<String, Int> =
        voices.mapIndexed { i, v -> v.id.substringAfter(':') to i }.toMap()

    /**
     * UI label for a raw voice key: flag + capitalized speaker handle,
     * `"af_bella"` → `"🇺🇸 Bella"`, `"jm_kumo"` → `"🇯🇵 Kumo"`. The raw
     * key stays in the voice id and everywhere machinery-facing
     * ([speakerIdFor], espeak/lang routing, `voices.bin` indexing).
     */
    fun prettyName(voiceKey: String): String {
        val flag = when (voiceKey.firstOrNull()) {
            'a' -> "🇺🇸"
            'b' -> "🇬🇧"
            'e' -> "🇪🇸"
            'f' -> "🇫🇷"
            'h' -> "🇮🇳"
            'i' -> "🇮🇹"
            'j' -> "🇯🇵"
            'p' -> "🇧🇷"
            'z' -> "🇨🇳"
            else -> return voiceKey
        }
        return "$flag ${bareName(voiceKey)}"
    }

    /**
     * The speaker handle alone, `"am_adam"` → `"Adam"` — for surfaces like
     * the alias card subtitle where the flag would be noise.
     */
    fun bareName(voiceKey: String): String =
        voiceKey.substringAfter('_', voiceKey)
            .replaceFirstChar { it.uppercase() }

    /** Gender derivation from Kokoro voice-key second character (`f` / `m`). */
    fun genderFor(voiceKey: String): String? = when {
        voiceKey.length < 2 -> null
        voiceKey[1] == 'f' -> "female"
        voiceKey[1] == 'm' -> "male"
        else -> null
    }

    /** Natural BCP-47 language from Kokoro voice-key first character. */
    fun languageFor(voiceKey: String): String? {
        if (voiceKey.isEmpty()) return null
        return when (voiceKey[0]) {
            'a' -> "en-US"
            'b' -> "en-GB"
            'e' -> "es-ES"
            'f' -> "fr-FR"
            'h' -> "hi-IN"
            'i' -> "it-IT"
            'j' -> "ja-JP"
            'p' -> "pt-BR"
            'z' -> "zh-CN"
            else -> null
        }
    }

    /**
     * Per-voice espeak language code. American/British map to upstream
     * `en-us`/`en-gb`; non-English voices use their language's espeak
     * code, including Hindi — espeak-ng 1.52 ships a working `hi` voice
     * and `hi_dict`, both of which are in the bundle. Codes whose espeak
     * voice FILE is named differently (`fr-fr`, `en-gb`) are mapped by
     * `EspeakPhonemizer.normalizeVoice` before reaching `SetVoiceByName`.
     *
     * Mandarin (`z*`) maps to `en-us`, NOT `cmn`: KokoroDirect phonemizes
     * Han characters through `lexicon-zh.txt` (misaki+pypinyin pre-baked),
     * not espeak — espeak-cmn produces broken IPA for the model. The espeak
     * voice is only used for the non-CJK fragments in mixed text (Latin
     * loanwords, numbers), where en-us is the right reading.
     */
    fun espeakVoiceFor(voiceKey: String): String {
        if (voiceKey.isEmpty()) return "en-us"
        return when (voiceKey[0]) {
            'a' -> "en-us"
            'b' -> "en-gb"
            'e' -> "es"
            'f' -> "fr-fr"
            'h' -> "hi"
            'i' -> "it"
            'j' -> "ja"
            'p' -> "pt-br"
            'z' -> "en-us"  // see kdoc — zh goes through lexicon-zh, not espeak
            else -> "en-us"
        }
    }

    private fun seed(name: String): VoiceMeta = VoiceMeta(
        id = voiceId(name),
        engine = ENGINE,
        displayName = prettyName(name),
        languageCode = languageFor(name)
            ?: error("Unsupported voice key prefix in catalog: '$name'"),
        sampleRate = SAMPLE_RATE,
        gender = genderFor(name),
        isInstalled = false,
    )
}
