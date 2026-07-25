package app.marmalade.tts.engine.api

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

// -----------------------------------------------------------------------------
// Why this exists
// -----------------------------------------------------------------------------
// Five of Venice's eleven TTS models ignore `response_format` entirely and
// return MP3 whatever you ask for — verified 2026-07-24 by requesting `wav`,
// `pcm` and `flac` from tts-minimax-speech-02-hd, tts-gemini-3-1-flash and
// tts-elevenlabs-turbo-v2-5 and getting byte-identical MP3 back each time.
// There is no request flag that changes this, so the choice is decode or
// drop the models. The CLI has always decoded (ffmpeg, api.py:124-131);
// this brings Android to parity.
//
// Shape: buffer-then-decode, emitting one block. That is not a shortcut —
// every MP3-returning model buffers the whole utterance server-side anyway
// (TTFB ≈ total), so there is no incremental audio to stream and a
// streaming decoder would add complexity for zero latency benefit.
//
// This is a seam because MediaCodec/MediaExtractor are Android framework
// classes with no plain-JVM implementation: without it, every existing
// CloudApiEngine unit test would need Robolectric, and Robolectric's
// MediaCodec shadow doesn't actually decode anything, so the tests would
// assert nothing real. Decode correctness is verified on-device; the tests
// here cover format sniffing and dispatch.
// -----------------------------------------------------------------------------

/** PCM output of a decode: 16-bit mono samples plus the rate they're at. */
data class DecodedAudio(val pcm: ShortArray, val sampleRate: Int) {
    // Kotlin requires these for a data class holding an array; identity is
    // fine since we never compare decoded blocks.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Decodes a compressed audio payload (MP3 today) to 16-bit PCM.
 *
 * Injectable so [CloudApiEngine] stays testable on a plain JVM.
 */
fun interface CompressedAudioDecoder {
    /**
     * Decode [bytes] to PCM. Throws [IOException] if the payload isn't
     * decodable audio — callers treat that the same as a malformed
     * response.
     */
    fun decode(bytes: ByteArray): DecodedAudio
}

/**
 * Real decoder, via the platform's MediaCodec.
 *
 * MP3 decode is mandatory for every Android device per the CDD, so this
 * needs no dependency and carries no licence question — which is why it
 * beats a bundled pure-Java decoder (JLayer and friends are LGPL).
 *
 * [MediaExtractor] needs a seekable source and won't take a byte array, so
 * the payload goes to a temp file in [cacheDir] first. Utterance-sized
 * payloads are tens of KB, and these models have already made us wait
 * seconds for the response, so the write is not material.
 */
class MediaCodecAudioDecoder(private val cacheDir: File) : CompressedAudioDecoder {

    override fun decode(bytes: ByteArray): DecodedAudio {
        val tmp = File.createTempFile("cloud-audio", ".bin", cacheDir)
        try {
            tmp.writeBytes(bytes)
            return decodeFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun decodeFile(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IOException("Compressed response contains no audio track")

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Audio track has no MIME type")
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val pcm = drain(codec, extractor)
                // Providers occasionally return stereo despite mono TTS;
                // downmix rather than fail, since the engine contract is mono.
                return DecodedAudio(if (channels > 1) downmix(pcm, channels) else pcm, rate)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Feed the extractor's samples through [codec] and collect the PCM. */
    private fun drain(codec: MediaCodec, extractor: MediaExtractor): ShortArray {
        val info = MediaCodec.BufferInfo()
        val out = ArrayList<ShortArray>()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)
                        ?: throw IOException("Decoder returned no input buffer")
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED,
                -> Unit
                else -> if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            out += buf.order(ByteOrder.LITTLE_ENDIAN).toShorts()
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        val total = out.sumOf { it.size }
        val merged = ShortArray(total)
        var offset = 0
        for (block in out) {
            block.copyInto(merged, offset)
            offset += block.size
        }
        return merged
    }

    private fun ByteBuffer.toShorts(): ShortArray {
        val shorts = ShortArray(remaining() / 2)
        asShortBuffer().get(shorts)
        return shorts
    }

    /** Average interleaved channels down to mono. */
    private fun downmix(pcm: ShortArray, channels: Int): ShortArray {
        val frames = pcm.size / channels
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[f * channels + c]
            mono[f] = (sum / channels).toShort()
        }
        return mono
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
