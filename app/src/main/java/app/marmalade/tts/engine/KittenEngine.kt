package app.marmalade.tts.engine

import android.content.Context
import android.util.Log
import app.marmalade.tts.data.KittenVoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Synthesised audio returned by [KittenEngine.synthesize].
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
 * Kitten TTS engine wrapping Sherpa-ONNX's [OfflineTts] in Kitten mode.
 *
 * Engine-as-plugin architecture: the model files are **not** bundled with
 * the APK. They live under `${filesDir}/engines/kitten/` after the user
 * opts in via the onboarding flow or Settings → Engines. The
 * [EngineInstaller] (separate class) downloads + extracts the files;
 * this class only reads them.
 *
 * Expected layout under `${filesDir}/engines/kitten/`:
 *
 * ```
 * model.fp16.onnx     ~25 MB, the acoustic model
 * voices.bin          speaker embedding table (8 voices)
 * tokens.txt          phoneme vocabulary
 * espeak-ng-data/     phonemizer data (~19 MB, ~355 files)
 * ```
 *
 * Why on-disk instead of bundled in the APK:
 *  - Cuts the default install from ~140 MB to ~115 MB.
 *  - Engines are opt-in (user chooses in onboarding) — they only download
 *    what they want, on the network they choose, when they choose.
 *  - The GPL'd espeak-ng-data only lands on devices whose users have
 *    explicitly accepted it during install. The default install stays
 *    MIT/Apache only.
 *
 * Thread-safety: synthesis is gated by a coarse `synchronized` block. TTS
 * is CPU-bound and serial inside `OfflineTts.generate` anyway — there is
 * no benefit to fine-grained locking.
 */
@Singleton
class KittenEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    companion object {
        private const val TAG = "KittenEngine"

        /** Engine identifier matched against `VoiceMeta.engine` and used as the install dir name. */
        const val ENGINE_NAME = "kitten"

        // File names inside the engine directory. These match Sherpa-ONNX's
        // released `kitten-nano-en-v0_1-fp16.tar.bz2` layout — keep in sync
        // with EngineInstaller's extraction logic.
        private const val MODEL_FILE = "model.fp16.onnx"
        private const val VOICES_FILE = "voices.bin"
        private const val TOKENS_FILE = "tokens.txt"
        private const val DATA_DIR = "espeak-ng-data"

        /** Speaker IDs follow the catalog order — keep in sync with KittenVoiceCatalog. */
        private val SPEAKER_ID_BY_NAME = mapOf(
            "Bella" to 0,
            "Jasper" to 1,
            "Luna" to 2,
            "Bruno" to 3,
            "Rosie" to 4,
            "Hugo" to 5,
            "Kiki" to 6,
            "Leo" to 7,
        )
    }

    /** Native sample rate the engine emits. Constant for kitten-nano = 24 kHz. */
    val sampleRate: Int get() = KittenVoiceCatalog.SAMPLE_RATE

    /** Where the installer puts engine assets on disk. */
    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")

    @Volatile
    private var tts: OfflineTts? = null

    private val loadLock = Any()

    /**
     * Cheap check that the installer has placed every required file on
     * disk. Used by UI to gate "Speak" / "Preview" buttons and to drive
     * the install/uninstall affordances.
     */
    fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        val required = listOf(MODEL_FILE, VOICES_FILE, TOKENS_FILE)
        for (name in required) {
            if (!File(engineDir, name).isFile) return false
        }
        val dataDir = File(engineDir, DATA_DIR)
        if (!dataDir.isDirectory) return false
        return (dataDir.listFiles()?.isNotEmpty() == true)
    }

    /**
     * Lazy. Idempotent. Safe to call from any thread.
     *
     * @throws EngineNotInstalledException if the engine bundle hasn't
     *   been downloaded yet. The UI catches this and steers the user
     *   toward Settings → Engines.
     * @throws IllegalStateException for genuine init failures
     *   (corrupt model, JNI load error, etc.).
     */
    fun ensureModelLoaded() {
        if (tts != null) return
        synchronized(loadLock) {
            if (tts != null) return

            if (!isInstalled()) {
                throw EngineNotInstalledException(ENGINE_NAME)
            }

            val kittenCfg = OfflineTtsKittenModelConfig(
                model = File(engineDir, MODEL_FILE).absolutePath,
                voices = File(engineDir, VOICES_FILE).absolutePath,
                tokens = File(engineDir, TOKENS_FILE).absolutePath,
                dataDir = File(engineDir, DATA_DIR).absolutePath,
                lengthScale = 1.0f,
            )

            val modelCfg = OfflineTtsModelConfig(
                kitten = kittenCfg,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )

            val ttsCfg = OfflineTtsConfig(model = modelCfg)

            try {
                Log.i(TAG, "Loading Kitten TTS model from $engineDir …")
                // Loading from filesDir, not assets — pass null AssetManager
                // so Sherpa-ONNX uses the absolute paths we provided.
                tts = OfflineTts(null, ttsCfg)
                Log.i(TAG, "Kitten TTS model loaded (sampleRate=${tts?.sampleRate()})")
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Failed to initialise Sherpa-ONNX OfflineTts for Kitten: ${t.message}",
                    t,
                )
            }
        }
    }

    /**
     * Synthesize [text] using the given [voiceId] (e.g. `"kitten:Bella"`).
     * Returns 16-bit signed PCM, mono.
     *
     * The work runs on `Dispatchers.Default` because synthesis is CPU-
     * bound — the caller can `runBlocking { synthesize(...) }` from the
     * system-TTS worker thread without monopolising it.
     *
     * @param speed length-scale style; 1.0 = native pace, >1 = faster.
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float = 1.0f,
    ): SynthAudio = withContext(Dispatchers.Default) {
        ensureModelLoaded()
        val engine = tts ?: error("OfflineTts vanished after ensureModelLoaded() — impossible state")

        val sid = speakerIdFor(voiceId)
        val audio = engine.generate(text, sid, speed)

        SynthAudio(
            pcm = floatToPcm16(audio.samples),
            sampleRate = audio.sampleRate,
        )
    }

    /**
     * Releases the native OfflineTts handle. Safe to call multiple times.
     * Called by the installer before deleting the engine directory, and
     * by the application on shutdown.
     */
    fun release() {
        synchronized(loadLock) {
            tts?.release()
            tts = null
        }
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Convert Sherpa-ONNX float samples (-1..1) into PCM16 mono. Values
     * outside the float range are clamped — they should not occur with a
     * well-behaved model, but guard anyway since a single rogue sample
     * would otherwise wrap to a loud click.
     */
    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            // 32767 (not 32768) so the +1.0 path stays inside Short.MAX_VALUE.
            out[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return out
    }

    /**
     * Map a voice ID to a Sherpa-ONNX speaker index. Unknown IDs fall
     * back to the default voice — the catalog is the source of truth for
     * what's installable, and the service has already validated.
     */
    private fun speakerIdFor(voiceId: String): Int {
        val displayName = voiceId.substringAfter(':', voiceId)
        return SPEAKER_ID_BY_NAME[displayName]
            ?: SPEAKER_ID_BY_NAME[KittenVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }
}
