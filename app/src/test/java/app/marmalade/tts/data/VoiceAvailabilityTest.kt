package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.VoicePackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-pack voice filter — the rule that had to exist before VITS Marmalade
 * could leave developer-only.
 *
 * The bug it replaces: the engine verifies as installed the moment ANY of its
 * nine per-language packs is on disk, so an engine-level filter listed all 25
 * voices after a single 18 MB download, and picking an absent one failed at
 * synthesis with `EngineNotInstalledException`. Every case below is one shape
 * of that mistake.
 */
class VoiceAvailabilityTest {

    private val vits = VitsVoiceCatalog.ENGINE

    private fun vitsRow(voiceKey: String) = VitsVoiceCatalog.voices
        .first { it.id == VitsVoiceCatalog.voiceId(voiceKey) }

    private fun row(engine: String, id: String) = VoiceMeta(
        id = id,
        engine = engine,
        displayName = "test",
        languageCode = "en-US",
        sampleRate = 24_000,
        gender = null,
    )

    @Test
    fun aVitsVoiceNeedsItsOwnPackNotJustItsEngine() {
        val assets = InstalledVoiceAssets(
            engines = setOf(vits),
            packs = setOf("uk-lada-x_low"),
        )
        assertTrue(isVoiceAvailable(vitsRow("uk-lada-x_low"), assets))
        // Same engine, different pack, not downloaded → not offered.
        assertFalse(isVoiceAvailable(vitsRow("sv-nst-medium"), assets))
        assertFalse(isVoiceAvailable(vitsRow("kk-issai-high#1"), assets))
    }

    @Test
    fun everySpeakerOfAnInstalledMultiSpeakerPackIsAvailable() {
        // One checkpoint, ten speakers: installing the pack must light up all
        // ten rows, not just the first.
        val assets = InstalledVoiceAssets(engines = setOf(vits), packs = setOf("no-nvcc-medium"))
        val norwegian = VoicePackCatalog.NO_NVCC_MEDIUM.voices
        assertEquals(10, norwegian.size)
        for (voice in norwegian) {
            assertTrue(voice.voiceKey, isVoiceAvailable(vitsRow(voice.voiceKey), assets))
        }
    }

    @Test
    fun noPacksMeansNoVitsVoicesAtAll() {
        val assets = InstalledVoiceAssets(engines = setOf(vits), packs = emptySet())
        assertTrue(VitsVoiceCatalog.voices.none { isVoiceAvailable(it, assets) })
    }

    @Test
    fun anUninstalledEngineHidesItsVoicesEvenWithAPackOnDisk() {
        // Belt and braces: a leftover pack directory under an engine whose own
        // layout check fails must not resurrect its voices.
        val assets = InstalledVoiceAssets(engines = emptySet(), packs = setOf("uk-lada-x_low"))
        assertFalse(isVoiceAvailable(vitsRow("uk-lada-x_low"), assets))
    }

    @Test
    fun aNonPackEngineIsStillFilteredByEngineAlone() {
        // The per-pack rule must not leak onto Kokoro/Kitten/cloud rows, whose
        // voices all arrive in one bundle (or none at all).
        val assets = InstalledVoiceAssets(engines = setOf("kokoro-direct-v1_0"))
        assertTrue(isVoiceAvailable(row("kokoro-direct-v1_0", "kokoro-direct-v1_0:af_bella"), assets))
        assertFalse(isVoiceAvailable(row("kitten-direct-v0_8", "kitten-direct-v0_8:Bella"), assets))
    }

    @Test
    fun aVitsRowWithNoParseablePackIsTreatedAsUnavailable() {
        // Nothing can route this row, so listing it would only buy the user a
        // synthesis failure.
        val assets = InstalledVoiceAssets(engines = setOf(vits), packs = setOf("uk-lada-x_low"))
        assertFalse(isVoiceAvailable(row(vits, vits), assets))
        assertFalse(isVoiceAvailable(row(vits, "$vits:"), assets))
        assertFalse(isVoiceAvailable(row(vits, "totally-wrong"), assets))
    }

    @Test
    fun filterAvailableKeepsOrderAndDropsTheRest() {
        val assets = InstalledVoiceAssets(engines = setOf(vits), packs = setOf("is-salka-medium"))
        val kept = VitsVoiceCatalog.voices.filterAvailable(assets)
        assertEquals(listOf(VitsVoiceCatalog.voiceId("is-salka-medium")), kept.map { it.id })
    }

    @Test
    fun packIdOfParsesTheVoiceIdAndRejectsOtherEngines() {
        assertEquals("uk-lada-x_low", VitsVoiceCatalog.packIdOf("$vits:uk-lada-x_low"))
        assertEquals("kk-issai-high", VitsVoiceCatalog.packIdOf("$vits:kk-issai-high#5"))
        // Parsed, not looked up: a pack this build no longer ships still yields
        // its id (and then reads as "not installed"), rather than null, which
        // would be indistinguishable from a malformed id.
        assertEquals("zz-retired-pack", VitsVoiceCatalog.packIdOf("$vits:zz-retired-pack#2"))
        assertNull(VitsVoiceCatalog.packIdOf("kokoro-direct-v1_0:af_bella"))
        assertNull(VitsVoiceCatalog.packIdOf(vits))
        assertNull(VitsVoiceCatalog.packIdOf("$vits:"))
        assertNull(VitsVoiceCatalog.packIdOf("$vits:#3"))
    }

    @Test
    fun packVoiceOfResolvesTheCatalogEntryBehindARoomRow() {
        // How a picker row reaches the pack's quality grade.
        val iseke = VitsVoiceCatalog.packVoiceOf("$vits:kk-issai-high#1")!!
        assertEquals("Iseke (Kazakh)", iseke.displayName)
        assertEquals(VoicePackCatalog.KK_ISSAI_HIGH.quality, iseke.quality)
        // Unknown speaker / unknown pack / other engine resolve to nothing
        // rather than to a neighbouring speaker.
        assertNull(VitsVoiceCatalog.packVoiceOf("$vits:kk-issai-high#9"))
        assertNull(VitsVoiceCatalog.packVoiceOf("$vits:zz-retired-pack"))
        assertNull(VitsVoiceCatalog.packVoiceOf("kokoro-direct-v1_0:af_bella"))
    }
}
