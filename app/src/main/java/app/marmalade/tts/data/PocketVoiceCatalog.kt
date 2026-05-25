package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 8 predefined voices that ship with the
 * `pocket-tts-en-v2026_04-int8` bundle (~78 MB compressed, ~145 MB on-disk).
 *
 * Pocket TTS is a different beast from the sherpa-onnx-backed engines:
 *  - It runs on Microsoft `onnxruntime-android` directly (no sherpa-onnx).
 *  - It uses a 5-graph pipeline (text conditioner + flow_lm + flow + mimi
 *    encoder + mimi decoder) with Latent Space Diffusion inference.
 *  - The 8 "predefined voices" are reference WAVs in `voices/<name>.wav`
 *    inside the bundle. On first use of each voice, the engine encodes
 *    the WAV through `mimi_encoder` to produce a `[numFrames, 1024]`
 *    embedding it caches on disk for subsequent runs.
 *  - Users can ALSO clone new voices from their own audio (recorder or
 *    file picker) — that path lands in v0.3.0 once the inference loop is
 *    in. Cloned voices get inserted into `voice_meta` at clone time.
 *
 * Voice IDs use the convention `"<engine>:<displayName>"`.
 */
object PocketVoiceCatalog {

    const val ENGINE = "pocket-tts-en-v2026_04"
    const val LANGUAGE = "en-US"

    /**
     * Sample rate documented in the bundle's `bundle.json` (`sample_rate: 24000`).
     * The mimi_decoder produces 24 kHz mono float samples — we convert to
     * PCM16 in the engine before the AudioTrack write.
     */
    const val SAMPLE_RATE = 24000

    /**
     * Pre-checked voice for first-launch / "any voice" requests. Alba is
     * upstream pocket-tts's documented default — a warm, lower-mid female
     * voice that demos well across short and long inputs.
     */
    const val DEFAULT_VOICE_ID = "pocket-tts-en-v2026_04:alba"

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /**
     * The 8 predefined voices, in the order they appear in
     * `bundle.json#predefined_voices`. Order has no functional meaning for
     * Pocket (voices are addressed by name, not index — there's no
     * speaker-ID like in voices.bin), but keep the upstream order so the
     * voice picker stays stable across bundle refreshes.
     *
     * Gender annotations are taken from upstream pocket-tts voice metadata
     * (Les Misérables character names line up with the source novel's
     * canonical genders).
     */
    val voices: List<VoiceMeta> = listOf(
        seed("alba", "female"),
        seed("azelma", "female"),
        seed("cosette", "female"),
        seed("eponine", "female"),
        seed("fantine", "female"),
        seed("javert", "male"),
        seed("jean", "male"),
        seed("marius", "male"),
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
