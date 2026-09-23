package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EspeakPhonemizer.phonemize] strips espeak's language-switch flags for
 * every engine. Vector: French "Je fais un update du software", untied, as
 * espeak-ng 1.52 returns it — Kitten used to encode the flags' parens and
 * letters as real vocab.
 */
class EspeakLanguageFlagTest {

    @Test
    fun `untied flags around loanwords are removed`() {
        assertEquals(
            "ʒə fɛ œ̃ ˈʌpdeɪt dy sˈɒftweə",
            EspeakPhonemizer.stripLanguageFlags(
                "ʒə fɛ œ̃ (en)ˈʌpdeɪt(fr) dy (en)sˈɒftweə(fr)",
            ),
        )
    }

    @Test
    fun `tied and regional flags are removed`() {
        assertEquals("das ˈʌpde^ɪt", EspeakPhonemizer.stripLanguageFlags("das (^e^n)ˈʌpde^ɪt(^d^e)"))
        assertEquals("həlˈoʊ", EspeakPhonemizer.stripLanguageFlags("(en-us)həlˈoʊ(fr)"))
    }

    @Test
    fun `flag-free ipa is untouched`() {
        val ipa = "ðɪs ɪz ɐ tˈɛst."
        assertEquals(ipa, EspeakPhonemizer.stripLanguageFlags(ipa))
    }
}
