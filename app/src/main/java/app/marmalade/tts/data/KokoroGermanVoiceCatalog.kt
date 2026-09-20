package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog for the native German Kokoro engine (`kokoro-de-v1_0`),
 * served by [app.marmalade.tts.engine.kokoro.KokoroGermanEngine].
 *
 * A separate downloadable engine from `kokoro-direct-v1_0`, not a voice pack:
 * Thorsten-Voice/Kokoro (Apache-2.0) is a German fine-tune of Kokoro-82M that
 * retrained the whole acoustic stack, so it can never share `voices.bin` or
 * weights with the multilingual base — the two engines coexist on disk. This
 * one ships a single trained German speaker and always phonemizes German
 * through [app.marmalade.tts.phonemizer.GermanG2P]; there is no language
 * picker (see AliasScreen's PHONEMIZATION_ENGINES, which deliberately omits
 * it).
 */
object KokoroGermanVoiceCatalog {

    const val ENGINE = "kokoro-de-v1_0"
    const val SAMPLE_RATE = 24000

    /** The single speaker's raw voice key (the part after `<engine>:`). */
    const val THORSTEN_VOICE_KEY = "thorsten"

    /** The only voice this engine speaks — used as the "any German voice" default. */
    const val DEFAULT_VOICE_ID = "$ENGINE:$THORSTEN_VOICE_KEY"

    /**
     * One voice: Thorsten, the male German speaker of the open Thorsten-Voice
     * dataset (recorded and CC0-published by the speaker himself). speaker id
     * is always 0 — voices.bin holds exactly one 510 × 256 style table.
     */
    val voices: List<VoiceMeta> = listOf(
        VoiceMeta(
            id = DEFAULT_VOICE_ID,
            engine = ENGINE,
            displayName = "🇩🇪 Thorsten",
            languageCode = "de-DE",
            sampleRate = SAMPLE_RATE,
            gender = "male",
            isInstalled = false,
            sortOrder = 0,
        ),
    )
}
