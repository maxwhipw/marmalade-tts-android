package app.marmalade.tts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeechService
import android.util.Log

/**
 * System TTS engine implementation.
 *
 * Registered in AndroidManifest with the android.intent.action.TTS_SERVICE
 * intent-filter so Android Settings → Languages → Text-to-speech can select
 * Marmalade as the device TTS engine.
 *
 * This skeleton produces silence: it opens the synthesis callback, immediately
 * signals completion, and returns no audio bytes. The result is a registered
 * engine that the system can enumerate and invoke without crashing.
 *
 * Synthesis logic (Sherpa-ONNX kitten engine + emoji prosody) is v0.1 feature
 * work added on top of this skeleton.
 */
class MarmaladeTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "MarmaladeTtsService"
        // 22 050 Hz mono 16-bit — the sample rate the kitten engine will use.
        // Declared now so the system TTS contract is established correctly.
        private const val SAMPLE_RATE = 22050
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // All languages reported as available until the engine implements selection.
        return android.speech.tts.TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return android.speech.tts.TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        Log.d(TAG, "onSynthesizeText: '${request.charSequenceText}' (skeleton — silence)")
        // Open the audio stream with the engine's intended output format.
        val startResult = callback.start(
            SAMPLE_RATE,
            AudioFormat.ENCODING_PCM_16BIT,
            1, // mono
        )
        if (startResult != android.speech.tts.TextToSpeech.SUCCESS) {
            callback.error()
            return
        }
        // No audio bytes written — produces silence.
        // v0.1 feature work will feed PCM frames from the kitten engine here.
        callback.done()
    }

    override fun onStop() {
        // Nothing to cancel in the skeleton.
    }
}
