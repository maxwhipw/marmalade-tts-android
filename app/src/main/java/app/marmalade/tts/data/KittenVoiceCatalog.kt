package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 8 Kitten TTS voices that ship with the kitten-nano
 * model (`kitten-tts-nano-0.8`, ~25 MB). Names match the CLI's
 * `marmalade_tts/engines/kitten.py` VOICES list — same identifiers across
 * CLI and app so users moving between them see the same names.
 *
 * Voice IDs use the convention `"<engine>:<displayName>"` so the Android
 * TTS framework's `voiceName` field round-trips cleanly through
 * `onLoadVoice` / `onGetDefaultVoiceNameFor`.
 *
 * The catalog itself is pure data — no Android dependencies — so it is
 * cheap to unit-test and safe to reference from any thread.
 */
object KittenVoiceCatalog {

    const val ENGINE = "kitten"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = 24000

    /** Voice ID used when the system requests "any voice" for en-US. */
    const val DEFAULT_VOICE_ID = "kitten:Bella"

    /**
     * Build a voice ID from a display name. Centralises the
     * `"<engine>:<displayName>"` convention so callers never assemble
     * the colon-separated form by hand.
     */
    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /** The 8 Kitten voices, matching the upstream model's `voice_aliases` map. */
    val voices: List<VoiceMeta> = listOf(
        seed("Bella", "female"),
        seed("Jasper", "male"),
        seed("Luna", "female"),
        seed("Bruno", "male"),
        seed("Rosie", "female"),
        seed("Hugo", "male"),
        seed("Kiki", "female"),
        seed("Leo", "male"),
    )

    private fun seed(name: String, gender: String): VoiceMeta = VoiceMeta(
        id = voiceId(name),
        engine = ENGINE,
        displayName = name,
        languageCode = LANGUAGE,
        sampleRate = SAMPLE_RATE,
        gender = gender,
        // Flips to true once KittenEngine.ensureModelLoaded() succeeds — see
        // KittenEngine.kt. Until the model is bundled (see STUBS.md) every
        // row stays false and the voice picker can show the "needs download"
        // state.
        isInstalled = false,
    )
}
