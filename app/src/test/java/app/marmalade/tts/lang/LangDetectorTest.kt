package app.marmalade.tts.lang

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the shipped detection table. Deliberately no
 * Robolectric: [LangDetector] takes the table's lines, so the real asset
 * can be read straight off disk and the whole suite stays fast.
 *
 * The battery below is an accuracy check, not a smoke test: several
 * sentences per language, in the registers a screen reader actually
 * receives — casual chat, UI strings and notifications, formal prose —
 * because that is the input the table and thresholds were tuned against.
 * A sentence that abstains is a finding about the table, so fix the
 * table rather than swapping the sentence for an easier one.
 */
class LangDetectorTest {

    // systemCjk pinned to null so results don't depend on the test JVM's
    // locale; the tiebreak itself is covered explicitly below.
    private val detector = LangDetector(
        File("src/main/assets/langdetect.tab").readLines(),
        systemCjk = null,
    )

    /** Assert every sentence detects as [lang], naming the failure. */
    private fun assertAll(lang: String, vararg sentences: String) {
        for (s in sentences) assertEquals("detect(\"$s\")", lang, detector.detect(s))
    }

    // -- stage 2: the Latin languages, one battery each ---------------------

    @Test
    fun detectsEnglish() = assertAll(
        "en",
        "Your download has finished and the file is ready to open.",
        "Hey, are you still coming over tonight or should I eat without you?",
        "Battery saver is on, so background activity is limited.",
        "The meeting has been moved to Thursday afternoon at half past two.",
        "Please review the attached document and let me know if anything is missing.",
        "I honestly can't believe how long that queue was this morning.",
    )

    @Test
    fun detectsSpanish() = assertAll(
        "es",
        "Su descarga ha terminado y el archivo está listo para abrir.",
        "Acuérdate de regar las plantas mientras estamos fuera.",
        "El ahorro de batería está activado y limita la actividad en segundo plano.",
        "La reunión se ha trasladado al jueves por la tarde a las dos y media.",
        "Le agradecería que revisara el documento adjunto antes del viernes.",
        "No me puedo creer la cola que había esta mañana en la oficina de correos.",
    )

    @Test
    fun detectsFrench() = assertAll(
        "fr",
        "Votre téléchargement est terminé et le fichier est prêt.",
        "N'oublie pas d'arroser les plantes pendant notre absence.",
        "L'économiseur de batterie est activé et limite l'activité en arrière-plan.",
        "La réunion a été déplacée à jeudi après-midi, à quatorze heures trente.",
        "Je vous remercie de bien vouloir relire le document joint avant vendredi.",
        "Je n'arrive pas à croire à quel point la file d'attente était longue ce matin.",
    )

    @Test
    fun detectsItalian() = assertAll(
        "it",
        "Il download è terminato e il file è pronto per essere aperto.",
        "Ricordati di annaffiare le piante mentre siamo via.",
        "Il risparmio energetico è attivo e limita l'attività in background.",
        "La riunione è stata spostata a giovedì pomeriggio alle due e mezza.",
        "La ringrazio di voler rileggere il documento allegato entro venerdì.",
        "Non riesco a credere a quanto fosse lunga la fila stamattina alla posta.",
    )

    @Test
    fun detectsPortuguese() = assertAll(
        "pt",
        "O seu download terminou e o arquivo está pronto para abrir.",
        "A economia de bateria está ativada e limita a atividade.",
        "Lembra de regar as plantas enquanto a gente estiver viajando.",
        "A reunião foi transferida para quinta-feira à tarde, às duas e meia.",
        "Agradeço que revise o documento anexo antes de sexta-feira.",
        "Não acredito no tamanho da fila que tinha hoje de manhã no correio.",
    )

    // -- stage 1: scripts ---------------------------------------------------

    @Test
    fun kanaBeatsHan() = assertAll(
        // Mixed kana + kanji is Japanese, never Chinese.
        "ja",
        "東京タワーは高いです",
        "ダウンロードが完了しました。ファイルを開けます。",
        "明日の会議は午後二時からに変更になりました",
        "バッテリーセーバーがオンになっています",
    )

