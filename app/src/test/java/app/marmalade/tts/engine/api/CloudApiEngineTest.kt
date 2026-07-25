package app.marmalade.tts.engine.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.cloud.CloudModel
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.engine.EngineNotInstalledException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// -----------------------------------------------------------------------------
// Fixtures
// -----------------------------------------------------------------------------

/** DataStore that satisfies the base-class constructor; never collected. */
private object NoOpDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flowOf(emptyPreferences())
    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = emptyPreferences()
}

private class FakeKeySettings(vararg keys: Pair<String, String>) :
    SettingsRepository(NoOpDataStore) {
    val keyState = MutableStateFlow(keys.toMap().filterValues { it.isNotBlank() })
    override val cloudApiKeys: Flow<Map<String, String>> = keyState
    override fun cloudApiKeyFor(providerId: String): Flow<String> =
        keyState.map { it[providerId] ?: "" }
    override val anyCloudApiKeySet: Flow<Boolean> = keyState.map { it.isNotEmpty() }
    override suspend fun setCloudApiKey(providerId: String, value: String) {
        keyState.value =
            if (value.isBlank()) keyState.value - providerId
            else keyState.value + (providerId to value.trim())
    }
}

private val VENICE = CloudProvider(
    id = "venice",
    displayName = "Venice",
    baseUrl = "https://api.venice.ai/api/v1",
    keyHint = "venice.ai",
    discoverVoices = true,
    models = listOf(
        CloudModel("tts-kokoro", "Kokoro", listOf("af_heart"), sampleRate = 24_000),
        // A 48 kHz model, so the tests can prove the engine checks against
        // the model's declared rate rather than an engine-wide constant.
        CloudModel("tts-gradium-v1", "Gradium", listOf("Alice"), sampleRate = 48_000),
    ),
)

private val FAKE_DIRECTORY = CloudProviderDirectory { id ->
    VENICE.takeIf { id == "venice" }
}

/** Serves a canned response and records the request. */
private class FakeHttp(private val response: () -> InputStream) : CloudSpeechHttp {
    var lastUrl: String? = null
    var lastKey: String? = null
    var lastJson: String? = null
    override fun post(url: String, apiKey: String, json: String): InputStream {
        lastUrl = url
        lastKey = apiKey
        lastJson = json
        return response()
    }
}

/** Build a minimal valid WAV byte stream around [samples]. */
private fun wavBytes(
    samples: ShortArray,
    sampleRate: Int = 24000,
    channels: Int = 1,
    extraChunkBeforeData: Boolean = false,
): ByteArray {
    val out = ByteArrayOutputStream()
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
    out.write("RIFF".toByteArray()); le32(36 + samples.size * 2)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); le32(16)
    le16(1); le16(channels); le32(sampleRate)
    le32(sampleRate * channels * 2); le16(channels * 2); le16(16)
    if (extraChunkBeforeData) {
        out.write("LIST".toByteArray()); le32(4); out.write("INFO".toByteArray())
    }
    out.write("data".toByteArray()); le32(samples.size * 2)
    for (s in samples) le16(s.toInt() and 0xFFFF)
    return out.toByteArray()
}

private fun engine(
    key: String = "test-key",
    http: CloudSpeechHttp = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(10))) },
) = CloudApiEngine(FakeKeySettings("venice" to key), FAKE_DIRECTORY, http)

// -----------------------------------------------------------------------------
// Tests
// -----------------------------------------------------------------------------

class CloudApiEngineTest {

    private val voiceId = CloudApiVoiceCatalog.voiceId("venice", "tts-kokoro", "af_heart")

