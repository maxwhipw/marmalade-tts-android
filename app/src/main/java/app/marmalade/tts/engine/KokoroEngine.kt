package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.KokoroVoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kokoro TTS engine wrapping Sherpa-ONNX's [com.k2fsa.sherpa.onnx.OfflineTts]
 * in Kokoro mode. See [SherpaEngine] for the shared lifecycle, loading,
 * and synthesis machinery; this subclass only contributes the engine-
 * specific bits (model config + speaker map).
 */
// `open` exists only to let MarmaladeTtsServiceTest substitute a JVM-safe
// fake engine (the real one calls into Sherpa-ONNX JNI which won't load in
// Robolectric). See app/src/test/java/.../service/MarmaladeTtsServiceTest.kt.
@Singleton
open class KokoroEngine @Inject constructor(
    @ApplicationContext ctx: Context,
) : SherpaEngine(ctx) {

    override val engineName: String = ENGINE_NAME
    override val modelFileName: String = MODEL_FILE
    override val defaultSampleRate: Int = KokoroVoiceCatalog.SAMPLE_RATE

    override fun buildModelConfig(modelDir: File): OfflineTtsModelConfig {
        // OfflineTtsKokoroModelConfig in Sherpa-ONNX 1.13.2 takes
        // (model, voices, tokens, dataDir, lexicon, lang, dictDir,
        //  lengthScale). For the English-only v0.19 port the lexicon,
        // lang, and dictDir fields are unused — leave them as the
        // empty-string default the no-arg constructor establishes.
        val kokoroCfg = OfflineTtsKokoroModelConfig(
            model = File(modelDir, MODEL_FILE).absolutePath,
            voices = File(modelDir, VOICES_FILE).absolutePath,
            tokens = File(modelDir, TOKENS_FILE).absolutePath,
            dataDir = File(modelDir, DATA_DIR).absolutePath,
            lexicon = "",
            lang = "",
            dictDir = "",
            lengthScale = 1.0f,
        )
        return OfflineTtsModelConfig(
            kokoro = kokoroCfg,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
    }

    /**
     * Map a voice ID to a Sherpa-ONNX speaker index. Unknown IDs fall
     * back to the default voice — the catalog is the source of truth for
     * what's installable, and the service has already validated.
     */
    override fun speakerIdFor(voiceId: String): Int {
        val displayName = voiceId.substringAfter(':', voiceId)
        return SPEAKER_ID_BY_NAME[displayName]
            ?: SPEAKER_ID_BY_NAME[KokoroVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }

    companion object {
        /** Engine identifier matched against `VoiceMeta.engine` and used as the install dir name. */
        const val ENGINE_NAME = "kokoro"

        // Matches Sherpa-ONNX's released `kokoro-int8-en-v0_19.tar.bz2`
        // layout — keep in sync with EngineInstaller's extraction logic.
        private const val MODEL_FILE = "model.int8.onnx"

        /**
         * Friendly-name → Sherpa-ONNX speaker index.
         *
         * Sherpa-ONNX's `voices.bin` for Kokoro v0.19 packs the 11 English
         * voices in alphabetical order by upstream voice key (see
         * `scripts/kokoro/v0_19/run.sh` and the `voices` list in
         * `generate_voices_bin.py` in the sherpa-onnx repo). The order
         * below mirrors that file, and matches [KokoroVoiceCatalog.voices].
         *
         * If the catalog list is ever reordered, this map must be updated
         * in lockstep — the unit test in `KokoroVoiceCatalogTest` pins
         * the relationship.
         */
        private val SPEAKER_ID_BY_NAME = mapOf(
            "af" to 0,
            "af_bella" to 1,
            "af_nicole" to 2,
            "af_sarah" to 3,
            "af_sky" to 4,
            "am_adam" to 5,
            "am_michael" to 6,
            "bf_emma" to 7,
            "bf_isabella" to 8,
            "bm_george" to 9,
            "bm_lewis" to 10,
        )
    }
}
