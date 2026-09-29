package app.marmalade.tts.perf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.perf.CpuClusterDetector.Cluster
import app.marmalade.tts.phonemizer.SharedEspeakData
import app.marmalade.tts.ui.screen.NoOpPreferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DeviceCapability]'s Tier 1 score and its benchmark-retry policy.
 *
 * Robolectric only for the Context the Kitten engine is constructed with; no
 * model is ever loaded — the fake reports the engine as not installed, which
 * is one of the benchmark's failure exits.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceCapabilityTest {

    // -- Tier 1 ----------------------------------------------------------------

    @Test
    fun `perf clusters score cores times GHz`() {
        val clusters = listOf(
            Cluster(maxFreqKhz = 1_800_000, cpuCount = 4),
            Cluster(maxFreqKhz = 2_400_000, cpuCount = 3),
            Cluster(maxFreqKhz = 3_000_000, cpuCount = 1),
        )
        assertEquals(3 * 2.4 + 1 * 3.0, computeScoreOf(clusters)!!, 1e-9)
    }

    @Test
    fun `a single cluster counts every core`() {
        assertEquals(8 * 2.0, computeScoreOf(listOf(Cluster(2_000_000, 8)))!!, 1e-9)
    }

    @Test
    fun `no clusters is no signal`() {
        assertNull(computeScoreOf(emptyList()))
    }

    /** The emulator: four policies, every cpuinfo_max_freq 0. Unknown, not slow. */
    @Test
    fun `clusters without a frequency are no signal, not a zero score`() {
        assertNull(computeScoreOf(List(4) { Cluster(maxFreqKhz = 0, cpuCount = 1) }))
    }

    @Test
    fun `a frequency-less cluster doesn't drag the others down`() {
        val clusters = listOf(Cluster(0, 2), Cluster(1_500_000, 4), Cluster(2_500_000, 2))
        assertEquals(2 * 2.5, computeScoreOf(clusters)!!, 1e-9)
    }

    // -- Tier 2 retry policy ---------------------------------------------------

    @Test
    fun `a failed benchmark is not re-run on every probe`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = NoMeasurementSettings()
        val kitten = CountingKitten(ctx, settings)
        val capability = DeviceCapability(kitten, settings)

        assertNull(capability.probe().measuredKittenRtf)
        assertNull(capability.probe().measuredKittenRtf)
        assertNull(capability.probe().measuredKittenRtf)

        assertEquals(1, kitten.installChecks)
    }

    /** No stored measurement, and the baked seed already landed. */
    private class NoMeasurementSettings : SettingsRepository(NoOpPreferencesDataStore) {
        override val kittenRtfMeasurement: Flow<KittenRtfMeasurement?> = flowOf(null)
        override val bakedDefaultSeeded: Flow<Boolean> = flowOf(true)
    }

    /** Never installed, so every benchmark attempt fails; counts the attempts. */
    private class CountingKitten(ctx: Context, settings: SettingsRepository) : KittenDirectEngine(
        ctx,
        settings,
        SharedEspeakData(
            targetDir = File(ctx.filesDir, "espeak-ng-data"),
            dataVersion = "test",
            copyAssets = { },
        ),
    ) {
        var installChecks = 0

        override fun isInstalled(): Boolean {
            installChecks++
            return false
        }
    }
}
