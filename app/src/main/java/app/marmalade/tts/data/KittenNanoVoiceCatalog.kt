package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 8 Kitten Nano voices that ship with the
 * `kitten-nano-en-v0_8-fp32` model (~61 MB compressed, ~74 MB on-disk).
 *
 * 15M-parameter model — the lightweight Kitten variant. Same 8 voice
 * names as the larger Kitten Mini (the voice identities are shared
 * across the Kitten family); they sound different at the audio level
 * because the underlying model differs.
 *
 * Voice IDs use the convention `"<engine>:<displayName>"`.
 */
object KittenNanoVoiceCatalog {

    const val ENGINE = "kitten-nano-v0_8"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = 24000

    const val DEFAULT_VOICE_ID = "kitten-nano-v0_8:Bella"

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

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
        isInstalled = false,
    )
}
