package app.marmalade.tts.service

import android.content.Intent
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Engine narrowing and intent parsing for the long-form foreground service.
 *
 * The two of them are the whole of what a JVM test can reach here: everything
 * past them needs audio focus, a notification channel and real ONNX sessions.
 * Narrowing is also where the bug was — the cloud and dev-Pocket engines were
 * absent from the dispatch list, so aliases pointing at them were synthesized
 * with Kokoro and no error was raised anywhere.
 *
 * Robolectric only so the bare Service can be constructed; no injected
 * field is touched, and [knownEngineOrDefault] is pure string logic.
 */
@RunWith(RobolectricTestRunner::class)
class MarmaladeSynthServiceTest {

    private val service = MarmaladeSynthService()

    @Test
    fun `every engine the app can alias survives narrowing`() {
        val engines = listOf(
            KokoroDirectVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
            PocketDevVoiceCatalog.ENGINE,
            VitsVoiceCatalog.ENGINE,
            CloudApiVoiceCatalog.ENGINE,
        )
        for (engine in engines) {
            assertEquals(engine, service.knownEngineOrDefault(engine))
        }
    }

    // -- Session speed multiplier ---------------------------------------------

    /**
     * The reader's per-article speed rides its own extra so it can *scale* the
     * speed the primary alias resolves to instead of replacing it (EXTRA_SPEED
     * is an override, and on the alias route the alias wins over it anyway).
     * These cover the parse; the multiply itself is one line in `runOne`, past
     * where a JVM test can reach.
     */
    @Test
    fun `the speed multiplier is carried when the caller sends it`() {
        val request = service.parseRequest(speakIntent(0.75f))

        assertEquals(0.75f, request!!.speedMultiplier, 0f)
    }

    /**
     * Every non-reader caller — share sheet, tile, Tasker, the Speak screen —
     * omits the extra, and must be spoken exactly as before: 1.0 is the
     * identity for the multiply in `runOne`.
     */
    @Test
    fun `a request without the extra is unaffected`() {
        val request = service.parseRequest(speakIntent(multiplier = null))

        assertEquals(1.0f, request!!.speedMultiplier, 0f)
    }

    /** A zero or negative factor would silence the engine; degrade, don't fail. */
    @Test
    fun `a nonsense multiplier degrades to no change`() {
        assertEquals(1.0f, service.parseRequest(speakIntent(0f))!!.speedMultiplier, 0f)
        assertEquals(1.0f, service.parseRequest(speakIntent(-2f))!!.speedMultiplier, 0f)
    }

    private fun speakIntent(multiplier: Float?) =
        Intent(MarmaladeSynthService.ACTION_SPEAK).apply {
            putExtra(MarmaladeSynthService.EXTRA_TEXT, "Hello.")
            multiplier?.let {
                putExtra(MarmaladeSynthService.EXTRA_SPEED_MULTIPLIER, it)
            }
        }

    @Test
    fun `unknown engine falls back to the default`() {
        assertEquals(
            MarmaladeSynthService.DEFAULT_ENGINE,
            service.knownEngineOrDefault("piper-en-us-v1"),
        )
        assertEquals(MarmaladeSynthService.DEFAULT_ENGINE, service.knownEngineOrDefault(""))
    }
}
