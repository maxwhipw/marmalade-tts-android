package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 8 Kitten Mini voices that ship with the
 * `kitten-mini-en-v0_8` model (~64 MB compressed, ~95 MB on-disk).
 *
 * 80M-parameter model — Kitten's quality-upgrade variant. Roughly the
 * same compressed-bundle size as Kitten Nano (62 MB nano fp32 vs 64 MB
 * mini) but ~5.3x more model parameters, leading to a marginal but
 * audible quality improvement over nano in the A/B test.
 *
 * Mixed-precision quantisation upstream (fp32 + fp16 + selective
 * int8/uint8) — distinct from the blanket dynamic-int8 quantisation
 * that sank kitten-nano-int8. Same 8 voice names as Kitten Nano.
 */
object KittenMiniVoiceCatalog {

    const val ENGINE = "kitten-mini-v0_8"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = 24000

    const val DEFAULT_VOICE_ID = "kitten-mini-v0_8:Bella"

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
