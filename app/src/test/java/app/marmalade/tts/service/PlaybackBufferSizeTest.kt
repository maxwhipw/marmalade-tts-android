package app.marmalade.tts.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferSizeTest {

    @Test
    fun bufferIsAWholeNumberOfFrames_atEveryEngineSampleRate() {
        // 16 kHz = VITS x_low packs, 22.05 kHz = VITS medium/high packs,
        // 24 kHz = Kokoro / Kitten / Pocket. 22 050 is the one that used to
        // come out odd (11 025) and make AudioTrack.Builder.build() throw.
        for (rate in listOf(16_000, 22_050, 24_000, 44_100, 48_000)) {
            val bytes = MarmaladeSynthService.quarterSecondBufferBytes(rate)
            assertEquals("odd byte count at $rate Hz", 0, bytes % 2)
        }
    }

    @Test
    fun bufferIsAboutAQuarterSecond() {
        assertEquals(12_000, MarmaladeSynthService.quarterSecondBufferBytes(24_000))
        assertEquals(11_024, MarmaladeSynthService.quarterSecondBufferBytes(22_050))
    }
}