    @Test
    fun hanWithoutKanaIsChinese() = assertAll(
        "zh",
        "今天天气很好，我们去公园散步吧",
        "下载已完成，文件可以打开了",
        "会议改到星期四下午两点半",
    )

    // -- kana-free Han tiebreak (Max, 2026-08-08) ---------------------------

    @Test
    fun hanMarkersConfirmChinese() = assertAll(
        "zh",
        // Simplified forms that differ from shinjitai (们, 时).
        "我们的时间不多了",
        // Chinese-only pronoun in an otherwise shared-glyph phrase.
        "謝謝你",
        // Traditional forms Japanese writes differently (們, 氣).
        "我們去公園散步",
    )

    @Test
    fun hanMarkersConfirmJapanese() = assertAll(
        "ja",
        // Shinjitai unique to Japan — a station sign has no kana.
        "東京駅集合",
        // Kokuji (込).
        "受付方法申込",
    )

    @Test
    fun longUnmarkedHanRunIsChinese() {
        // Real Japanese this long would carry kana.
        assertEquals("zh", detector.detect("山川草木花鳥風月"))
    }

    @Test
    fun shortUnmarkedHanRunAbstains() {
        assertNull(detector.detect("水草"))
        assertNull(detector.detect("明日"))
    }

    @Test
    fun shortAmbiguousHanFallsBackToACjkSystemDefault() {
        val lines = File("src/main/assets/langdetect.tab").readLines()
        val jaDevice = LangDetector(lines, systemCjk = "ja")
        val zhDevice = LangDetector(lines, systemCjk = "zh")
        assertEquals("ja", jaDevice.detect("明日"))
        assertEquals("zh", zhDevice.detect("明日"))
        // Markers and length still outrank the system default.
        assertEquals("zh", jaDevice.detect("我们的时间不多了"))
        assertEquals("ja", zhDevice.detect("東京駅集合"))
    }

    @Test
    fun detectsDevanagari() = assertAll(
        "hi",
        "आपकी फ़ाइल डाउनलोड हो गई है",
        "बैठक गुरुवार दोपहर ढाई बजे कर दी गई है",
        "बैटरी सेवर चालू है और पृष्ठभूमि गतिविधि सीमित है",
    )

    @Test
    fun latinDominantMixedTextStaysLatin() {
        // A couple of Han characters inside an English sentence must not
        // flip the whole utterance to Chinese phonemization.
        assertEquals("en", detector.detect("Play 東京 for me when you have a moment please"))
        assertEquals(
            "en",
            detector.detect("The restaurant is called 火鍋 and it opens again on Monday evening"),
        )
    }

    // -- abstention ---------------------------------------------------------

    @Test
    fun abstainsOnTooFewTrigrams() {
        assertNull(detector.detect("OK"))
        assertNull(detector.detect("Hi"))
        assertNull(detector.detect("42%"))
    }

    @Test
    fun abstainsOnTextWithNoLetters() {
        assertNull(detector.detect("123"))
        assertNull(detector.detect(""))
        assertNull(detector.detect("   !!! "))
        assertNull(detector.detect("🎉🎉🎉"))
        assertNull(detector.detect("+1 555 0134"))
    }

    // -- robustness ---------------------------------------------------------

    @Test
    fun supplementaryPlaneHanDoesNotCrash() {
        // U+20000 is a surrogate pair — the script scan iterates code
        // points, so it must count as one Han character, not two unknowns.
        // (们 marks the run as Chinese; unmarked short runs abstain.)
        assertEquals("zh", detector.detect("𠀀𠀁 我们今天"))
    }

    // -- the AUTO sentinel ---------------------------------------------------

    @Test
    fun resolveOnlyActsOnTheAutoSentinel() {
        assertEquals("ja", detector.resolve("ja", "Votre téléchargement est terminé.", "en-us"))
        assertNull(detector.resolve(null, "Votre téléchargement est terminé.", "en-us"))
        assertEquals(
            "fr-fr",
            detector.resolve(
                LangDetector.AUTO,
                "Votre téléchargement est terminé et le fichier est prêt.",
                "ja",
            ),
        )
    }

    // -- the espeak mapping --------------------------------------------------

