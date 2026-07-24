package app.marmalade.tts.engine.api

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking

/**
 * Cloud API engine — synthesis over an OpenAI-compatible `/audio/speech`
 * endpoint. Nothing runs on-device: the request carries text + model +
 * voice + speed, the response is a WAV byte stream. One engine covers
 * every provider that speaks this shape (Venice, OpenAI, …): the voice id
 * names the provider + model (`cloud-api-v1:<provider>:<model>:<voice>`)
 * and [CloudProviderDirectory] supplies the provider's base URL, so adding a
 * provider is data, not code.
 *
 * Latency: with `"streaming": true` Venice sends the first audio bytes in
 * ~0.6 s while synthesis continues server-side, so [synthesizeStream]
 * emits PCM chunks as they arrive — time-to-first-audio is one network
 * round trip, independent of utterance length. (Port of the CLI's
 * `marmalade_tts/engines/api.py`, which carries the provider findings:
 * streaming flag behaviour, and that some Venice models ignore
 * `response_format` and return MP3 — those are excluded via the provider
 * descriptor's modelExclude list, and the WAV header check below fails
 * loudly if one slips through.)
 *
 * "Installed" means "an API key is configured"
 * ([SettingsRepository.cloudApiKeys]) — there is no bundle on disk and no
 * [app.marmalade.tts.install.EngineCatalog] entry. The key never leaves
 * the request's Authorization header and is never logged.
 */
