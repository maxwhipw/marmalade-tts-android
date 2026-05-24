package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kokoro v1.1 multi-lang engine. Uses Sherpa-ONNX's `kokoro-multi-lang-v1_1`
 * fp32 bundle (~348 MB compressed) with 103 voices — only 3 English
 * (`af_maple`, `af_sol`, `bf_vale`) and 100 Mandarin (`zf_001..zm_100`).
 *
 * v1.1 is a Mandarin-specialist variant. Pre-ship A/B showed v1.0 has
 * noticeably better English audio quality, so v1.0 stays the default
 * for English-primary use. v1.1 ships alongside as an opt-in for users
 * who want the extensive Mandarin voice catalog. The two engines
 * install independently and have completely disjoint voice IDs.
 *
 * Same `lang = "en-us"` and dict_dir conventions as v1.0 — see
 * [KokoroV10Engine] for the espeak-routing rationale.
 */
@Singleton
open class KokoroV11Engine @Inject constructor(
    @ApplicationContext ctx: Context,
) : SherpaEngine(ctx) {

    override val engineName: String = ENGINE_NAME
    override val modelFileName: String = MODEL_FILE
    override val defaultSampleRate: Int = KokoroV11VoiceCatalog.SAMPLE_RATE

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
            ?: SPEAKER_ID_BY_NAME[KokoroV11VoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }

    companion object {
        const val ENGINE_NAME = "kokoro-v1_1"
        private const val MODEL_FILE = "model.onnx"

        /**
         * Display name → Sherpa-ONNX speaker index for v1.1's voices.bin.
         * v1.1 has a completely different voice catalog from v1.0 — the
         * sids do not transfer. Built lazily so the file isn't 100+ lines
         * of literal map entries.
         */
        private val SPEAKER_ID_BY_NAME: Map<String, Int> = buildMap {
            // 0-2  English
            put("af_maple", 0)
            put("af_sol", 1)
            put("bf_vale", 2)
            // 3-57  Mandarin female (zf_NNN, non-sequential)
            val zfHandles = listOf(
                "001", "002", "003", "004", "005", "006", "007", "008",
                "017", "018", "019", "021", "022", "023", "024", "026",
                "027", "028", "032", "036", "038", "039", "040", "042",
                "043", "044", "046", "047", "048", "049", "051", "059",
                "060", "067", "070", "071", "072", "073", "074", "075",
                "076", "077", "078", "079", "083", "084", "085", "086",
                "087", "088", "090", "092", "093", "094", "099",
            )
            zfHandles.forEachIndexed { i, h -> put("zf_$h", 3 + i) }
            // 58-102  Mandarin male (zm_NNN, non-sequential)
            val zmHandles = listOf(
                "009", "010", "011", "012", "013", "014", "015", "016",
                "020", "025", "029", "030", "031", "033", "034", "035",
                "037", "041", "045", "050", "052", "053", "054", "055",
                "056", "057", "058", "061", "062", "063", "064", "065",
                "066", "068", "069", "080", "081", "082", "089", "091",
                "095", "096", "097", "098", "100",
            )
            zmHandles.forEachIndexed { i, h -> put("zm_$h", 58 + i) }
        }
    }
}
