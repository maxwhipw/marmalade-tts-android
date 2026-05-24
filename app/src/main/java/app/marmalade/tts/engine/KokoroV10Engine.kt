package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kokoro v1.0 multi-lang engine. Uses Sherpa-ONNX's `kokoro-multi-lang-v1_0`
 * fp32 bundle (~333 MB compressed) with 53 voices across American +
 * British English, Spanish, French, Hindi, Italian, Japanese, Brazilian
 * Portuguese, and Mandarin.
 *
 * This is the recommended default Kokoro variant for English-primary use.
 * v1.1 is shipped alongside as a Mandarin-specialist alternative — same
 * underlying architecture but with a completely different voice catalog
 * (3 English + 100 Mandarin). They install independently.
 *
 * `lang = "en-us"` (not bare `"en"`): sherpa-onnx multi-lang Kokoro routes
 * English text straight through espeak-ng for phonemisation; bare "en"
 * makes espeak fall back to British defaults (drops rhotic r, "Lord" →
 * "lawd"). v0.1.21 settled this — see project memory
 * sherpa-kokoro-lexicon-unused for the full diagnosis.
 */
@Singleton
open class KokoroV10Engine @Inject constructor(
    @ApplicationContext ctx: Context,
) : SherpaEngine(ctx) {

    override val engineName: String = ENGINE_NAME
    override val modelFileName: String = MODEL_FILE
    override val defaultSampleRate: Int = KokoroV10VoiceCatalog.SAMPLE_RATE

    override fun buildModelConfig(modelDir: File): OfflineTtsModelConfig {
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
            lang = "en-us",
            dictDir = File(modelDir, "dict").absolutePath,
            lengthScale = 1.0f,
        )
        return OfflineTtsModelConfig(
            kokoro = kokoroCfg,
            numThreads = 4,
            debug = false,
            provider = "cpu",
        )
    }

    override fun speakerIdFor(voiceId: String): Int {
        val displayName = voiceId.substringAfter(':', voiceId)
        return SPEAKER_ID_BY_NAME[displayName]
            ?: SPEAKER_ID_BY_NAME[KokoroV10VoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }

    companion object {
        const val ENGINE_NAME = "kokoro-v1_0"
        private const val MODEL_FILE = "model.onnx"

        /**
         * Display name → Sherpa-ONNX speaker index for v1.0's voices.bin.
         * Order matches `scripts/kokoro/v1.0/generate_voices_bin.py` in the
         * sherpa-onnx repo (53 entries, sid 0-52). Must stay in lockstep
         * with [KokoroV10VoiceCatalog.voices].
         */
        private val SPEAKER_ID_BY_NAME = mapOf(
            // American female (11)
            "af_alloy" to 0, "af_aoede" to 1, "af_bella" to 2,
            "af_heart" to 3, "af_jessica" to 4, "af_kore" to 5,
            "af_nicole" to 6, "af_nova" to 7, "af_river" to 8,
            "af_sarah" to 9, "af_sky" to 10,
            // American male (9)
            "am_adam" to 11, "am_echo" to 12, "am_eric" to 13,
            "am_fenrir" to 14, "am_liam" to 15, "am_michael" to 16,
            "am_onyx" to 17, "am_puck" to 18, "am_santa" to 19,
            // British female (4)
            "bf_alice" to 20, "bf_emma" to 21, "bf_isabella" to 22, "bf_lily" to 23,
            // British male (4)
            "bm_daniel" to 24, "bm_fable" to 25, "bm_george" to 26, "bm_lewis" to 27,
            // Spanish (2)
            "ef_dora" to 28, "em_alex" to 29,
            // French (1)
            "ff_siwis" to 30,
            // Hindi (4)
            "hf_alpha" to 31, "hf_beta" to 32, "hm_omega" to 33, "hm_psi" to 34,
            // Italian (2)
            "if_sara" to 35, "im_nicola" to 36,
            // Japanese (5)
            "jf_alpha" to 37, "jf_gongitsune" to 38, "jf_nezumi" to 39,
            "jf_tebukuro" to 40, "jm_kumo" to 41,
            // Brazilian Portuguese (3)
            "pf_dora" to 42, "pm_alex" to 43, "pm_santa" to 44,
            // Mandarin female (4)
            "zf_xiaobei" to 45, "zf_xiaoni" to 46, "zf_xiaoxiao" to 47, "zf_xiaoyi" to 48,
            // Mandarin male (4)
            "zm_yunjian" to 49, "zm_yunxi" to 50, "zm_yunxia" to 51, "zm_yunyang" to 52,
        )
    }
}
