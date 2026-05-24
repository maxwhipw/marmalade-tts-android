package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 53 Kokoro v1.0 voices that ship with Sherpa-ONNX's
 * `kokoro-multi-lang-v1_0` port (fp32, ~333 MB compressed, ~382 MB on-disk).
 *
 * v1.0 has broad coverage: American + British English, Spanish, French,
 * Hindi, Italian, Japanese, Brazilian Portuguese, Mandarin. This is the
 * recommended default Kokoro variant for general English-primary use —
 * v1.1 ships alongside as a Mandarin-specialist alternative (see
 * [KokoroV11VoiceCatalog]).
 *
 * Voice IDs follow the project-wide `"<engine>:<displayName>"` convention.
 *
 * Voice naming convention (upstream `hexgrad/Kokoro-82M` v1.0):
 *   First letter (region/language):
 *     a = American English   b = British English   e = Spanish
 *     f = French             h = Hindi              i = Italian
 *     j = Japanese           p = Brazilian Portuguese  z = Mandarin
 *   Second letter (gender): f = female, m = male
 *   Trailing tag: upstream speaker handle (e.g. `af_bella`, `jm_kumo`)
 */
object KokoroV10VoiceCatalog {

    const val ENGINE = "kokoro-v1_0"
    const val SAMPLE_RATE = 24000

    /**
     * Voice ID used when the system requests "any voice" for en-US.
     * `af_bella` is the highest-rated American-English Kokoro voice in
     * the upstream eval table.
     */
    const val DEFAULT_VOICE_ID = "kokoro-v1_0:af_bella"

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /**
     * The 53 Kokoro v1.0 voices, in the exact order Sherpa-ONNX's
     * `voices.bin` packs them (see `scripts/kokoro/v1.0/generate_voices_bin.py`
     * in the sherpa-onnx repo). The order here drives the speaker-index
     * mapping in [app.marmalade.tts.engine.KokoroV10Engine] — they must
     * stay in lockstep.
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
    )

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

    private fun seed(name: String): VoiceMeta = VoiceMeta(
        id = voiceId(name),
        engine = ENGINE,
        displayName = name,
        languageCode = languageFor(name)
            ?: error("Unsupported voice key prefix in catalog: '$name'"),
        sampleRate = SAMPLE_RATE,
        gender = genderFor(name),
        isInstalled = false,
    )
}
