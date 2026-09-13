package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the split that landed on 2026-09-12, when the USER's rate change
 * moved off Kitten's `speed` tensor and onto the service-level
 * time-stretch (`applySpeedFallback`): the per-voice priors did NOT move
 * with it.
 *
 * The priors are Max's picks from the 2026-08-07 ear-lab — part of each
 * voice's blessed sound, and the raw model runs ~25% too fast without
 * them. So the tensor must still carry `prior × incoming speed`, and with
 * the services now pinning the incoming speed at 1.0, that is exactly the
 * prior. A regression that "simplified" this to a flat 1.0 would make
 * every voice rush, quietly, with every test still green — hence this one.
 */
class KittenSpeedPriorTest {

    @Test
    fun `service-driven call at 1x still feeds the voice prior`() {
        // What the services now pass after the tempo fallback claims the
        // user's rate. Bella runs fast and Jasper slow; both must keep
        // their own number rather than collapsing to 1.0.
        assertEquals(1.24f, KittenDirectEngine.effectiveModelSpeed("bella", 1.0f), 1e-6f)
        assertEquals(0.84f, KittenDirectEngine.effectiveModelSpeed("jasper", 1.0f), 1e-6f)
    }

    @Test
    fun `voice name case does not change the prior`() {
        // Voice IDs reach the engine as "kitten-direct-v0_8:Bella" and get
        // split to the capitalised name.
        assertEquals(
            KittenDirectEngine.effectiveModelSpeed("bella", 1.0f),
            KittenDirectEngine.effectiveModelSpeed("Bella", 1.0f),
            0f,
        )
    }

    @Test
    fun `an unknown voice falls back to upstream's uniform prior`() {
        assertEquals(0.8f, KittenDirectEngine.effectiveModelSpeed("nosuchvoice", 1.0f), 1e-6f)
    }

    @Test
    fun `a direct caller's speed still multiplies the prior`() {
        // Benchmarks and the capability probe call the engine directly.
        assertEquals(1.68f, KittenDirectEngine.effectiveModelSpeed("jasper", 2.0f), 1e-6f)
    }
}
