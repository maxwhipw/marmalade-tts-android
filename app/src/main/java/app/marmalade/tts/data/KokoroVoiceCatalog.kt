package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 53 Kokoro TTS voices that ship with the
 * Sherpa-ONNX `kokoro-multi-lang-v1_0` port (fp32, ~333 MB compressed,
 * ~382 MB on-disk).
 *
 * v0.1.19 upgraded from the English-only `kokoro-int8-en-v0_19` (11
 * voices) to the multi-language v1.0 bundle. v0.1.20 swapped the int8-v1.0
 * weights (unblessed upstream export; produced tinny audio) for the fp32
 * weights. The voice catalog itself is unchanged — voices.bin is
 * byte-identical between int8 and fp32 bundles.
 *
 * Voice/language orthogonality is preserved at the synthesizer level:
 * any voice can speak any supported language, with the natural language
 * driving prosody and the lexicon-driven phonemiser routing on text
 * content (Sherpa-ONNX picks lexicon-us-en.txt for ASCII text and
 * lexicon-zh.txt for CJK).
 *
 * Voice IDs follow the project-wide `"<engine>:<displayName>"` convention.
 *
 * The catalog is pure data — no Android dependencies — so it's cheap
 * to unit-test and safe to reference from any thread.
 *
 * Voice naming convention (upstream `hexgrad/Kokoro-82M` v1.0):
 *   First letter (region/language):
 *     a = American English   b = British English   e = Spanish
 *     f = French             h = Hindi              i = Italian
 *     j = Japanese           p = Brazilian Portuguese  z = Mandarin
 *   Second letter (gender): f = female, m = male
 *   Trailing tag: upstream speaker handle (e.g. `af_bella`, `jm_kumo`)
 */
object KokoroVoiceCatalog {

    const val ENGINE = "kokoro"
    const val SAMPLE_RATE = 24000

    /**
     * Voice ID used when the system requests "any voice" for en-US.
     * `af_bella` is the highest-rated American-English Kokoro voice in
     * the upstream eval table — same default we shipped pre-multi-lang.
     */
    const val DEFAULT_VOICE_ID = "kokoro:af_bella"

    /**
     * Build a voice ID from a display name. Centralises the
     * `"<engine>:<displayName>"` convention so callers never assemble
     * the colon-separated form by hand.
     */
    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /**
     * The 53 Kokoro v1.0 voices, in the exact order Sherpa-ONNX's
     * `voices.bin` packs them (see `scripts/kokoro/v1.0/generate_voices_bin.py`
     * in the sherpa-onnx repo). The order here drives:
     *   - the speaker-index mapping in [app.marmalade.tts.engine.KokoroEngine]
     *   - the display order in the voice picker
     *
     * If you change the order, [app.marmalade.tts.engine.KokoroEngine]'s
     * `SPEAKER_ID_BY_NAME` MUST be updated in lockstep, and
     * `KokoroVoiceCatalogTest.voiceCountMatchesExpectedKokoroV1Set` will
     * fail loudly if it isn't.
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

    /**
     * Derive gender from the upstream voice key. The character at
     * index 1 ([af]_*, [am]_*, [bf]_*, [bm]_*, …) is the canonical signal —
     * exposed as a helper so tests can pin the convention without
     * duplicating it.
     *
     * Returns `"male"` / `"female"`, or null if the key doesn't follow
     * the convention (should not happen for any of the seeded voices).
     */
    fun genderFor(voiceKey: String): String? = when {
        voiceKey.length < 2 -> null
        voiceKey[1] == 'f' -> "female"
        voiceKey[1] == 'm' -> "male"
        else -> null
    }

    /**
     * Derive the natural BCP-47 language code from the voice key's first
     * letter (Kokoro's region-/language-code convention).
     *
     * Returns the language a given voice naturally speaks. Sherpa-ONNX's
     * multi-lang Kokoro will accept English text against any voice (the
     * espeak lexicon route handles ASCII regardless of natural language),
     * which is how the "non-English voice → accented English" use case
     * works. The catalog still tags each voice with its natural language
     * so the voice picker can group / filter sanely.
     *
     * Unknown prefixes return null — the catalog never produces these,
     * but the helper stays conservative for forward-compatibility with
     * future Kokoro releases that add a new prefix.
     */
    fun languageFor(voiceKey: String): String? {
        if (voiceKey.isEmpty()) return null
        return when (voiceKey[0]) {
            'a' -> "en-US"   // American English
            'b' -> "en-GB"   // British English
            'e' -> "es-ES"   // Spanish (European)
            'f' -> "fr-FR"   // French
            'h' -> "hi-IN"   // Hindi
            'i' -> "it-IT"   // Italian
            'j' -> "ja-JP"   // Japanese
            'p' -> "pt-BR"   // Brazilian Portuguese (Kokoro uses BR variant)
            'z' -> "zh-CN"   // Mandarin (Simplified)
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
        // Flips to true once KokoroEngine.ensureModelLoaded() succeeds.
        // Until the bundle is downloaded every row stays false and the
        // voice picker shows the "needs download" state.
        isInstalled = false,
    )
}
