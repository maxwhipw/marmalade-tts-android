package app.marmalade.tts.reader

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.reader.ReaderVoiceDecision.Reason
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReaderVoiceChooser] — which voice reads an article (Max, 2026-09-28: "Reader
 * should load a language-supported engine if the default alias does not
 * support it"). The device evidence: a Chinese page with primary alias "Heart"
 * (kokoro af_heart, English) was phonemized as Chinese but spoken by the
 * English speaker.
 */
class ReaderVoiceChooserTest {

    private val kokoro = KokoroDirectVoiceCatalog.ENGINE

    private val heart = voice("af_heart")
    private val emma = voice("bf_emma")
    private val xiaobei = voice("zf_xiaobei")
    private val xiaoni = voice("zf_xiaoni")
    private val dora = voice("ef_dora")

    private val heartAlias = alias("id-heart", heart, speed = 1.1f, createdAt = 1)

    // -- Language matching --------------------------------------------------------

    @Test
    fun `regions of one language are the same language`() {
        assertTrue(ReaderVoiceChooser.speaks("en-US", "en"))
        assertTrue(ReaderVoiceChooser.speaks("en-GB", "en"))
        assertTrue(ReaderVoiceChooser.speaks("en_GB", "EN"))
        assertTrue(ReaderVoiceChooser.speaks("zh-CN", "zh"))
        assertFalse(ReaderVoiceChooser.speaks("en-US", "zh"))
        assertFalse(ReaderVoiceChooser.speaks(null, "en"))
        assertEquals("pt", ReaderVoiceChooser.languageOf("pt-BR"))
        assertNull(ReaderVoiceChooser.languageOf(" "))
    }

    /** A British primary reading an English article is not "unsupported". */
    @Test
    fun `a primary of another region of the language is kept`() {
        val decision = choose("en", alias("id-emma", emma), emma, pickable = listOf(heart))

        assertEquals(ReaderVoice.Primary, decision.voice)
        assertEquals(Reason.PrimarySupports, decision.reason)
    }

    // -- Choice order ---------------------------------------------------------------

    @Test
    fun `an unsupported language moves to an installed voice that speaks it`() {
        val decision = choose("zh", heartAlias, heart, pickable = listOf(heart, xiaobei, xiaoni))

        assertEquals(ReaderVoice.Installed(xiaobei.id, kokoro), decision.voice)
        assertEquals(Reason.InstalledVoice, decision.reason)
    }

    @Test
    fun `another alias that speaks it beats an installed voice`() {
        val zhAlias = alias("id-ni", xiaoni, speed = 0.9f, createdAt = 2)

        val decision = choose(
            "zh", heartAlias, heart,
            aliases = listOf(heartAlias, zhAlias),
            pickable = listOf(heart, xiaobei, xiaoni),
        )

        assertEquals(ReaderVoice.Alias("id-ni", xiaoni.id, kokoro, 0.9f), decision.voice)
        assertEquals(Reason.OtherAlias, decision.reason)
    }

    /** The alias list's own order (createdAt), first match wins. */
    @Test
    fun `the first matching alias in list order wins`() {
        val first = alias("id-a", xiaobei, createdAt = 2)
        val second = alias("id-b", xiaoni, createdAt = 3)

        val decision = choose(
            "zh", heartAlias, heart,
            aliases = listOf(heartAlias, first, second),
            pickable = listOf(heart, xiaobei, xiaoni),
        )

        assertEquals("id-a", (decision.voice as ReaderVoice.Alias).aliasId)
    }

    /** An alias whose voice isn't on disk (engine uninstalled) can't speak anything. */
    @Test
    fun `an alias whose voice is not pickable is skipped`() {
        val stale = alias("id-stale", xiaoni, createdAt = 2)

        val decision = choose(
            "zh", heartAlias, heart,
            aliases = listOf(heartAlias, stale),
            pickable = listOf(heart, xiaobei),
        )

        assertEquals(ReaderVoice.Installed(xiaobei.id, kokoro), decision.voice)
    }

    @Test
    fun `nothing that speaks it keeps the primary`() {
        val decision = choose("zh", heartAlias, heart, pickable = listOf(heart, dora))

        assertEquals(ReaderVoice.Primary, decision.voice)
        assertEquals(Reason.NoVoiceForLanguage, decision.reason)
    }

    /** Detection abstained — no evidence the primary is wrong, so keep it. */
    @Test
    fun `an undetected language keeps the primary`() {
        val decision = choose(null, heartAlias, heart, pickable = listOf(xiaobei))

        assertEquals(ReaderVoice.Primary, decision.voice)
        assertEquals(Reason.LanguageUnknown, decision.reason)
    }

    @Test
    fun `a primary voice with no catalog row keeps the primary`() {
        val decision = choose("zh", heartAlias, primaryVoice = null, pickable = listOf(xiaobei))

        assertEquals(ReaderVoice.Primary, decision.voice)
        assertEquals(Reason.PrimaryVoiceUnknown, decision.reason)
    }

    /** No primary alias: the service speaks its default (English Kitten) voice. */
    @Test
    fun `no primary alias still moves off the service default voice`() {
        val kittenDefault = VoiceMeta(
            id = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
            engine = KittenDirectVoiceCatalog.ENGINE,
            displayName = "Bella",
            languageCode = "en-US",
            sampleRate = 24_000,
            gender = "female",
        )

        val decision = choose("zh", primary = null, primaryVoice = kittenDefault, pickable = listOf(xiaobei))

        assertEquals(ReaderVoice.Installed(xiaobei.id, kokoro), decision.voice)
    }

    /**
     * Reading an article to a cloud provider is opted into per alias; the
     * reader never sends one there on its own.
     */
    @Test
    fun `an installed cloud voice is never picked, a cloud alias can be`() {
        val cloudZh = VoiceMeta(
            id = "cloud-api-v1:venice:tts-kokoro:zf_xiaobei",
            engine = CloudApiVoiceCatalog.ENGINE,
            displayName = "Xiaobei",
            languageCode = "zh-CN",
            sampleRate = 24_000,
            gender = "female",
        )
        assertEquals(
            ReaderVoice.Primary,
            choose("zh", heartAlias, heart, pickable = listOf(heart, cloudZh)).voice,
        )

        val cloudAlias = alias("id-cloud", cloudZh, createdAt = 2)
        val decision = choose(
            "zh", heartAlias, heart,
            aliases = listOf(heartAlias, cloudAlias),
            pickable = listOf(heart, cloudZh),
        )
        assertEquals("id-cloud", (decision.voice as ReaderVoice.Alias).aliasId)
    }

    // -- Detection sample --------------------------------------------------------

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

        assertEquals("zh", detector.detect(ReaderVoiceChooser.detectionSample(article)))
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

        val sample = ReaderVoiceChooser.detectionSample(long)

        assertEquals(ReaderVoiceChooser.DETECTION_SAMPLE_CHARS, sample.length)
        assertEquals("en", detector.detect(sample))
    }

    // -- Log line ----------------------------------------------------------------

    @Test
    fun `the log line names the language, the primary and the choice`() {
        val decision = choose("zh", heartAlias, heart, pickable = listOf(xiaobei))

        assertEquals(
            "article lang=zh primary=$kokoro:af_heart unsupported -> $kokoro:zf_xiaobei (installed voice)",
            ReaderVoiceChooser.describe(decision, heart.id),
        )
    }

    // -- Helpers ------------------------------------------------------------------

    private fun choose(
        language: String?,
        primary: VoiceAlias?,
        primaryVoice: VoiceMeta?,
        aliases: List<VoiceAlias> = listOfNotNull(primary),
        pickable: List<VoiceMeta>,
    ) = ReaderVoiceChooser.choose(language, primary, primaryVoice, aliases, pickable)

    private fun voice(key: String) = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":$key") }

    private fun alias(id: String, voice: VoiceMeta, speed: Float = 1.0f, createdAt: Long = 0L) =
        VoiceAlias(
            id = id,
            name = id,
            engine = voice.engine,
            voiceId = voice.id,
            speed = speed,
            effectPreset = "NONE",
            createdAt = createdAt,
        )
}
