package app.marmalade.tts.service

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.engine.TtsEngine

/**
 * What the services should actually ask for, once the engine's speed
 * capability has been taken into account: the [speed] handed to the
 * engine, and the effect chain applied to its output.
 */
data class SpeedPlan(val speed: Float, val blocks: List<EffectBlock>)

/**
 * Rate change for engines that can't do it themselves.
 *
 * Engines with `supportsNativeSpeed = false` (Pocket TTS — autoregressive
 * graphs with no speed input) get speed = 1.0 and an
 * [EffectBlock.Tempo] block prepended to the chain: sox-tempo-style
 * overlap-add time-stretch, pitch preserved. `Tempo.factor` uses the same
 * convention as `speed` — >1 faster and shorter, <1 slower and longer — so
 * the value passes through unconverted.
 *
 * Tempo goes FIRST so the rest of the chain shapes the already-retimed
 * signal; a reverb tail stretched after the fact would smear (the Dragon
 * preset puts its 0.85× Tempo last on purpose, for exactly that reason).
 *
 * Everything else — Kokoro, Kitten, cloud — takes the default true branch
 * and comes out byte-identical.
 */
fun applySpeedFallback(
    engine: TtsEngine,
    speed: Float,
    blocks: List<EffectBlock>,
): SpeedPlan =
    if (engine.supportsNativeSpeed || speed == 1.0f) {
        SpeedPlan(speed, blocks)
    } else {
        SpeedPlan(1.0f, listOf(EffectBlock.Tempo(factor = speed)) + blocks)
    }
