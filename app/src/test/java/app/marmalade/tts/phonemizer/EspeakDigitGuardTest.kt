package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [EspeakPhonemizer.phonemize] strips the ASCII digits espeak passes through
 * from its dictionaries, for every engine (#48). Vectors are pinned
 * espeak-ng 1.52 output from tools/espeak-shim-check.
 */
class EspeakDigitGuardTest {

    @Test
    fun `hyphenated aneinander loses espeak's stray 1`() {
        // de, tie mode: "Sie sind aneinander-geklebt."
        assertEquals(
            "ziː zɪnt ˌana^ɪnˈandɜɡəklˈeːpt . ",
            EspeakPhonemizer.stripDigits("ziː zɪnt ˌan1a^ɪnˈandɜɡəklˈeːpt . "),
        )
    }

    @Test
    fun `a lone Cyrillic letter on an English voice loses its 1`() {
        // en-us: "The letter л is Cyrillic."
        assertEquals(
            "ðə lˈɛɾɚɹ ˈɛl ɪz sɪɹˈɪlɪk . ",
            EspeakPhonemizer.stripDigits("ðə lˈɛɾɚɹ ˈɛl1 ɪz sɪɹˈɪlɪk . "),
        )
    }

    @Test
    fun `bare aneinander loses espeak's stray 1 on the German path`() {
        // de, tie mode: "Sie lehnten aneinander." The guard gives the IPA the
        // GermanOverrides entry #46 added for it, so #62 removed that entry.
        val g2p = GermanG2P { span ->
            EspeakPhonemizer.stripDigits(
                when (span.trim()) {
                    "Sie lehnten aneinander." -> "ziː lˈeːntən ˌan1a^ɪnˈandɜ . "
                    else -> error("unexpected espeak span: $span")
                },
            )
        }
        assertEquals("ziː lˈeːntən ˌanInˈandɜ .", g2p.phonemes("Sie lehnten aneinander."))
    }

    @Test
    fun `the German path gets the same IPA in a compound`() {
        // Production wires GermanG2P to espeak.phonemize, which applies the guard.
        val g2p = GermanG2P { span ->
            EspeakPhonemizer.stripDigits(
                when (span.trim()) {
                    "Sie sind aneinander-geklebt." -> "ziː zɪnt ˌan1a^ɪnˈandɜɡəklˈeːpt . "
                    else -> error("unexpected espeak span: $span")
                },
            )
        }
        assertEquals("ziː zɪnt ˌanInˈandɜɡəklˈeːpt .", g2p.phonemes("Sie sind aneinander-geklebt."))
    }

    @Test
    fun `digit-free ipa is returned as is`() {
        val ipa = "ðɪs ɪz ɐ tˈɛst."
        assertSame(ipa, EspeakPhonemizer.stripDigits(ipa))
    }
}
