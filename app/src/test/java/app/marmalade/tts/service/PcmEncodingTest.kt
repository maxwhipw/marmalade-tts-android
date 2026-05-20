package app.marmalade.tts.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the PCM16 → little-endian byte encoding feeds Android's
 * `SynthesisCallback.audioAvailable` correctly. Endianness bugs here
 * produce screech instead of speech — silent on a build, audible on the
 * device. Worth a unit test.
 *
 * Calls the encoder directly via the `companion object` reference so we
 * don't need to construct a TextToSpeechService instance (which requires
 * Android framework binding).
 */
class PcmEncodingTest {

    @Test
    fun emptyInputProducesEmptyOutput() {
        val bytes = MarmaladeTtsService.pcm16ToLittleEndianBytes(ShortArray(0))
        assertEquals(0, bytes.size)
    }

    @Test
    fun singleZeroSampleIsTwoZeroBytes() {
        val bytes = MarmaladeTtsService.pcm16ToLittleEndianBytes(shortArrayOf(0))
        assertArrayEquals(byteArrayOf(0, 0), bytes)
    }

    @Test
    fun positiveSampleIsLittleEndian() {
        // 0x1234 -> [0x34, 0x12]
        val bytes = MarmaladeTtsService.pcm16ToLittleEndianBytes(shortArrayOf(0x1234))
        assertArrayEquals(byteArrayOf(0x34, 0x12), bytes)
    }

    @Test
    fun negativeSampleEncodesAsTwosComplementLowByteFirst() {
        // -1 (0xFFFF) -> [0xFF, 0xFF]
        val bytes = MarmaladeTtsService.pcm16ToLittleEndianBytes(shortArrayOf(-1))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), bytes)
    }

    @Test
    fun maxAndMinSamplesEncodeCorrectly() {
        // Short.MAX_VALUE = 0x7FFF -> [0xFF, 0x7F]
        // Short.MIN_VALUE = 0x8000 -> [0x00, 0x80]
        val bytes = MarmaladeTtsService.pcm16ToLittleEndianBytes(
            shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()),
            bytes,
        )
    }

    @Test
    fun outputLengthIsAlwaysDoubleInputLength() {
        for (n in listOf(1, 2, 5, 16, 100, 4096)) {
            val pcm = ShortArray(n) { it.toShort() }
            val bytes = MarmaladeTtsService.pcm16ToLittleEndianBytes(pcm)
            assertEquals("size mismatch at n=$n", n * 2, bytes.size)
        }
    }
}
