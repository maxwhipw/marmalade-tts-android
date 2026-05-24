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
 *
 * v0.1.20 swapped the int8-v1.0 export for the fp32 `kokoro-multi-lang-v1_0`
 * bundle. The int8-v1.0 export turned out to be an unblessed power-user
 * artefact: PR k2-fsa/sherpa-onnx#2137 added the file but the official APK
 * build script (`scripts/apk/generate-tts-apk-script.py`) never picked it
 * up — the team did a second int8 export (v1.1) and shipped that instead.
 * Naive dynamic int8 quantisation on a vocoder produces the "tinny /
 * staticy" output we heard. fp32 is ~2.6x larger on disk but renders the
 * voices the model was trained to produce.
 *
 * Also bumped `numThreads` from 2 → 4. The Tensor G3 SoC (Pixel 8a) has
 * 4 P-cores (Cortex-A715); inference benefits from filling them.
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
        //  lengthScale). For the multi-lang v1.0 bundle the official
        // sherpa-onnx Android sample (Example 10 in TtsEngine.kt) sets:
        //   - lexicon = "<modelDir>/lexicon-us-en.txt,<modelDir>/lexicon-zh.txt"
        //   - lang    = "en"   (primary; English — ISO 639-1)
        //   - lang2   = "zh"   (secondary; Mandarin)
        //
        // CRITICAL #1: setting `lang = ""` silently disables the
        // lexicon-driven phonemiser and falls back to pure espeak — that's
        // what produced the tinny / staticy v0.1.19 output. With a valid
        // 2-letter code the English lexicon is the primary route and
        // espeak is the OOV fallback, which is the path the model was
        // trained against.
        //
        // CRITICAL #2: the lang code must be ISO 639-1 (`"en"`), not
        // ISO 639-3 (`"eng"`). The Kotlin sample app's Example 10 has a
        // misleading `"eng"` comment, but Sherpa-ONNX's C++ source
        // (offline-tts-kokoro-model-config.h) and the official APK build
        // script both use the 2-letter form. Passing `"eng"` lands in an
        // unknown-language branch and crashes inside generate.
        //
        // The Kotlin API of OfflineTtsKokoroModelConfig in 1.13.2 doesn't
        // expose a `lang2` field directly. Sherpa-ONNX's multi-lang Kokoro
        // C++ implementation (`KokoroMultiLangLexicon`) routes by text
        // character set anyway — ASCII to lexicon-us-en, CJK to
        // lexicon-zh — so omitting lang2 doesn't break Mandarin via voice
        // selection; it just means we don't get the Chinese rule-FST
        // text normalisation (handled separately at the outer config
        // level — TODO follow-up).
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
            lang = "en",
            dictDir = "",
            lengthScale = 1.0f,
        )
        return OfflineTtsModelConfig(
            kokoro = kokoroCfg,
            // numThreads = 4 fills the Tensor G3 P-cluster (4× Cortex-A715).
            // XNNPACK/NNAPI providers are available in the AAR (Apache-2.0,
            // verified via upstream source); we stay on `"cpu"` for v0.1.20
            // and revisit acceleration as a separate change so any audio
            // regression is unambiguously attributable.
            numThreads = 4,
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

        // Matches Sherpa-ONNX's released `kokoro-multi-lang-v1_0.tar.bz2`
        // layout (fp32 — note no `.int8` infix). The voices.bin, tokens, and
        // lexicons are byte-identical to the int8-v1.0 bundle; only the
        // model weights changed. See class doc for why we left int8-v1.0.
        private const val MODEL_FILE = "model.onnx"

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
