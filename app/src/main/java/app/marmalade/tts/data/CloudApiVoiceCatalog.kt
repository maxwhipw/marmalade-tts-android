package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Voices for the Cloud API engine — hosted synthesis over an
 * OpenAI-compatible `/audio/speech` endpoint (Venice.ai's `tts-kokoro`
 * model). Nothing runs on-device, so there is no bundle, no download and
 * no entry in [app.marmalade.tts.install.EngineCatalog]; the engine is
 * "installed" when an API key is configured in Settings
 * ([SettingsRepository.cloudApiKey]).
 *
 * The voice list mirrors Venice's live `tts-kokoro` lineup (their
 * `/models?type=tts` endpoint, snapshot 2026-07-19). It is a superset of
 * the local KokoroDirect catalog (adds `af_jadzia`, `em_santa`; drops
 * `bf_isabella`) — same `<lang><gender>_<name>` key convention, so the
 * gender/language helpers from [KokoroDirectVoiceCatalog] apply.
 */
object CloudApiVoiceCatalog {

    const val ENGINE = "cloud-api-v1"
    const val SAMPLE_RATE = 24000

    const val DEFAULT_VOICE_ID = "$ENGINE:af_heart"

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    private val VOICE_KEYS = listOf(
        // American English
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jadzia",
        "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
        "af_sarah", "af_sky",
        "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam",
        "am_michael", "am_onyx", "am_puck", "am_santa",
        // British English
        "bf_alice", "bf_emma", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
        // Spanish
        "ef_dora", "em_alex", "em_santa",
        // French
        "ff_siwis",
        // Hindi
        "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
        // Italian
        "if_sara", "im_nicola",
        // Japanese
        "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
        // Brazilian Portuguese
        "pf_dora", "pm_alex", "pm_santa",
        // Mandarin
        "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
        "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
    )

    val voices: List<VoiceMeta> = VOICE_KEYS.map { key ->
        VoiceMeta(
            id = voiceId(key),
            engine = ENGINE,
            displayName = key,
            languageCode = KokoroDirectVoiceCatalog.languageFor(key) ?: "en-US",
            sampleRate = SAMPLE_RATE,
            gender = KokoroDirectVoiceCatalog.genderFor(key),
            isInstalled = false,
        )
    }
}
