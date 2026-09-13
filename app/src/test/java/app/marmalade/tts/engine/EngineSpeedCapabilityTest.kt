package app.marmalade.tts.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.phonemizer.SharedEspeakData
import app.marmalade.tts.ui.screen.NoOpPreferencesDataStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins which engines honour `speed` themselves (issue #7). Pocket can't —
 * its graphs take no speed input — so the services time-stretch instead
 * (`applySpeedFallback`); everyone else must keep saying yes, or their
 * audio would silently start going through an extra OLA stage.
 *
 * Robolectric only for a Context: constructing an engine touches nothing
 * but its own field initialisers, and no model is loaded here.
 */
@RunWith(RobolectricTestRunner::class)
class EngineSpeedCapabilityTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(NoOpPreferencesDataStore)
    private val espeak = SharedEspeakData(
        targetDir = File(ctx.filesDir, "espeak-ng-data"),
        dataVersion = "test",
        copyAssets = { },
    )

    @Test
    fun `pocket cannot do speed natively`() {
        assertFalse(PocketEngine(ctx, settings).supportsNativeSpeed)
        assertFalse(PocketDevEngine(ctx).supportsNativeSpeed)
    }

    @Test
    fun `kokoro and kitten do speed natively`() {
        assertTrue(KokoroDirectEngine(ctx, settings, espeak).supportsNativeSpeed)
        assertTrue(KittenDirectEngine(ctx, settings, espeak).supportsNativeSpeed)
    }
}