    @Test
    fun englishTakesItsRegionFromTheVoice() {
        // The region is the voice's business — never guessed from text —
        // but it must be *stated*, or a non-English voice would keep its
        // own G2P for English text.
        assertEquals("en-gb", LangDetector.espeakCodeFor("en", "en-gb"))
        assertEquals("en-us", LangDetector.espeakCodeFor("en", "en-us"))
        assertEquals("en-us", LangDetector.espeakCodeFor("en", "ja"))
        assertEquals("en-us", LangDetector.espeakCodeFor("en", "fr-fr"))
        assertEquals("en-us", LangDetector.espeakCodeFor("en", null))
    }

    @Test
    fun mandarinAlwaysMapsToAmericanEspeak() {
        // Han runs go through lexicon-zh whatever espeak is set to; the
        // latin spans are what the code decides, and en-us is what the
        // z-voices' own catalog default says.
        assertEquals("en-us", LangDetector.espeakCodeFor("zh", "ja"))
        assertEquals("en-us", LangDetector.espeakCodeFor("zh", "en-gb"))
        assertEquals("en-us", LangDetector.espeakCodeFor("zh", null))
    }

    @Test
    fun theOtherLanguagesIgnoreTheVoiceDefault() {
        assertEquals("es", LangDetector.espeakCodeFor("es", "ja"))
        assertEquals("fr-fr", LangDetector.espeakCodeFor("fr", "ja"))
        assertEquals("hi", LangDetector.espeakCodeFor("hi", "ja"))
        assertEquals("it", LangDetector.espeakCodeFor("it", "ja"))
        assertEquals("ja", LangDetector.espeakCodeFor("ja", "en-us"))
        assertEquals("pt-br", LangDetector.espeakCodeFor("pt", "ja"))
    }

    @Test
    fun noDetectionLeavesTheVoiceAlone() {
        // The one true no-information case: nothing detected and no
        // fallback locale, so the voice's own language stands.
        assertNull(LangDetector.espeakCodeFor(null, "ja"))
        assertNull(LangDetector.espeakCodeFor(null, null))
    }

    // -----------------------------------------------------------------
    // Short-text margin ramp (Max, 2026-08-09: "button" spoke Italian)
    // -----------------------------------------------------------------
    // English input must NEVER come back as a non-English language —
    // "en" and null (fall back to the alias language) are both fine.
    // LOCKSTEP with the CLI's tests/test_langdetect.py battery.

    @Test
    fun shortEnglishNeverSwitchesLanguage() {
        val words = listOf(
            "button", "hello", "menu", "system", "pause", "volume",
            "language", "settings", "voice", "camera", "message",
            "calendar", "computer", "important", "different",
            "application", "developer", "calculator", "no", "done",
            "undo", "close", "delete", "cancel", "resume",
            "no signal", "well done", "turn it up", "good morning",
        )
        for (w in words) {
            val got = detector.detect(w)
            assertTrue("detect(\"$w\") -> $got", got == "en" || got == null)
        }
    }

    @Test
    fun shortButDecisiveForeignStillDetects() {
        assertAll("fr", "bonjour à tous", "merci beaucoup", "à bientôt")
        assertAll("es", "buenos días", "¿cómo estás?", "hasta mañana")
        assertAll("it", "grazie mille", "va bene così", "arrivederci amici")
        assertAll("pt", "muito obrigado", "até amanhã", "bom dia pessoal")
    }

    @Test
    fun requiredMarginRampShape() {
        assertEquals(LangDetector.MIN_MARGIN, LangDetector.requiredMargin(LangDetector.SHORT_TRIGRAMS), 0.0)
        assertEquals(LangDetector.MIN_MARGIN, LangDetector.requiredMargin(1000), 0.0)
        assertEquals(LangDetector.SHORT_MARGIN, LangDetector.requiredMargin(LangDetector.MIN_TRIGRAMS), 0.0)
        val mid = LangDetector.requiredMargin((LangDetector.MIN_TRIGRAMS + LangDetector.SHORT_TRIGRAMS) / 2)
        assertTrue(mid > LangDetector.MIN_MARGIN && mid < LangDetector.SHORT_MARGIN)
    }
}
