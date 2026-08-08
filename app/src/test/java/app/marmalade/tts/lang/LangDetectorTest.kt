package app.marmalade.tts.lang

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain-JVM tests for the shipped detection table. Deliberately no
 * Robolectric: [LangDetector] takes the table's lines, so the real asset
 * can be read straight off disk and the whole suite stays fast.
 *
 * The sentences are the kind of thing a screen reader actually receives —
 * app notifications and UI strings — not literary prose, because that's
 * the input the thresholds were tuned against.
 */
class LangDetectorTest {

    private val detector = LangDetector(
        File("src/main/assets/langdetect.tab").readLines(),
    )

    // -- stage 2: Latin languages -------------------------------------------

    @Test
    fun detectsEnglish() {
        assertEquals("en", detector.detect("Your download has finished and the file is ready to open."))
    }

    @Test
    fun detectsSpanish() {
        assertEquals("es", detector.detect("Su descarga ha terminado y el archivo está listo para abrir."))
    }

    @Test
    fun detectsFrench() {
        assertEquals("fr", detector.detect("Votre téléchargement est terminé et le fichier est prêt."))
    }

    @Test
    fun detectsItalian() {
        assertEquals("it", detector.detect("Il download è terminato e il file è pronto per essere aperto."))
    }

    @Test
    fun detectsPortuguese() {
        assertEquals("pt", detector.detect("O seu download terminou e o arquivo está pronto para abrir."))
    }

    // -- stage 1: scripts ---------------------------------------------------

    @Test
    fun kanaBeatsHan() {
        // Mixed kana + kanji is Japanese, never Chinese.
        assertEquals("ja", detector.detect("東京タワーは高いです"))
    }

    @Test
    fun hanWithoutKanaIsChinese() {
        assertEquals("zh", detector.detect("今天天气很好，我们去公园散步吧"))
    }

    @Test
    fun detectsDevanagari() {
        assertEquals("hi", detector.detect("आपकी फ़ाइल डाउनलोड हो गई है"))
    }

    @Test
    fun latinDominantMixedTextStaysLatin() {
        // A couple of Han characters inside an English sentence must not
        // flip the whole utterance to Chinese phonemization.
        assertEquals("en", detector.detect("Play 東京 for me when you have a moment please"))
    }

    // -- abstention ---------------------------------------------------------

    @Test
    fun abstainsOnTooFewTrigrams() {
        assertNull(detector.detect("OK"))
    }

    @Test
    fun abstainsOnTextWithNoLetters() {
        assertNull(detector.detect("123"))
        assertNull(detector.detect(""))
        assertNull(detector.detect("   !!! "))
    }

    // -- robustness ---------------------------------------------------------

    @Test
    fun supplementaryPlaneHanDoesNotCrash() {
        // U+20000 is a surrogate pair — the script scan iterates code
        // points, so it must count as one Han character, not two unknowns.
        assertEquals("zh", detector.detect("𠀀𠀁 今天"))
    }

    // -- the AUTO sentinel + espeak mapping ---------------------------------

    @Test
    fun resolveOnlyActsOnTheAutoSentinel() {
        assertEquals("ja", detector.resolve("ja", "Votre téléchargement est terminé."))
        assertNull(detector.resolve(null, "Votre téléchargement est terminé."))
        assertEquals(
            "fr-fr",
            detector.resolve(LangDetector.AUTO, "Votre téléchargement est terminé et le fichier est prêt."),
        )
    }

    @Test
    fun englishAndChineseMapToNoEspeakOverride() {
        // No region guessing for English; zh goes through lexicon-zh.
        assertNull(LangDetector.espeakCodeFor("en"))
        assertNull(LangDetector.espeakCodeFor("zh"))
        assertNull(LangDetector.espeakCodeFor(null))
        assertEquals("pt-br", LangDetector.espeakCodeFor("pt"))
    }
}
