package app.marmalade.tts.phonemizer

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// -----------------------------------------------------------------------------
// EspeakPhonemizer — sentence-mode espeak driver.
//
// Calls into the espeak C API via a small JNI shim in app/src/main/cpp/.
// The shim itself only does dlopen()/dlsym() — no espeak code is linked
// into the APK at build time. libttsespeak.so (GPL-3.0) and the
// espeak-ng-data directory ship in the engine bundle that the user
// downloads on opt-in.
//
// Architecture:
//
//   Kotlin                JNI shim                espeak (GPL, in bundle)
//   ──────                ────────                ──────────────────────
//   EspeakPhonemizer ───▶ libespeak-jni.so ───▶ libttsespeak.so
//   (MIT, in APK)         (MIT, in APK)          (GPL, dlopen'd at runtime
//                                                from filesDir bundle path)
//
// Only one espeak engine instance can be alive in a process — espeak's
// internal state is global. We enforce that with a singleton-style
// init and a top-level Mutex on the native side. Multiple Kotlin
// engines sharing this phonemizer is fine; concurrent calls serialise.
// -----------------------------------------------------------------------------

private const val TAG = "EspeakPhonemizer"

class EspeakPhonemizer(
    private val libPath: String,
    private val dataPath: String,
    private val voice: String = "en-us",
) {

    private val opened = AtomicBoolean(false)

    init {
        // Load the JNI shim from the APK (always present). It registers
        // the native methods declared below; espeak itself is dlopen'd
        // inside nativeOpen against [libPath].
        if (!jniLoaded) {
            try {
                System.loadLibrary("espeak-jni")
                jniLoaded = true
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load libespeak-jni.so", t)
                throw IllegalStateException("espeak JNI shim missing from APK", t)
            }
        }
        require(File(libPath).isFile) { "libttsespeak.so not found at $libPath" }
        require(File(dataPath).isDirectory) { "espeak-ng-data not found at $dataPath" }
    }

    /**
     * Initialise espeak. Returns the engine's native sample rate (≥0) or
     * a negative status code on failure (-1 dlopen, -2 missing symbols,
     * -3 espeak_Initialize failed, -4 voice not found). Safe to call
     * twice — the shim tears down any previous instance first.
     */
    fun open(): Int {
        val rate = nativeOpen(libPath, dataPath, voice)
        if (rate < 0) {
            Log.e(TAG, "espeak open failed: rate=$rate libPath=$libPath dataPath=$dataPath voice=$voice")
            opened.set(false)
        } else {
            opened.set(true)
            // nativeOpen sets espeak's (global) voice to [voice]. Record it
            // in the shared cache so a sibling instance's setVoice can tell
            // whether the global voice already matches.
            activeVoice = voice
            Log.i(TAG, "espeak open: ${nativeVersion()} sampleRate=$rate voice=$voice")
        }
        return rate
    }

    /**
     * Sentence-mode phonemization. The entire [text] string is consumed
     * in one call so espeak applies its sentence-level rules — initialism
     * spell-out ("TLS" → "tee-el-ess"), liaison ("the apple" → /ði/),
     * prosody marks. Returns the resulting IPA string, possibly empty.
     */
    fun phonemize(text: String): String {
        if (!opened.get()) {
            Log.w(TAG, "phonemize called on a closed phonemizer")
            return ""
        }
        return nativePhonemize(text) ?: ""
    }

    /**
     * Switch espeak's active voice (language code, e.g. `"en-us"`,
     * `"ja"`, `"cmn"`). Cheap-no-op when [name] already matches the voice
     * loaded in the (process-global) espeak instance. Returns true on
     * success.
     *
     * Espeak's voice load reads ~hundreds of KB of language data; pays
     * ~50 ms per switch on first call for a given language. The cache hit
     * on the common case (same language across chunks) skips that. The
     * cache is shared across instances ([activeVoice] is companion-level)
     * because espeak's voice is one global — a per-instance cache would let
     * one engine's voice change go unseen by another, e.g. Kitten keeping a
     * stale "en-us" cache and skipping the native call after Kokoro parked
     * espeak on "ja", so English came out spelled letter-by-letter.
     */
    fun setVoice(name: String): Boolean {
        if (!opened.get()) {
            Log.w(TAG, "setVoice called on a closed phonemizer")
            return false
        }
        if (name == activeVoice) return true
        val status = nativeSetVoice(name)
        return if (status == 0) {
            activeVoice = name
            true
        } else {
            Log.w(TAG, "setVoice($name) failed with status=$status; keeping $activeVoice")
            false
        }
    }

    /** Linked espeak version (diagnostic only). Null if not yet opened. */
    fun version(): String? = if (opened.get()) nativeVersion() else null

    fun close() {
        if (opened.compareAndSet(true, false)) {
            nativeClose()
            // espeak is torn down — the global voice is gone, so the next
            // open()/setVoice (possibly from a sibling instance) must apply.
            activeVoice = null
        }
    }

    // -- JNI surface ---------------------------------------------------------
    //
    // Implemented in app/src/main/cpp/espeak_jni.c. The function names
    // here must match the JNIEXPORT functions there exactly (after the
    // Java_<class>_ prefix is added by the JVM).

    private external fun nativeOpen(libPath: String, dataPath: String, voice: String): Int
    private external fun nativePhonemize(text: String): String?
    private external fun nativeSetVoice(voice: String): Int
    private external fun nativeVersion(): String?
    private external fun nativeClose()

    companion object {
        @Volatile private var jniLoaded: Boolean = false

        /**
         * The voice currently loaded in the process-global espeak instance,
         * or null when espeak is closed / its voice is unknown. Shared across
         * every [EspeakPhonemizer] (one per engine) because they all drive the
         * same global espeak — a per-instance cache desynced when another
         * engine changed the voice, leaking e.g. Kokoro's "ja" into Kitten and
         * spelling English out letter-by-letter. null forces the next
         * [setVoice] to apply unconditionally.
         */
        @Volatile private var activeVoice: String? = null
    }
}
