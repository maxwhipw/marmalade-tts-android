package app.marmalade.tts.lang

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the per-engine auto-detect rules the three
 * synthesis routes share. Real table off disk, same as [LangDetectorTest]
 * — a fake detector would only pin the fake.
 */
class UtteranceLanguageTest {

    private val detector = LangDetector(
        File("src/main/assets/langdetect.tab").readLines(),
    )

    private val kokoro = KokoroDirectVoiceCatalog.ENGINE
    private val kitten = KittenDirectVoiceCatalog.ENGINE
    private val pocket = PocketVoiceCatalog.ENGINE

    private val bella = "kokoro-direct-v1_0:af_bella"
    private val lewis = "kokoro-direct-v1_0:bm_lewis"
    private val kittenBella = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID

    private val spanish = "La descarga ha terminado y el archivo está listo para usar."
    private val english = "Your download has finished and the file is ready."

    // -- which stored values mean "detect" ----------------------------------

    @Test
    fun nullMeansDetectOnKokoroAndNowhereElse() {
        assertTrue(UtteranceLanguage.isAuto(kokoro, null))
        assertFalse("Kitten's detection is opt-in", UtteranceLanguage.isAuto(kitten, null))
        assertFalse(UtteranceLanguage.isAuto(pocket, null))
    }

    @Test
    fun theSentinelMeansDetectEverywhere() {
        assertTrue(UtteranceLanguage.isAuto(kokoro, LangDetector.AUTO))
        assertTrue(UtteranceLanguage.isAuto(kitten, LangDetector.AUTO))
        assertTrue(UtteranceLanguage.isAuto(pocket, LangDetector.AUTO))
    }

    @Test
    fun anExplicitCodeIsNeverDetection() {
        assertFalse(UtteranceLanguage.isAuto(kokoro, "ja"))
        assertFalse(UtteranceLanguage.isAuto(kitten, "en-us"))
        assertEquals("ja", UtteranceLanguage.resolve(detector, kokoro, bella, "ja", spanish))
        assertEquals("en-us", UtteranceLanguage.resolve(detector, kitten, kittenBella, "en-us", spanish))
    }

    // -- Kitten renders the detected language itself -------------------------

    @Test
    fun kittenAutoEnglishStaysEnglish() {
        assertEquals(
            "en-us",
            UtteranceLanguage.resolve(detector, kitten, kittenBella, LangDetector.AUTO, english),
        )
    }

    @Test
    fun kittenAutoSpanishPhonemizesInSpanish() {
        // Max, 2026-08-08: accented Spanish from Kitten beats Spanish read
        // through English letter rules. The reroute to a Kokoro voice is
        // the system TTS service's business, above this.
        assertEquals(
            "es",
            UtteranceLanguage.resolve(detector, kitten, kittenBella, LangDetector.AUTO, spanish),
        )
    }

    @Test
    fun kittenAutoFallsBackToTheRequestLanguageWhenUnsure() {
        assertEquals(
            "fr-fr",
            UtteranceLanguage.resolve(
                detector, kitten, kittenBella, LangDetector.AUTO, "OK", fallback = "fr",
            ),
        )
    }

    @Test
    fun kittenNeverInheritsAnotherVoicesRegion() {
        // Kitten's model is en-us; there is no British Kitten to keep
        // British, so detected English always resolves to en-us.
        assertEquals(
            "en-us",
            UtteranceLanguage.espeakFor(kitten, kittenBella, "en"),
        )
    }

    // -- the other engines ---------------------------------------------------

    @Test
    fun kokoroTakesItsRegionFromTheVoice() {
        assertEquals("en-us", UtteranceLanguage.espeakFor(kokoro, bella, "en"))
        assertEquals("en-gb", UtteranceLanguage.espeakFor(kokoro, lewis, "en"))
        assertEquals(
            "es",
            UtteranceLanguage.resolve(detector, kokoro, bella, null, spanish),
        )
    }

    @Test
    fun pocketHasNoEspeakToPointAnywhere() {
        // Pocket doesn't phonemize through espeak, so a leftover sentinel
        // clears to the engine's own English rather than naming a language
        // it can't act on.
        assertNull(UtteranceLanguage.espeakFor(pocket, PocketVoiceCatalog.DEFAULT_VOICE_ID, "es"))
        assertNull(
            UtteranceLanguage.resolve(
                detector, pocket, PocketVoiceCatalog.DEFAULT_VOICE_ID, LangDetector.AUTO, spanish,
            ),
        )
    }
}
