package app.marmalade.tts.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack-management list's derivation rules: grouping, per-row action and
 * progress, and the aggregate counts the engine card shows.
 *
 * Pure logic with sharp edges, which is why it's tested here rather than
 * through the UI: the action mapping decides whether a mid-download row offers
 * a second "Install" button, and the summary decides whether the engine card
 * claims nine packs the user hasn't got.
 */
class VoicePackRowsTest {

    private val engine = VoicePackCatalog.VITS_MARMALADE_ENGINE

    @Test
    fun packsAreGroupedByLanguageInCatalogOrder() {
        val groups = voicePackGroups(engine, emptyMap())
        // Languages in first-appearance order; the two Ukrainian packs land in
        // ONE group even though they sit at opposite ends of the catalog.
        assertEquals(
            listOf("uk-UA", "is-IS", "sv-SE", "kk-KZ", "nb-NO"),
            groups.map { it.languageCode },
        )
        val ukrainian = groups.first { it.languageCode == "uk-UA" }
        assertEquals(
            listOf("uk-lada-x_low", "uk-ukrainian_tts-medium"),
            ukrainian.rows.map { it.pack.id },
        )
        // Every catalog pack appears exactly once across the groups.
        assertEquals(
            VoicePackCatalog.forEngine(engine).map { it.id }.sorted(),
            groups.flatMap { group -> group.rows.map { it.pack.id } }.sorted(),
        )
    }

    @Test
    fun aGroupCountsTheVoicesOfEveryPackBeneathIt() {
        val groups = voicePackGroups(engine, emptyMap())
        // 4 single-speaker Icelandic packs.
        assertEquals(4, groups.first { it.languageCode == "is-IS" }.voiceCount)
        // One pack, ten speakers.
        assertEquals(10, groups.first { it.languageCode == "nb-NO" }.voiceCount)
        // x_low Lada (1) + the 3-speaker medium pack.
        assertEquals(4, groups.first { it.languageCode == "uk-UA" }.voiceCount)
    }

    @Test
    fun anUnprobedPackReadsAsNotInstalledRatherThanDisappearing() {
        // The section must draw its full list before the disk probe finishes —
        // an empty map is "we haven't looked yet", not "nothing exists".
        val groups = voicePackGroups(engine, emptyMap())
        val rows = groups.flatMap { it.rows }
        assertEquals(VoicePackCatalog.forEngine(engine).size, rows.size)
        for (row in rows) {
            assertEquals(InstallState.NotInstalled, row.state)
            assertEquals(VoicePackAction.INSTALL, row.action)
            assertFalse(row.isUsable)
        }
    }

    @Test
    fun eachStateMapsToExactlyOneAction() {
        val pack = VoicePackCatalog.SV_NST_MEDIUM
        assertEquals(
            VoicePackAction.INSTALL,
            VoicePackRow(pack, InstallState.NotInstalled).action,
        )
        assertEquals(
            VoicePackAction.UNINSTALL,
            VoicePackRow(pack, InstallState.Installed).action,
        )
        assertEquals(
            VoicePackAction.RETRY,
            VoicePackRow(pack, InstallState.Failed("no network")).action,
        )
        assertEquals(
            VoicePackAction.REINSTALL,
            VoicePackRow(pack, InstallState.Corrupt).action,
        )
        assertEquals(
            VoicePackAction.UPDATE,
            VoicePackRow(pack, InstallState.Outdated(null, "abc")).action,
        )
        // In-flight rows offer NO action — a second tap would start a
        // concurrent install of the same pack.
        assertNull(VoicePackRow(pack, InstallState.Downloading(1L, 2L, "")).action)
        assertNull(VoicePackRow(pack, InstallState.Extracting(1L, 2L)).action)
    }

    @Test
    fun busyAndUsableReadTheStateTheWayTheUiNeeds() {
        val pack = VoicePackCatalog.SV_NST_MEDIUM
        assertTrue(VoicePackRow(pack, InstallState.Downloading(0L, 0L, "")).isBusy)
        assertTrue(VoicePackRow(pack, InstallState.Extracting(0L, 1L)).isBusy)
        assertFalse(VoicePackRow(pack, InstallState.Installed).isBusy)

        assertTrue(VoicePackRow(pack, InstallState.Installed).isUsable)
        // Outdated = a newer archive was published, not a broken install. The
        // voices still speak, so they must not vanish from the picker.
        assertTrue(VoicePackRow(pack, InstallState.Outdated(null, "abc")).isUsable)
        assertFalse(VoicePackRow(pack, InstallState.Corrupt).isUsable)
        assertFalse(VoicePackRow(pack, InstallState.Failed("boom")).isUsable)
    }

    @Test
    fun progressIsAFractionWhileBusyAndNullWhenTheTotalIsUnknown() {
        val pack = VoicePackCatalog.SV_NST_MEDIUM
        assertEquals(
            0.5f,
            VoicePackRow(pack, InstallState.Downloading(50L, 100L, "")).progress!!,
            1e-6f,
        )
        assertEquals(
            0.25f,
            VoicePackRow(pack, InstallState.Extracting(25L, 100L)).progress!!,
            1e-6f,
        )
        // A 0-byte total would divide by zero; null tells the UI to draw an
        // indeterminate bar rather than a stalled 0%.
        assertNull(VoicePackRow(pack, InstallState.Downloading(0L, 0L, "")).progress)
        // Over-report (the extraction estimate can undershoot) clamps instead
        // of overflowing the bar.
        assertEquals(
            1f,
            VoicePackRow(pack, InstallState.Extracting(300L, 100L)).progress!!,
            1e-6f,
        )
        assertNull(VoicePackRow(pack, InstallState.Installed).progress)
    }

    @Test
    fun theFailureReasonIsCarriedOnlyByAFailedRow() {
        val pack = VoicePackCatalog.SV_NST_MEDIUM
        assertEquals(
            "sha256 mismatch",
            VoicePackRow(pack, InstallState.Failed("sha256 mismatch")).failureReason,
        )
        assertNull(VoicePackRow(pack, InstallState.Installed).failureReason)
    }

    @Test
    fun theSummaryCountsPacksLanguagesAndWhatIsActuallyOnDisk() {
        val all = voicePackSummary(engine, emptyMap())
        assertEquals(9, all.packCount)
        assertEquals(5, all.languageCount)
        assertEquals(0, all.installedCount)

        val partial = voicePackSummary(
            engine,
            mapOf(
                "uk-lada-x_low" to InstallState.Installed,
                // Outdated still counts as installed — the files work.
                "sv-nst-medium" to InstallState.Outdated(null, "abc"),
                // These do not.
                "kk-issai-high" to InstallState.Downloading(1L, 2L, ""),
                "no-nvcc-medium" to InstallState.Corrupt,
            ),
        )
        assertEquals(9, partial.packCount)
        assertEquals(2, partial.installedCount)
    }

    @Test
    fun anEngineWithNoPacksSummarisesToZeroRatherThanThrowing() {
        // The engine card asks for a summary per engine; a non-pack engine
        // (or an unknown name from a stale route) must be a quiet zero.
        val summary = voicePackSummary("kokoro-direct-v1_0", emptyMap())
        assertEquals(0, summary.packCount)
        assertEquals(0, summary.languageCount)
        assertEquals(0, summary.installedCount)
        assertTrue(voicePackGroups("not-an-engine", emptyMap()).isEmpty())
    }
}