@Singleton
class CloudApiEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val providers: CloudProviderDirectory,
    private val http: CloudSpeechHttp,
) : TtsEngine {

    override val engineName: String = CloudApiVoiceCatalog.ENGINE
    override val sampleRate: Int = CloudApiVoiceCatalog.SAMPLE_RATE

    /**
     * Sentence-scale requests: the provider caps input at 4096 chars, but
     * smaller chunks keep each HTTP request short and let the existing
     * chunker/streaming pipeline interleave network and playback.
     */
    override val maxInputChars: Int = 1000

    override fun isInstalled(): Boolean =
        // Sync-by-contract; a DataStore snapshot read is a few ms and the
        // one main-thread caller (CheckVoiceDataActivity) already
        // runBlocks a Room read in the same spot.
        runBlocking { settings.cloudApiKeys.first() }.isNotEmpty()

    override fun ensureModelLoaded() {
        if (!isInstalled()) throw EngineNotInstalledException(engineName)
    }

    override fun release() {
        // Nothing held — no sessions, no file handles.
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio {
        val parts = mutableListOf<SynthAudio>()
        synthesizeStream(text, voiceId, speed, phonemizationLanguage)
            .collect { parts.add(it) }
        if (parts.size == 1) return parts[0]
        val total = ShortArray(parts.sumOf { it.pcm.size })
        var offset = 0
        for (part in parts) {
            part.pcm.copyInto(total, offset)
            offset += part.pcm.size
        }
        return SynthAudio(total, parts.firstOrNull()?.sampleRate ?: sampleRate)
    }

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = flow {
        val ref = CloudApiVoiceCatalog.parseVoiceId(voiceId)
            ?: throw IOException("Not a cloud voice id: $voiceId")
        val key = settings.cloudApiKeyFor(ref.providerId).first()
        if (key.isBlank()) throw EngineNotInstalledException(engineName)
        val baseUrl = providers.providerById(ref.providerId)?.baseUrl
            ?: throw IOException("Unknown cloud provider '${ref.providerId}' for $voiceId")

        val body = requestJson(text, ref.modelId, ref.voice, speed)
        http.post("$baseUrl/audio/speech", key, body).use { stream ->
            val header = WavStreamHeader.parse(stream)
            // The service's callback.start() already committed to the
            // catalog rate; a mismatch would play at the wrong pitch, so
            // fail loudly rather than degrade quietly.
            if (header.sampleRate != sampleRate || header.channels != 1) {
                throw IOException(
                    "Cloud API returned unexpected format: " +
                        "${header.sampleRate} Hz, ${header.channels} ch " +
                        "(expected $sampleRate Hz mono)",
                )
            }
            val buf = ByteArray(CHUNK_BYTES)
            var carry: Byte? = null
            while (true) {
                var filled = 0
                if (carry != null) {
                    buf[0] = carry
                    filled = 1
                    carry = null
                }
                while (filled < buf.size) {
                    val n = stream.read(buf, filled, buf.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) break
                if (filled % 2 != 0) {
                    carry = buf[filled - 1]
                    filled -= 1
                }
                if (filled > 0) emit(SynthAudio(pcm16ToShorts(buf, filled), header.sampleRate))
                if (filled < buf.size) break // EOF reached mid-buffer
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Hand-built JSON — org.json is a throwing stub in JVM unit tests
     * (see EffectBlockJson), and this payload is six fields.
     */
    private fun requestJson(text: String, model: String, voice: String, speed: Float): String {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        return """{"model":"${escapeJson(model)}","input":"${escapeJson(text)}",""" +
            """"voice":"${escapeJson(voice)}","response_format":"wav",""" +
            """"speed":$clamped,"streaming":true}"""
    }

    companion object {
        /** ~0.68 s of 24 kHz mono PCM16 per emitted chunk. */
        private const val CHUNK_BYTES = 32 * 1024

        internal fun escapeJson(s: String): String = buildString(s.length + 8) {
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }

        internal fun pcm16ToShorts(bytes: ByteArray, length: Int): ShortArray {
            val out = ShortArray(length / 2)
            for (i in out.indices) {
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort()
            }
            return out
        }
    }
}

/**
 * Minimal RIFF/WAVE header reader for a streaming response: consumes the
 * container preamble and chunk list up to (and including) the `data`
 * chunk header, leaving the stream positioned at the first PCM byte.
 *
 * Chunk sizes in a streamed WAV can be placeholders (the server doesn't
 * know the final length when it writes the header), so the caller reads
 * PCM until EOF rather than trusting `dataSize`.
 */
internal data class WavStreamHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    companion object {
        fun parse(stream: InputStream): WavStreamHeader {
            val din = DataInputStream(stream)
            val riff = ByteArray(12)
            din.readFully(riff)
            if (!riff.startsWith("RIFF") || String(riff, 8, 4) != "WAVE") {
                throw IOException("Cloud API response is not a WAV stream")
            }
            var fmt: WavStreamHeader? = null
            while (true) {
                val chunkHeader = ByteArray(8)
                try {
                    din.readFully(chunkHeader)
                } catch (e: EOFException) {
                    throw IOException("WAV stream ended before a data chunk", e)
                }
                val id = String(chunkHeader, 0, 4)
                val size = leInt(chunkHeader, 4)
                when (id) {
                    "fmt " -> {
                        val body = ByteArray(size)
                        din.readFully(body)
                        val audioFormat = leShort(body, 0)
                        if (audioFormat != 1) {
                            throw IOException("WAV stream is not PCM (format $audioFormat)")
                        }
                        fmt = WavStreamHeader(
                            sampleRate = leInt(body, 4),
                            channels = leShort(body, 2),
                            bitsPerSample = leShort(body, 14),
                        )
                    }
                    "data" -> {
                        return fmt ?: throw IOException("WAV data chunk before fmt chunk")
                    }
                    else -> {
                        // skipNBytes needs API 34 — loop skipBytes instead.
                        var remaining = size
                        while (remaining > 0) {
                            val skipped = din.skipBytes(remaining)
                            if (skipped <= 0) throw IOException("WAV stream truncated in chunk $id")
                            remaining -= skipped
                        }
                    }
                }
            }
        }

        private fun ByteArray.startsWith(ascii: String): Boolean =
            size >= ascii.length && ascii.indices.all { this[it] == ascii[it].code.toByte() }

        private fun leInt(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun leShort(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}

/**
 * Injectable seam for the synthesis POST so JVM unit tests can serve a
 * canned WAV without a network. Mirrors the installer's
 * [app.marmalade.tts.install.HttpFetcher] pattern.
 */
fun interface CloudSpeechHttp {
    /**
     * POST [json] to [url] with `Authorization: Bearer` [apiKey]; return
     * the (streaming) response body.
     *
     * @throws IOException on connection failure or a non-2xx status —
     *         message carries the HTTP code + server error body so the
     *         user-facing failure explains itself (401 bad key, 402 out
     *         of credit, …).
     */
    fun post(url: String, apiKey: String, json: String): InputStream
}

/** Production [CloudSpeechHttp] on HttpURLConnection (no extra deps). */
class UrlCloudSpeechHttp @Inject constructor() : CloudSpeechHttp {
    override fun post(url: String, apiKey: String, json: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = conn.errorStream?.use { err ->
                err.readBytes().toString(Charsets.UTF_8).take(300)
            }.orEmpty()
            conn.disconnect()
            throw IOException("Cloud API HTTP $code: $detail")
        }
        return conn.inputStream
    }
}
