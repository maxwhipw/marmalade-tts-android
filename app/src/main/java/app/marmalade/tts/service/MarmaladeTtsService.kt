package app.marmalade.tts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.KokoroVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.KittenEngine
import app.marmalade.tts.engine.KokoroEngine
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.preprocessing.EmojiProsody
import app.marmalade.tts.preprocessing.Preprocessor
import app.marmalade.tts.preprocessing.ProsodyApplier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Android system  ── onSynthesizeText(request, callback) ──▶  this service
//      │
//      ├── request.voiceName  →  pickVoiceFromRequest  ──► voiceId
//      │      │                                           e.g. "kokoro:af_bella"
//      │      ▼                                           or "kitten:Bella"
//      │   VoiceMetaDao.findById(voiceId)  (suspend, runBlocking)
//      │      │
//      │      ▼                                           fallback when no
//      │   engineNameFor(voiceId)  ──► "kokoro" | "kitten"   match: kokoro
//      │
//      ├── request.charSequenceText  → String (raw, may contain emojis)
//      │      │
//      │      ├── EmojiProsody.detect(raw) ──► ProsodyHint(emotion, intensity)
//      │      ├── settings.enabledRules(engineName).first()  (runBlocking)
//      │      │     ──► Set<String> of enabled preprocessing rule names
//      │      ├── Preprocessor.apply(raw, enabled)  ──► normalized text
//      │      │     (HTML decode, currency, numbers→words, etc.)
//      │      └── EmojiProsody.stripEmojis(normalized) ──► cleaned text
//      │              for the engine (Sherpa-ONNX can't phonemize 🤣;
//      │              espeak would read it as "rolling on the floor
//      │              laughing face").
//      ▼
//   callback.start(sampleRateFor(engineName), ENCODING_PCM_16BIT, mono)
//      │
//      ▼
//   synthesizeForEngine(engineName, text, voiceId)   (runBlocking)
//      │   ├── "kokoro" → KokoroEngine.synthesize(...)
//      │   └── "kitten" → KittenEngine.synthesize(...)
//      │  produces SynthAudio(pcm: ShortArray, sampleRate)
//      ▼
//   ProsodyApplier.apply(pcm, sampleRate, detectedEmotion)
//      │  pitch/rate/volume/extras per emotion; Neutral is identity.
//      ▼
//   pcm → little-endian ByteArray → chunked writes via
//          callback.audioAvailable(buf, offset, n)  while
//          n <= callback.maxBufferSize on each iteration.
//      │
//      ▼
//   callback.done()   (or callback.error() on any exception)
//
//   Voice negotiation (onLoadVoice / onIsLanguageAvailable) accepts any
//   voice belonging to a known engine (kitten or kokoro) so the system
//   can enumerate both catalogs through Settings → Languages → TTS.
//
//   onLoadLanguage additionally fires a background warm-up — calling
//   ensureModelLoaded() on both engines off-thread — so that the first
//   onSynthesizeText doesn't pay the ~2–5 s cold-start tax for loading
//   model bytes + espeak-ng-data via JNI. Engines that aren't installed
//   throw EngineNotInstalledException which the warm-up silently absorbs.
// -----------------------------------------------------------------------------

/**
 * System TTS engine implementation.
 *
 * Registered in `AndroidManifest.xml` with the
 * `android.intent.action.TTS_SERVICE` intent-filter so Android Settings
 * → Languages → Text-to-speech can select Marmalade as the device TTS
 * engine.
 *
 * Wires the system synthesis callback contract to [KittenEngine] and
 * [KokoroEngine], routing each request to the engine the requested voice
 * belongs to:
 *
 * 1. `onSynthesizeText` resolves the requested voice against [VoiceMetaDao],
 *    picks the engine from `voice.engine`, asks that engine for PCM, and
 *    streams it through the callback in `callback.maxBufferSize` chunks.
 * 2. `onIsLanguageAvailable` / `onLoadLanguage` honour English variants
 *    only — both installable engines are English-only today.
 * 3. `onLoadVoice` accepts any voice ID seeded for either engine.
 *
 * Failures (missing model, JNI crash, etc.) surface as `callback.error()`
 * with a logged reason. The system retries or falls back to its default
 * engine, which is the documented behaviour and never produces fake
 * audio.
 */
@AndroidEntryPoint
class MarmaladeTtsService : TextToSpeechService() {

    @Inject lateinit var engine: KittenEngine

    @Inject lateinit var kokoroEngine: KokoroEngine

    @Inject lateinit var voiceDao: VoiceMetaDao

    @Inject lateinit var preprocessor: Preprocessor

    @Inject lateinit var settings: SettingsRepository

