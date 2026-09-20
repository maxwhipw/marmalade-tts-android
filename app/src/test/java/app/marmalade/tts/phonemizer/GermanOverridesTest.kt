package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [GermanOverrides] against the golden `lookup` vectors and spot-checks
 * the override table. Robolectric only for the org.json fixture load.
 */
@RunWith(RobolectricTestRunner::class)
class GermanOverridesTest {

    @Test
    fun `every lookup vector matches byte-exactly`() {
        for (v in GermanFixtures.lookup()) {
            assertEquals("normalizeForLookup(${v.input})", v.expected, GermanOverrides.normalizeForLookup(v.input))
        }
    }

    @Test
    fun `override hits resolve to the table phonemes`() {
        assertEquals("ɡˈɪthab", GermanOverrides.overrideFor("GitHub"))
        assertEquals("pˈaɪtɔːɹtʃ", GermanOverrides.overrideFor("PyTorch"))
        assertEquals("hˈyːpnoːzə", GermanOverrides.overrideFor("Hypnose"))
    }

    @Test
    fun `plain German word is not overridden`() {
        assertNull(GermanOverrides.overrideFor("Haus"))
    }
}
