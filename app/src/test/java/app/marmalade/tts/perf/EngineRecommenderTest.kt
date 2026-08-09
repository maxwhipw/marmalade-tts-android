package app.marmalade.tts.perf

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bucket coverage for [EngineRecommender].
 *
 * Pure logic with no injected collaborators, so these tests construct the
 * probe directly — no DataStore, no fake SettingsRepository (whose
 * un-overridden flows never emit and would hang a `combine` for 60 s).
 *
 * Boundaries are probed with an epsilon on either side rather than at the
 * exact threshold: the predicted RTF arrives via a floating-point multiply,
 * and pinning behaviour at a value that lands on a rounding tie would make
 * the suite fragile rather than strict.
 */
class EngineRecommenderTest {

    private val kitten = KittenDirectVoiceCatalog.ENGINE
    private val kokoro = KokoroDirectVoiceCatalog.ENGINE
    private val pocket = PocketVoiceCatalog.ENGINE

    /** A measured probe whose predicted Kokoro RTF is [kokoroRtf]. */
    private fun measured(kokoroRtf: Double) = DeviceProbe(
        measuredKittenRtf = kokoroRtf / EngineRecommender.KOKORO_RTF_PER_KITTEN_RTF,
        computeScore = EngineRecommender.ANCHOR_COMPUTE_SCORE,
    )

    // -- buckets ---------------------------------------------------------------

