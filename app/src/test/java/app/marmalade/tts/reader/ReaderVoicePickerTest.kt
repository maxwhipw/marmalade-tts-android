package app.marmalade.tts.reader

import app.marmalade.tts.lang.LangDetector
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The article text [LanguageAwareReaderVoicePicker] detects the language on. */
class ReaderVoicePickerTest {

    private val detector = LangDetector(
        File("src/main/assets/langdetect.tab").readLines(),
        systemCjk = null,
    )

    /** The device case: a Chinese article whose chrome (title) is English. */
    @Test
    fun `a Chinese article with an English title detects as Chinese`() {
        val article = ReaderArticle(
            url = "https://example.com/zh",
            title = "News",
            byline = null,
            blocks = listOf(
                ArticleBlock.Paragraph("这是一个关于果酱的故事。我们今天去市场买了很多橙子。"),
                ArticleBlock.Paragraph("他们说这个应用可以读出文章。"),
            ),
            totalTextChars = 40,
        )

        val sample = LanguageAwareReaderVoicePicker.detectionSample(article)

        assertEquals("zh", detector.detect(sample))
    }

    @Test
    fun `the detection sample is capped`() {
        val long = ReaderArticle(
            url = "https://example.com/long",
            title = null,
            byline = null,
            blocks = List(500) { ArticleBlock.Paragraph("The quick brown fox jumps over the lazy dog.") },
            totalTextChars = 22_000,
        )

        val sample = LanguageAwareReaderVoicePicker.detectionSample(long)

        assertEquals(LanguageAwareReaderVoicePicker.DETECTION_SAMPLE_CHARS, sample.length)
        assertEquals("en", detector.detect(sample))
    }
}
