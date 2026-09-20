package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end pins for the German/Bulgarian Kokoro phonemization pipeline
 * against the golden g2p vectors (see GermanFixtures).
 *
 * For German, [GermanG2P] is driven with a fake phonemize seam that returns
 * each span's captured `tied` value; the test asserts both the sequence of
 * espeak spans requested (override + passthrough spans must NOT hit the
 * seam) and the final rendered output, byte-exactly. For Bulgarian the path
 * is postprocess + the bg vocab substitution, no normalizer/overrides.
 * Robolectric only for the org.json fixture load.
 */
@RunWith(RobolectricTestRunner::class)
class GermanG2PTest {

    @Test
    fun `every g2p_de vector renders byte-exactly`() {
        for (v in GermanFixtures.g2pDe()) {
            val requested = ArrayList<String>()
            val tiedByText = v.spans
                .filter { it.text != null }
                .associate { it.text!! to it.tied!! }
            val g2p = GermanG2P { span ->
                requested.add(span)
                tiedByText.getValue(span)
            }

            val output = g2p.phonemes(v.text)

            val expectedSpans = v.spans.mapNotNull { it.text }
            assertEquals("espeak spans requested for: ${v.text}", expectedSpans, requested)
            assertEquals("rendered output for: ${v.text}", v.expected, output)
        }
    }

    @Test
    fun `every g2p_bg vector maps postprocess plus bg substitution`() {
        for (v in GermanFixtures.g2pBg()) {
            val output = KokoroLangSubstitutions.apply(KokoroEspeakG2P.postprocess(v.tied), "bg")
            assertEquals("bg output for: ${v.text}", v.expected, output)
        }
    }

    @Test
    fun `lang substitutions are per-language and leave others untouched`() {
        assertEquals("ʏ becomes y", "my", KokoroLangSubstitutions.apply("mʏ", "de"))
        assertEquals("ɫ becomes l", "tˈopl", KokoroLangSubstitutions.apply("tˈopɫ", "bg"))
        assertEquals("de leaves ɫ", "tˈopɫ", KokoroLangSubstitutions.apply("tˈopɫ", "de"))
        assertEquals("bg leaves ʏ", "mʏ", KokoroLangSubstitutions.apply("mʏ", "bg"))
        assertEquals("unknown lang untouched", "mʏɫ", KokoroLangSubstitutions.apply("mʏɫ", "en"))
    }
}
