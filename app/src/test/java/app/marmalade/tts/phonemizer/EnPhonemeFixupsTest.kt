package app.marmalade.tts.phonemizer

import app.marmalade.tts.phonemizer.EnPhonemeFixups.Model.KITTEN
import app.marmalade.tts.phonemizer.EnPhonemeFixups.Model.KOKORO
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the "yeah" mispronunciation: espeak-ng has no
 * dictionary entry for it, and its letter-to-sound fallback emits /jɛh/
 * (a literal aspirated [h]). The raw inputs below are verbatim espeak
 * 1.51/1.52 sentence-mode output for the quoted text, as assembled by
 * espeak_jni.c (punctuation re-injected, space-joined).
 *
 * Targets are per model, chosen by ear (2026-07-27 A/B lab): Kitten
 * renders misaki's /jɛə/ poorly, so it gets flat /jæ/; Kokoro was
 * trained on misaki output, so it gets the faithful /jɛə/.
 */
class EnPhonemeFixupsTest {

    @Test
    fun `kitten standalone yeah becomes flat ya`() {
        // "Yeah."
        assertEquals("jˈæ . ", EnPhonemeFixups.apply("jˈɛh . ", KITTEN))
    }

    @Test
    fun `kokoro standalone yeah gains schwa off-glide`() {
        // "Yeah."
        assertEquals("jˈɛə . ", EnPhonemeFixups.apply("jˈɛh . ", KOKORO))
    }

    @Test
    fun `yeah before comma clause`() {
        // "Yeah, sure."
        assertEquals("jˈæ , ʃˈʊɹ . ", EnPhonemeFixups.apply("jˈɛh , ʃˈʊɹ . ", KITTEN))
        assertEquals("jˈɛə , ʃˈʊɹ . ", EnPhonemeFixups.apply("jˈɛh , ʃˈʊɹ . ", KOKORO))
    }

    @Test
    fun `mid-sentence yeah`() {
        // "I said yeah to that."
        assertEquals(
            "aɪ sˈɛd jˈæ tə ðˈæt . ",
            EnPhonemeFixups.apply("aɪ sˈɛd jˈɛh tə ðˈæt . ", KITTEN),
        )
    }

    @Test
    fun `possessive yeah's keeps trailing consonant`() {
        // "yeah's" — espeak appends /z/ directly, so the match must not
        // demand a right-hand word boundary.
        assertEquals("jˈæz", EnPhonemeFixups.apply("jˈɛhz", KITTEN))
        assertEquals("jˈɛəz", EnPhonemeFixups.apply("jˈɛhz", KOKORO))
    }

    @Test
    fun `secondary and no stress variants`() {
        assertEquals("jˌæ", EnPhonemeFixups.apply("jˌɛh", KITTEN))
        assertEquals("jæ", EnPhonemeFixups.apply("jɛh", KITTEN))
        assertEquals("jˌɛə", EnPhonemeFixups.apply("jˌɛh", KOKORO))
    }

    @Test
    fun `sequence inside a word is left alone`() {
        // Only word-initial /jɛh/ is the "yeah" LTS artifact.
        assertEquals("bəjˈɛh", EnPhonemeFixups.apply("bəjˈɛh", KITTEN))
        assertEquals("bəjˈɛh", EnPhonemeFixups.apply("bəjˈɛh", KOKORO))
    }

    @Test
    fun `unrelated words untouched`() {
        // "yes" /jˈɛs/, "yea" /jˈeɪ/, "hurrah" /hɚɹˈɑː/
        assertEquals("jˈɛs jˈeɪ hɚɹˈɑː", EnPhonemeFixups.apply("jˈɛs jˈeɪ hɚɹˈɑː", KITTEN))
        assertEquals("jˈɛs jˈeɪ hɚɹˈɑː", EnPhonemeFixups.apply("jˈɛs jˈeɪ hɚɹˈɑː", KOKORO))
    }
}
