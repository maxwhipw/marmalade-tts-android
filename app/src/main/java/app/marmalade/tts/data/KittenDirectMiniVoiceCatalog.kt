package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Voices for the KittenDirect **Mini** engine — the 80M-parameter KittenML
 * model run via direct ORT (sherpa-onnx-free), the larger sibling of
 * [KittenDirectVoiceCatalog]'s 15M nano.
 *
 * Same 8 voice identities and the same speaker ordering as the nano catalog
 * (KittenML packs both models' `voices.bin` in the same speaker index order:
 * Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo). The style embeddings
 * themselves differ between nano and mini (model-specific latent spaces), so
 * the bundle ships Mini's own per-voice `.bin` files — but the names, genders,
 * and order match nano exactly.
 *
 * Distinct [ENGINE] string so the catalog/installer/router treat it as a
 * separate installable engine from nano (`kitten-direct-v0_8`).
 */
object KittenDirectMiniVoiceCatalog {

    const val ENGINE = "kitten-direct-mini-v0_8"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = 24000

    const val DEFAULT_VOICE_ID = "kitten-direct-mini-v0_8:Bella"

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
