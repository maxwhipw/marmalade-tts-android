package app.marmalade.tts.engine.pocket

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// -----------------------------------------------------------------------------
// Audio helpers for Pocket TTS voice encoding.
//
// `mimi_encoder` expects 24 kHz mono float32 PCM in [-1, 1]. The
// predefined-voice WAVs in `voices/<name>.wav` are 48 kHz / 16-bit / mono,
// so we need a WAV decoder and a 48 → 24 kHz downsampler.
//
// Resampling strategy: averaging decimator for 2:1 downsampling (acts as
// a crude box-filter low-pass that's sufficient for feature extraction).
// For arbitrary rate conversions (the future voice-cloning path, where
// users might supply 44.1 kHz / 96 kHz / weird rates) we fall back to
// linear interpolation. The result is fed to a learned encoder that
// extracts timbral features — sinc-quality resampling isn't worth the
// extra code for this use.
//
// What this module does NOT do (matches the Python ground truth):
//  - No high-pass / hum filter (NekoSpeak adds one; Python doesn't).
//  - No silence trimming / VAD.
//  - No peak normalization (only divide by max when max > 1.0).
// -----------------------------------------------------------------------------

data class WavPcm(val samples: FloatArray, val sampleRate: Int)

object PocketAudio {

    /**
     * Read a mono 16-bit PCM WAV file into [-1, 1] floats. Stereo WAVs
     * are silently mixed to mono (channel average).
     *
     * This is a deliberately minimal parser — handles only what we
     * actually need for the predefined-voice WAVs. Compressed formats,
     * 8/24/32-bit samples, and non-PCM extensions will throw.
     */
    fun readWav(file: File): WavPcm {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "$file: shorter than the minimum WAV header" }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte(),
        ) { "$file: not a RIFF file" }
        require(
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte(),
        ) { "$file: not a WAVE file" }

        // Walk the chunks looking for `fmt ` and `data`. We can't rely on
        // them being at fixed offsets — some WAV writers prepend `LIST`
        // or `JUNK` chunks before `fmt `.
        var pos = 12
        var sampleRate = 0
        var numChannels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = -1
        var dataSize = -1
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = buf.getInt(pos + 4)
            val chunkBodyStart = pos + 8
            when (chunkId) {
                "fmt " -> {
                    audioFormat = buf.getShort(chunkBodyStart).toInt() and 0xFFFF
                    numChannels = buf.getShort(chunkBodyStart + 2).toInt() and 0xFFFF
                    sampleRate = buf.getInt(chunkBodyStart + 4)
                    // bytes 12-15 = byte rate (ignored), 16-17 = block align (ignored)
                    bitsPerSample = buf.getShort(chunkBodyStart + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = chunkBodyStart
                    dataSize = chunkSize
                    break
                }
            }
            pos = chunkBodyStart + chunkSize
            // WAV chunks are padded to even byte boundaries.
            if (chunkSize and 1 == 1) pos++
        }

        require(sampleRate > 0) { "$file: no fmt chunk found" }
        require(dataOffset >= 0) { "$file: no data chunk found" }
        // 1 = PCM, 0xFFFE = WAVE_FORMAT_EXTENSIBLE (treat as PCM if the
        // bitsPerSample matches our supported widths).
        require(audioFormat == 1 || audioFormat == 0xFFFE) {
            "$file: only PCM WAVs are supported (audioFormat=$audioFormat)"
        }
        require(bitsPerSample == 16) {
            "$file: only 16-bit WAVs are supported (got $bitsPerSample)"
        }
        require(numChannels in 1..2) {
            "$file: only mono or stereo supported (got $numChannels channels)"
        }

        val sampleCount = dataSize / 2
        val pcm = ShortArray(sampleCount)
        ByteBuffer.wrap(bytes, dataOffset, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(pcm)

        // Stereo → mono by channel-average. WAV interleaves L,R,L,R...
        val monoFrames = sampleCount / numChannels
        val floats = FloatArray(monoFrames)
        when (numChannels) {
            1 -> {
                for (i in 0 until monoFrames) floats[i] = pcm[i] / 32768f
            }
            2 -> {
                for (i in 0 until monoFrames) {
                    val l = pcm[i * 2].toInt()
                    val r = pcm[i * 2 + 1].toInt()
                    floats[i] = ((l + r) / 2f) / 32768f
                }
            }
        }
        return WavPcm(samples = floats, sampleRate = sampleRate)
    }

    /**
     * Resample [pcm] from [srcRate] to [dstRate]. If they match, returns
     * the input unchanged. For 2:1 (e.g. 48 → 24 kHz) uses an averaging
     * decimator that doubles as a crude low-pass; otherwise falls back
     * to linear interpolation.
     */
    fun resample(pcm: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return pcm
        if (srcRate == 2 * dstRate) {
            // 2:1 averaging decimator — perfect fit for 48 → 24 kHz, the
            // common case for our predefined voices.
            val n = pcm.size / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                out[i] = (pcm[i * 2] + pcm[i * 2 + 1]) * 0.5f
            }
            return out
        }
        // Generic linear interpolation. Acceptable quality for feature
        // extraction; not for direct playback.
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val n = (pcm.size / ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val srcPos = i * ratio
            val src0 = srcPos.toInt()
            val src1 = (src0 + 1).coerceAtMost(pcm.size - 1)
            val frac = (srcPos - src0).toFloat()
            out[i] = pcm[src0] * (1 - frac) + pcm[src1] * frac
        }
        return out
    }

    /**
     * Apply the Python ground-truth normalization: if any sample's
     * absolute value exceeds 1.0, divide by the max. Otherwise no-op.
     * Mutates the input array in place and returns it.
     */
    fun normalizeIfClipping(pcm: FloatArray): FloatArray {
        var max = 0f
        for (s in pcm) {
            val a = if (s < 0f) -s else s
            if (a > max) max = a
        }
        if (max <= 1f) return pcm
        val inv = 1f / max
        for (i in pcm.indices) pcm[i] *= inv
        return pcm
    }
}
