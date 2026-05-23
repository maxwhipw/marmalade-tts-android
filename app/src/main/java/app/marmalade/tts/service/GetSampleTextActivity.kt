package app.marmalade.tts.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Returns the sample sentence Android speaks when the user taps the
 * "Listen to an example" button in Settings → Languages → TTS. No UI;
 * themed `@android:style/Theme.NoDisplay` and finished from [onCreate].
 *
 * Marmalade is English-only today, so we don't bother matching on the
 * requested language — the same sentence ships for every locale Android
 * might pass in. If we add non-English voices later this picks up the
 * same switch table the sherpa-onnx engine uses.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sample = "Hello, this is Marmalade speaking."

        val data = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        }
        setResult(TextToSpeech.LANG_AVAILABLE, data)
        finish()
    }
}
