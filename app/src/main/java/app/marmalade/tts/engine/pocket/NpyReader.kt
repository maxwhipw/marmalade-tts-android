package app.marmalade.tts.engine.pocket

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal NumPy `.npy` v1/v2 reader.
 *
 * Used to load `bos_before_voice.npy` from the Pocket TTS bundle — a
 * `[1, 1, 1024]` float32 tensor that gets prepended to the voice
 * embedding before voice conditioning. (Skipping the prepend was
 * NekoSpeak's bug #1; we read this file at load time and stash the
 * floats on the engine.)
 *
 * Format spec: https://numpy.org/devdocs/reference/generated/numpy.lib.format.html
 *
 * What this supports:
 *  - v1 + v2 magic (`\x93NUMPY` + major/minor version).
 *  - Little-endian little-endian float32 (`'<f4'`) — the only dtype/
 *    endianness we use. Anything else throws.
 *  - C order (`fortran_order: False`). Fortran order throws.
 *
 * What's intentionally not supported:
 *  - Object/pickle dtypes (v3+ feature; security concern anyway).
 *  - Multi-array .npz files (would need a zip reader).
 *  - Big-endian dtypes (none of our bundles use them).
 */
object NpyReader {

    /**
     * Read a float32 `.npy` file from disk. Returns the raw row-major
     * flattened data plus the parsed shape. The caller is responsible
     * for re-interpreting the shape (we use it for shape validation
     * but otherwise treat the data as a flat FloatArray).
     */
    fun readFloat32(file: File): NpyFloat32 {
        val bytes = file.readBytes()
        require(bytes.size >= 10) { "$file is too small to be a valid .npy file" }
        require(
            bytes[0] == 0x93.toByte() &&
                bytes[1] == 'N'.code.toByte() &&
                bytes[2] == 'U'.code.toByte() &&
                bytes[3] == 'M'.code.toByte() &&
                bytes[4] == 'P'.code.toByte() &&
                bytes[5] == 'Y'.code.toByte(),
        ) { "$file does not start with the NUMPY magic" }

        val major = bytes[6].toInt() and 0xFF
        val minor = bytes[7].toInt() and 0xFF
        require(major in 1..3) { "$file: unsupported npy version $major.$minor" }

        // v1 has a 2-byte header length; v2/v3 have 4 bytes.
        val (headerStart, headerLen) = if (major >= 2) {
            val len = (bytes[8].toInt() and 0xFF) or
                ((bytes[9].toInt() and 0xFF) shl 8) or
                ((bytes[10].toInt() and 0xFF) shl 16) or
                ((bytes[11].toInt() and 0xFF) shl 24)
            12 to len
        } else {
            val len = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            10 to len
        }

        val headerStr = String(bytes, headerStart, headerLen, Charsets.US_ASCII).trim()
        // header looks like: {'descr': '<f4', 'fortran_order': False, 'shape': (1, 1, 1024), }
        require(headerStr.contains("'descr': '<f4'") || headerStr.contains("\"descr\": \"<f4\"")) {
            "$file: only little-endian float32 supported, header was: $headerStr"
        }
        require(headerStr.contains("'fortran_order': False") || headerStr.contains("\"fortran_order\": false")) {
            "$file: Fortran order arrays not supported, header was: $headerStr"
        }

        // Parse the shape tuple. It looks like `(1, 1, 1024)` or `(1,)`.
        val shapeMatch = Regex("""['"]shape['"]\s*:\s*\(([^)]*)\)""").find(headerStr)
            ?: throw IllegalStateException("$file: no shape in header: $headerStr")
        val shape = shapeMatch.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
            .toIntArray()

        val dataStart = headerStart + headerLen
        val expectedFloats = shape.fold(1) { acc, d -> acc * d }
        val expectedBytes = expectedFloats * 4
        require(bytes.size - dataStart >= expectedBytes) {
            "$file: data section too short — expected $expectedBytes bytes, got ${bytes.size - dataStart}"
        }

        val data = FloatArray(expectedFloats)
        val buf = ByteBuffer.wrap(bytes, dataStart, expectedBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().get(data)
        return NpyFloat32(shape = shape, data = data)
    }
}

data class NpyFloat32(val shape: IntArray, val data: FloatArray)
