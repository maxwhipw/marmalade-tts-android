package app.marmalade.tts.service

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Engine narrowing for the long-form foreground service.
 *
 * This is the whole of what a JVM test can reach here: everything past
 * the narrowing needs audio focus, a notification channel and real ONNX
 * sessions. It's also where the bug was — the cloud and dev-Pocket
 * engines were absent from the dispatch list, so aliases pointing at
 * them were synthesized with Kokoro and no error was raised anywhere.
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
            CloudApiVoiceCatalog.ENGINE,
        )
        for (engine in engines) {
            assertEquals(engine, service.knownEngineOrDefault(engine))
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
