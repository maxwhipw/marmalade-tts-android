package app.marmalade.tts.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parsing tests for the provider-descriptor document and the live
 * `/models?type=tts` discovery response. Robolectric because the parser
 * uses org.json, which is a throwing stub on the plain JVM (SDK 34 —
 * see VoiceMetaDaoTest for why that level).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudProvidersTest {

    @Test
    fun `parses a providers document`() {
        val providers = CloudProviders.parse(
            """
            {"version": 1, "providers": [
              {"id": "venice", "displayName": "Venice",
               "baseUrl": "https://api.venice.ai/api/v1/",
               "keyHint": "venice.ai", "discoverVoices": true,
               "modelExclude": ["tts-qwen3"],
               "models": [{"id": "tts-kokoro", "voices": ["af_heart", "am_adam"]}]},
              {"id": "openai", "displayName": "OpenAI",
               "baseUrl": "https://api.openai.com/v1",
               "models": [{"id": "tts-1", "displayName": "TTS-1", "voices": ["alloy"]}]}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, providers.size)
        val venice = providers[0]
        assertEquals("venice", venice.id)
        // Trailing slash must be stripped — the engine appends /audio/speech.
        assertEquals("https://api.venice.ai/api/v1", venice.baseUrl)
        assertTrue(venice.discoverVoices)
        assertEquals(listOf("tts-qwen3"), venice.modelExclude)
        // displayName omitted on the model → falls back to the id.
        assertEquals("tts-kokoro", venice.models.single().displayName)
        assertEquals(listOf("af_heart", "am_adam"), venice.models.single().voices)

        val openai = providers[1]
        assertFalse(openai.discoverVoices)
        assertEquals("TTS-1", openai.models.single().displayName)
    }

    @Test
    fun `bundled asset parses and covers venice + openai`() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("assets/cloud-providers.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        // Robolectric merges src/main/assets onto the test classpath; if
        // the location ever moves, read the file directly.
            ?: java.io.File("src/main/assets/cloud-providers.json").readText()
        val providers = CloudProviders.parse(json)

        val ids = providers.map { it.id }
        assertTrue("venice" in ids)
        assertTrue("openai" in ids)
        for (p in providers) {
            assertTrue("provider ${p.id} has no models", p.models.isNotEmpty())
            assertTrue(
                "provider ${p.id} has no voices",
                p.models.all { it.voices.isNotEmpty() },
            )
            assertFalse(p.baseUrl.endsWith("/"))
        }
        // Venice's fallback list matches the pre-provider static catalog size.
        assertEquals(
            54,
            providers.first { it.id == "venice" }.models.single().voices.size,
        )
    }

    @Test
    fun `discovery response parses models with voices and applies excludes`() {
        val models = CloudProviders.parseDiscoveredModels(
            """
            {"data": [
              {"id": "tts-kokoro", "type": "tts",
               "model_spec": {"name": "Kokoro", "voices": ["af_heart", "bm_fable"]}},
              {"id": "tts-qwen3-235b", "type": "tts",
               "model_spec": {"name": "Qwen3 TTS", "voices": ["qw_a"]}},
              {"id": "not-tts-no-voices", "type": "tts", "model_spec": {"name": "x"}}
            ]}
            """.trimIndent(),
            exclude = listOf("tts-qwen3"),
        )

        val kokoro = models.single()
        assertEquals("tts-kokoro", kokoro.id)
        assertEquals("Kokoro", kokoro.displayName)
        assertEquals(listOf("af_heart", "bm_fable"), kokoro.voices)
    }

    @Test
    fun `discovery response without data is empty`() {
        assertTrue(CloudProviders.parseDiscoveredModels("{}", emptyList()).isEmpty())
    }
}
