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
 * Pins which engines honour `speed` themselves. Pocket can't — its graphs
 * take no speed input (issue #7) — and Kokoro shouldn't, because its speed
 * tensor saturates and slurs (2026-09-12). Both let the services
 * time-stretch instead (`applySpeedFallback`). Each value here is a
 * decision, not an accident: flipping one silently re-routes that engine's
 * audio through (or around) the OLA stage.
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

    /**
     * Kokoro's `speed` tensor works but saturates (~2.2x for a requested
     * 3.0x) and smears articulation, so user speed became a time-stretch
     * on 2026-09-12 — matching the CLI. Flipping this back to true would
     * silently reinstate the saturating path.
     */
    @Test
    fun `kokoro defers speed to the time-stretch`() {
        assertFalse(KokoroDirectEngine(ctx, settings, espeak).supportsNativeSpeed)
    }

    @Test
    fun `kitten does speed natively`() {
        assertTrue(KittenDirectEngine(ctx, settings, espeak).supportsNativeSpeed)
    }
}
