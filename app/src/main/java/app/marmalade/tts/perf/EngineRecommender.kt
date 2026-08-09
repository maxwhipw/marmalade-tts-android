package app.marmalade.tts.perf

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   DeviceCapability.probe()  ──►  DeviceProbe(measuredKittenRtf, computeScore)
//                                       │
//                                       ▼
//                            EngineRecommender.recommend()
//                                       │
//                                       ▼
//                         EngineRecommendation(order, fits)
//                                       │
//                                       ▼
//              OnboardingViewModel.engines ──► card order + Recommended /
//                                              "May be slow" labels
// -----------------------------------------------------------------------------

/** How well one engine is expected to run on this device. */
enum class EngineFit {
    /** Best pick for this phone — gets the Recommended pill. */
    RECOMMENDED,

    /** Runs acceptably. No label; the card is offered plainly. */
    FINE,

    /** Predicted to synthesize slower than comfortable — warn before download. */
    MAY_BE_SLOW,
}

/**
 * Per-engine fit plus the onboarding card order this device should see.
 *
 * [order] holds engine names best-first. Engines absent from it (the
 * developer-only diagnostics) keep their catalog position at the tail.
 */
data class EngineRecommendation(
    val order: List<String>,
    val fits: Map<String, EngineFit>,
    /** The predicted Kokoro RTF the buckets were derived from. */
    val predictedKokoroRtf: Double,
)

/**
 * Turns a device probe into "which engine should this phone install".
 *
 * Pure logic — no Android types, no I/O, no injection. Everything the
 * decision needs arrives in [DeviceProbe], which is what makes the
 * bucket boundaries cheap to pin in unit tests.
 */
object EngineRecommender {

    /**
     * Kokoro costs about 1.5× Kitten per second of audio.
     *
     * Single-device anchor: Pixel 8a, 2026-08-08 — Kokoro RTF 0.42 vs
     * Kitten RTF 0.28. Both engines are ORT-CPU matmul-bound on the same
     * perf cluster, so the ratio should travel better across devices than
     * either absolute number does. Re-derive if a second datapoint
     * disagrees rather than averaging blind.
     */
    const val KOKORO_RTF_PER_KITTEN_RTF: Double = 1.5

    /**
     * Pocket costs roughly 2× Kokoro. Anchored on the same device's
     * time-to-first-audio (Pocket 4.7 s vs Kokoro 1.9 s); Pocket is
     * autoregressive so its cost curve is shaped differently from the
     * one-shot engines, and 2× is the deliberately round approximation.
     */
    const val POCKET_COST_VS_KOKORO: Double = 2.0

    /** Below this predicted RTF the engine is the device's best pick. */
    const val RECOMMENDED_MAX_RTF: Double = 0.6

    /** Up to this predicted RTF the engine is offered without a warning. */
    const val FINE_MAX_RTF: Double = 0.9

    /**
     * Tier-1 anchor. The Pixel 8a's Tensor G3 has 1× Cortex-X1 @ 2.91 GHz
     * plus 4× Cortex-A78 @ 2.37 GHz in its non-efficiency clusters, so
     * `1×2.91 + 4×2.37 = 12.39`, and that device measures Kokoro at ~0.45
     * RTF cold. RTF is modelled as inversely proportional to compute score:
     * twice the weighted perf silicon, half the RTF.
     */
    const val ANCHOR_COMPUTE_SCORE: Double = 12.39

    /** Kokoro RTF measured on the device [ANCHOR_COMPUTE_SCORE] describes. */
    const val ANCHOR_KOKORO_RTF: Double = 0.45

    /**
     * Tier-1 pessimism factor. The heuristic ignores memory bandwidth,
     * thermal headroom and ORT kernel coverage, all of which cut the wrong
     * way on cheap silicon — so an unmeasured device is quoted 15% slower
     * than the raw ratio says. A device wrongly warned can still install;
     * a device wrongly praised gets a stuttering first impression.
     */
    const val TIER1_CONSERVATISM: Double = 1.15

    /**
     * Predicted Kokoro RTF for this device, or null when the probe carries
     * no usable signal (no measurement and unreadable sysfs) — callers then
     * fall back to the catalog's static ordering.
     */
    fun predictedKokoroRtf(probe: DeviceProbe): Double? {
        probe.measuredKittenRtf?.let { return it * KOKORO_RTF_PER_KITTEN_RTF }
        val score = probe.computeScore ?: return null
        if (score <= 0.0) return null
        return ANCHOR_KOKORO_RTF * (ANCHOR_COMPUTE_SCORE / score) * TIER1_CONSERVATISM
    }

    /**
     * Fit + ordering for the three production engines, or null when the
     * probe says nothing (see [predictedKokoroRtf]).
     *
     * Ordering follows the heavy engine: when Kokoro is the device's best
     * pick it leads, otherwise the baked Kitten leads and Kokoro follows.
     * Pocket is always last — it's the heaviest engine on every device we
     * can predict, and it is never given the Recommended pill even on a
     * phone fast enough to bucket it there, so exactly one card wears it.
     */
    fun recommend(probe: DeviceProbe): EngineRecommendation? {
        val kokoroRtf = predictedKokoroRtf(probe) ?: return null
        val kokoroFit = bucket(kokoroRtf)
        val pocketFit = bucket(kokoroRtf * POCKET_COST_VS_KOKORO).atMost(EngineFit.FINE)
        // Kitten is the baked floor and the fastest engine everywhere, so it
        // is never warned about; it takes the pill whenever Kokoro doesn't.
        val kittenFit =
            if (kokoroFit == EngineFit.RECOMMENDED) EngineFit.FINE else EngineFit.RECOMMENDED

        val order = if (kokoroFit == EngineFit.RECOMMENDED) {
            listOf(KOKORO, KITTEN, POCKET)
        } else {
            listOf(KITTEN, KOKORO, POCKET)
        }
        return EngineRecommendation(
            order = order,
            fits = mapOf(KITTEN to kittenFit, KOKORO to kokoroFit, POCKET to pocketFit),
            predictedKokoroRtf = kokoroRtf,
        )
    }

    private fun bucket(rtf: Double): EngineFit = when {
        rtf < RECOMMENDED_MAX_RTF -> EngineFit.RECOMMENDED
        rtf <= FINE_MAX_RTF -> EngineFit.FINE
        else -> EngineFit.MAY_BE_SLOW
    }

    /** Demote a fit that outranks [cap]; leave weaker ones alone. */
    private fun EngineFit.atMost(cap: EngineFit): EngineFit =
        if (ordinal < cap.ordinal) cap else this

    private const val KITTEN = KittenDirectVoiceCatalog.ENGINE
    private const val KOKORO = KokoroDirectVoiceCatalog.ENGINE
    private const val POCKET = PocketVoiceCatalog.ENGINE
}