    @Test
    fun `synthesize returns decoded PCM and sends expected request`() = runTest {
        val samples = ShortArray(1000) { (it * 3 - 200).toShort() }
        val http = FakeHttp { ByteArrayInputStream(wavBytes(samples)) }
        val audio = engine(http = http).synthesize("hello there", voiceId, 1.5f)

        assertEquals(24000, audio.sampleRate)
        assertTrue(audio.pcm.contentEquals(samples))
        assertEquals("${VENICE.baseUrl}/audio/speech", http.lastUrl)
        assertEquals("test-key", http.lastKey)
        val json = http.lastJson!!
        assertTrue(json.contains("\"model\":\"tts-kokoro\""))
        assertTrue(json.contains("\"input\":\"hello there\""))
        assertTrue(json.contains("\"voice\":\"af_heart\""))
        assertTrue(json.contains("\"speed\":1.5"))
        assertTrue(json.contains("\"streaming\":true"))
        assertTrue(json.contains("\"response_format\":\"wav\""))
    }

    @Test
    fun `legacy 2-part voice id still resolves to venice kokoro`() = runTest {
        val http = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(4))) }
        engine(http = http).synthesize("x", "cloud-api-v1:af_sky", 1.0f)
        assertEquals("${VENICE.baseUrl}/audio/speech", http.lastUrl)
        assertTrue(http.lastJson!!.contains("\"model\":\"tts-kokoro\""))
        assertTrue(http.lastJson!!.contains("\"voice\":\"af_sky\""))
    }

    @Test
    fun `unknown provider fails loudly without a request`() = runTest {
        val http = FakeHttp { fail("no request expected"); throw AssertionError() }
        val eng = CloudApiEngine(
            FakeKeySettings("venice" to "k", "nope" to "k2"),
            FAKE_DIRECTORY,
            http,
        )
        try {
            eng.synthesize("x", CloudApiVoiceCatalog.voiceId("nope", "m", "v"), 1.0f)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("nope"))
        }
        assertNull(http.lastUrl)
    }

    @Test
    fun `speed is clamped to provider range`() = runTest {
        val http = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(4))) }
        engine(http = http).synthesize("x", voiceId, 9.0f)
        assertTrue(http.lastJson!!.contains("\"speed\":4.0"))
    }

    @Test
    fun `stream emits multiple chunks for long audio`() = runTest {
        // 3 × 32 KiB chunk size → at least 3 emissions.
        val samples = ShortArray(3 * 16 * 1024 + 123) { it.toShort() }
        val http = FakeHttp { ByteArrayInputStream(wavBytes(samples)) }
        val chunks = engine(http = http)
            .synthesizeStream("long text", voiceId, 1.0f).toList()

        assertTrue("expected >1 chunk, got ${chunks.size}", chunks.size > 1)
        val joined = ShortArray(chunks.sumOf { it.pcm.size })
        var off = 0
        for (c in chunks) { c.pcm.copyInto(joined, off); off += c.pcm.size }
        assertTrue(joined.contentEquals(samples))
    }

    @Test
    fun `extra riff chunks before data are skipped`() = runTest {
        val samples = ShortArray(64) { it.toShort() }
        val http = FakeHttp {
            ByteArrayInputStream(wavBytes(samples, extraChunkBeforeData = true))
        }
        val audio = engine(http = http).synthesize("x", voiceId, 1.0f)
        assertTrue(audio.pcm.contentEquals(samples))
    }

    @Test
    fun `missing provider key throws EngineNotInstalled before any request`() = runTest {
        val http = FakeHttp { fail("no request expected"); throw AssertionError() }
        try {
            engine(key = "", http = http).synthesize("x", voiceId, 1.0f)
            fail("expected EngineNotInstalledException")
        } catch (_: EngineNotInstalledException) {
        }
        assertNull(http.lastUrl)
    }

    @Test
    fun `unexpected sample rate fails loudly`() = runTest {
        val http = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(8), sampleRate = 44100)) }
        try {
            engine(http = http).synthesize("x", voiceId, 1.0f)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("44100"))
        }
    }

    @Test
    fun `a 48 kHz model accepts 48 kHz audio`() = runTest {
        // Regression guard for the bug this replaced: the engine used to
        // compare against a hardcoded 24000, so Gradium — fast, streaming,
        // native WAV — was rejected purely for being 48 kHz.
        val gradium = CloudApiVoiceCatalog.voiceId("venice", "tts-gradium-v1", "Alice")
        val http = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(8), sampleRate = 48000)) }
        val audio = engine(http = http).synthesize("x", gradium, 1.0f)
        assertEquals(48000, audio.sampleRate)
    }

    @Test
    fun `the rate check is per model not per engine`() = runTest {
        // 24 kHz is correct for Kokoro but wrong for Gradium. If the check
        // ever regresses to an engine-wide constant this passes silently and
        // system TTS plays the utterance at half speed.
        val gradium = CloudApiVoiceCatalog.voiceId("venice", "tts-gradium-v1", "Alice")
        val http = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(8), sampleRate = 24000)) }
        try {
            engine(http = http).synthesize("x", gradium, 1.0f)
            fail("expected IOException — 24 kHz is not Gradium's declared rate")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("48000"))
        }
    }

    @Test
    fun `non-wav response fails loudly`() = runTest {
        val http = FakeHttp { ByteArrayInputStream("{\"error\":\"nope\"}".toByteArray()) }
        try {
            engine(http = http).synthesize("x", voiceId, 1.0f)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not a WAV"))
        }
    }

    @Test
    fun `isInstalled reflects any key presence`() {
        assertTrue(engine(key = "k").isInstalled())
        assertTrue(!engine(key = "").isInstalled())
    }

    @Test
    fun `json escaping handles quotes newlines and control chars`() {
        assertEquals(
            "say \\\"hi\\\"\\nline2\\ttab",
            CloudApiEngine.escapeJson("say \"hi\"\nline2\ttab"),
        )
        assertEquals("\\u0001", CloudApiEngine.escapeJson("\u0001"))
        assertEquals("back\\\\slash", CloudApiEngine.escapeJson("back\\slash"))
    }
}

