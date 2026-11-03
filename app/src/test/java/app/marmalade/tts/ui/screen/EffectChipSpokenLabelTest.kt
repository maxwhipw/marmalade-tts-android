package app.marmalade.tts.ui.screen

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectBlock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// -----------------------------------------------------------------------------
// #118 UI-17: what TalkBack reads for an effect card's block chips. Robolectric
// for the merged resources.
// -----------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
class EffectChipSpokenLabelTest {

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    @Test
    fun `abbreviated chips are spoken with full names and units`() {
        assertEquals("Low-pass, 3446 Hz", blockSpokenLabel(resources, EffectBlock.Lowpass(3446f)))
        assertEquals("High-pass, 85 Hz", blockSpokenLabel(resources, EffectBlock.Highpass(85f)))
        assertEquals(
            "Compressor, -22 dB, 3.0:1",
            blockSpokenLabel(resources, EffectBlock.Compressor(thresholdDb = -22f, ratio = 3f)),
        )
        assertEquals(
            "Bitcrush, 7-bit, 8×",
            blockSpokenLabel(resources, EffectBlock.Bitcrush(bits = 7f, downsample = 8f)),
        )
        assertEquals(
            "Mid, 2500 Hz, +2 dB",
            blockSpokenLabel(resources, EffectBlock.Mid(freqHz = 2500f, gainDb = 2f)),
        )
    }

    @Test
    fun `a block with no values is spoken by name alone`() {
        val echo = EffectBlock.Echo(gainIn = 0.6f, gainOut = 0.6f, delayMs = 120f, decay = 0.3f)
        assertEquals("Echo", blockSpokenLabel(resources, echo))
    }

    @Test
    @Config(qualifiers = "es")
    fun `chip labels follow the app language`() {
        val spoken = blockSpokenLabel(resources, EffectBlock.Lowpass(3446f))
        assertEquals(resources.getString(R.string.effects_block_lowpass) + ", 3446 Hz", spoken)
    }
}
