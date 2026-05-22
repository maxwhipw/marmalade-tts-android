package app.marmalade.tts.engine

import android.content.Context
import android.util.Log
import app.marmalade.tts.data.KokoroVoiceCatalog
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Service / Synthesizer ── synthesize(text, voiceId="kokoro:af_bella", speed)
//      │
//      ├── ensureModelLoaded()   (lazy, idempotent, synchronized)
//      │     │
//      │     ├── isInstalled() — check model.int8.onnx, voices.bin,
//      │     │                   tokens.txt, espeak-ng-data/ on disk
//      │     │
//      │     └── OfflineTts(null, OfflineTtsConfig {
//      │             model = OfflineTtsModelConfig {
//      │                 kokoro = OfflineTtsKokoroModelConfig {…}
//      │                 numThreads = 2, debug = false, provider = "cpu"
//      │             }
//      │         })
//      │
//      ├── speakerIdFor(voiceId)  ──► Int speaker index in voices.bin
//      │
//      ├── OfflineTts.generate(text, sid, speed)  ──► float samples + sr
//      │
//      └── floatToPcm16(samples)  ──► SynthAudio(pcm: ShortArray, sampleRate)
//
//   Errors:
//     EngineNotInstalledException — model files missing on disk
//     IllegalStateException       — corrupt model / JNI load failure
// -----------------------------------------------------------------------------

/**
 * Kokoro TTS engine wrapping Sherpa-ONNX's [OfflineTts] in Kokoro mode.
 *
 * Engine-as-plugin architecture (same shape as [KittenEngine]): the model
 * files are **not** bundled with the APK. They live under
 * `${filesDir}/engines/kokoro/` after the user opts in via the onboarding
 * flow or Settings → Engines. [app.marmalade.tts.install.EngineInstaller]
 * downloads + extracts the files; this class only reads them.
 *
 * Expected layout under `${filesDir}/engines/kokoro/`:
 *
 * ```
 * model.int8.onnx     ~128 MB, the Kokoro acoustic model (int8 quantised)
 * voices.bin          speaker embedding table (11 English voices, ~5.5 MB)
 * tokens.txt          phoneme vocabulary
 * espeak-ng-data/     phonemizer data (~19 MB)
 * ```
 *
 * Why on-disk instead of bundled in the APK:
 *  - Kokoro is ~98 MB compressed — bundling would double the default APK.
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
// `open` exists only to let MarmaladeTtsServiceTest substitute a JVM-safe
// fake engine (the real one calls into Sherpa-ONNX JNI which won't load in
// Robolectric). See app/src/test/java/.../service/MarmaladeTtsServiceTest.kt.
@Singleton
open class KokoroEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    companion object {
        private const val TAG = "KokoroEngine"

        /** Engine identifier matched against `VoiceMeta.engine` and used as the install dir name. */
        const val ENGINE_NAME = "kokoro"

        // File names inside the engine directory. These match Sherpa-ONNX's
        // released `kokoro-int8-en-v0_19.tar.bz2` layout — keep in sync
        // with EngineInstaller's extraction logic.
        private const val MODEL_FILE = "model.int8.onnx"
        private const val VOICES_FILE = "voices.bin"
        private const val TOKENS_FILE = "tokens.txt"
        private const val DATA_DIR = "espeak-ng-data"

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

    /**
     * Sample rate the loaded engine emits, in Hz. Reads from the live
     * Sherpa-ONNX handle once the model is loaded; falls back to the
     * documented Kokoro constant (24 kHz) otherwise. The fallback matters
     * for the system-TTS callback path, which has to declare a sample
     * rate before any synthesis happens.
     */
    open val sampleRate: Int get() = tts?.sampleRate() ?: KokoroVoiceCatalog.SAMPLE_RATE

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
    open fun isInstalled(): Boolean {
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

            // OfflineTtsKokoroModelConfig in Sherpa-ONNX 1.13.2 takes
            // (model, voices, tokens, dataDir, lexicon, lang, dictDir,
            //  lengthScale). For the English-only v0.19 port the lexicon,
            // lang, and dictDir fields are unused — leave them as the
            // empty-string default the no-arg constructor establishes.
            val kokoroCfg = OfflineTtsKokoroModelConfig(
                model = File(engineDir, MODEL_FILE).absolutePath,
                voices = File(engineDir, VOICES_FILE).absolutePath,
                tokens = File(engineDir, TOKENS_FILE).absolutePath,
                dataDir = File(engineDir, DATA_DIR).absolutePath,
                lexicon = "",
                lang = "",
                dictDir = "",
                lengthScale = 1.0f,
            )

            val modelCfg = OfflineTtsModelConfig(
                kokoro = kokoroCfg,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )

            val ttsCfg = OfflineTtsConfig(model = modelCfg)

            try {
                Log.i(TAG, "Loading Kokoro TTS model from $engineDir …")
                // Loading from filesDir, not assets — pass null AssetManager
                // so Sherpa-ONNX uses the absolute paths we provided.
                tts = OfflineTts(null, ttsCfg)
                Log.i(TAG, "Kokoro TTS model loaded (sampleRate=${tts?.sampleRate()})")
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Failed to initialise Sherpa-ONNX OfflineTts for Kokoro: ${t.message}",
                    t,
                )
            }
        }
    }

    /**
     * Synthesize [text] using the given [voiceId] (e.g. `"kokoro:af_bella"`).
     * Returns 16-bit signed PCM, mono.
     *
     * The work runs on `Dispatchers.Default` because synthesis is CPU-
     * bound — the caller can `runBlocking { synthesize(...) }` from the
     * system-TTS worker thread without monopolising it.
     *
     * @param speed length-scale style; 1.0 = native pace, >1 = faster.
     */
    open suspend fun synthesize(
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
            ?: SPEAKER_ID_BY_NAME[KokoroVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':')]
            ?: 0
    }
}
