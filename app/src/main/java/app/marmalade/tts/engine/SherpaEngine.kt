package app.marmalade.tts.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared base for engines wrapping Sherpa-ONNX's [OfflineTts].
 *
 * KittenEngine and KokoroEngine were ~90% identical through v0.1.16: the
 * lifecycle (load lock, lazy ensureModelLoaded, release), the synthesize
 * coroutine entrypoint, the FloatArray → PCM16 conversion, and the
 * sample-rate fallback were copy-pasted across both. The only meaningful
 * divergence was the model-specific [OfflineTtsModelConfig] block and the
 * per-engine speaker-id map. This base owns the shared machinery; the
 * subclasses contribute exactly the engine-specific bits.
 *
 * Engine-as-plugin architecture (both engines): model files are **not**
 * bundled with the APK. They live under `${filesDir}/engines/<name>/`
 * after the user opts in via the onboarding flow or Settings → Engines.
 * [app.marmalade.tts.install.EngineInstaller] downloads + extracts the
 * files; this class only reads them.
 *
 * Why on-disk instead of bundled in the APK:
 *  - Cuts the default install dramatically (Kokoro alone is ~98 MB
 *    compressed; bundling both engines would more than double the APK).
 *  - Engines are opt-in (user chooses in onboarding) — they only
 *    download what they want, on the network they choose, when they
 *    choose.
 *  - The GPL'd espeak-ng-data only lands on devices whose users have
 *    explicitly accepted it during install. The default install stays
 *    MIT/Apache only.
 *
 * Thread-safety: synthesis is gated by a coarse `synchronized` block on
 * a per-subclass [loadLock]. TTS is CPU-bound and serial inside
 * `OfflineTts.generate` anyway — there is no benefit to fine-grained
 * locking. Each subclass MUST own its own lock instance; sharing the
 * lock would serialise unrelated engines.
 */
abstract class SherpaEngine(
    protected val ctx: Context,
) : TtsEngine {

    // -- subclass-supplied configuration --------------------------------------

    /** Engine identifier matched against `VoiceMeta.engine` and used as the install dir name. */
    abstract override val engineName: String

    /**
     * Acoustic model filename inside the engine directory. Both engines
     * currently ship `model.int8.onnx`, but keeping this abstract leaves
     * room for future engines with a different naming convention without
     * widening the base class.
     */
    protected abstract val modelFileName: String

    /**
     * Build the engine-specific [OfflineTtsModelConfig] (the part of the
     * Sherpa-ONNX config that differs between Kitten and Kokoro: each
     * engine has its own typed model-config struct in the Sherpa-ONNX API).
     */
    protected abstract fun buildModelConfig(modelDir: File): OfflineTtsModelConfig

    /**
     * Map a voice ID (e.g. `"kokoro:af_bella"`) to a Sherpa-ONNX speaker
     * index in the engine's `voices.bin`. The subclass owns this map
     * because the ordering is engine-specific (Kitten and Kokoro pack
     * voices into `voices.bin` in different sequences).
     */
    protected abstract fun speakerIdFor(voiceId: String): Int

    /**
     * Documented default sample rate for the engine, used when no model
     * is yet loaded. The system-TTS callback path has to declare a sample
     * rate before any synthesis happens, so we can't always wait for the
     * live model handle.
     */
    protected abstract val defaultSampleRate: Int

    // -- shared state ---------------------------------------------------------

    @Volatile
    protected var tts: OfflineTts? = null

    /**
     * Per-subclass load lock. MUST be an `Any()` declared in the subclass
     * (or this base) — never share an object between subclasses, that
     * would needlessly serialise different engines.
     */
    protected val loadLock = Any()

    /** Where the installer puts engine assets on disk. */
    private val engineDir: File get() = File(ctx.filesDir, "engines/$engineName")

    /**
     * Sample rate the loaded engine emits, in Hz. Reads from the live
     * Sherpa-ONNX handle once the model is loaded; falls back to the
     * subclass-supplied default otherwise. The fallback matters for the
     * system-TTS callback path, which has to declare a sample rate before
     * any synthesis happens.
     */
    override val sampleRate: Int get() = tts?.sampleRate() ?: defaultSampleRate

    // -- lifecycle ------------------------------------------------------------

    /**
     * Cheap check that the installer has placed every required file on
     * disk. Used by UI to gate "Speak" / "Preview" buttons and to drive
     * the install/uninstall affordances.
     */
    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        val required = listOf(modelFileName, VOICES_FILE, TOKENS_FILE)
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
    override fun ensureModelLoaded() {
        if (tts != null) return
        synchronized(loadLock) {
            if (tts != null) return

            if (!isInstalled()) {
                throw EngineNotInstalledException(engineName)
            }

            val modelCfg = buildModelConfig(engineDir)
            val ttsCfg = OfflineTtsConfig(model = modelCfg)

            try {
                Log.i(tag(), "Loading $engineName TTS model from $engineDir …")
                // Loading from filesDir, not assets — pass null AssetManager
                // so Sherpa-ONNX uses the absolute paths we provided.
                tts = OfflineTts(null, ttsCfg)
                Log.i(tag(), "$engineName TTS model loaded (sampleRate=${tts?.sampleRate()})")
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Failed to initialise Sherpa-ONNX OfflineTts for $engineName: ${t.message}",
                    t,
                )
            }
        }
    }

    /**
     * Synthesize [text] using the given [voiceId] (e.g. `"kokoro:af_bella"`
     * or `"kitten:Bella"`). Returns 16-bit signed PCM, mono.
     *
     * The work runs on `Dispatchers.Default` because synthesis is CPU-
     * bound — the caller can `runBlocking { synthesize(...) }` from the
     * system-TTS worker thread without monopolising it.
     *
     * @param speed length-scale style; 1.0 = native pace, >1 = faster.
     */
    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
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
    override fun release() {
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

    /** Log tag — derived from the subclass's simple name so logcat groups stay legible. */
    private fun tag(): String = this::class.java.simpleName

    companion object {
        // File names inside the engine directory. Shared between Kitten
        // (`kitten-nano-en-v0_8-int8.tar.bz2`) and Kokoro
        // (`kokoro-int8-en-v0_19.tar.bz2`) — both bundle the same
        // sherpa-onnx layout. Keep in sync with EngineInstaller's
        // extraction logic.
        const val VOICES_FILE = "voices.bin"
        const val TOKENS_FILE = "tokens.txt"
        const val DATA_DIR = "espeak-ng-data"
    }
}
