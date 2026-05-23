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
 *
 * v0.1.19 switched the underlying bundle from `kokoro-int8-en-v0_19`
 * (English only, 11 voices) to `kokoro-int8-multi-lang-v1_0` (53 voices
 * across American + British English, Spanish, French, Hindi, Italian,
 * Japanese, Brazilian Portuguese, and Mandarin). Voice/language
 * orthogonality is preserved at the Sherpa-ONNX level: any voice will
 * speak any input text; the lexicon path (us-en / zh) is selected by the
 * runtime based on the text's character set, and prosody comes from the
 * voice embedding. That's why a Japanese voice speaking English produces
 * Japanese-accented English — the original ask for v0.1.19.
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
        //  lengthScale). For the multi-lang v1.0 bundle:
        //   - `lexicon` is a comma-separated list of absolute paths to
        //     the lexicon files inside the bundle. The runtime routes
        //     text to a lexicon based on character set (ASCII → us-en,
        //     CJK → zh); both must be configured or only the first
        //     ASCII voice would work.
        //   - `lang` left empty lets the per-voice natural language drive
        //     prosody. Forcing it to a specific value would override the
        //     voice's natural pronunciation for all text.
        //   - `dictDir` left empty: Sherpa-ONNX locates the jieba `dict/`
        //     directory relative to the lexicon paths on its own. The
        //     official `scripts/apk/generate-tts-apk-script.py` confirms
        //     the field is unset for v1.0 / v1.1 multi-lang bundles.
        val lexicon = listOf(
            File(modelDir, "lexicon-us-en.txt").absolutePath,
            File(modelDir, "lexicon-zh.txt").absolutePath,
        ).joinToString(",")

        val kokoroCfg = OfflineTtsKokoroModelConfig(
            model = File(modelDir, MODEL_FILE).absolutePath,
            voices = File(modelDir, VOICES_FILE).absolutePath,
            tokens = File(modelDir, TOKENS_FILE).absolutePath,
            dataDir = File(modelDir, DATA_DIR).absolutePath,
            lexicon = lexicon,
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

        // Matches Sherpa-ONNX's released `kokoro-int8-multi-lang-v1_0.tar.bz2`
        // layout — same model filename as the v0.19 English-only bundle, but
        // larger because it carries embeddings for all 53 voices and the
        // multi-lingual front-end weights.
        private const val MODEL_FILE = "model.int8.onnx"

        /**
         * Display name → Sherpa-ONNX speaker index.
         *
         * Order MUST match `scripts/kokoro/v1.0/generate_voices_bin.py`'s
         * `id2speaker` map in the sherpa-onnx repo (53 entries, sid 0–52).
         * The matching catalog in [KokoroVoiceCatalog.voices] is the
         * source of truth for the seed; this map is the source of truth
         * for the runtime speaker selection. They must stay in lockstep —
         * the unit test in `KokoroVoiceCatalogTest` pins the relationship.
         */
        private val SPEAKER_ID_BY_NAME = mapOf(
            // American female (11)
            "af_alloy" to 0,
            "af_aoede" to 1,
            "af_bella" to 2,
            "af_heart" to 3,
            "af_jessica" to 4,
            "af_kore" to 5,
            "af_nicole" to 6,
            "af_nova" to 7,
            "af_river" to 8,
            "af_sarah" to 9,
            "af_sky" to 10,
            // American male (9)
            "am_adam" to 11,
            "am_echo" to 12,
            "am_eric" to 13,
            "am_fenrir" to 14,
            "am_liam" to 15,
            "am_michael" to 16,
            "am_onyx" to 17,
            "am_puck" to 18,
            "am_santa" to 19,
            // British female (4)
            "bf_alice" to 20,
            "bf_emma" to 21,
            "bf_isabella" to 22,
            "bf_lily" to 23,
            // British male (4)
            "bm_daniel" to 24,
            "bm_fable" to 25,
            "bm_george" to 26,
            "bm_lewis" to 27,
            // Spanish (2)
            "ef_dora" to 28,
            "em_alex" to 29,
            // French (1)
            "ff_siwis" to 30,
            // Hindi (4)
            "hf_alpha" to 31,
            "hf_beta" to 32,
            "hm_omega" to 33,
            "hm_psi" to 34,
            // Italian (2)
            "if_sara" to 35,
            "im_nicola" to 36,
            // Japanese (5)
            "jf_alpha" to 37,
            "jf_gongitsune" to 38,
            "jf_nezumi" to 39,
            "jf_tebukuro" to 40,
            "jm_kumo" to 41,
            // Brazilian Portuguese (3)
            "pf_dora" to 42,
            "pm_alex" to 43,
            "pm_santa" to 44,
            // Mandarin female (4)
            "zf_xiaobei" to 45,
            "zf_xiaoni" to 46,
            "zf_xiaoxiao" to 47,
            "zf_xiaoyi" to 48,
            // Mandarin male (4)
            "zm_yunjian" to 49,
            "zm_yunxi" to 50,
            "zm_yunxia" to 51,
            "zm_yunyang" to 52,
        )
    }
}
