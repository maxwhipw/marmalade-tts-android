package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `espeak_SetVoiceByName` resolves a voice by display name, identifier,
 * or voice-file basename — never by the `language` attributes inside the
 * file. The app's language codes are file basenames except French
 * (`lang/roa/fr`, no `fr-fr` file) and British English (`lang/gmw/en`),
 * which returned VOICE_NOT_FOUND and silently left espeak on the previous
 * (English) voice. normalizeVoice maps exactly those two.
 */
class EspeakVoiceNameTest {

    @Test
    fun `french maps to the fr voice file`() {
        assertEquals("fr", EspeakPhonemizer.normalizeVoice("fr-fr"))
    }

    @Test
    fun `british english maps to the en voice file`() {
        assertEquals("en", EspeakPhonemizer.normalizeVoice("en-gb"))
    }

    @Test
    fun `mapping is case-insensitive`() {
        assertEquals("fr", EspeakPhonemizer.normalizeVoice("fr-FR"))
        assertEquals("en", EspeakPhonemizer.normalizeVoice("en-GB"))
    }

    @Test
    fun `codes that are real espeak file names pass through`() {
        for (code in listOf("en-us", "es", "hi", "it", "ja", "pt-br", "cmn")) {
            assertEquals(code, EspeakPhonemizer.normalizeVoice(code))
        }
    }
}
