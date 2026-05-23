package app.marmalade.tts.engine

import android.content.Context
import app.marmalade.tts.data.KittenVoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synthesised audio returned by [SherpaEngine.synthesize].
 *
 * `pcm` is 16-bit signed PCM, mono, in little-endian native order.
 * Sherpa-ONNX hands us `FloatArray` in -1..1; we clamp and convert to
 * `ShortArray` here so the system TTS callback path (which expects PCM16)
 * has no further work to do.
 */
data class SynthAudio(val pcm: ShortArray, val sampleRate: Int) {

    // Generated equality so test fixtures can compare audio buffers directly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthAudio) return false
        return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int =
        31 * pcm.contentHashCode() + sampleRate
}

/**
 * Signalled when the user hasn't installed (downloaded) the engine yet.
 * The UI catches this and routes to the Engines screen / onboarding flow.
 *
 * Extends [UnsupportedOperationException] so existing call sites that catch
 * the broader type continue to work; new code should match against this
 * specific subtype to distinguish "not installed" from other failures.
 */
class EngineNotInstalledException(engineName: String) :
    UnsupportedOperationException(
        "Engine '$engineName' is not installed. " +
            "Open Settings → Engines to install it.",
    )

/**
 * Kitten TTS engine wrapping Sherpa-ONNX's [com.k2fsa.sherpa.onnx.OfflineTts]
 * in Kitten mode. See [SherpaEngine] for the shared lifecycle, loading,
 * and synthesis machinery; this subclass only contributes the engine-
 * specific bits (model config + speaker map).
 */
// `open` exists only to let MarmaladeTtsServiceTest substitute a JVM-safe
// fake engine (the real one calls into Sherpa-ONNX JNI which won't load in
// Robolectric). See app/src/test/java/.../service/MarmaladeTtsServiceTest.kt.
@Singleton
open class KittenEngine @Inject constructor(
    @ApplicationContext ctx: Context,
) : SherpaEngine(ctx) {

    override val engineName: String = ENGINE_NAME
    override val modelFileName: String = MODEL_FILE
    override val defaultSampleRate: Int = KittenVoiceCatalog.SAMPLE_RATE

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

    /**
     * Map a voice ID to a Sherpa-ONNX speaker index. Unknown IDs fall
     * back to the default voice — the catalog is the source of truth for
     * what's installable, and the service has already validated.
     */
    override fun speakerIdFor(voiceId: String): Int {
        val displayName = voiceId.substringAfter(':', voiceId)
        return SPEAKER_ID_BY_NAME[displayName]
            ?: SPEAKER_ID_BY_NAME[KittenVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }

    companion object {
        /** Engine identifier matched against `VoiceMeta.engine` and used as the install dir name. */
        const val ENGINE_NAME = "kitten"

        // Matches Sherpa-ONNX's released `kitten-nano-en-v0_8-int8.tar.bz2`
        // layout — keep in sync with EngineInstaller's extraction logic.
        private const val MODEL_FILE = "model.int8.onnx"

        /**
         * Friendly-name → Sherpa-ONNX speaker index.
         *
         * The friendly names match upstream Kitten's `voice_aliases` map
         * (see `KittenML/kitten-tts-nano-0.8-int8/config.json`):
         *
         *     Bella  → expr-voice-2-f       Jasper → expr-voice-2-m
         *     Luna   → expr-voice-3-f       Bruno  → expr-voice-3-m
         *     Rosie  → expr-voice-4-f       Hugo   → expr-voice-4-m
         *     Kiki   → expr-voice-5-f       Leo    → expr-voice-5-m
         *
         * Sherpa-ONNX's `voices.bin` orders speakers MALE-FIRST in each pair
         * (see `scripts/kitten-tts/v0_8/generate_voices_bin.py` in the
         * sherpa-onnx repo):
         *
         *     0: expr-voice-2-m   1: expr-voice-2-f
         *     2: expr-voice-3-m   3: expr-voice-3-f
         *     4: expr-voice-4-m   5: expr-voice-4-f
         *     6: expr-voice-5-m   7: expr-voice-5-f
         *
         * v0.1.0–v0.1.3 hard-coded a female-first ordering (`Bella → 0,
         * Jasper → 1, ...`), so every voice was gender-swapped against its
         * label. v0.1.4 fixes this.
         */
        private val SPEAKER_ID_BY_NAME = mapOf(
            "Jasper" to 0,  // expr-voice-2-m
            "Bella" to 1,   // expr-voice-2-f
            "Bruno" to 2,   // expr-voice-3-m
            "Luna" to 3,    // expr-voice-3-f
            "Hugo" to 4,    // expr-voice-4-m
            "Rosie" to 5,   // expr-voice-4-f
            "Leo" to 6,     // expr-voice-5-m
            "Kiki" to 7,    // expr-voice-5-f
        )
    }
}
