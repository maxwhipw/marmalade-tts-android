package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Voice catalog for the developer-only clean-room Pocket engine
 * ([app.marmalade.tts.engine.PocketDevEngine]). Mirrors the 8 built-in
 * voices from [PocketVoiceCatalog] but under a separate engine name so
 * they appear as parallel choices in the voice picker.
 *
 * No separate install — the engine reads from production Pocket's bundle
 * directory. These voices are valid iff production Pocket is installed.
 */
object PocketDevVoiceCatalog {

    /**
     * Engine name. The `-dev` suffix marks it as the diagnostic reference;
     * production Pocket uses [PocketVoiceCatalog.ENGINE] without the suffix.
     */
    const val ENGINE = "pocket-tts-en-v2026_04-dev"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = PocketVoiceCatalog.SAMPLE_RATE

    fun voiceId(name: String): String = "$ENGINE:$name"

    val voices: List<VoiceMeta> = PocketVoiceCatalog.voices.map { v ->
        VoiceMeta(
            // Same lowercase voice key as production Pocket's id — the engine
            // resolves `voices/<key>.wav` from it.
            id = voiceId(v.id.substringAfterLast(':')),
            engine = ENGINE,
            displayName = "${v.displayName} (clean)",
            languageCode = LANGUAGE,
            sampleRate = SAMPLE_RATE,
            gender = v.gender,
            isInstalled = false,
        )
    }
}
