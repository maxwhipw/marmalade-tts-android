package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 11 Kokoro TTS voices that ship with the
 * `kokoro-int8-en-v0_19` Sherpa-ONNX port (~98 MB compressed,
 * ~151 MB on-disk).
 *
 * Kokoro is the recommended default engine for v0.1.9+ — it sounds
 * noticeably better than Kitten, at the cost of being ~4x the download.
 * Kitten remains available as a smaller alternative.
 *
 * Voice IDs follow the project-wide `"<engine>:<displayName>"`
 * convention so the Android TTS framework's `voiceName` round-trips
 * through `onLoadVoice` / `onGetDefaultVoiceNameFor`.
 *
 * The catalog is pure data — no Android dependencies — so it is cheap
 * to unit-test and safe to reference from any thread.
 *
 * Voice naming convention (upstream `hexgrad/Kokoro-82M`):
 *   - First letter:  region — `a` = American English, `b` = British English
 *   - Second letter: gender — `f` = female, `m` = male
 *   - Trailing tag:  speaker handle (e.g. `af_bella`, `bm_george`)
 *   - Plain `af` is the upstream blended "average American female" voice.
 */
object KokoroVoiceCatalog {

    const val ENGINE = "kokoro"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = 24000

    /**
     * Voice ID used when the system requests "any voice" for en-US.
     * `af_bella` is the highest-rated Kokoro voice in the upstream
     * eval table — same vibe as Kitten's Bella default, on purpose.
     */
    const val DEFAULT_VOICE_ID = "kokoro:af_bella"

    /**
     * Build a voice ID from a display name. Centralises the
     * `"<engine>:<displayName>"` convention so callers never assemble
     * the colon-separated form by hand.
     */
    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /**
     * The 11 Kokoro v0.19 voices, in the same order Sherpa-ONNX's
     * `voices.bin` packs them (alphabetical by upstream voice key).
     * The order here drives:
     *   - the speaker-index mapping in [app.marmalade.tts.engine.KokoroEngine]
     *   - the display order in the voice picker
     */
    val voices: List<VoiceMeta> = listOf(
        seed("af", "female"),           // 0 — upstream "average American female" blend
        seed("af_bella", "female"),     // 1
        seed("af_nicole", "female"),    // 2
        seed("af_sarah", "female"),     // 3
        seed("af_sky", "female"),       // 4
        seed("am_adam", "male"),        // 5
        seed("am_michael", "male"),     // 6
        seed("bf_emma", "female"),      // 7
        seed("bf_isabella", "female"),  // 8
        seed("bm_george", "male"),      // 9
        seed("bm_lewis", "male"),       // 10
    )

    /**
     * Derive gender from the upstream voice key. The character at
     * index 1 (`af_*` → 'f', `am_*` → 'm', `bf_*` → 'f', `bm_*` → 'm')
     * is the canonical signal — exposed as a helper so tests can pin
     * the convention without duplicating it.
     *
     * The plain `af` key has only two characters and still encodes
     * gender at index 1 ("f") — the function handles it identically.
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

    private fun seed(name: String, gender: String): VoiceMeta = VoiceMeta(
        id = voiceId(name),
        engine = ENGINE,
        displayName = name,
        languageCode = LANGUAGE,
        sampleRate = SAMPLE_RATE,
        gender = gender,
        // Flips to true once KokoroEngine.ensureModelLoaded() succeeds.
        // Until the bundle is downloaded every row stays false and the
        // voice picker shows the "needs download" state.
        isInstalled = false,
    )
}