    // Bound to the service lifecycle (cancelled in onDestroy). Used for the
    // background model warm-up kicked off from onLoadLanguage. Dispatchers.IO
    // because ensureModelLoaded() reads ~44 MB off disk before handing to JNI.
    private val serviceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Widened to public so MarmaladeTtsServiceTest can exercise the
    // language-negotiation logic without subclassing the framework type.
    public override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val l = lang?.lowercase()
        val c = country?.uppercase()
        return when {
            // Match ISO-639-1 ("en") and -3 ("eng"); the system uses both in
            // different paths.
            l == "en" || l == "eng" -> when (c) {
                null, "" -> TextToSpeech.LANG_AVAILABLE
                "US", "USA" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
                else -> TextToSpeech.LANG_AVAILABLE
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /**
     * Called by the framework when the user selects this engine in
     * Settings → Languages → Text-to-speech, and again on engine start.
     *
     * Returns the same `LANG_*` code as [onIsLanguageAvailable] (the
     * contract is identical — same inputs, same outputs). On any positive
     * match we additionally fire a background warm-up so the first
     * [onSynthesizeText] does not pay the ~2–5 s cold-start tax of loading
     * ~25 MB of model + 19 MB of espeak-ng-data via JNI. Some clients
     * (screen readers especially) treat a slow `callback.start()` as an
     * engine fault.
     */
    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        val supported = result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (supported) {
            warmUpEngine()
        }
        return result
    }

    /**
     * Pre-load every installed engine on a background coroutine so the
     * next [onSynthesizeText] is fast. Safe to call repeatedly —
     * each engine's `ensureModelLoaded` is idempotent. Failures are logged
     * and swallowed; the synthesis path's own catch will still surface
     * `EngineNotInstalledException` correctly if a warm-up failed.
     *
     * We warm both Kitten and Kokoro speculatively. If the user only has
     * one installed, the other's `isInstalled()` returns false and
     * `ensureModelLoaded()` throws `EngineNotInstalledException`, which
     * the catch silently absorbs.
     */
    private fun warmUpEngine() {
        serviceScope.launch {
            // Walk a list of (name, loader) pairs rather than (name, engine):
            // KittenEngine and KokoroEngine don't share a base class, so
            // arrayOf("kitten" to engine, "kokoro" to kokoroEngine) infers
            // Pair<String, Any> and .ensureModelLoaded() is unresolved. A
            // function reference per engine keeps the call site monomorphic.
            val loaders: List<Pair<String, () -> Unit>> = listOf(
                "kitten" to engine::ensureModelLoaded,
                "kokoro" to kokoroEngine::ensureModelLoaded,
            )
            for ((name, load) in loaders) {
                try {
                    load()
                    Log.d(TAG, "$name engine warm-up complete")
                } catch (ex: Exception) {
                    // Don't crash the service — onSynthesizeText will report
                    // the same error to the system the next time it fires.
                    Log.w(TAG, "$name engine warm-up skipped/failed", ex)
                }
            }
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    /**
     * Called by the system when a voice is selected. Accept any voice ID
     * the catalog knows about (kitten or kokoro); reject the rest so the
     * system falls back to the language-level default.
     */
    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName.isNullOrEmpty()) return TextToSpeech.ERROR
        val match = runBlocking { voiceDao.findById(voiceName) }
        return if (match != null && isKnownEngine(match.engine)) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int = onLoadVoice(voiceName)

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String {
        // English request → the catalog-recommended default voice.
        // Kokoro is the recommended engine starting v0.1.9; the picker UX
        // also tracks isRecommended, so this stays in sync without a
        // separate source of truth.
        return if (onIsLanguageAvailable(lang, country, variant)
            != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            KokoroVoiceCatalog.DEFAULT_VOICE_ID
        } else {
            ""
        }
    }

    // Widened to public so MarmaladeTtsServiceTest can exercise the
    // synthesis orchestration without subclassing the framework type.
    public override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val rawText = request.charSequenceText?.toString().orEmpty()

        // Resolve the engine up-front: callback.start() needs a sample
        // rate, and that's engine-dependent (Kokoro and Kitten v0.8 both
        // emit at 24 kHz today, but routing through the right object
        // future-proofs the path).
        val voiceId = pickVoiceFromRequest(request)
        val engineName = engineNameFor(voiceId)
        val activeSampleRate = sampleRateFor(engineName)

        if (rawText.isBlank()) {
            // Open + close so the system isn't left waiting on us.
            callback.start(activeSampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // Emoji prosody is computed off the *raw* text (the emojis are
        // the signal). The text fed to the engine then goes through:
        //   1. User-configured text preprocessing (currency, numbers,
        //      abbreviations, … per Settings → Text preprocessing).
        //   2. Emotion-emoji stripping — a safety net for the 11 emoji
        //      in the EmojiProsody set, in case the user disabled the
        //      preprocessing `emoji` rule.
        // Rules are looked up per-engine so a user who curates kokoro's
        // ruleset separately from kitten's gets the right behaviour.
        val hint = EmojiProsody.detect(rawText)
        val enabled = runBlocking { settings.enabledRules(engineName).first() }
        val preprocessed = preprocessor.apply(rawText, enabled)
        val text = EmojiProsody.stripEmojis(preprocessed)
        if (text.isBlank()) {
            // Stripping left nothing speakable (e.g. input was emojis
            // only). Don't pass a blank line to the engine — close the
            // callback cleanly.
            callback.start(activeSampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        Log.d(TAG, "onSynthesizeText: voice=$voiceId engine=$engineName " +
            "chars=${text.length} emotion=${hint.emotion}")

        val startResult = callback.start(
            activeSampleRate,
            AudioFormat.ENCODING_PCM_16BIT,
            1, // mono
        )
        if (startResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "callback.start() returned $startResult — aborting")
            callback.error()
            return
        }

        try {
            val audio: SynthAudio = runBlocking { synthesizeForEngine(engineName, text, voiceId) }
            val shaped = ProsodyApplier.apply(audio.pcm, audio.sampleRate, hint.emotion)
            streamPcm(callback, shaped)
            callback.done()
        } catch (e: Exception) {
            // Covers IllegalStateException from either engine (assets
            // missing / corrupt / JNI failure) and any other synthesis
            // exception. The system retries or falls back to its default
            // engine — never produces fake audio.
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    // TODO: STUBS.md — onStop cancellation
    override fun onStop() {
        // Nothing to cancel: synthesis is synchronous and the system will
        // simply ignore any pcm we'd write after onStop returns. Future
        // work (chunked streaming generation) will set a cancel flag here.
    }

    override fun onDestroy() {
        // Tear down the warm-up scope before the framework releases us.
        serviceScope.cancel()
        super.onDestroy()
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Resolve the voice ID for this synthesis request.
     *
     * Order of precedence:
     *   1. `request.voiceName` if it exactly matches a seeded voice
     *      (kitten or kokoro);
     *   2. fall back to [KokoroVoiceCatalog.DEFAULT_VOICE_ID] (the
     *      recommended-engine default starting v0.1.9).
     *
     * Runs blocking against the DAO — the system invokes this off the
     * main thread per the TextToSpeechService contract.
     */
    internal fun pickVoiceFromRequest(request: SynthesisRequest): String {
        val requested = request.voiceName?.takeIf { it.isNotBlank() }
        if (requested != null) {
            val hit = runBlocking { voiceDao.findById(requested) }
            if (hit != null && isKnownEngine(hit.engine)) return hit.id
        }
        return KokoroVoiceCatalog.DEFAULT_VOICE_ID
    }

    /**
     * Engine name embedded in [voiceId] (everything before the first `:`).
     * Defaults to `"kokoro"` when the form doesn't match — the catch-all
     * keeps the synthesis path robust against junk input from third-party
     * TTS clients that bypass the voice negotiation contract.
     */
    private fun engineNameFor(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        if (sep <= 0) return KokoroVoiceCatalog.ENGINE
        val name = voiceId.substring(0, sep)
        return if (isKnownEngine(name)) name else KokoroVoiceCatalog.ENGINE
    }

    /** Per-engine sample rate. Both ship 24 kHz today; future engines may differ. */
    private fun sampleRateFor(engineName: String): Int = when (engineName) {
        KokoroVoiceCatalog.ENGINE -> kokoroEngine.sampleRate
        KittenVoiceCatalog.ENGINE -> engine.sampleRate
        else -> engine.sampleRate
    }

    /**
     * Route synthesis to the engine identified by [engineName]. Suspending
     * because both engines hand off to `Dispatchers.Default` internally,
     * which the caller `runBlocking`s on the system-TTS worker thread.
     *
     * Unknown engine names degrade to Kitten — same fallback policy as
     * [pickVoiceFromRequest]; the upstream voice-negotiation paths already
     * filtered out engines we don't know about.
     */
    private suspend fun synthesizeForEngine(
        engineName: String,
        text: String,
        voiceId: String,
    ): SynthAudio = when (engineName) {
        KokoroVoiceCatalog.ENGINE -> kokoroEngine.synthesize(text, voiceId)
        KittenVoiceCatalog.ENGINE -> engine.synthesize(text, voiceId)
        else -> engine.synthesize(text, voiceId)
    }

    /** True for any engine the catalog ships. Updated whenever a new engine lands. */
    private fun isKnownEngine(name: String): Boolean =
        name == KittenVoiceCatalog.ENGINE || name == KokoroVoiceCatalog.ENGINE

    /**
     * Stream [pcm] through the synthesis callback in chunks of at most
     * `callback.maxBufferSize` bytes (the system-imposed cap). The audio
     * is encoded little-endian per the `ENCODING_PCM_16BIT` contract.
     */
    private fun streamPcm(callback: SynthesisCallback, pcm: ShortArray) {
        val bytes = pcm16ToLittleEndianBytes(pcm)
        val chunkSize = callback.maxBufferSize.coerceAtLeast(2) // never zero
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(chunkSize, bytes.size - offset)
            val written = callback.audioAvailable(bytes, offset, n)
            if (written != TextToSpeech.SUCCESS) {
                Log.w(TAG, "callback.audioAvailable returned $written — aborting stream")
                throw RuntimeException("audioAvailable rejected chunk: $written")
            }
            offset += n
        }
    }

    companion object {
        private const val TAG = "MarmaladeTtsService"

        /** Convert PCM16 samples to a little-endian byte array. */
        internal fun pcm16ToLittleEndianBytes(pcm: ShortArray): ByteArray {
            val out = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val s = pcm[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
