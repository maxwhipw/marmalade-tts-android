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
        // An MP3-returning model at a third rate, for the decode path.
        CloudModel(
            "tts-minimax-speech-02-hd", "MiniMax Speech-02 HD",
            listOf("InspirationalGirl"), sampleRate = 32_000,
        ),
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

/**
 * Stand-in for MediaCodec, which has no plain-JVM implementation. Records
 * what it was handed so tests can prove the engine sniffed and dispatched
 * correctly; the decode itself is verified on-device.
 */
private class FakeDecoder(
    private val result: () -> DecodedAudio = { DecodedAudio(ShortArray(4), 24_000) },
) : CompressedAudioDecoder {
    var received: ByteArray? = null
    override fun decode(bytes: ByteArray): DecodedAudio {
        received = bytes
        return result()
    }
}

/** What Venice's MP3-returning models actually put on the wire. */
private val ID3_HEADER = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00)

/** Fails the test if the engine tries to decode — WAV must never route here. */
private val REJECTING_DECODER = CompressedAudioDecoder {
    fail("WAV response must not go through the decoder")
    throw AssertionError()
}

private fun engine(
    key: String = "test-key",
    http: CloudSpeechHttp = FakeHttp { ByteArrayInputStream(wavBytes(ShortArray(10))) },
    decoder: CompressedAudioDecoder = REJECTING_DECODER,
) = CloudApiEngine(FakeKeySettings("venice" to key), FAKE_DIRECTORY, http, decoder)

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
            REJECTING_DECODER,
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
    fun `undecodable response fails loudly`() = runTest {
        // A non-RIFF body now goes to the decoder, which is what real
        // MediaCodec does with a JSON error body: no audio track, throw.
        val http = FakeHttp { ByteArrayInputStream("{\"error\":\"nope\"}".toByteArray()) }
        val decoder = CompressedAudioDecoder { throw IOException("no audio track") }
        try {
            engine(http = http, decoder = decoder).synthesize("x", voiceId, 1.0f)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("no audio track", e.message)
        }
    }

    @Test
    fun `mp3 response is decoded rather than rejected`() = runTest {
        // The reported bug: InspirationalGirl (tts-minimax-speech-02-hd)
        // returns MP3 with an ID3v2.4 header no matter what response_format
        // asks for, and the engine used to throw "not a WAV stream".
        val minimax = CloudApiVoiceCatalog.voiceId(
            "venice", "tts-minimax-speech-02-hd", "InspirationalGirl",
        )
        val body = ID3_HEADER + ByteArray(64)
        val pcm = ShortArray(800) { (it * 7).toShort() }
        val decoder = FakeDecoder { DecodedAudio(pcm, 32_000) }

        val audio = engine(http = FakeHttp { ByteArrayInputStream(body) }, decoder = decoder)
            .synthesize("x", minimax, 1.0f)

        assertEquals(32_000, audio.sampleRate)
        assertEquals(pcm.size, audio.pcm.size)
        // The decoder must receive the whole body including the ID3 header —
        // the sniff peeks, it must not consume.
        assertEquals(body.size, decoder.received!!.size)
    }

    @Test
    fun `a decoded rate that contradicts the descriptor fails loudly`() = runTest {
        // Same invariant as the WAV path: the service already committed to
        // the declared rate in callback.start(), so a surprise here would be
        // an inaudible pitch shift rather than an error.
        val minimax = CloudApiVoiceCatalog.voiceId(
            "venice", "tts-minimax-speech-02-hd", "InspirationalGirl",
        )
        val decoder = CompressedAudioDecoder { DecodedAudio(ShortArray(8), 44_100) }
        try {
            engine(
                http = FakeHttp { ByteArrayInputStream(ID3_HEADER + ByteArray(16)) },
                decoder = decoder,
            ).synthesize("x", minimax, 1.0f)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("44100"))
            assertTrue(e.message!!.contains("32000"))
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
