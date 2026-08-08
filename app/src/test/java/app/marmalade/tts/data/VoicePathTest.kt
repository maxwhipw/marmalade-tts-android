package app.marmalade.tts.data

import app.marmalade.tts.data.cloud.CloudModel
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers the one-hierarchy resolution that the alias card, the editor's Voice
// row and the picker breadcrumb all read. Regression-guards the raw-id leak
// that prompted the redesign: a cloud alias used to render
// "cloud-api-v1 · venice:tts-elevenlabs-turbo-v2-5:Aria" because the display
// code assumed a 2-part voiceId.
// -----------------------------------------------------------------------------

private val VENICE = CloudProvider(
    id = "venice",
    displayName = "Venice",
    baseUrl = "https://api.venice.ai/api/v1",
    keyHint = "",
    discoverVoices = true,
    models = listOf(
        CloudModel("tts-elevenlabs-turbo-v2-5", "ElevenLabs Turbo v2.5", listOf("Aria"), 44_100),
        CloudModel("tts-kokoro", "Kokoro", listOf("af_heart"), 24_000),
    ),
)

private val DIRECTORY = CloudProviderDirectory { id -> VENICE.takeIf { id == "venice" } }

class VoicePathTest {

    private val resolver = VoicePathResolver(DIRECTORY)

    @Test
    fun `cloud voice resolves provider, model and voice separately`() {
        val path = resolver.resolve(
            CloudApiVoiceCatalog.voiceId("venice", "tts-elevenlabs-turbo-v2-5", "Aria"),
            CloudApiVoiceCatalog.ENGINE,
        )

        assertEquals("Venice", path.source)
        assertEquals("ElevenLabs Turbo v2.5", path.model)
        assertEquals("Aria", path.voice)
        assertTrue(path.isCloud)
        // Three levels, so the middle one carries information and is kept.
        assertEquals("Venice › ElevenLabs Turbo v2.5", path.collapsed)
        assertEquals("ElevenLabs Turbo v2.5 · Aria", path.summary)
    }

    @Test
    fun `on-device voice collapses the redundant model level`() {
        val path = resolver.resolve(
            KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
            KittenDirectVoiceCatalog.ENGINE,
        )

        assertFalse(path.isCloud)
        // The engine IS the model, so `collapsed` must not repeat it —
        // "Kitten Nano › Kitten Nano" would be the giveaway of a bad merge.
        assertEquals(path.source, path.model)
        assertEquals(path.model, path.collapsed)
    }

    @Test
    fun `kokoro voice key renders as its bare speaker name`() {
        val path = resolver.resolve(
            KokoroDirectVoiceCatalog.voiceId("am_adam"),
            KokoroDirectVoiceCatalog.ENGINE,
        )

        assertEquals("Adam", path.voice)
    }

    @Test
    fun `pocket voice key renders capitalized`() {
        val path = resolver.resolve(
            PocketVoiceCatalog.voiceId("marius"),
            PocketVoiceCatalog.ENGINE,
        )

        assertEquals("Marius", path.voice)
    }

    @Test
    fun `an unknown cloud model degrades to its raw id rather than throwing`() {
        // An alias can outlive the model it points at — the user downgrades,
        // or the provider retires a model. A slightly ugly label beats a crash.
        val path = resolver.resolve(
            CloudApiVoiceCatalog.voiceId("venice", "tts-retired", "Ghost"),
            CloudApiVoiceCatalog.ENGINE,
        )

        assertEquals("Venice", path.source)
        assertEquals("tts-retired", path.model)
        assertEquals("Ghost", path.voice)
        assertTrue(path.isCloud)
    }

    @Test
    fun `an unknown provider degrades to its raw id`() {
        val path = resolver.resolve(
            CloudApiVoiceCatalog.voiceId("gone", "tts-x", "Voice"),
            CloudApiVoiceCatalog.ENGINE,
        )

        assertEquals("gone", path.source)
        assertTrue(path.isCloud)
    }

    @Test
    fun `legacy two-part cloud ids still resolve`() {
        // Ids seeded before the provider era (CATALOG_VERSION 25) are
        // `cloud-api-v1:<voice>`; parseVoiceId maps them onto venice/kokoro.
        val path = resolver.resolve("${CloudApiVoiceCatalog.ENGINE}:af_heart", CloudApiVoiceCatalog.ENGINE)

        assertEquals("Venice", path.source)
        assertEquals("Kokoro", path.model)
        assertEquals("af_heart", path.voice)
    }

    @Test
    fun `no raw voice id ever reaches a display string`() {
        // The specific bug this redesign fixed: every user-visible field must
        // be free of colons and the engine key.
        val path = resolver.resolve(
            CloudApiVoiceCatalog.voiceId("venice", "tts-kokoro", "af_heart"),
            CloudApiVoiceCatalog.ENGINE,
        )

        for (shown in listOf(path.source, path.model, path.voice, path.summary)) {
            assertFalse("'$shown' leaks the engine key", shown.contains(CloudApiVoiceCatalog.ENGINE))
            assertFalse("'$shown' leaks a raw id separator", shown.contains(":"))
        }
    }
}
