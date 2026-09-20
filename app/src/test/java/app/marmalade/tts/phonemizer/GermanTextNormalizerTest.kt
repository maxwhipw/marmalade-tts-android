package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [GermanTextNormalizer] against the golden `normalizer` vectors from
 * misaki.de.normalize_text_de (see GermanFixtures), plus targeted
 * _int_to_de boundary cases. Robolectric only for the org.json fixture load;
 * the normalizer itself is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class GermanTextNormalizerTest {

    @Test
    fun `every normalizer vector matches byte-exactly`() {
        for (v in GermanFixtures.normalizer()) {
            assertEquals("normalize(${v.input})", v.expected, GermanTextNormalizer.normalize(v.input))
        }
    }

    @Test
    fun `intToDe boundaries`() {
        assertEquals("null", GermanTextNormalizer.intToDe(0))
        assertEquals("eins", GermanTextNormalizer.intToDe(1))
        assertEquals("ein", GermanTextNormalizer.intToDe(1, standalone = false))
        assertEquals("einundzwanzig", GermanTextNormalizer.intToDe(21))
        assertEquals("einhundertein", GermanTextNormalizer.intToDe(101))
        assertEquals("eintausendein", GermanTextNormalizer.intToDe(1_001))
        assertEquals("eine Million", GermanTextNormalizer.intToDe(1_000_000))
        assertEquals("eine Milliarde ein", GermanTextNormalizer.intToDe(1_000_000_001))
    }
}
