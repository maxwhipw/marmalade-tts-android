package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [EspeakPhonemizer.phonemize] drops the dot espeak writes inside a Hindi
 * word, and keeps every clause mark the JNI shim re-injects (#62). Vectors are
 * pinned espeak-ng 1.52 output from tools/espeak-shim-check.
 */
class EspeakDotGuardTest {

    @Test
    fun `the retroflex flap loses its dot`() {
        // hi: "पृष्ठ 10–20 पढ़ें।"
        assertEquals(
            "pɾˈɪʂʈʰ dˈʌs bˈiːs pˈʌrhẽː . ",
            EspeakPhonemizer.stripInWordDots("pɾˈɪʂʈʰ dˈʌs bˈiːs pˈʌr.hẽː . "),
        )
    }

    @Test
    fun `a flap at the end of a word loses its dot too`() {
        // hi: "आंदोलन की जड़ में ऊपरी कमाई" (Common Voice).
        assertEquals(
            "ãdˈoːlən ki ɟˈʌr mẽː ˈupɾˌi kəmˈaːi",
            EspeakPhonemizer.stripInWordDots("ãdˈoːlən ki ɟˈʌr. mẽː ˈupɾˌi kəmˈaːi"),
        )
    }

    @Test
    fun `clause marks the shim injects stay`() {
        // hi: "पहला वाक्य। दूसरा वाक्य॥ तीसरा।", the danda's "." after a space.
        val ipa = "pˈʌhəlˌaː ʋˈaːkːjə .  dˈuːsɾˌaː ʋˈaːkːjə .  tˈiːsɾˌaː . "
        assertEquals(ipa, EspeakPhonemizer.stripInWordDots(ipa))
    }

    @Test
    fun `dot-free ipa is returned as is`() {
        val ipa = "nəmˈʌsteː , dˈʊnɪjˌaː ? "
        assertSame(ipa, EspeakPhonemizer.stripInWordDots(ipa))
    }
}
