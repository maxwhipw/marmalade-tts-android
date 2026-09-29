package app.marmalade.tts.lang

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.lang.VoiceDecision.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoiceForLanguage] — which voice reads an article or a shared text (Max,
 * 2026-09-28: "Reader should load a language-supported engine if the default
 * alias does not support it", then the same for plain-text shares). The
 * device evidence: Chinese text with primary alias "Heart" (kokoro af_heart,
 * English) was phonemized as Chinese but spoken by the English speaker.
 */
class VoiceForLanguageTest {

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
        assertTrue(VoiceForLanguage.speaks("en-US", "en"))
        assertTrue(VoiceForLanguage.speaks("en-GB", "en"))
        assertTrue(VoiceForLanguage.speaks("en_GB", "EN"))
        assertTrue(VoiceForLanguage.speaks("zh-CN", "zh"))
        assertFalse(VoiceForLanguage.speaks("en-US", "zh"))
        assertFalse(VoiceForLanguage.speaks(null, "en"))
        assertEquals("pt", VoiceForLanguage.languageOf("pt-BR"))
        assertNull(VoiceForLanguage.languageOf(" "))
    }

    /** A British primary reading an English article is not "unsupported". */
    @Test
    fun `a primary of another region of the language is kept`() {
        val decision = choose("en", alias("id-emma", emma), emma, pickable = listOf(heart))

        assertEquals(VoiceChoice.Primary, decision.voice)
        assertEquals(Reason.PrimarySupports, decision.reason)
    }

    // -- Choice order ---------------------------------------------------------------

    @Test
    fun `an unsupported language moves to an installed voice that speaks it`() {
        val decision = choose("zh", heartAlias, heart, pickable = listOf(heart, xiaobei, xiaoni))

        assertEquals(VoiceChoice.Installed(xiaobei.id, kokoro), decision.voice)
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

        assertEquals(VoiceChoice.Alias("id-ni", xiaoni.id, kokoro, 0.9f), decision.voice)
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

        assertEquals("id-a", (decision.voice as VoiceChoice.Alias).aliasId)
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

        assertEquals(VoiceChoice.Installed(xiaobei.id, kokoro), decision.voice)
    }

    @Test
    fun `nothing that speaks it keeps the primary`() {
        val decision = choose("zh", heartAlias, heart, pickable = listOf(heart, dora))

        assertEquals(VoiceChoice.Primary, decision.voice)
        assertEquals(Reason.NoVoiceForLanguage, decision.reason)
    }

    /** Detection abstained — no evidence the primary is wrong, so keep it. */
    @Test
    fun `an undetected language keeps the primary`() {
        val decision = choose(null, heartAlias, heart, pickable = listOf(xiaobei))

        assertEquals(VoiceChoice.Primary, decision.voice)
        assertEquals(Reason.LanguageUnknown, decision.reason)
    }

    @Test
    fun `a primary voice with no catalog row keeps the primary`() {
        val decision = choose("zh", heartAlias, primaryVoice = null, pickable = listOf(xiaobei))

        assertEquals(VoiceChoice.Primary, decision.voice)
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

        assertEquals(VoiceChoice.Installed(xiaobei.id, kokoro), decision.voice)
    }

    /**
     * Max's privacy rule: the auto-pick never sends text to a cloud provider
     * on its own — neither an installed cloud voice nor another alias that
     * uses one. Only a cloud alias the user made primary is used.
     */
    @Test
    fun `neither an installed cloud voice nor a cloud alias is picked`() {
        val cloudZh = VoiceMeta(
            id = "cloud-api-v1:venice:tts-kokoro:zf_xiaobei",
            engine = CloudApiVoiceCatalog.ENGINE,
            displayName = "Xiaobei",
            languageCode = "zh-CN",
            sampleRate = 24_000,
            gender = "female",
        )
        assertEquals(
            VoiceChoice.Primary,
            choose("zh", heartAlias, heart, pickable = listOf(heart, cloudZh)).voice,
        )

        val cloudAlias = alias("id-cloud", cloudZh, createdAt = 2)
        val decision = choose(
            "zh", heartAlias, heart,
            aliases = listOf(heartAlias, cloudAlias),
            pickable = listOf(heart, cloudZh),
        )
        assertEquals(VoiceChoice.Primary, decision.voice)
        assertEquals(Reason.NoVoiceForLanguage, decision.reason)

        // ...and a later on-device alias that speaks it still wins.
        val xiaobeiAlias = alias("id-xiaobei", xiaobei, createdAt = 3)
        val onDevice = choose(
            "zh", heartAlias, heart,
            aliases = listOf(heartAlias, cloudAlias, xiaobeiAlias),
            pickable = listOf(heart, cloudZh, xiaobei),
        )
        assertEquals("id-xiaobei", (onDevice.voice as VoiceChoice.Alias).aliasId)
    }

    /** A cloud alias the user made primary is still used when it speaks the language. */
    @Test
    fun `a cloud primary alias is still used`() {
        val cloudZh = VoiceMeta(
            id = "cloud-api-v1:venice:tts-kokoro:zf_xiaobei",
            engine = CloudApiVoiceCatalog.ENGINE,
            displayName = "Xiaobei",
            languageCode = "zh-CN",
            sampleRate = 24_000,
            gender = "female",
        )
        val cloudPrimary = alias("id-cloud", cloudZh)

        val decision = choose("zh", cloudPrimary, cloudZh, pickable = listOf(cloudZh, xiaobei))

        assertEquals(VoiceChoice.Primary, decision.voice)
        assertEquals(Reason.PrimarySupports, decision.reason)
    }

    /**
     * An OpenAI-style cloud voice speaks whatever it is sent; its row says
     * en-US only as a placeholder. As the primary it must not be judged
     * unable to read French and swapped for an on-device voice.
     */
    @Test
    fun `a multilingual cloud primary is kept for any language`() {
        val alloy = VoiceMeta(
            id = "cloud-api-v1:openai:gpt-4o-mini-tts:alloy",
            engine = CloudApiVoiceCatalog.ENGINE,
            displayName = "alloy",
            languageCode = "en-US",
            sampleRate = 24_000,
            gender = null,
        )
        val frenchVoice = voice("ff_siwis")
        val cloudPrimary = alias("id-alloy", alloy, createdAt = 1)
        val frenchAlias = alias("id-fr", frenchVoice, createdAt = 2)

        val decision = choose(
            "fr", cloudPrimary, alloy,
            aliases = listOf(cloudPrimary, frenchAlias),
            pickable = listOf(alloy, frenchVoice),
        )

        assertEquals(VoiceChoice.Primary, decision.voice)
        assertEquals(Reason.PrimaryVoiceUnknown, decision.reason)
        assertNull(VoiceForLanguage.spokenLanguage(alloy))
    }

    /** A Kokoro-style cloud voice key does carry its language. */
    @Test
    fun `a kokoro-style cloud primary keeps its language`() {
        val cloudHeart = VoiceMeta(
            id = "cloud-api-v1:venice:tts-kokoro:af_heart",
            engine = CloudApiVoiceCatalog.ENGINE,
            displayName = "Heart",
            languageCode = "en-US",
            sampleRate = 24_000,
            gender = "female",
        )
        assertEquals("en-US", VoiceForLanguage.spokenLanguage(cloudHeart))

        val decision = choose("zh", alias("id-cloud-heart", cloudHeart), cloudHeart, pickable = listOf(xiaobei))

        assertEquals(VoiceChoice.Installed(xiaobei.id, kokoro), decision.voice)
    }

    // -- Log line ----------------------------------------------------------------

    @Test
    fun `the log line names the language, the primary and the choice`() {
        val decision = choose("zh", heartAlias, heart, pickable = listOf(xiaobei))

        assertEquals(
            "article lang=zh primary=$kokoro:af_heart unsupported -> $kokoro:zf_xiaobei (installed voice)",
            VoiceForLanguage.describe("article", decision, heart.id),
        )
    }

    // -- Helpers ------------------------------------------------------------------

    private fun choose(
        language: String?,
        primary: VoiceAlias?,
        primaryVoice: VoiceMeta?,
        aliases: List<VoiceAlias> = listOfNotNull(primary),
        pickable: List<VoiceMeta>,
    ) = VoiceForLanguage.choose(language, primary, primaryVoice, aliases, pickable)

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
