package app.marmalade.tts.engine

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.engine.vits.VitsDirectEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one engine table. Narrowing is where the original bug was — the cloud
 * and dev-Pocket engines were absent from one of the duplicated dispatch
 * lists, so aliases pointing at them were synthesized with Kokoro and no
 * error was raised anywhere.
 */
class EngineRegistryTest {

    private class NamedEngine(override val engineName: String) : TtsEngine {
        override val sampleRate: Int = 24_000
        override fun isInstalled(): Boolean = true
        override fun isLoaded(): Boolean = false
        override fun ensureModelLoaded() = Unit
        override suspend fun synthesize(
            text: String,
            voiceId: String,
            speed: Float,
            phonemizationLanguage: String?,
        ): SynthAudio = SynthAudio(ShortArray(0), sampleRate)
        override fun release() = Unit
    }

    private val all = EngineRegistry.ENGINE_NAMES.map(::NamedEngine)
    private val registry = EngineRegistry(all)

    @Test
    fun `every engine the app can alias survives narrowing`() {
        val engines = listOf(
            KokoroDirectVoiceCatalog.ENGINE,
            KokoroGermanVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
            PocketDevVoiceCatalog.ENGINE,
            VitsVoiceCatalog.ENGINE,
            CloudApiVoiceCatalog.ENGINE,
        )
        for (engine in engines) {
            assertEquals(engine, EngineRegistry.knownEngineOrDefault(engine))
            assertEquals(engine, EngineRegistry.engineNameFor("$engine:voice"))
        }
    }

    @Test
    fun `unknown engine falls back to the default`() {
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.knownEngineOrDefault("piper-en-us-v1"))
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.knownEngineOrDefault(""))
        assertEquals(KokoroDirectVoiceCatalog.ENGINE, EngineRegistry.DEFAULT_ENGINE)
    }

    @Test
    fun `malformed voice ids route to the default`() {
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.engineNameFor("no-separator"))
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.engineNameFor(":leading"))
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.systemTtsEngineNameFor(""))
        assertNull(EngineRegistry.enginePrefixOf("no-separator"))
    }

    @Test
    fun `system TTS never routes to the developer-only engine`() {
        assertFalse(EngineRegistry.isSystemTtsEngine(PocketDevVoiceCatalog.ENGINE))
        assertEquals(
            EngineRegistry.DEFAULT_ENGINE,
            EngineRegistry.systemTtsEngineNameFor("${PocketDevVoiceCatalog.ENGINE}:alba"),
        )
        // Everything else, cloud included, is a system-TTS engine.
        assertEquals(
            EngineRegistry.ENGINE_NAMES - PocketDevVoiceCatalog.ENGINE,
            EngineRegistry.SYSTEM_TTS_ENGINE_NAMES,
        )
        assertTrue(EngineRegistry.isSystemTtsEngine(CloudApiVoiceCatalog.ENGINE))
    }

    @Test
    fun `lookup returns the named engine and degrades unknown names to Kokoro`() {
        for (engine in all) assertSame(engine, registry[engine.engineName])
        assertSame(registry[KokoroDirectVoiceCatalog.ENGINE], registry["piper-en-us-v1"])
        assertNull(registry.find("piper-en-us-v1"))
    }

    @Test
    fun `on-device excludes only the cloud engine, warm-up also skips developer-only`() {
        assertEquals(
            EngineRegistry.ENGINE_NAMES - CloudApiVoiceCatalog.ENGINE,
            registry.onDevice.map { it.engineName },
        )
        assertEquals(
            listOf(
                KokoroDirectVoiceCatalog.ENGINE,
                KokoroGermanVoiceCatalog.ENGINE,
                KittenDirectVoiceCatalog.ENGINE,
                PocketVoiceCatalog.ENGINE,
                VitsVoiceCatalog.ENGINE,
            ),
            registry.warmable.map { it.engineName },
        )
    }

    @Test
    fun `engine classes name themselves after their catalogs`() {
        // The registry keys instances by TtsEngine.engineName; a drift here
        // would silently route every request for that engine to Kokoro.
        assertEquals(KokoroDirectVoiceCatalog.ENGINE, KokoroDirectEngine.ENGINE_NAME)
        assertEquals(KittenDirectVoiceCatalog.ENGINE, KittenDirectEngine.ENGINE_NAME)
        assertEquals(PocketVoiceCatalog.ENGINE, PocketEngine.ENGINE_NAME)
        assertEquals(VitsVoiceCatalog.ENGINE, VitsDirectEngine.ENGINE_NAME)
    }

    @Test
    fun `display names for notification copy`() {
        assertEquals("Kokoro", EngineRegistry.displayName(KokoroDirectVoiceCatalog.ENGINE))
        assertEquals("Cloud", EngineRegistry.displayName(CloudApiVoiceCatalog.ENGINE))
        assertEquals("mystery", EngineRegistry.displayName("mystery"))
    }
}
