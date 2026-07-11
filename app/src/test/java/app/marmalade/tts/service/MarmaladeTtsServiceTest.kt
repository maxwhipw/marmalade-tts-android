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
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.preprocessing.EngineProfiles
import app.marmalade.tts.preprocessing.Preprocessor
import app.marmalade.tts.preprocessing.PreprocessingRules
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            proEntitlement = AlwaysProEntitlement,
        ))
    }

    private object EmptyMappingDao : app.marmalade.tts.data.db.AppAliasMappingDao {
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<app.marmalade.tts.data.db.AppAliasMapping>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun findByPackage(packageName: String) = null
        override suspend fun upsert(mapping: app.marmalade.tts.data.db.AppAliasMapping) = Unit
        override suspend fun delete(packageName: String) = Unit
    }

    private object EmptyAliasDao : app.marmalade.tts.data.db.VoiceAliasDao {
        override fun getAll(): kotlinx.coroutines.flow.Flow<List<app.marmalade.tts.data.db.VoiceAlias>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun findByName(name: String) = null
        override suspend fun upsert(alias: app.marmalade.tts.data.db.VoiceAlias) = Unit
        override suspend fun delete(name: String) = Unit
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
    private fun installKittenPrimary() {
        val alias = app.marmalade.tts.data.db.VoiceAlias(
            name = "kitty",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Bella",
            speed = 1.5f,
            effectPreset = "NONE",
            createdAt = 0L,
        )
        val aliasDao = object : app.marmalade.tts.data.db.VoiceAliasDao {
            override fun getAll(): kotlinx.coroutines.flow.Flow<List<app.marmalade.tts.data.db.VoiceAlias>> =
                kotlinx.coroutines.flow.flowOf(listOf(alias))
            override suspend fun findByName(name: String) = alias.takeIf { it.name == name }
            override suspend fun upsert(alias: app.marmalade.tts.data.db.VoiceAlias) = Unit
            override suspend fun delete(name: String) = Unit
        }
        kotlinx.coroutines.runBlocking { fakeSettings.setPrimaryAliasName("kitty") }
        setField(service, "router", TtsRouter(
            mappingDao = EmptyMappingDao,
            aliasDao = aliasDao,
            settings = fakeSettings,
            proEntitlement = AlwaysProEntitlement,
        ))
    }

    @Test
    fun onSynthesizeText_autoFilledDefaultVoice_yieldsToPrimaryAlias() {
        installKittenPrimary()
        fakeEngine.nextPcm = ShortArray(1024) { 0 }

        // The request carries the catalog default — the auto-fill echo, not
        // a deliberate pick — so the kitten primary must win.
        val request = newRequestWithVoice("hello", KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID)
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

        // And a non-English locale must report NOT_SUPPORTED.
        val notSupported = service.onIsLanguageAvailable("fr", "FR", "")
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, notSupported)
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
) : KittenDirectEngine(ctx, settings) {

    /** PCM to return from synthesize(); ignored if synthesizeException is set. */
    var nextPcm: ShortArray = ShortArray(0)

    /** If non-null, synthesize() throws this instead of returning audio. */
    var synthesizeException: Throwable? = null

    /** Track invocations for any test that wants to assert how synthesize was called. */
    val calls: MutableList<Triple<String, String, Float>> = mutableListOf()

    override val sampleRate: Int get() = 24_000

    override fun isInstalled(): Boolean = synthesizeException !is EngineNotInstalledException

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio {
        calls += Triple(text, voiceId, speed)
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
) : KokoroDirectEngine(ctx, settings) {

    /** PCM to return from synthesize(); ignored if synthesizeException is set. */
    var nextPcm: ShortArray = ShortArray(0)

    /** If non-null, synthesize() throws this instead of returning audio. */
    var synthesizeException: Throwable? = null

    /** Track invocations for any test that wants to assert how synthesize was called. */
    val calls: MutableList<Triple<String, String, Float>> = mutableListOf()

    override val sampleRate: Int get() = 24_000

    override fun isInstalled(): Boolean = synthesizeException !is EngineNotInstalledException

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio {
        calls += Triple(text, voiceId, speed)
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

    // TtsRouter.resolveAlias calls settings.primaryAliasName.first() —
    // the parent's flow is built on the no-op DataStore which emits
    // nothing, causing first() to fail. Override with a real flow that
    // emits null (= no primary set) so the router falls through to
    // "use engine default."
    private val primary = MutableStateFlow<String?>(null)
    override val primaryAliasName: Flow<String?> = primary
    override suspend fun setPrimaryAliasName(value: String?) { primary.value = value }
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

private object AlwaysProEntitlement : app.marmalade.tts.pro.ProEntitlement {
    override val isPro: kotlinx.coroutines.flow.StateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(true)
    override suspend fun launchPurchase(
        activity: android.app.Activity
    ): app.marmalade.tts.pro.PurchaseResult =
        app.marmalade.tts.pro.PurchaseResult.NotApplicable
    override suspend fun restorePurchases() = Unit
}
