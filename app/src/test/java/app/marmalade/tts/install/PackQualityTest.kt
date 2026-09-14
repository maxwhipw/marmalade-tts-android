package app.marmalade.tts.install

import app.marmalade.tts.ui.components.packQualityLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pack-quality grade scale and the grades the catalog ships.
 *
 * Worth a test on two counts. First, [PackQuality.meterFill] is not decoration:
 * it is both the number of lit meter segments and the key the meter colours
 * itself by, so an off-by-one would render "Excellent" in the same red as
 * "Rough recording". Second, the grades are Max's listening judgement — an
 * agent "correcting" one to match a pack's upstream `qualityTier` is exactly
 * the mistake this file exists to catch.
 */
class PackQualityTest {

    @Test
    fun theScaleIsLinearAndFillsOneToFourSegments() {
        assertEquals(
            listOf(4, 3, 2, 1),
            PackQuality.entries.map { it.meterFill },
        )
        // Declaration order must be best-first: it is an ordered scale, and
        // `entries` order is what any future "sort by quality" would use.
        assertEquals(
            listOf(
                PackQuality.EXCELLENT,
                PackQuality.GOOD,
                PackQuality.BASIC,
                PackQuality.ROUGH,
            ),
            PackQuality.entries.toList(),
        )
        for (quality in PackQuality.entries) {
            assertTrue(
                "$quality: meterFill must fit a 4-segment meter, was ${quality.meterFill}",
                quality.meterFill in 1..4,
            )
        }
    }

    @Test
    fun everyGradeHasItsOwnLabel() {
        // A missing branch would be a compile error; a copy-pasted one would
        // silently label two grades the same, which is worse than no label.
        val labels = PackQuality.entries.map { packQualityLabelRes(it) }
        assertEquals(labels.size, labels.distinct().size)
        for (res in labels) assertNotEquals(0, res)
    }

    @Test
    fun theShippedPacksCarryMaxsGrades() {
        // DO NOT "fix" these to match `qualityTier`. They are listening
        // judgements owned by Max (VoicePackCatalog's kdoc says so); changing
        // one means he listened again and decided differently.
        val expected = mapOf(
            "uk-lada-x_low" to PackQuality.BASIC,
            "is-bui-medium" to PackQuality.GOOD,
            "is-salka-medium" to PackQuality.GOOD,
            "is-steinn-medium" to PackQuality.GOOD,
            "is-ugla-medium" to PackQuality.GOOD,
            "sv-nst-medium" to PackQuality.GOOD,
            "kk-issai-high" to PackQuality.GOOD,
            "no-nvcc-medium" to PackQuality.ROUGH,
            "uk-ukrainian_tts-medium" to PackQuality.GOOD,
        )
        assertEquals(expected, VoicePackCatalog.all.associate { it.id to it.quality })
    }

    @Test
    fun theNorwegianPackIsTheOneThatAdmitsItSoundsRough() {
        // Max shipped NVCC anyway ("something is better than nothing") on the
        // condition that the label says plainly what it is. If this ever reads
        // GOOD, the honesty half of that deal is gone.
        assertEquals(PackQuality.ROUGH, VoicePackCatalog.NO_NVCC_MEDIUM.quality)
        assertEquals(1, VoicePackCatalog.NO_NVCC_MEDIUM.quality.meterFill)
    }

    @Test
    fun everyVoiceInheritsItsPacksGrade() {
        // The picker rows read the grade off PackVoice, so a speaker that
        // dropped it would show no grade at all for a 10-speaker pack.
        for (pack in VoicePackCatalog.all) {
            for (voice in pack.voices) {
                assertEquals("${voice.voiceKey}", pack.quality, voice.quality)
            }
        }
    }
}
