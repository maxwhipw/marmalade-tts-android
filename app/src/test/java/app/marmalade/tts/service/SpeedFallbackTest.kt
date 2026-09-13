package app.marmalade.tts.service

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins [applySpeedFallback], the one place the services decide whether a
 * rate change happens in the engine or in the effect chain (issue #7 —
 * Pocket TTS silently dropped `speed`).
 */
class SpeedFallbackTest {

    /** Minimal [TtsEngine] — only the capability flag matters here. */
    private class FakeEngine(override val supportsNativeSpeed: Boolean) : TtsEngine {
        override val engineName = "fake"
        override val sampleRate = 24_000
        override fun isInstalled() = true
        override fun isLoaded() = true
        override fun ensureModelLoaded() = Unit
        override fun release() = Unit
        override suspend fun synthesize(
            text: String,
            voiceId: String,
            speed: Float,
            phonemizationLanguage: String?,
        ): SynthAudio = SynthAudio(ShortArray(0), sampleRate)
    }

    private val reverb = EffectBlock.Reverb(reverberance = 40f)
    private val blocks = listOf<EffectBlock>(reverb)

    @Test
    fun `native engine passes speed straight through`() {
        val plan = applySpeedFallback(FakeEngine(supportsNativeSpeed = true), 2.0f, blocks)
        assertEquals(2.0f, plan.speed, 0f)
        assertSame(blocks, plan.blocks)
    }

    @Test
    fun `non-native engine at 1x is untouched`() {
        val plan = applySpeedFallback(FakeEngine(supportsNativeSpeed = false), 1.0f, blocks)
        assertEquals(1.0f, plan.speed, 0f)
        assertSame(blocks, plan.blocks)
    }

    @Test
    fun `non-native engine gets a Tempo block ahead of the chain`() {
        val plan = applySpeedFallback(FakeEngine(supportsNativeSpeed = false), 2.0f, blocks)
        assertEquals(1.0f, plan.speed, 0f)
        assertEquals(listOf(EffectBlock.Tempo(factor = 2.0f), reverb), plan.blocks)
    }

    @Test
    fun `Tempo factor is the speed, not its reciprocal`() {
        // sox `tempo` and our `speed` share the convention: >1 faster and
        // shorter. Half speed must stay 0.5, never 2.0.
        val plan = applySpeedFallback(FakeEngine(supportsNativeSpeed = false), 0.5f, emptyList())
        assertEquals(listOf(EffectBlock.Tempo(factor = 0.5f)), plan.blocks)
    }

    @Test
    fun `an empty chain still gets the stretch`() {
        val plan = applySpeedFallback(FakeEngine(supportsNativeSpeed = false), 1.5f, emptyList())
        assertEquals(1.0f, plan.speed, 0f)
        assertEquals(listOf(EffectBlock.Tempo(factor = 1.5f)), plan.blocks)
    }
}
