package app.marmalade.tts.phonemizer

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// -----------------------------------------------------------------------------
// EspeakPhonemizer — sentence-mode espeak driver.
//
// Calls into the espeak C API via a small JNI shim in app/src/main/cpp/.
// The shim itself only does dlopen()/dlsym(); libespeak-ng.so is compiled
// from source (pinned submodule, GPL-3.0-or-later) and ships INSIDE the
// APK — Play forbids downloading executable code, so the lib can't live in
// the engine bundles the way it used to. The espeak-ng-data directory is
// data, not code, and still ships in the downloaded engine bundle.
// Shipping the lib makes the distributed APK a GPL-3.0-or-later combined
// work; every source file here stays MIT. See NOTICE.md.
//
// Architecture:
//
//   Kotlin                JNI shim               espeak (GPL, in APK)
//   ──────                ────────               ────────────────────
//   EspeakPhonemizer ───▶ libespeak-jni.so ───▶ libespeak-ng.so
//   (MIT source)          (MIT source)           (dlopen'd from the APK's
//                                                nativeLibraryDir)
//
// Only one espeak engine instance can be alive in a process — espeak's
// internal state is global. The companion-level [nativeLock] serialises
// every native call across wrapper instances, the open/close lifetime is
// refcounted (last close tears down), and voice-select + phonemize run
// as one atomic unit so concurrent engines can't phonemize with each
// other's language.
// -----------------------------------------------------------------------------

private const val TAG = "EspeakPhonemizer"

class EspeakPhonemizer(
    private val libPath: String,
    private val dataPath: String,
    private val voice: String = "en-us",
    private val fixupModel: EnPhonemeFixups.Model,
) {

    private val opened = AtomicBoolean(false)

    init {
        // Load both APK-resident libs through System.loadLibrary so they
        // resolve under both packaging modes (extractNativeLibs=true →
        // physical .so under nativeLibraryDir, =false → mapped straight
        // from inside the APK; AGP defaults to the second). The shim
        // then dlopens libespeak-ng.so by basename to get a handle for
        // dlsym — Android's linker finds the already-loaded library in
        // the app's namespace either way.
        if (!libsLoaded) {
            try {
                System.loadLibrary("espeak-ng")
                System.loadLibrary("espeak-jni")
                libsLoaded = true
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load espeak native libs", t)
                throw IllegalStateException("espeak native libs missing from APK", t)
            }
        }
        require(File(dataPath).isDirectory) { "espeak-ng-data not found at $dataPath" }
    }

    /**
     * Initialise espeak. Returns the engine's native sample rate (≥0) or
     * a negative status code on failure (-1 dlopen, -2 missing symbols,
     * -3 espeak_Initialize failed, -4 voice not found).
     *
     * espeak is ONE process-global instance behind every wrapper, so the
     * lifetime is refcounted at companion level: only the first open()
     * actually initialises the native engine (with that instance's
     * dataPath — every bundle ships the full standard espeak-ng-data, so
     * whichever engine opens first serves all languages), and only the
     * last [close] tears it down. Before refcounting, one engine's
     * release closed espeak out from under the other loaded engine,
     * whose phonemize() then returned "" — silent no-audio until reload.
     */
    fun open(): Int = synchronized(nativeLock) {
        if (opened.get()) return globalRate
        if (openCount > 0) {
            openCount++
            opened.set(true)
            Log.i(TAG, "espeak open: joined running instance (refs=$openCount)")
            return globalRate
        }
        val rate = nativeOpen(libPath, dataPath, voice)
        if (rate < 0) {
            Log.e(TAG, "espeak open failed: rate=$rate libPath=$libPath dataPath=$dataPath voice=$voice")
            opened.set(false)
        } else {
            openCount = 1
            globalRate = rate
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
     * Sentence-mode phonemization in [voice]. The entire [text] string is
     * consumed in one call so espeak applies its sentence-level rules —
     * initialism spell-out ("TLS" → "tee-el-ess"), liaison ("the apple" →
     * /ði/), prosody marks. Returns the resulting IPA string, possibly
     * empty.
     *
     * Voice-select + phonemize execute atomically under [nativeLock]:
     * espeak's active voice is process-global state shared by every
     * engine, and the engines' synth locks are per-engine — without the
     * pairing, a concurrent Kokoro-French + Kitten-English synthesis
     * interleaved chunks and phonemized them with each other's language
     * rules. The voice switch is a cheap no-op when [voice] already
     * matches ([activeVoice] cache); a genuine switch costs ~50 ms.
     */
    fun phonemize(text: String, voice: String): String {
        if (!opened.get()) {
            Log.w(TAG, "phonemize called on a closed phonemizer")
            return ""
        }
        val raw = synchronized(nativeLock) {
            applyVoiceLocked(voice)
            nativePhonemize(text) ?: ""
        }
        // espeak's English LTS gets some common informal words wrong
        // ("yeah" → /jɛh/); see EnPhonemeFixups. The replacement differs
        // per acoustic model, hence [fixupModel] on the constructor.
        return if (voice.startsWith("en")) EnPhonemeFixups.apply(raw, fixupModel) else raw
    }

    /**
     * Pre-switch espeak's active voice (language code, e.g. `"en-us"`,
     * `"ja"`, `"cmn"`) so the first chunk doesn't pay the ~50 ms language
     * load mid-synthesis. Purely a warm-up: [phonemize] re-asserts the
     * voice atomically per call, so correctness never depends on this.
     * Returns true on success.
     */
    fun setVoice(name: String): Boolean {
        if (!opened.get()) {
            Log.w(TAG, "setVoice called on a closed phonemizer")
            return false
        }
        synchronized(nativeLock) {
            return applyVoiceLocked(name)
        }
    }

    /** Caller holds [nativeLock]. */
    private fun applyVoiceLocked(name: String): Boolean {
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
    fun version(): String? = if (opened.get()) synchronized(nativeLock) { nativeVersion() } else null

    fun close() {
        synchronized(nativeLock) {
            if (!opened.compareAndSet(true, false)) return
            openCount--
            if (openCount <= 0) {
                openCount = 0
                nativeClose()
                // espeak is torn down — the global voice is gone, so the next
                // open()/setVoice (possibly from a sibling instance) must apply.
                activeVoice = null
            } else {
                Log.i(TAG, "espeak close: sibling still open (refs=$openCount); keeping instance")
            }
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
        /**
         * The libespeak-ng.so basename. Passed straight through to dlopen
         * inside the JNI shim; Android's dynamic linker resolves it
         * against the app's namespace (works with extractNativeLibs both
         * true and false). The actual loading is done by System.loadLibrary
         * in [init] — this string just gets dlopen a handle for dlsym.
         */
        const val APK_LIB_NAME: String = "libespeak-ng.so"

        @Volatile private var libsLoaded: Boolean = false

        /**
         * Serialises every native call across all wrapper instances (the
         * native engine is one process-wide global), and guards the
         * refcount + voice cache. Held for the setVoice+phonemize pair so
         * voice selection can't interleave between engines.
         */
        private val nativeLock = Any()

        /** Live open() count across all instances. Guarded by [nativeLock]. */
        private var openCount: Int = 0

        /**
         * Sample rate reported by the running native instance — returned
         * to openers that join an already-running espeak. Guarded by
         * [nativeLock].
         */
        private var globalRate: Int = -1

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
