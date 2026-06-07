package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Voice catalog for the developer-only ExecuTorch Pocket engine
 * ([app.marmalade.tts.engine.PocketExecuTorchDevEngine]). Mirrors the 8
 * built-in voices from [PocketVoiceCatalog] but under a separate engine name
 * so they appear as parallel choices in the voice picker for the on-device
 * A/B against the shipping ORT Pocket path.
 *
 * No separate bundle install — the engine reads the non-ONNX files (tokenizer,
 * bos_before_voice, bundle.json, voice WAVs) from production Pocket's directory
 * and side-loads the `.pte` graphs from `getExternalFilesDir(null)`. These
 * voices are valid iff production Pocket is installed AND the `.pte` files are
 * pushed.
 */
object PocketEtVoiceCatalog {

    /**
     * Engine name. The `-et` suffix marks the ExecuTorch backend; production
     * Pocket uses [PocketVoiceCatalog.ENGINE] (onnxruntime), and the clean
     * reference uses [PocketDevVoiceCatalog.ENGINE].
     */
    const val ENGINE = "pocket-tts-en-v2026_04-et"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = PocketVoiceCatalog.SAMPLE_RATE

    fun voiceId(displayName: String): String = "$ENGINE:$displayName"

    val voices: List<VoiceMeta> = PocketVoiceCatalog.voices.map { v ->
        VoiceMeta(
            id = voiceId(v.displayName),
            engine = ENGINE,
            displayName = "${v.displayName} (ET)",
            languageCode = LANGUAGE,
            sampleRate = SAMPLE_RATE,
            gender = v.gender,
            isInstalled = false,
        )
    }
}
