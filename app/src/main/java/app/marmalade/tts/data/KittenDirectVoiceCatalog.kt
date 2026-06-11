package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Voices for the KittenDirect engine — the direct-ORT Kitten path.
 *
 * The 8 voice identities come from the upstream `voices.npz` in
 * KittenML/kitten-tts-nano-0.8, which both the nano and mini Kitten
 * models consume. Phonemization is espeak-ng.
 *
 * Bundled as one `.bin` file per voice — produced by extracting each
 * named array out of upstream's combined `voices.npz`. Filenames are
 * the lowercased displayName: `voices/bella.bin`, `voices/jasper.bin`,
 * etc. The engine indexes into the per-voice table by IPA-string length.
 */
object KittenDirectVoiceCatalog {

    const val ENGINE = "kitten-direct-v0_8"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = 24000

    const val DEFAULT_VOICE_ID = "kitten-direct-v0_8:Bella"

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