class CloudApiVoiceCatalogTest {

    @Test
    fun `voice id round-trips through parse`() {
        val id = CloudApiVoiceCatalog.voiceId("openai", "tts-1", "alloy")
        val ref = CloudApiVoiceCatalog.parseVoiceId(id)!!
        assertEquals("openai", ref.providerId)
        assertEquals("tts-1", ref.modelId)
        assertEquals("alloy", ref.voice)
    }

    @Test
    fun `legacy 2-part id parses to venice kokoro`() {
        val ref = CloudApiVoiceCatalog.parseVoiceId("cloud-api-v1:af_heart")!!
        assertEquals("venice", ref.providerId)
        assertEquals("tts-kokoro", ref.modelId)
        assertEquals("af_heart", ref.voice)
    }

    @Test
    fun `other engines' ids parse to null`() {
        assertNull(CloudApiVoiceCatalog.parseVoiceId("kokoro-direct-v1_0:af_heart"))
        assertNull(CloudApiVoiceCatalog.parseVoiceId("cloud-api-v1:a:b:c:d"))
    }

    @Test
    fun `voiceMeta derives language and gender for kokoro-style keys only`() {
        val model = VENICE.models.first { it.id == "tts-kokoro" }
        val kokoro = CloudApiVoiceCatalog.voiceMeta(VENICE, model, "jf_alpha")
        assertEquals("ja-JP", kokoro.languageCode)
        assertEquals("female", kokoro.gender)

        // OpenAI-style names must not hit the prefix heuristics —
        // "ballad" would read as en-GB and "echo" as es-ES.
        val ballad = CloudApiVoiceCatalog.voiceMeta(VENICE, model, "ballad")
        assertEquals("en-US", ballad.languageCode)
        assertNull(ballad.gender)
    }

    @Test
    fun `voiceMeta carries the model's sample rate into the row`() {
        // The row is what MarmaladeTtsService reads to decide what rate to
        // commit in callback.start(), so a model's rate has to survive the
        // trip into Room rather than being flattened to the engine default.
        val gradium = VENICE.models.first { it.id == "tts-gradium-v1" }
        assertEquals(48_000, CloudApiVoiceCatalog.voiceMeta(VENICE, gradium, "Alice").sampleRate)

        val kokoro = VENICE.models.first { it.id == "tts-kokoro" }
        assertEquals(24_000, CloudApiVoiceCatalog.voiceMeta(VENICE, kokoro, "af_heart").sampleRate)
    }
}
