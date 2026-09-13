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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins which engines honour `speed` themselves. Pocket can't — its graphs
 * take no speed input (issue #7) — and Kokoro and Kitten shouldn't,
 * because their speed tensors saturate and slur (2026-09-12). All three
 * let the services time-stretch instead (`applySpeedFallback`); the
 * default in [TtsEngine] stays true for engines that do their own rate,
 * e.g. the cloud providers. Each value here is a decision, not an
 * accident: flipping one silently re-routes that engine's audio through
 * (or around) the OLA stage.
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

    /**
     * Kitten saturates worse than Kokoro — 2.5x and 3.0x render
     * byte-identical audio at about 1.85x — so the user's rate is a
     * time-stretch too. Its per-voice priors are unaffected: they're the
     * voice's blessed pace, not a user rate, and stay in the tensor
     * (pinned by [KittenSpeedPriorTest]).
     */
    @Test
    fun `kitten defers user speed to the time-stretch`() {
        assertFalse(KittenDirectEngine(ctx, settings, espeak).supportsNativeSpeed)
    }
}
