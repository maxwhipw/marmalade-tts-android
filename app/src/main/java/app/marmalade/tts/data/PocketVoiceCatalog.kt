package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the 6 predefined voices that ship with the
 * `pocket-tts-en-v2026_04-mixed` bundle (~96 MB compressed, ~217 MB on-disk).
 *
 * Upstream pocket-tts defines 8 predefined voices, but we ship only 6:
 * `cosette` (Expresso) and `jean` (EARS) are **CC-BY-NC-4.0 (non-commercial)**
 * and were dropped before the public release so the store build distributes
 * no non-commercial voice data. The retained 6 are all commercial-safe:
 * `alba` (alba-mackenna, CC-BY-4.0), `azelma`/`eponine`/`fantine` (VCTK,
 * CC-BY-4.0), and `javert`/`marius` (voice-donations, CC0). See
 * `LICENSES/pocket-tts.md` for the per-voice source/license map.
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
     * upstream pocket-tts's documented default — a warm low-mid male
     * voice (~133 Hz median F0) that demos well across short and long
     * inputs.
     */
    const val DEFAULT_VOICE_ID = "pocket-tts-en-v2026_04:alba"

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    /**
     * The 6 commercial-safe predefined voices, in the order they appear in
     * `bundle.json#predefined_voices`. Order has no functional meaning for
     * Pocket (voices are addressed by name, not index — there's no
     * speaker-ID like in voices.bin), but keep the upstream order so the
     * voice picker stays stable across bundle refreshes. The upstream
     * `cosette` (Expresso) and `jean` (EARS) voices are CC-BY-NC-4.0 and
     * are intentionally absent — see the class doc.
     *
     * Gender annotations derived from autocorrelation-based F0 estimation
     * of the bundled `voices/<name>.wav` reference samples (median F0 over
     * voiced frames; threshold at ~150 Hz). The names don't reliably map
     * to the Les Misérables canonical genders — `alba` isn't a Les Mis
     * name at all, and `marius` (a male character) ships with a female
     * voice prompt. Trust the audio, not the name.
     */
    val voices: List<VoiceMeta> = listOf(
        seed("alba", "male"),       //  ~133 Hz
        seed("azelma", "female"),   //  ~214 Hz
        seed("eponine", "female"),  //  ~164 Hz
        seed("fantine", "female"),  //  ~211 Hz
        seed("javert", "male"),     //   ~96 Hz
        seed("marius", "female"),   //  ~214 Hz
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
