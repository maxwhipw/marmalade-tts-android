package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the "yeah" mispronunciation: espeak-ng has no
 * dictionary entry for it, and its letter-to-sound fallback emits /jɛh/
 * (a literal aspirated [h]) instead of /jɛə/. The raw inputs below are
 * verbatim espeak 1.51/1.52 sentence-mode output for the quoted text,
 * as assembled by espeak_jni.c (punctuation re-injected, space-joined).
 */
class EnPhonemeFixupsTest {

    @Test
    fun `standalone yeah gains schwa off-glide`() {
        // "Yeah."
        assertEquals("jˈɛə . ", EnPhonemeFixups.apply("jˈɛh . "))
    }

    @Test
    fun `yeah before comma clause`() {
        // "Yeah, sure."
        assertEquals("jˈɛə , ʃˈʊɹ . ", EnPhonemeFixups.apply("jˈɛh , ʃˈʊɹ . "))
    }

    @Test
    fun `mid-sentence yeah`() {
        // "I said yeah to that."
        assertEquals(
            "aɪ sˈɛd jˈɛə tə ðˈæt . ",
            EnPhonemeFixups.apply("aɪ sˈɛd jˈɛh tə ðˈæt . "),
        )
    }

    @Test
    fun `possessive yeah's keeps trailing consonant`() {
        // "yeah's" — espeak appends /z/ directly, so the match must not
        // demand a right-hand word boundary.
        assertEquals("jˈɛəz", EnPhonemeFixups.apply("jˈɛhz"))
    }

    @Test
    fun `secondary and no stress variants`() {
        assertEquals("jˌɛə", EnPhonemeFixups.apply("jˌɛh"))
        assertEquals("jɛə", EnPhonemeFixups.apply("jɛh"))
    }

    @Test
    fun `sequence inside a word is left alone`() {
        // Only word-initial /jɛh/ is the "yeah" LTS artifact.
        assertEquals("bəjˈɛh", EnPhonemeFixups.apply("bəjˈɛh"))
    }

    @Test
    fun `unrelated words untouched`() {
        // "yes" /jˈɛs/, "yea" /jˈeɪ/, "hurrah" /hɚɹˈɑː/
        assertEquals("jˈɛs jˈeɪ hɚɹˈɑː", EnPhonemeFixups.apply("jˈɛs jˈeɪ hɚɹˈɑː"))
    }
}
