package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kitten Mini v0.8 engine. Uses Sherpa-ONNX's `kitten-mini-en-v0_8`
 * bundle (~64 MB compressed, ~95 MB on-disk) — 80M-parameter model
 * with mixed-precision quantisation (fp32 + fp16 + selective int8/uint8,
 * a deliberate quantize-aware design, not the blanket dynamic int8 that
 * sank nano-int8).
 *
 * Same 8 voice handles as [KittenNanoEngine] but different audio
 * characteristics — the underlying model has ~5.3x more parameters,
 * giving marginal but audible quality lift per the pre-ship A/B.
 *
 * Model filename here is bare `model.onnx` (no `.fp32` / `.int8`
 * infix) because that's how upstream packages the mini variant — it
 * only ships in one precision.
 */
@Singleton
open class KittenMiniEngine @Inject constructor(
    @ApplicationContext ctx: Context,
) : SherpaEngine(ctx) {

    override val engineName: String = ENGINE_NAME
    override val modelFileName: String = MODEL_FILE
    override val defaultSampleRate: Int = KittenMiniVoiceCatalog.SAMPLE_RATE

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
            ?: KittenSpeakers.SPEAKER_ID_BY_NAME[KittenMiniVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }

    companion object {
        const val ENGINE_NAME = "kitten-mini-v0_8"
        private const val MODEL_FILE = "model.onnx"
    }
}
