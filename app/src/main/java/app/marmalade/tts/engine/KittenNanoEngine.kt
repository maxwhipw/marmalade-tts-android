package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kitten Nano v0.8 engine. Uses Sherpa-ONNX's `kitten-nano-en-v0_8-fp32`
 * bundle (~61 MB compressed, ~74 MB on-disk) — 15M-parameter fp32 model
 * with 8 English voices.
 *
 * v0.1.22 switched from int8 to fp32 because the dynamic int8
 * quantisation (no per-channel calibration) produced audibly grainy
 * output. Same 15M-parameter model, ~2x the download. Kitten Mini
 * (80M params, [KittenMiniEngine]) ships alongside as a quality upgrade.
 */
@Singleton
open class KittenNanoEngine @Inject constructor(
    @ApplicationContext ctx: Context,
) : SherpaEngine(ctx) {

    override val engineName: String = ENGINE_NAME
    override val modelFileName: String = MODEL_FILE
    override val defaultSampleRate: Int = KittenNanoVoiceCatalog.SAMPLE_RATE

    override fun buildModelConfig(modelDir: File): OfflineTtsModelConfig {
        val kittenCfg = OfflineTtsKittenModelConfig(
            model = File(modelDir, MODEL_FILE).absolutePath,
            voices = File(modelDir, VOICES_FILE).absolutePath,
            tokens = File(modelDir, TOKENS_FILE).absolutePath,
            dataDir = File(modelDir, DATA_DIR).absolutePath,
            lengthScale = 1.0f,
        )
        return OfflineTtsModelConfig(
            kitten = kittenCfg,
            numThreads = 2,
            debug = false,
            provider = "cpu",
        )
    }

    override fun speakerIdFor(voiceId: String): Int {
        val displayName = voiceId.substringAfter(':', voiceId)
        return KittenSpeakers.SPEAKER_ID_BY_NAME[displayName]
            ?: KittenSpeakers.SPEAKER_ID_BY_NAME[KittenNanoVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }

    companion object {
        const val ENGINE_NAME = "kitten-nano-v0_8"
        private const val MODEL_FILE = "model.fp32.onnx"
    }
}
