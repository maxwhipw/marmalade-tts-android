package app.marmalade.tts.service

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectResolver
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.preprocessing.EngineProfiles
import app.marmalade.tts.preprocessing.Preprocessor
import app.marmalade.tts.preprocessing.PreprocessingRules
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// ---------------------------------------------------------------------------
// Data flow
// ---------------------------------------------------------------------------
//   Robolectric provides ApplicationContext + real android.speech.tts classes.
//   We instantiate MarmaladeTtsService directly (no ServiceController setup —
//   the public-API path we exercise, onSynthesizeText / onIsLanguageAvailable,
//   is callable on a bare service instance once its injected fields are set).
//
//   The two `@Inject lateinit var` fields are set via reflection in @Before
//   (Hilt is not running in this JVM test). We swap in:
//     * FakeKittenDirectEngine  — JVM-safe subclass overriding sampleRate +
//                             synthesize() so we never touch the ONNX Runtime
//                             session (which won't load in Robolectric).
//     * FakeVoiceMetaDao    — in-memory stub seeded with KittenDirectVoiceCatalog.
//
//   FakeSynthesisCallback implements android.speech.tts.SynthesisCallback
//   (an interface — no test constructor needed) and records every call as
//   a typed Event so assertions can verify ordering, sample-rate, format,
//   and total byte counts without poking at framework internals.
//
//   These tests cover the orchestration that PcmEncodingTest can't: the
//   pcm16ToLittleEndianBytes helper is already unit-tested in isolation,
//   but the start→audioAvailable*→done call sequence, the error-on-throw
//   path, and the maxBufferSize chunking guarantee all need the full
//   onSynthesizeText flow to assert against.
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
class MarmaladeTtsServiceTest {

