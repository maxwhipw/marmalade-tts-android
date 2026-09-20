package app.marmalade.tts.service

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.engine.TtsEngine

/**
 * What the services should actually ask for, once the engine's speed
 * capability has been taken into account: the [speed] handed to the
 * engine, the effect chain applied to its output, and [playbackRate] —
 * the rate the engine's audio will be consumed at relative to its own
 * clock, i.e. the downstream Tempo factor (1.0 when no time-stretch is
 * applied, including the native-speed branch where the engine already
 * renders at the final rate).
 *
 * Engines don't use [playbackRate] for synthesis — it exists so their
 * streaming pre-roll can budget against the *played* clock: a chunk
 * that will be time-stretched to 2× drains in half its rendered
 * duration, which is what turned Kokoro at 2× into a between-sentence
 * stall on the 8a (warm RTF 0.51–0.56 ≈ realtime once doubled).
 */
data class SpeedPlan(
    val speed: Float,
    val blocks: List<EffectBlock>,
    val playbackRate: Float = 1f,
)

/**
 * Rate change for engines that can't do it themselves.
 *
 * Engines with `supportsNativeSpeed = false` — Pocket (no speed input at
 * all), Kokoro and Kitten (native speed tensors degrade articulation and
 * saturate; see [TtsEngine.supportsNativeSpeed]) — get speed = 1.0 and an
 * [EffectBlock.Tempo] block prepended to the chain: sox-tempo-style
 * overlap-add time-stretch, pitch preserved. `Tempo.factor` uses the same
 * convention as `speed` — >1 faster and shorter, <1 slower and longer — so
 * the value passes through unconverted.
 *
 * Tempo goes FIRST so the rest of the chain shapes the already-retimed
 * signal; a reverb tail stretched after the fact would smear (the Dragon
 * preset puts its 0.85× Tempo last on purpose, for exactly that reason).
 *
 * Engines on the default true branch (cloud API today) come out
 * byte-identical.
 */
fun applySpeedFallback(
    engine: TtsEngine,
    speed: Float,
    blocks: List<EffectBlock>,
): SpeedPlan =
    if (engine.supportsNativeSpeed || speed == 1.0f) {
        SpeedPlan(speed, blocks)
    } else {
        SpeedPlan(1.0f, listOf(EffectBlock.Tempo(factor = speed)) + blocks, playbackRate = speed)
    }
