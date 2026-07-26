package app.marmalade.tts.audio

import app.marmalade.tts.data.BuiltinEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trips effect chains through [EffectBlockJson]. Robolectric so the
 * Android-bundled `org.json` is the real implementation (plain JVM unit tests
 * return stubbed defaults).
 */
@RunWith(RobolectricTestRunner::class)
class EffectBlockJsonTest {

    @Test
    fun `every block type round-trips`() {
        val chain = listOf(
            EffectBlock.Reverb(reverberance = 80f),
            EffectBlock.Echo(gainIn = 0.6f, gainOut = 0.6f, delayMs = 120f, decay = 0.3f),
            EffectBlock.Overdrive(gainDb = 20f),
            EffectBlock.Pitch(cents = -300f),
            EffectBlock.Tempo(factor = 0.95f),
            EffectBlock.Bandpass(lowHz = 300f, highHz = 3400f),
            EffectBlock.Vol(factor = 1.5f),
            EffectBlock.Treble(db = 4f),
            EffectBlock.Bass(db = 6f),
            EffectBlock.Mid(freqHz = 1000f, gainDb = 6f),
            EffectBlock.Lowpass(freqHz = 3000f),
            EffectBlock.Highpass(freqHz = 300f),
            EffectBlock.Tremolo(speedHz = 5f, depth = 0.5f),
            EffectBlock.Flanger(speedHz = 0.5f, depthMs = 2f),
            EffectBlock.Chorus(speedHz = 0.25f, depthMs = 2f),
            EffectBlock.Phaser(speedHz = 0.5f, decay = 0.4f),
            EffectBlock.Compressor(thresholdDb = -20f, ratio = 4f),
            EffectBlock.Bitcrush(bits = 8f, downsample = 4f),
            EffectBlock.RingMod(freqHz = 60f, mix = 0.6f),
            EffectBlock.Monotone(targetHz = 160f),
        )
        val decoded = EffectBlockJson.decode(EffectBlockJson.encode(chain))
        assertEquals("round-trip must preserve the chain exactly", chain, decoded)
    }

    @Test
    fun `empty chain round-trips to empty`() {
        assertEquals(emptyList<EffectBlock>(), EffectBlockJson.decode(EffectBlockJson.encode(emptyList())))
    }

    @Test
    fun `built-in effects decode back to their CLI block lists`() {
        // The seeded JSON must decode to exactly the canonical preset chains,
        // so a seeded built-in renders identically to the CLI preset.
        fun blocksOf(id: String) =
            EffectBlockJson.decode(BuiltinEffects.seedRows.first { it.id == id }.blocksJson)

        assertEquals(EffectChain.CAVE_BLOCKS, blocksOf(BuiltinEffects.CAVE_ID))
        assertEquals(EffectChain.TELEPHONE_BLOCKS, blocksOf(BuiltinEffects.TELEPHONE_ID))
        assertEquals(EffectChain.CHIPMUNK_BLOCKS, blocksOf(BuiltinEffects.CHIPMUNK_ID))
        assertEquals(EffectChain.DEEP_BLOCKS, blocksOf(BuiltinEffects.DEEP_ID))
        assertEquals(EffectChain.STADIUM_BLOCKS, blocksOf(BuiltinEffects.STADIUM_ID))
        assertEquals(EffectChain.MEGAPHONE_BLOCKS, blocksOf(BuiltinEffects.MEGAPHONE_ID))
        // Curated voice stackups (E-K).
        assertEquals(EffectChain.BROADCASTER_BLOCKS, blocksOf(BuiltinEffects.BROADCASTER_ID))
        assertEquals(EffectChain.PODCAST_BLOCKS, blocksOf(BuiltinEffects.PODCAST_ID))
        assertEquals(EffectChain.TRAILER_BLOCKS, blocksOf(BuiltinEffects.TRAILER_ID))
        assertEquals(EffectChain.AUDIOBOOK_BLOCKS, blocksOf(BuiltinEffects.AUDIOBOOK_ID))
        assertEquals(EffectChain.WALKIE_TALKIE_BLOCKS, blocksOf(BuiltinEffects.WALKIE_TALKIE_ID))
        assertEquals(EffectChain.VINTAGE_RADIO_BLOCKS, blocksOf(BuiltinEffects.VINTAGE_RADIO_ID))
        assertEquals(EffectChain.INTERCOM_BLOCKS, blocksOf(BuiltinEffects.INTERCOM_ID))
        assertEquals(EffectChain.UNDERWATER_BLOCKS, blocksOf(BuiltinEffects.UNDERWATER_ID))
        assertEquals(EffectChain.AI_BLOCKS, blocksOf(BuiltinEffects.AI_ID))
        assertEquals(EffectChain.ETHEREAL_BLOCKS, blocksOf(BuiltinEffects.ETHEREAL_ID))
        assertEquals(EffectChain.DRAGON_BLOCKS, blocksOf(BuiltinEffects.DRAGON_ID))
        // Android-only stackups (E-L).
        assertEquals(EffectChain.CYBORG_BLOCKS, blocksOf(BuiltinEffects.CYBORG_ID))
        assertEquals(EffectChain.EIGHT_BIT_BLOCKS, blocksOf(BuiltinEffects.EIGHT_BIT_ID))
        assertEquals(EffectChain.GLITCH_BLOCKS, blocksOf(BuiltinEffects.GLITCH_ID))
    }

    @Test
    fun `unknown block types are skipped`() {
        val json = """[{"type":"vol","factor":1.5},{"type":"warp","amount":3.0}]"""
        val decoded = EffectBlockJson.decode(json)
        assertEquals("unknown block dropped, known kept", 1, decoded.size)
        assertTrue(decoded[0] is EffectBlock.Vol)
    }
}