    private lateinit var service: MarmaladeTtsService
    private lateinit var fakeEngine: FakeKittenDirectEngine
    private lateinit var fakeKokoroDirectEngine: FakeKokoroDirectEngine
    private lateinit var fakeDao: FakeVoiceMetaDao
    private lateinit var fakeSettings: FakePreprocessSettings
    private lateinit var preprocessor: Preprocessor

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        fakeSettings = FakePreprocessSettings()
        fakeEngine = FakeKittenDirectEngine(ctx, fakeSettings)
        fakeKokoroDirectEngine = FakeKokoroDirectEngine(ctx, fakeSettings)
        // Seed both catalogs so the engine-routing tests can resolve
        // either a kitten-direct:* or kokoro-direct:* voice through the DAO lookup.
        fakeDao = FakeVoiceMetaDao(KittenDirectVoiceCatalog.voices + KokoroDirectVoiceCatalog.voices)
        preprocessor = Preprocessor(
            rulesByName = PreprocessingRules.ALL.associateBy { it.name },
        )
        service = MarmaladeTtsService()
        // Inject by reflection — Hilt isn't running so the @Inject lateinit
        // vars are unset. Reflection bypasses the lateinit "isInitialized"
        // guard. We only set the fields these tests exercise: the two engines
        // they route to (kittenDirect, kokoroDirect), the DAO/preprocessor/
        // settings/router, and effectResolver. The other engine fields stay
        // unset — no test routes a voice to them, so they're never accessed.
        setField(service, "kittenDirect", fakeEngine)
        setField(service, "kokoroDirect", fakeKokoroDirectEngine)
        setField(service, "voiceDao", fakeDao)
        setField(service, "preprocessor", preprocessor)
        setField(service, "settings", fakeSettings)
        // effectResolver is only touched on the alias-routing branch, which
        // these tests don't hit (explicit voices / no primary alias). Set a
        // dry resolver anyway so the lateinit is initialised defensively.
        setField(service, "effectResolver", object : EffectResolver {
            override suspend fun blocksFor(effectId: String?): List<EffectBlock> = emptyList()
        })
        // TtsRouter takes a (mappingDao, aliasDao, settings) triple. The
        // existing service tests don't exercise per-app routing — they
        // hand off voice IDs explicitly — but the field must be set or
        // the lateinit guard fires. Empty inline fakes are simpler than
        // mocking; resolveAlias returns null (no per-app match, no
        // primary set on FakePreprocessSettings) which is what these
        // tests expect (= service falls back to engine default).
        setField(service, "router", TtsRouter(
            mappingDao = EmptyMappingDao,
            aliasDao = EmptyAliasDao,
            settings = fakeSettings,
        ))
        // onLoadLanguage fires the background warm-up. The two real engines
        // here have nothing installed, so ensureModelLoaded throws
        // EngineNotInstalledException inside the warm-up's own catch.
        // onSynthesizeText brackets every synthesis with residency
        // begin/end. Nothing to evict here — an empty releaser map keeps
        // the policy inert while the lateinit stays satisfied.
        setField(service, "residency", EngineResidency(
            releasers = emptyMap(),
            keepaliveMode = { KeepaliveMode.Off },
            clock = { 0L },
        ))
        // Real detector against the shipped table — the auto-detect tests
        // are about the service's routing decisions, and a fake detector
        // would only pin the fake. Read off disk rather than through
        // Robolectric's asset manager, which doesn't serve this module's
        // assets to unit tests.
        setField(
            service,
            "langDetector",
            LangDetector(java.io.File("src/main/assets/langdetect.tab").readLines()),
        )
        setField(service, "engineWarmup", EngineWarmup(
            kokoroDirect = fakeKokoroDirectEngine,
            kittenDirect = fakeEngine,
            pocket = PocketEngine(ctx, fakeSettings),
        ))
    }

    private object EmptyMappingDao : app.marmalade.tts.data.db.AppAliasMappingDao {
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<app.marmalade.tts.data.db.AppAliasMapping>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun findByPackage(packageName: String) = null
        override suspend fun upsert(mapping: app.marmalade.tts.data.db.AppAliasMapping) = Unit
        override suspend fun delete(packageName: String) = Unit
        override suspend fun releaseAppsRoutedTo(aliasName: String) = Unit
    }

    private object EmptyAliasDao : app.marmalade.tts.data.db.VoiceAliasDao {
        override suspend fun findById(id: String) = null
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<app.marmalade.tts.data.db.VoiceAlias>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun findByName(name: String) = null
        override suspend fun upsert(alias: app.marmalade.tts.data.db.VoiceAlias) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun repointEngine(fromEngine: String, toEngine: String) = Unit
    }

    // -- 1. happy path: start → audioAvailable* → done --------------------

    @Test
    fun onSynthesizeText_happyPath_callOrderIsStartAudioAvailableDone() {
        // Explicit kokoro voice → routes to the kokoro engine. Seed the
        // kokoro fake with PCM and assert the synth call order.
        val sampleCount = 48_000
        fakeKokoroDirectEngine.nextPcm = ShortArray(sampleCount) { (it and 0xFF).toShort() }

        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello world", "kokoro-direct-v1_0:af_bella")

        service.onSynthesizeText(request, callback)

        val events = callback.events
        assertTrue(
            "Expected at least one Start + AudioAvailable + Done, got $events",
            events.size >= 3,
        )
        // First event: exactly one Start with the engine's sampleRate, mono,
        // PCM_16BIT.
        val first = events.first()
        assertTrue("First event should be Start, got $first", first is FakeSynthesisCallback.Event.Start)
        val start = first as FakeSynthesisCallback.Event.Start
        assertEquals(24_000, start.sampleRate)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, start.format)
        assertEquals(1, start.channels)

        // Last event: exactly one Done.
        assertEquals(
            "Last event should be Done, got ${events.last()}",
            FakeSynthesisCallback.Event.Done,
            events.last(),
        )

        // Exactly one Start, exactly one Done, zero Errors.
        assertEquals(1, events.count { it is FakeSynthesisCallback.Event.Start })
        assertEquals(1, events.count { it == FakeSynthesisCallback.Event.Done })
        assertEquals(0, events.count { it is FakeSynthesisCallback.Event.Error })

        // Middle events: ≥1 AudioAvailable summing to sampleCount * 2 bytes.
        val audioEvents = events.filterIsInstance<FakeSynthesisCallback.Event.AudioAvailable>()
        assertTrue("Expected ≥1 AudioAvailable event", audioEvents.isNotEmpty())
        val totalBytes = audioEvents.sumOf { it.byteCount }
        assertEquals(sampleCount * 2, totalBytes)

        // The kokoro fake was hit; kitten fake was not.
        assertEquals(
            "kokoro voice should route to the kokoro engine",
            1,
            fakeKokoroDirectEngine.calls.size,
        )
        assertEquals(
            "kokoro voice should NOT touch the kitten engine",
            0,
            fakeEngine.calls.size,
        )
    }

    // -- 2. engine not installed → callback.error() exactly once ----------

    @Test
    fun onSynthesizeText_engineNotInstalled_callsErrorExactlyOnce() {
        // Explicit kokoro voice → kokoro engine. Configure that fake to throw.
        fakeKokoroDirectEngine.synthesizeException = EngineNotInstalledException("kokoro-direct-v1_0")

        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello world", "kokoro-direct-v1_0:af_bella")

        service.onSynthesizeText(request, callback)

        val events = callback.events
        // The service calls callback.start() *before* invoking the engine,
        // so a Start event IS expected on this path — but Done must not
        // fire, and exactly one Error must.
        assertEquals(
            "Expected exactly one Error event, got $events",
            1,
            events.count { it is FakeSynthesisCallback.Event.Error },
        )
        assertEquals(
            "Expected zero Done events on the error path, got $events",
            0,
            events.count { it == FakeSynthesisCallback.Event.Done },
        )
    }

    // -- 3. chunking respects maxBufferSize -------------------------------

    @Test
    fun onSynthesizeText_audioChunkingRespectsMaxBufferSize() {
        // Explicit kokoro voice → kokoro engine.
        val sampleCount = 100_000
        val expectedBytes = sampleCount * 2 // PCM16
        fakeKokoroDirectEngine.nextPcm = ShortArray(sampleCount) { (it and 0xFF).toShort() }

        val maxBuf = 8192
        val callback = FakeSynthesisCallback(maxBufferSize = maxBuf)
        val request = newRequestWithVoice("a longer sentence to synthesize", "kokoro-direct-v1_0:af_bella")

        service.onSynthesizeText(request, callback)

        val audioEvents = callback.events.filterIsInstance<FakeSynthesisCallback.Event.AudioAvailable>()
        assertTrue("Expected several AudioAvailable events", audioEvents.size >= 2)

        for ((i, e) in audioEvents.withIndex()) {
            assertTrue(
                "Chunk $i exceeded maxBufferSize=$maxBuf (got ${e.byteCount})",
                e.byteCount <= maxBuf,
            )
            assertTrue("Chunk $i must be > 0 bytes", e.byteCount > 0)
        }
        val total = audioEvents.sumOf { it.byteCount }
        assertEquals(
            "Sum of audioAvailable bytes should match PCM byte count",
            expectedBytes,
            total,
        )
    }

    // -- 5. engine routing: voice prefix selects the right engine --------

    @Test
    fun onSynthesizeText_kittenVoiceRoutesToKittenDirectEngine() {
        // Explicit kitten voiceName on the request — service must dispatch
        // to the kitten engine, never touching kokoro.
        fakeEngine.nextPcm = ShortArray(1024) { 0 }

        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello kitten", "kitten-direct-v0_8:Bella")

        service.onSynthesizeText(request, callback)

        assertEquals(
            "kitten-direct-v0_8:* voiceName should hit the kitten engine exactly once",
            1,
            fakeEngine.calls.size,
        )
        assertEquals(
            "kitten-direct-v0_8:* voiceName should NOT touch the kokoro engine",
            0,
            fakeKokoroDirectEngine.calls.size,
        )
        // The voice id passed to the engine must round-trip unchanged.
        assertEquals("kitten-direct-v0_8:Bella", fakeEngine.calls.single().second)
    }

    @Test
    fun onSynthesizeText_kokoroVoiceRoutesToKokoroDirectEngine() {
        // Explicit kokoro voiceName on the request — service must dispatch
        // to the kokoro engine, never touching kitten.
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024) { 0 }

        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello kokoro", "kokoro-direct-v1_0:bm_lewis")

        service.onSynthesizeText(request, callback)

        assertEquals(
            "kokoro-direct-v1_0:* voiceName should hit the kokoro engine exactly once",
            1,
            fakeKokoroDirectEngine.calls.size,
        )
        assertEquals(
            "kokoro-direct-v1_0:* voiceName should NOT touch the kitten engine",
            0,
            fakeEngine.calls.size,
        )
        assertEquals("kokoro-direct-v1_0:bm_lewis", fakeKokoroDirectEngine.calls.single().second)
    }

    // -- primary-alias routing vs auto-filled voiceName --------------------
    //
    // TTS clients auto-fill SynthesisRequest.voiceName from the engine's
    // advertised default (onGetDefaultVoiceNameFor / system Settings).
    // Such an echo must NOT outrank the user's primary alias — that was
    // the "kitten primary alias keeps speaking Bella Kokoro" bug.

    /** Wire a kitten primary alias ("kitty", 1.5×) into the service's router. */
    private fun installKittenPrimary(phonemizationLanguage: String? = null) {
        installPrimary(
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Bella",
            phonemizationLanguage = phonemizationLanguage,
        )
    }

    /** Wire a kokoro primary alias (af_bella by default, 1.5×). */
    private fun installKokoroPrimary(
        phonemizationLanguage: String? = null,
        voiceId: String = "kokoro-direct-v1_0:af_bella",
    ) {
        installPrimary(
            engine = KokoroDirectVoiceCatalog.ENGINE,
            voiceId = voiceId,
            phonemizationLanguage = phonemizationLanguage,
        )
    }

    private fun installPrimary(
        engine: String,
        voiceId: String,
        phonemizationLanguage: String?,
    ) {
        val alias = app.marmalade.tts.data.db.VoiceAlias(
            name = "kitty",
            id = "id-test",
            engine = engine,
            voiceId = voiceId,
            speed = 1.5f,
            effectPreset = "NONE",
            createdAt = 0L,
            phonemizationLanguage = phonemizationLanguage,
        )
        val aliasDao = object : app.marmalade.tts.data.db.VoiceAliasDao {
            override fun getAll(): kotlinx.coroutines.flow.Flow<List<app.marmalade.tts.data.db.VoiceAlias>> =
                kotlinx.coroutines.flow.flowOf(listOf(alias))
            override suspend fun findById(id: String) = alias.takeIf { it.id == id }
            override suspend fun findByName(name: String) = alias.takeIf { it.name == name }
            override suspend fun upsert(alias: app.marmalade.tts.data.db.VoiceAlias) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun repointEngine(fromEngine: String, toEngine: String) = Unit
        }
        kotlinx.coroutines.runBlocking { fakeSettings.setPrimaryAliasId(alias.id) }
        setField(service, "router", TtsRouter(
            mappingDao = EmptyMappingDao,
            aliasDao = aliasDao,
            settings = fakeSettings,
        ))
    }

    @Test
    fun onSynthesizeText_autoFilledDefaultVoice_yieldsToPrimaryAlias() {
        installKittenPrimary()
        fakeEngine.nextPcm = ShortArray(1024) { 0 }

        // The request carries the catalog default — the auto-fill echo, not
        // a deliberate pick — so the kitten primary must win.
        val request = newRequestWithVoice("hello", KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(1, fakeEngine.calls.size)
        assertEquals(0, fakeKokoroDirectEngine.calls.size)
        val (_, voiceId, speed) = fakeEngine.calls.single()
        assertEquals("kitten-direct-v0_8:Bella", voiceId)
        assertEquals(1.5f, speed)
    }

    @Test
    fun onSynthesizeText_requestedVoiceMatchingPrimary_appliesAliasBundle() {
        installKittenPrimary()
        fakeEngine.nextPcm = ShortArray(1024) { 0 }

        // Requesting exactly the primary's voice is treated as the alias:
        // its speed (and effects) ride along instead of the bare 1.0×.
        val request = newRequestWithVoice("hello", "kitten-direct-v0_8:Bella")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(1.5f, fakeEngine.calls.single().third)
    }

    @Test
    fun onSynthesizeText_deliberateVoicePick_stillBeatsPrimaryAlias() {
        installKittenPrimary()
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024) { 0 }

        // bm_lewis is neither the advertised default nor the primary's
        // voice — a deliberate caller pick, honored at 1.0× with no effect.
        val request = newRequestWithVoice("hello", "kokoro-direct-v1_0:bm_lewis")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(0, fakeEngine.calls.size)
        val (_, voiceId, speed) = fakeKokoroDirectEngine.calls.single()
        assertEquals("kokoro-direct-v1_0:bm_lewis", voiceId)
        assertEquals(1.0f, speed)
    }

    @Test
    fun onGetDefaultVoiceNameFor_advertisesPrimaryAliasVoice() {
        installKittenPrimary()
        assertEquals(
            "kitten-direct-v0_8:Bella",
            service.onGetDefaultVoiceNameFor("en", "US", ""),
        )
    }

    @Test
    fun onLoadVoice_acceptsBothEnginesAndRejectsUnknown() {
        // Both engines' voices must round-trip — required for the system
        // TTS picker to enumerate them through Settings → Languages → TTS.
        assertEquals(TextToSpeech.SUCCESS, service.onLoadVoice("kitten-direct-v0_8:Bella"))
        assertEquals(TextToSpeech.SUCCESS, service.onLoadVoice("kokoro-direct-v1_0:af_bella"))
        // Unknown engines (or junk) are rejected so the system falls back
        // to the language-level default rather than us silently swallowing
        // a bad voice request.
        assertEquals(TextToSpeech.ERROR, service.onLoadVoice("piper:Alan"))
        assertEquals(TextToSpeech.ERROR, service.onLoadVoice(""))
        assertEquals(TextToSpeech.ERROR, service.onLoadVoice(null))
    }

    // -- client speech rate ---------------------------------------------------

    @Test
    fun onSynthesizeText_clientSpeechRateMultipliesResolvedSpeed() {
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)
        val request = newRequestWithVoice("hello world", "kokoro-direct-v1_0:af_bella")
        // 200 = 2.0x per the framework contract (100 = normal).
        val rateField = SynthesisRequest::class.java.getDeclaredField("mSpeechRate")
        rateField.isAccessible = true
        rateField.set(request, 200)

        service.onSynthesizeText(request, FakeSynthesisCallback())

        val (_, _, speed) = fakeKokoroDirectEngine.calls.single()
        assertEquals(2.0f, speed)
    }

    // -- onStop cancellation ------------------------------------------------

    @Test
    fun onStop_cancelsInFlightSynthesisAndClosesCallbackCleanly() {
        fakeKokoroDirectEngine.streamForever = true
        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello world", "kokoro-direct-v1_0:af_bella")

        val synthThread = Thread { service.onSynthesizeText(request, callback) }
        synthThread.start()
        // Wait for streaming to actually start (first chunk delivered).
        val deadline = System.currentTimeMillis() + 5_000
        while (
            callback.events.none { it is FakeSynthesisCallback.Event.AudioAvailable } &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        assertTrue(
            "stream should have delivered at least one chunk before stop",
            callback.events.any { it is FakeSynthesisCallback.Event.AudioAvailable },
        )

        service.onStop()
        synthThread.join(5_000)

        assertTrue("onSynthesizeText should return after onStop", !synthThread.isAlive)
        // Stop is not an error; the callback must close cleanly.
        assertEquals(0, callback.events.count { it is FakeSynthesisCallback.Event.Error })
        assertEquals(FakeSynthesisCallback.Event.Done, callback.events.last())
    }

    // -- 4. language negotiation for en-US --------------------------------

    @Test
    fun onSynthesizeText_voiceNegotiation_setsLanguageAvailable() {
        // onIsLanguageAvailable is the spec-prescribed entry point for
        // language negotiation; doesn't require any callback infra.
        val locale = Locale("en", "US")
        val result = service.onIsLanguageAvailable(
            locale.language,
            locale.country,
            locale.variant,
        )
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, result)

        // Sanity: ISO-639-3 ("eng") + "USA" should also be COUNTRY_AVAILABLE
        // because the system uses both code paths.
        val iso3 = service.onIsLanguageAvailable("eng", "USA", "")
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, iso3)

        // A language no installed engine ships must report NOT_SUPPORTED —
        // German is in no catalog.
        val notSupported = service.onIsLanguageAvailable("deu", "DEU", "")
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, notSupported)
    }

    // -- language negotiation follows what is installed ---------------------
    //
    // Kokoro is installed in this fixture (FakeKokoroDirectEngine.isInstalled
    // is true unless configured to throw), so its eight non-English locales
    // are available. That is the whole point of atom F: the check activity
    // advertised them through CHECK_TTS_DATA while the service rejected them.

    @Test
    fun onIsLanguageAvailable_installedKokoroLocalesAreAvailable() {
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.onIsLanguageAvailable("fr", "FR", ""),
        )
        // Android hands us ISO-639-3 / ISO-3166-alpha-3 on most paths.
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.onIsLanguageAvailable("jpn", "JPN", ""),
        )
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.onIsLanguageAvailable("spa", "ESP", ""),
        )
        // Language matches, region doesn't.
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            service.onIsLanguageAvailable("fra", "CAN", ""),
        )
    }

    @Test
    fun onIsLanguageAvailable_uninstalledEngineLocalesAreNotSupported() {
        // Knock Kokoro out; only Kitten (English) remains installed.
        fakeKokoroDirectEngine.synthesizeException =
            EngineNotInstalledException(KokoroDirectVoiceCatalog.ENGINE)

        assertEquals(
            TextToSpeech.LANG_NOT_SUPPORTED,
            service.onIsLanguageAvailable("jpn", "JPN", ""),
        )
        // English stays available regardless — it is the engine's baseline
        // and the framework asks before anything is installed.
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.onIsLanguageAvailable("eng", "USA", ""),
        )
    }

    @Test
    fun onGetVoices_listsEveryInstalledVoiceWithItsLocale() {
        val voices = service.onGetVoices()
        assertEquals(
            KittenDirectVoiceCatalog.voices.size + KokoroDirectVoiceCatalog.voices.size,
            voices.size,
        )
        val japanese = voices.single { it.name == "kokoro-direct-v1_0:jf_alpha" }
        assertEquals("ja", japanese.locale.language)
        assertEquals("JP", japanese.locale.country)
        // On-device engines never need the network.
        assertTrue(voices.none { it.isNetworkConnectionRequired })
    }

    @Test
    fun onGetVoices_skipsUninstalledEngines() {
        fakeKokoroDirectEngine.synthesizeException =
            EngineNotInstalledException(KokoroDirectVoiceCatalog.ENGINE)

        val voices = service.onGetVoices()
        assertEquals(KittenDirectVoiceCatalog.voices.size, voices.size)
        assertTrue(voices.all { it.name.startsWith("kitten-direct-v0_8:") })
    }

    @Test
    fun onGetDefaultVoiceNameFor_nonEnglishLocalePicksAVoiceOfThatLanguage() {
        // The primary alias is English; a Japanese request must not get it.
        installKittenPrimary()

        assertEquals(
            "kokoro-direct-v1_0:jf_alpha",
            service.onGetDefaultVoiceNameFor("jpn", "JPN", ""),
        )
        assertEquals(
            "kokoro-direct-v1_0:ff_siwis",
            service.onGetDefaultVoiceNameFor("fr", "FR", ""),
        )
        // English is unchanged: the user's primary alias.
        assertEquals(
            "kitten-direct-v0_8:Bella",
            service.onGetDefaultVoiceNameFor("eng", "USA", ""),
        )
        // Nothing installed speaks German.
        assertEquals("", service.onGetDefaultVoiceNameFor("deu", "DEU", ""))
    }

    @Test
    fun onLoadLanguage_reportsTheLoadedLanguageBack() {
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, service.onLoadLanguage("jpn", "JPN", ""))
        assertEquals(listOf("jpn", "JPN", ""), service.onGetLanguage().toList())

        service.onLoadLanguage("eng", "USA", "")
        assertEquals(listOf("eng", "USA", ""), service.onGetLanguage().toList())
    }

    // -- per-utterance language auto-detection ------------------------------
    //
    // Design (Max, 2026-08-07): the phonemization language is detected from
    // the utterance itself. On Kokoro that is the default — a null column
    // means the same as the "auto" sentinel — and only an explicit language
    // turns it off. The English-only engines are pinned at English and a
    // null column there is left completely alone. The system request's
    // language is no longer allowed to pick a voice or a language; it
    // survives purely as the fallback for when detection abstains.

    @Test
    fun onSynthesizeText_requestLanguageNoLongerSwitchesTheVoice() {
        // An English primary alias with no auto-detect opt-in, exactly as
        // on Max's phone. A Japanese request used to hijack the voice; now
        // a Kitten alias with a null language is left completely alone.
        installKittenPrimary()
        fakeEngine.nextPcm = ShortArray(1024)

        val request = newRequestWithLanguage("こんにちは", "jpn", "JPN")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(0, fakeKokoroDirectEngine.calls.size)
        assertEquals("kitten-direct-v0_8:Bella", fakeEngine.calls.single().second)
        assertNull(fakeEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_autoAliasDetectsFrenchWithoutChangingTheVoice() {
        installKokoroPrimary(phonemizationLanguage = "auto")
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest(
            "Votre téléchargement est terminé et le fichier est prêt.",
            Bundle(),
        )
        service.onSynthesizeText(request, FakeSynthesisCallback())

        // The voice is untouched — an American voice reading French is
        // accented French, which is the point.
        assertEquals("kokoro-direct-v1_0:af_bella", fakeKokoroDirectEngine.calls.single().second)
        assertEquals("fr-fr", fakeKokoroDirectEngine.languages.single())
        // The alias's speed still rides along.
        assertEquals(1.5f, fakeKokoroDirectEngine.calls.single().third)
    }

    @Test
    fun onSynthesizeText_autoAliasEnglishTextTakesItsRegionFromTheVoice() {
        installKokoroPrimary(phonemizationLanguage = "auto")
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest("Your download has finished and the file is ready.", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        // en-US vs en-GB is not inferable from text, so the region comes
        // from af_bella's own catalog code. It is still *stated* rather
        // than left null — see the Japanese-voice suite below for why.
        assertEquals("en-us", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_autoAliasEnglishTextOnABritishVoiceStaysBritish() {
        installKokoroPrimary(phonemizationLanguage = "auto", voiceId = "kokoro-direct-v1_0:bm_lewis")
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest("Your download has finished and the file is ready.", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("en-gb", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_autoAliasFallsBackToTheRequestLanguageWhenUnsure() {
        installKokoroPrimary(phonemizationLanguage = "auto")
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        // Two letters is far below the detector's floor — it abstains, and
        // the system request's locale is what that fallback is still for.
        val request = newRequestWithLanguage("OK", "fra", "FRA")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("kokoro-direct-v1_0:af_bella", fakeKokoroDirectEngine.calls.single().second)
        assertEquals("fr-fr", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_autoAliasOnEnglishOnlyEngineReroutesForCapability() {
        // Kitten can only render English phonemes, so Chinese has to move
        // to an installed Kokoro voice — that's the one case where
        // detection is allowed to change the voice.
        //
        // The dropdown no longer offers auto-detect on Kitten (it is
        // pinned at English), so a stored literal "auto" is now only a
        // legacy row of the one build that did offer it. The branch stays
        // pending Max's accented-phonemization verdict.
        installKittenPrimary(phonemizationLanguage = "auto")
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest("今天天气很好，我们去公园散步吧", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(0, fakeEngine.calls.size)
        assertEquals("kokoro-direct-v1_0:zf_xiaobei", fakeKokoroDirectEngine.calls.single().second)
        // Han runs go through lexicon-zh whatever espeak is set to; the
        // catalog's per-voice code is what rides along.
        assertEquals("en-us", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_autoAliasWithNoVoiceForTheLanguageStaysPut() {
        installKittenPrimary(phonemizationLanguage = "auto")
        // Nothing Kokoro is installed → no Chinese voice to reroute to.
        fakeKokoroDirectEngine.synthesizeException =
            EngineNotInstalledException(KokoroDirectVoiceCatalog.ENGINE)
        fakeEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest("今天天气很好，我们去公园散步吧", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        // Today's behaviour: Kitten speaks it as English.
        assertEquals("kitten-direct-v0_8:Bella", fakeEngine.calls.single().second)
        assertNull(fakeEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_namedVoiceIsNeverRerouted() {
        installKittenPrimary(phonemizationLanguage = "auto")
        fakeEngine.nextPcm = ShortArray(1024)

        // A caller that names a voice has out-specified everything. The
        // named voice happens to be the primary's, so the alias bundle
        // (including "auto") still applies — but the voice cannot move.
        val request = newRequestWithVoice("今天天气很好，我们去公园散步吧", "kitten-direct-v0_8:Bella")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(0, fakeKokoroDirectEngine.calls.size)
        assertEquals("kitten-direct-v0_8:Bella", fakeEngine.calls.single().second)
        assertNull(fakeEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_unsupportedRequestLanguageKeepsTheDefaultVoice() {
        fakeEngine.nextPcm = ShortArray(1024)

        val request = newRequestWithLanguage("guten Tag", "deu", "DEU")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        // No German voice exists → the default voice speaks. The default is
        // now Kitten (baked, always present), so it routes to the kitten engine.
        assertEquals(
            KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
            fakeEngine.calls.single().second,
        )
    }

    // -- the Japanese-voice regression suite --------------------------------
    //
    // Max's repro (2026-08-08): "auto-detecting is not working when I switch
    // the alias to auto-detect with a Japanese voice." A detected language
    // used to map to a null espeak code for English and Chinese, and null
    // means "the voice's own language stands" — so English text on a
    // Japanese voice kept Japanese G2P and detection looked inert. Every
    // detected language must now name a real code.

    /** Wire the repro: a Kokoro Japanese voice on an auto alias. */
    private fun installJapaneseAutoAlias() {
        installKokoroPrimary(
            phonemizationLanguage = "auto",
            voiceId = "kokoro-direct-v1_0:jf_alpha",
        )
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)
    }

    @Test
    fun onSynthesizeText_japaneseVoice_englishTextPhonemizesAsEnglish() {
        installJapaneseAutoAlias()

        val request = SynthesisRequest("Your download has finished and the file is ready.", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        // THE BUG: this was null, which left jf_alpha's "ja" in place.
        assertEquals("en-us", fakeKokoroDirectEngine.languages.single())
        // The voice never moves — an accented reading is the point.
        assertEquals("kokoro-direct-v1_0:jf_alpha", fakeKokoroDirectEngine.calls.single().second)
    }

    @Test
    fun onSynthesizeText_japaneseVoice_japaneseTextPhonemizesAsJapanese() {
        installJapaneseAutoAlias()

        val request = SynthesisRequest("ダウンロードが完了しました。ファイルを開けます。", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("ja", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_japaneseVoice_chineseTextTakesTheLexiconPath() {
        installJapaneseAutoAlias()

        val request = SynthesisRequest("今天天气很好，我们去公园散步吧", Bundle())
        service.onSynthesizeText(request, FakeSynthesisCallback())

        // Han runs go through lexicon-zh inside the engine; espeak only
        // sees the latin spans, so it must not be left on Japanese.
        assertEquals("en-us", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_japaneseVoice_frenchTextPhonemizesAsFrench() {
        installJapaneseAutoAlias()

        val request = SynthesisRequest(
            "Votre téléchargement est terminé et le fichier est prêt.",
            Bundle(),
        )
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("fr-fr", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_japaneseVoice_abstainFallsBackToTheRequestLocale() {
        installJapaneseAutoAlias()

        // Two letters is far below the detector's floor. The request's
        // locale is the fallback, and it goes through the same mapping —
        // so English there also has to name a region.
        val request = newRequestWithLanguage("OK", "eng", "USA")
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("en-us", fakeKokoroDirectEngine.languages.single())
    }

    // -- auto-detect is the default -----------------------------------------

    @Test
    fun onSynthesizeText_kokoroAliasWithNoStoredLanguageStillDetects() {
        // Null is the new auto: legacy rows and every newly created alias
        // get detection with no migration.
        installKokoroPrimary(phonemizationLanguage = null)
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest(
            "Votre téléchargement est terminé et le fichier est prêt.",
            Bundle(),
        )
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("fr-fr", fakeKokoroDirectEngine.languages.single())
        assertEquals("kokoro-direct-v1_0:af_bella", fakeKokoroDirectEngine.calls.single().second)
    }

    @Test
    fun onSynthesizeText_kokoroAliasWithAnExplicitLanguageNeverDetects() {
        installKokoroPrimary(phonemizationLanguage = "ja")
        fakeKokoroDirectEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest(
            "Votre téléchargement est terminé et le fichier est prêt.",
            Bundle(),
        )
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals("ja", fakeKokoroDirectEngine.languages.single())
    }

    @Test
    fun onSynthesizeText_kittenAliasWithNoStoredLanguageIsUntouched() {
        // The English-only engines are pinned at English in the UI, so a
        // null column there means exactly what it always did: no
        // detection, no reroute, Kitten's own English phonemes.
        installKittenPrimary(phonemizationLanguage = null)
        fakeEngine.nextPcm = ShortArray(1024)

        val request = SynthesisRequest(
            "Votre téléchargement est terminé et le fichier est prêt.",
            Bundle(),
        )
        service.onSynthesizeText(request, FakeSynthesisCallback())

        assertEquals(0, fakeKokoroDirectEngine.calls.size)
        assertEquals("kitten-direct-v0_8:Bella", fakeEngine.calls.single().second)
        assertNull(fakeEngine.languages.single())
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Build a SynthesisRequest that has [voiceName] set on it. The
     * framework class only exposes a String/Bundle constructor; voiceName
     * is set via the package-private mutator, so reflect into it. Used by
     * the engine-routing tests to assert each voice-prefix dispatches to
     * the right engine.
     */
    private fun newRequestWithVoice(text: String, voiceName: String): SynthesisRequest {
        val req = SynthesisRequest(text, Bundle())
        // SynthesisRequest hides the voiceName setter — reflect to set it.
        // (The matching field name on the public API 28+ class is
        // `mVoiceName`; if that ever changes the test will fail loudly.)
        val field = SynthesisRequest::class.java.getDeclaredField("mVoiceName")
        field.isAccessible = true
        field.set(req, voiceName)
        return req
    }

    /**
     * Build a SynthesisRequest carrying a language but no voice name —
     * the shape a client produces when it asks for a locale and leaves
     * the voice to the engine.
     */
    private fun newRequestWithLanguage(
        text: String,
        language: String,
        country: String,
    ): SynthesisRequest = SynthesisRequest(text, Bundle()).also {
        setLanguage(it, language, country)
    }

    /**
     * `SynthesisRequest.setLanguage` is package-private (the framework
     * fills it in for real requests), so reflect into it.
     */
    private fun setLanguage(request: SynthesisRequest, language: String, country: String) {
        val method = SynthesisRequest::class.java.getDeclaredMethod(
            "setLanguage",
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(request, language, country, "")
    }

    /** Set a (possibly private/lateinit) field by reflection. */
    private fun setField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}

// ---------------------------------------------------------------------------
// FakeSynthesisCallback — records every callback call as a typed Event so
// tests can assert call ordering and arguments without depending on
// SynthesisCallback's implementation details.
// ---------------------------------------------------------------------------

internal class FakeSynthesisCallback(
    private val maxBufferSize: Int = 8192,
) : SynthesisCallback {

    sealed class Event {
        data class Start(val sampleRate: Int, val format: Int, val channels: Int) : Event()
        data class AudioAvailable(val byteCount: Int) : Event()
        object Done : Event()
        data class Error(val code: Int) : Event()
        data class RangeStart(val markerInFrames: Int, val start: Int, val end: Int) : Event()
    }

    // CopyOnWriteArrayList: the onStop test polls events from the test
    // thread while the synth thread appends.
    val events: MutableList<Event> = java.util.concurrent.CopyOnWriteArrayList()
    private var started = false
    private var finished = false

    override fun getMaxBufferSize(): Int = maxBufferSize

    override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
        events += Event.Start(sampleRateInHz, audioFormat, channelCount)
        started = true
        return TextToSpeech.SUCCESS
    }

    override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
        events += Event.AudioAvailable(length)
        return TextToSpeech.SUCCESS
    }

    override fun done(): Int {
        events += Event.Done
        finished = true
        return TextToSpeech.SUCCESS
    }

    override fun error() {
        events += Event.Error(0)
        finished = true
    }

    override fun error(errorCode: Int) {
        events += Event.Error(errorCode)
        finished = true
    }

    override fun hasStarted(): Boolean = started
    override fun hasFinished(): Boolean = finished

    override fun rangeStart(markerInFrames: Int, start: Int, end: Int) {
        events += Event.RangeStart(markerInFrames, start, end)
    }
}

// ---------------------------------------------------------------------------
// FakeKittenDirectEngine — JVM-safe subclass of KittenDirectEngine that returns a
// fixed PCM ShortArray (or throws a configured exception) without touching the
// ONNX Runtime session. KittenDirectEngine is declared `open` solely to enable
// this test double — see the prod-code comment above the class declaration.
// ---------------------------------------------------------------------------

internal class FakeKittenDirectEngine(
    ctx: Context,
    settings: SettingsRepository,
) : KittenDirectEngine(ctx, settings, fakeSharedEspeakData()) {

    /** PCM to return from synthesize(); ignored if synthesizeException is set. */
    var nextPcm: ShortArray = ShortArray(0)

    /** If non-null, synthesize() throws this instead of returning audio. */
    var synthesizeException: Throwable? = null

    /** Track invocations for any test that wants to assert how synthesize was called. */
    val calls: MutableList<Triple<String, String, Float>> = mutableListOf()

    /** phonemizationLanguage of each call, index-aligned with [calls]. */
    val languages: MutableList<String?> = mutableListOf()

    override val sampleRate: Int get() = 24_000

    override fun isInstalled(): Boolean = synthesizeException !is EngineNotInstalledException

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio {
        calls += Triple(text, voiceId, speed)
        languages += phonemizationLanguage
        synthesizeException?.let { throw it }
        return SynthAudio(pcm = nextPcm, sampleRate = sampleRate)
    }

    // The service's streaming path collects synthesizeStream; the real
    // override would hit runInference (no ORT session in Robolectric), so
    // emit the fixture PCM as a single chunk instead.
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = kotlinx.coroutines.flow.flow {
        calls += Triple(text, voiceId, speed)
        languages += phonemizationLanguage
        synthesizeException?.let { throw it }
        emit(SynthAudio(pcm = nextPcm, sampleRate = sampleRate))
    }
}

// ---------------------------------------------------------------------------
// FakeKokoroDirectEngine — JVM-safe subclass of KokoroDirectEngine that mirrors
// the FakeKittenDirectEngine pattern. KokoroDirectEngine is declared `open`
// solely to enable this test double — same reasoning as KittenDirectEngine.
// ---------------------------------------------------------------------------

internal class FakeKokoroDirectEngine(
    ctx: Context,
    settings: SettingsRepository,
) : KokoroDirectEngine(ctx, settings, fakeSharedEspeakData()) {

    /** PCM to return from synthesize(); ignored if synthesizeException is set. */
    var nextPcm: ShortArray = ShortArray(0)

    /** If non-null, synthesize() throws this instead of returning audio. */
    var synthesizeException: Throwable? = null

    /** Track invocations for any test that wants to assert how synthesize was called. */
    val calls: MutableList<Triple<String, String, Float>> = mutableListOf()

    /** phonemizationLanguage of each call, index-aligned with [calls]. */
    val languages: MutableList<String?> = mutableListOf()

    override val sampleRate: Int get() = 24_000

    override fun isInstalled(): Boolean = synthesizeException !is EngineNotInstalledException

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio {
        calls += Triple(text, voiceId, speed)
        languages += phonemizationLanguage
        synthesizeException?.let { throw it }
        return SynthAudio(pcm = nextPcm, sampleRate = sampleRate)
    }

    /**
     * When true, synthesizeStream emits chunks forever (with a small
     * cancellable delay) — lets the onStop test verify cancellation
     * actually tears the collection down.
     */
    var streamForever: Boolean = false

    // See FakeKittenDirectEngine.synthesizeStream.
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = kotlinx.coroutines.flow.flow {
        calls += Triple(text, voiceId, speed)
        languages += phonemizationLanguage
        synthesizeException?.let { throw it }
        if (streamForever) {
            while (true) {
                emit(SynthAudio(pcm = ShortArray(512), sampleRate = sampleRate))
                kotlinx.coroutines.delay(5)
            }
        }
        emit(SynthAudio(pcm = nextPcm, sampleRate = sampleRate))
    }
}

// ---------------------------------------------------------------------------
// FakeVoiceMetaDao — in-memory VoiceMetaDao for the service's voice lookups.
// The service only calls findById() in the onSynthesizeText path; the other
// methods are implemented as best-effort stubs so the interface contract is
// honoured if a future revision starts using them.
// ---------------------------------------------------------------------------

internal class FakeVoiceMetaDao(seed: List<VoiceMeta>) : VoiceMetaDao {

    private val rows: MutableMap<String, VoiceMeta> = seed.associateBy { it.id }.toMutableMap()

    override fun getAll(): Flow<List<VoiceMeta>> = flowOf(rows.values.toList())

    override fun getByEngine(engine: String): Flow<List<VoiceMeta>> =
        flowOf(rows.values.filter { it.engine == engine })

    override suspend fun findById(id: String): VoiceMeta? = rows[id]

    override suspend fun count(): Int = rows.size

    override suspend fun upsert(voice: VoiceMeta) {
        rows[voice.id] = voice
    }

    override suspend fun upsertAll(voices: List<VoiceMeta>) {
        for (v in voices) rows[v.id] = v
    }

    override suspend fun deleteByEngine(engine: String) {
        rows.values.removeAll { it.engine == engine }
    }
}

// ---------------------------------------------------------------------------
// FakePreprocessSettings — minimal SettingsRepository override that only
// implements the preprocessing-rule lookup. The service reads
// settings.enabledRules("kitten-direct-v0_8") via runBlocking; everything else
// routes to the parent's no-op DataStore (never collected in this test).
// ---------------------------------------------------------------------------

internal class FakePreprocessSettings(
    initialRules: Set<String> = EngineProfiles.defaultsFor("kitten-direct-v0_8"),
) : SettingsRepository(NoOpPreferencesDataStoreForService) {
    private val rules = MutableStateFlow(initialRules)
    override fun enabledRules(engineName: String): Flow<Set<String>> = rules
    override suspend fun setEnabledRules(engineName: String, rules: Set<String>) {
        this.rules.value = rules
    }

    // TtsRouter.resolveAlias calls settings.primaryAliasId.first() —
    // the parent's flow is built on the no-op DataStore which emits
    // nothing, causing first() to fail. Override with a real flow that
    // emits null (= no primary set) so the router falls through to
    // "use engine default."
    private val primary = MutableStateFlow<String?>(null)
    override val primaryAliasId: Flow<String?> = primary
    override suspend fun setPrimaryAliasId(value: String?) { primary.value = value }
}

private val NoOpPreferencesDataStoreForService =
    object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data: Flow<androidx.datastore.preferences.core.Preferences> =
            kotlinx.coroutines.flow.emptyFlow()

        override suspend fun updateData(
            transform: suspend (
                androidx.datastore.preferences.core.Preferences,
            ) -> androidx.datastore.preferences.core.Preferences,
        ): androidx.datastore.preferences.core.Preferences =
            throw UnsupportedOperationException("test stub")
    }

/**
 * [SharedEspeakData] stand-in for the fake engines. They never load, so
 * ensure() is never called — a temp target + no-op copy satisfies the
 * constructor without touching AssetManager.
 */
private fun fakeSharedEspeakData(): app.marmalade.tts.phonemizer.SharedEspeakData =
    app.marmalade.tts.phonemizer.SharedEspeakData(
        targetDir = java.io.File(System.getProperty("java.io.tmpdir"), "espeak-test"),
        dataVersion = "test",
        copyAssets = {},
    )