    @Test
    fun fastDeviceRecommendsKokoroAndListsItFirst() {
        // Pixel 8a anchor: Kitten 0.28 → Kokoro 0.42, comfortably under 0.6.
        val rec = EngineRecommender.recommend(
            DeviceProbe(measuredKittenRtf = 0.28, computeScore = 12.39),
        )!!

        assertEquals(0.42, rec.predictedKokoroRtf, 1e-9)
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kokoro])
        // Exactly one Recommended pill — Kitten steps aside for Kokoro.
        assertEquals(EngineFit.FINE, rec.fits[kitten])
        // Pocket at 0.84 is still inside the no-warning band.
        assertEquals(EngineFit.FINE, rec.fits[pocket])
    }

    @Test
    fun midDeviceKeepsKokoroUnwarnedAndHandsKittenThePill() {
        val rec = EngineRecommender.recommend(measured(0.75))!!

        assertEquals(EngineFit.FINE, rec.fits[kokoro])
        // Kitten leads, so it takes the pill.
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kitten])
        // Pocket at 1.5 is past the warning line.
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[pocket])
    }

    @Test
    fun slowDeviceWarnsOnKokoroAndPocket() {
        val rec = EngineRecommender.recommend(measured(1.2))!!

        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[kokoro])
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[pocket])
    }

    // -- boundaries ------------------------------------------------------------

    @Test
    fun recommendedBoundaryIsExclusiveAtTheTop() {
        val eps = 1e-6
        assertEquals(
            EngineFit.RECOMMENDED,
            EngineRecommender.recommend(measured(EngineRecommender.RECOMMENDED_MAX_RTF - eps))!!
                .fits[kokoro],
        )
        assertEquals(
            EngineFit.FINE,
            EngineRecommender.recommend(measured(EngineRecommender.RECOMMENDED_MAX_RTF + eps))!!
                .fits[kokoro],
        )
    }

    @Test
    fun fineBoundaryIsInclusiveAtTheTop() {
        val eps = 1e-6
        assertEquals(
            EngineFit.FINE,
            EngineRecommender.recommend(measured(EngineRecommender.FINE_MAX_RTF - eps))!!
                .fits[kokoro],
        )
        assertEquals(
            EngineFit.MAY_BE_SLOW,
            EngineRecommender.recommend(measured(EngineRecommender.FINE_MAX_RTF + eps))!!
                .fits[kokoro],
        )
    }

    // -- the Pocket shift ------------------------------------------------------

    @Test
    fun pocketBucketsOnDoubleTheKokoroCost() {
        // Predicted Kokoro 0.44 → Pocket 0.88, one notch inside FINE. Nudging
        // Kokoro to 0.46 pushes Pocket to 0.92 and over the warning line while
        // Kokoro itself stays Recommended — that gap is the ×2 shift.
        assertEquals(EngineFit.FINE, EngineRecommender.recommend(measured(0.44))!!.fits[pocket])

        val nudged = EngineRecommender.recommend(measured(0.46))!!
        assertEquals(EngineFit.RECOMMENDED, nudged.fits[kokoro])
        assertEquals(EngineFit.MAY_BE_SLOW, nudged.fits[pocket])
    }

    @Test
    fun pocketNeverTakesTheRecommendedPill() {
        // Flagship-fast: Kokoro 0.2 → Pocket 0.4, which buckets Recommended
        // on the raw thresholds. Pocket is capped at FINE so only one card
        // wears the pill.
        val rec = EngineRecommender.recommend(measured(0.2))!!
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kokoro])
        assertEquals(EngineFit.FINE, rec.fits[pocket])
    }

    @Test
    fun kittenIsNeverWarnedAboutEvenOnAVerySlowDevice() {
        val rec = EngineRecommender.recommend(measured(4.0))!!
        assertNotEquals(EngineFit.MAY_BE_SLOW, rec.fits[kitten])
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kitten])
    }

    // -- Tier 1 fallback -------------------------------------------------------

    @Test
    fun nullMeasurementFallsBackToTheComputeScore() {
        val rec = EngineRecommender.recommend(
            DeviceProbe(
                measuredKittenRtf = null,
                computeScore = EngineRecommender.ANCHOR_COMPUTE_SCORE,
            ),
        )!!

        // The anchor device itself, quoted through the pessimism factor.
        assertEquals(
            EngineRecommender.ANCHOR_KOKORO_RTF * EngineRecommender.TIER1_CONSERVATISM,
            rec.predictedKokoroRtf,
            1e-9,
        )
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kokoro])
    }

    @Test
    fun tier1IsConservativeRelativeToARealMeasurement() {
        val score = EngineRecommender.ANCHOR_COMPUTE_SCORE
        val tier1 = EngineRecommender.predictedKokoroRtf(
            DeviceProbe(measuredKittenRtf = null, computeScore = score),
        )!!
        assertTrue(
            "Tier 1 must round toward slower than the anchor it is built on",
            tier1 > EngineRecommender.ANCHOR_KOKORO_RTF,
        )
    }

    @Test
    fun halfTheComputeScorePredictsRoughlyDoubleTheRtf() {
        val full = EngineRecommender.predictedKokoroRtf(
            DeviceProbe(null, EngineRecommender.ANCHOR_COMPUTE_SCORE),
        )!!
        val half = EngineRecommender.predictedKokoroRtf(
            DeviceProbe(null, EngineRecommender.ANCHOR_COMPUTE_SCORE / 2),
        )!!
        assertEquals(full * 2, half, 1e-9)
    }

    @Test
    fun weakSiliconIsWarnedAboutWithoutAMeasurement() {
        // A budget chip a third of the anchor's weighted perf silicon:
        // 0.45 × 3 × 1.15 = 1.55 predicted, clearly past the warning line.
        val rec = EngineRecommender.recommend(
            DeviceProbe(null, EngineRecommender.ANCHOR_COMPUTE_SCORE / 3),
        )!!
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[kokoro])
    }

    @Test
    fun measurementWinsOverTheComputeScoreWhenBothArePresent() {
        // Anchor-class silicon that actually benchmarks slowly — the real
        // measurement must not be diluted by the topology guess.
        val rec = EngineRecommender.recommend(
            DeviceProbe(
                measuredKittenRtf = 1.0,
                computeScore = EngineRecommender.ANCHOR_COMPUTE_SCORE,
            ),
        )!!
        assertEquals(1.5, rec.predictedKokoroRtf, 1e-9)
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[kokoro])
    }

    // -- no signal at all ------------------------------------------------------

    @Test
    fun noMeasurementAndNoComputeScoreYieldsNoRecommendation() {
        assertNull(EngineRecommender.recommend(DeviceProbe(null, null)))
        assertNull(EngineRecommender.predictedKokoroRtf(DeviceProbe(null, null)))
    }

    @Test
    fun nonsensicalComputeScoreIsTreatedAsNoSignal() {
        assertNull(EngineRecommender.recommend(DeviceProbe(null, 0.0)))
        assertNull(EngineRecommender.recommend(DeviceProbe(null, -1.0)))
    }
}
