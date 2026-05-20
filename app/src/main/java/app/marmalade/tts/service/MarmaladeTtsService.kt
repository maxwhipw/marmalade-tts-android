package app.marmalade.tts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.KittenEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Android system  ── onSynthesizeText(request, callback) ──▶  this service
//      │
//      ├── request.charSequenceText  → String to synthesize
//      ├── request.voiceName / language fields → resolveVoiceId()
//      │      │
//      │      ▼
//      │   VoiceMetaDao.findById(voiceId)  (suspend, runBlocking)
//      │      │
//      │      ▼
//      │   "kitten:Bella" (default fallback if request didn't match)
//      │
//      ▼
//   callback.start(sampleRate, ENCODING_PCM_16BIT, mono)
//      │
//      ▼
//   KittenEngine.synthesize(text, voiceId, speed)   (runBlocking)
//      │  produces SynthAudio(pcm: ShortArray, sampleRate)
//      ▼
//   pcm → little-endian ByteArray → chunked writes via
//          callback.audioAvailable(buf, offset, n)  while
//          n <= callback.maxBufferSize on each iteration.
//      │
//      ▼
//   callback.done()   (or callback.error() on any exception)
//
//   Voice negotiation (onLoadVoice / onIsLanguageAvailable) is wired to
//   the same VoiceMetaDao seed (KittenVoiceCatalog) so the system can
//   enumerate the 8 Kitten voices through Settings → Languages → TTS.
// -----------------------------------------------------------------------------

/**
 * System TTS engine implementation.
 *
 * Registered in `AndroidManifest.xml` with the
 * `android.intent.action.TTS_SERVICE` intent-filter so Android Settings
 * → Languages → Text-to-speech can select Marmalade as the device TTS
 * engine.
 *
 * Wires the system synthesis callback contract to [KittenEngine]:
 *
 * 1. `onSynthesizeText` resolves the requested voice against [VoiceMetaDao],
 *    asks `KittenEngine` for PCM, and streams it through the callback in
 *    `callback.maxBufferSize` chunks.
 * 2. `onIsLanguageAvailable` / `onLoadLanguage` honour `en-US` only for
 *    v0.1 — the bundled Kitten model is English-only.
 * 3. `onLoadVoice` accepts any voice ID seeded in the database.
 *
 * Failures (missing model, JNI crash, etc.) surface as `callback.error()`
 * with a logged reason. The system retries or falls back to its default
 * engine, which is the documented behaviour and never produces fake
 * audio.
 */
@AndroidEntryPoint
class MarmaladeTtsService : TextToSpeechService() {

    @Inject lateinit var engine: KittenEngine

    @Inject lateinit var voiceDao: VoiceMetaDao

    override fun onIsLanguageAvailable(
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

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int = onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    /**
     * Called by the system when a voice is selected. Accept any voice ID
     * the catalog knows about; reject the rest so the system falls back
     * to the language-level default.
     */
    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName.isNullOrEmpty()) return TextToSpeech.ERROR
        val match = runBlocking { voiceDao.findById(voiceName) }
        return if (match != null && match.engine == KittenVoiceCatalog.ENGINE) {
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
        // English request → our default Kitten voice. Otherwise return an
        // empty string so the system reports "no voice for that language".
        return if (onIsLanguageAvailable(lang, country, variant)
            != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            KittenVoiceCatalog.DEFAULT_VOICE_ID
        } else {
            ""
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            // Open + close so the system isn't left waiting on us.
            callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        val voiceId = pickVoiceFromRequest(request)
        Log.d(TAG, "onSynthesizeText: voice=$voiceId chars=${text.length}")

        val startResult = callback.start(
            engine.sampleRate,
            AudioFormat.ENCODING_PCM_16BIT,
            1, // mono
        )
        if (startResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "callback.start() returned $startResult — aborting")
            callback.error()
            return
        }

        try {
            val audio = runBlocking { engine.synthesize(text, voiceId) }
            streamPcm(callback, audio.pcm)
            callback.done()
        } catch (e: Exception) {
            // Covers IllegalStateException from KittenEngine (assets
            // missing / corrupt / JNI failure) and any other synthesis
            // exception. The system retries or falls back to its default
            // engine — never produces fake audio.
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    override fun onStop() {
        // Nothing to cancel: synthesis is synchronous and the system will
        // simply ignore any pcm we'd write after onStop returns. Future
        // work (chunked streaming generation) will set a cancel flag here.
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Resolve the voice ID for this synthesis request.
     *
     * Order of precedence:
     *   1. `request.voiceName` if it exactly matches a seeded voice;
     *   2. language match → default voice when language is en;
     *   3. fall back to [KittenVoiceCatalog.DEFAULT_VOICE_ID].
     *
     * Runs blocking against the DAO — the system invokes this off the
     * main thread per the TextToSpeechService contract.
     */
    internal fun pickVoiceFromRequest(request: SynthesisRequest): String {
        val requested = request.voiceName?.takeIf { it.isNotBlank() }
        if (requested != null) {
            val hit = runBlocking { voiceDao.findById(requested) }
            if (hit != null && hit.engine == KittenVoiceCatalog.ENGINE) return hit.id
        }
        return KittenVoiceCatalog.DEFAULT_VOICE_ID
    }

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
