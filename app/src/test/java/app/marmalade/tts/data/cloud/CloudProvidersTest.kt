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
        val doc = CloudProviders.parseDocument(
            """
            {"version": 2, "providers": [
              {"id": "venice", "displayName": "Venice",
               "baseUrl": "https://api.venice.ai/api/v1/",
               "keyHint": "venice.ai", "discoverVoices": true,
               "models": [{"id": "tts-kokoro", "voices": ["af_heart", "am_adam"]}]},
              {"id": "openai", "displayName": "OpenAI",
               "baseUrl": "https://api.openai.com/v1",
               "models": [{"id": "tts-1", "displayName": "TTS-1", "sampleRate": 48000,
                           "voices": ["alloy"]}]}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, doc.version)
        val providers = doc.providers
        assertEquals(2, providers.size)
        val venice = providers[0]
        assertEquals("venice", venice.id)
        // Trailing slash must be stripped — the engine appends /audio/speech.
        assertEquals("https://api.venice.ai/api/v1", venice.baseUrl)
        assertTrue(venice.discoverVoices)
        // displayName omitted on the model → falls back to the id.
        assertEquals("tts-kokoro", venice.models.single().displayName)
        assertEquals(listOf("af_heart", "am_adam"), venice.models.single().voices)
        // sampleRate omitted → the historical 24 kHz assumption.
        assertEquals(24_000, venice.models.single().sampleRate)

        val openai = providers[1]
        assertFalse(openai.discoverVoices)
        assertEquals("TTS-1", openai.models.single().displayName)
        assertEquals(48_000, openai.models.single().sampleRate)
    }

    @Test
    fun `document without a version parses as version zero`() {
        // Pre-versioning documents must lose to any versioned copy, so they
        // parse as the oldest possible rather than defaulting to current.
        val doc = CloudProviders.parseDocument(
            """{"providers": [{"id": "v", "displayName": "V", "baseUrl": "https://x/"}]}""",
        )
        assertEquals(0, doc.version)
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
        // Venice's Kokoro entry still carries the full static voice list.
        val venice = providers.first { it.id == "venice" }
        assertEquals(
            54,
            venice.models.first { it.id == "tts-kokoro" }.voices.size,
        )
        // Every allowlisted model must declare a rate that was measured, not
        // guessed — a wrong value here plays the whole utterance at the wrong
        // pitch through system TTS. Measured 2026-07-24.
        val rates = venice.models.associate { it.id to it.sampleRate }
        assertEquals(24_000, rates["tts-kokoro"])
        assertEquals(24_000, rates["tts-xai-v1"])
        assertEquals(48_000, rates["tts-gradium-v1"])
        assertEquals(48_000, rates["tts-inworld-1-5-max"])
        // Models that ignore response_format and return MP3 must stay out.
        for (bad in listOf(
            "tts-qwen3-0-6b", "tts-gemini-3-1-flash",
            "tts-minimax-speech-02-hd", "tts-elevenlabs-turbo-v2-5",
        )) {
            assertFalse("$bad returns MP3 and must not be allowlisted", bad in rates)
        }
    }

    @Test
    fun `discovery response parses every advertised model`() {
        // Filtering is mergeDiscovered's job, not the parser's — the raw
        // response is cached verbatim so it can be re-filtered when the
        // descriptor changes without re-hitting the network.
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
        )

        // Entries with no voices are still dropped — nothing to speak with.
        assertEquals(listOf("tts-kokoro", "tts-qwen3-235b"), models.map { it.id })
        val kokoro = models.first()
        assertEquals("Kokoro", kokoro.displayName)
        assertEquals(listOf("af_heart", "bm_fable"), kokoro.voices)
    }

    @Test
    fun `discovery response without data is empty`() {
        assertTrue(CloudProviders.parseDiscoveredModels("{}").isEmpty())
    }

    @Test
    fun `merge keeps capabilities from the allowlist and voices from discovery`() {
        val allowed = listOf(
            CloudModel("tts-kokoro", "Kokoro", listOf("stale"), sampleRate = 24_000),
            CloudModel("tts-gradium-v1", "Gradium", listOf("Alice"), sampleRate = 48_000),
        )
        val discovered = listOf(
            CloudModel("tts-kokoro", "Kokoro TTS", listOf("af_heart", "bm_fable")),
            // Advertised by the provider but not allowlisted — must be dropped,
            // this is the fail-closed guarantee.
            CloudModel("tts-minimax-speech-02-hd", "MiniMax", listOf("InspirationalGirl")),
        )

        val merged = CloudProviders.mergeDiscovered(allowed, discovered)

        assertEquals(listOf("tts-kokoro", "tts-gradium-v1"), merged.map { it.id })
        val kokoro = merged.first()
        // Voices refreshed from discovery...
        assertEquals(listOf("af_heart", "bm_fable"), kokoro.voices)
        assertEquals("Kokoro TTS", kokoro.displayName)
        // ...but the rate survives, because discovery never carries it.
        assertEquals(24_000, kokoro.sampleRate)
        // A model absent from discovery keeps its static list rather than
        // vanishing, so an unreachable network degrades to the bundled catalog.
        assertEquals(listOf("Alice"), merged[1].voices)
        assertEquals(48_000, merged[1].sampleRate)
    }
}
