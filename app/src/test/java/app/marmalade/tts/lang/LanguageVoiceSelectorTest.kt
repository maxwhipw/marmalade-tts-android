package app.marmalade.tts.lang

import app.marmalade.tts.data.InstalledVoiceAssets
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.ui.screen.FakeAliasDao
import app.marmalade.tts.ui.screen.FakeDao
import app.marmalade.tts.ui.screen.FakeSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LanguageVoiceSelector] end to end over fakes — the decision a plain-text
 * share gets (Max, 2026-09-28): detected once on the whole shared text, then
 * [VoiceForLanguage]'s order. The reader goes through the same selector.
 */
class LanguageVoiceSelectorTest {

    private val kokoro = KokoroDirectVoiceCatalog.ENGINE
    private val heart = alias("id-heart", "af_heart", createdAt = 1)

    private val chinese = "这是一个关于果酱的故事。我们今天去市场买了很多橙子，他们说这个应用可以读出文章。"
    private val english = "This is a story about marmalade, and the oranges we bought at the market today."

    @Test
    fun `a shared Chinese text on an English primary moves to an installed Chinese voice`() = runBlocking {
        val choice = selector(heart).select(chinese, "share", "ShareVoice")

        choice as VoiceChoice.Installed
        assertEquals(kokoro, choice.engine)
        assertEquals("zh-CN", KokoroDirectVoiceCatalog.languageFor(choice.voiceId.substringAfter(':')))
    }

    @Test
    fun `a shared text in the primary's language stays on the primary`() = runBlocking {
        assertEquals(VoiceChoice.Primary, selector(heart).select(english, "share", "ShareVoice"))
    }

    @Test
    fun `an alias that speaks the shared text's language beats an installed voice`() = runBlocking {
        val zh = alias("id-zh", "zf_xiaoni", createdAt = 2, speed = 0.9f)

        val choice = selector(heart, zh).select(chinese, "share", "ShareVoice")

        assertEquals(VoiceChoice.Alias("id-zh", zh.voiceId, kokoro, 0.9f), choice)
    }

    /** Nothing on disk speaks it: today's behaviour. */
    @Test
    fun `with no installed voice for the language the primary is kept`() = runBlocking {
        val choice = selector(heart, assets = InstalledVoiceAssets()).select(chinese, "share", "ShareVoice")

        assertEquals(VoiceChoice.Primary, choice)
    }

    @Test
    fun `the whole shared text is what gets detected`() = runBlocking {
        // An English lead-in longer than the reader's 2000-char article
        // sample; a share detects on all of it, so the Chinese majority wins.
        val text = english.repeat(30) + chinese.repeat(80)
        assertTrue(text.substring(0, 2_000).none { it in chinese })

        assertTrue(selector(heart).select(text, "share", "ShareVoice") is VoiceChoice.Installed)
    }

    // -- Helpers -------------------------------------------------------------------

    private fun selector(
        primary: VoiceAlias,
        vararg others: VoiceAlias,
        assets: InstalledVoiceAssets = InstalledVoiceAssets(engines = setOf(kokoro)),
    ): LanguageVoiceSelector {
        val settings = FakeSettings(initialId = primary.voiceId)
        runBlocking { settings.setPrimaryAliasId(primary.id) }
        return LanguageVoiceSelector(
            detector = LangDetector(File("src/main/assets/langdetect.tab").readLines(), systemCjk = null),
            settings = settings,
            aliasDao = FakeAliasDao(initial = listOf(primary) + others),
            voiceDao = FakeDao(KokoroDirectVoiceCatalog.voices),
            probeAssets = { assets },
        )
    }

    private fun alias(id: String, key: String, createdAt: Long, speed: Float = 1.0f) = VoiceAlias(
        id = id,
        name = id,
        engine = kokoro,
        voiceId = KokoroDirectVoiceCatalog.voiceId(key),
        speed = speed,
        effectPreset = "NONE",
        createdAt = createdAt,
    )
}
