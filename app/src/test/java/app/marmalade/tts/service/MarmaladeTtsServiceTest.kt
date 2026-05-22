package app.marmalade.tts.service

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.KokoroVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.KittenEngine
import app.marmalade.tts.engine.KokoroEngine
import app.marmalade.tts.engine.SynthAudio
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
//     * FakeKittenEngine    — JVM-safe subclass overriding sampleRate +
//                             synthesize() so we never touch the Sherpa-ONNX
//                             JNI bridge (which won't load in Robolectric).
//     * FakeVoiceMetaDao    — in-memory stub seeded with KittenVoiceCatalog.
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
    private lateinit var fakeEngine: FakeKittenEngine
    private lateinit var fakeKokoroEngine: FakeKokoroEngine
    private lateinit var fakeDao: FakeVoiceMetaDao
    private lateinit var fakeSettings: FakePreprocessSettings
    private lateinit var preprocessor: Preprocessor

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        fakeEngine = FakeKittenEngine(ctx)
        fakeKokoroEngine = FakeKokoroEngine(ctx)
        // Seed both catalogs so the engine-routing tests can resolve
        // either a kitten:* or kokoro:* voice through the DAO lookup.
        fakeDao = FakeVoiceMetaDao(KittenVoiceCatalog.voices + KokoroVoiceCatalog.voices)
        fakeSettings = FakePreprocessSettings()
        preprocessor = Preprocessor(
            rulesByName = PreprocessingRules.ALL.associateBy { it.name },
        )
        service = MarmaladeTtsService()
        // Inject by reflection — Hilt isn't running so the @Inject lateinit
        // vars are unset. All five fields are public-by-Kotlin-default
        // backing properties; reflection bypasses the lateinit
        // "isInitialized" guard.
        setField(service, "engine", fakeEngine)
        setField(service, "kokoroEngine", fakeKokoroEngine)
        setField(service, "voiceDao", fakeDao)
        setField(service, "preprocessor", preprocessor)
        setField(service, "settings", fakeSettings)
    }

    // -- 1. happy path: start → audioAvailable* → done --------------------

    @Test
    fun onSynthesizeText_happyPath_callOrderIsStartAudioAvailableDone() {
        // No voiceName on the request → service defaults to the
        // recommended engine (kokoro). Seed the kokoro fake with PCM.
        val sampleCount = 48_000
        fakeKokoroEngine.nextPcm = ShortArray(sampleCount) { (it and 0xFF).toShort() }

        val callback = FakeSynthesisCallback()
        val request = newRequest("hello world")

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
            "Default voice should route to the kokoro engine",
            1,
            fakeKokoroEngine.calls.size,
        )
        assertEquals(
            "Default voice should NOT touch the kitten engine",
            0,
            fakeEngine.calls.size,
        )
    }

    // -- 2. engine not installed → callback.error() exactly once ----------

    @Test
    fun onSynthesizeText_engineNotInstalled_callsErrorExactlyOnce() {
        // Default voice → kokoro engine. Configure that fake to throw.
        fakeKokoroEngine.synthesizeException = EngineNotInstalledException("kokoro")

        val callback = FakeSynthesisCallback()
        val request = newRequest("hello world")

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
        // Default voice → kokoro engine.
        val sampleCount = 100_000
        val expectedBytes = sampleCount * 2 // PCM16
        fakeKokoroEngine.nextPcm = ShortArray(sampleCount) { (it and 0xFF).toShort() }

        val maxBuf = 8192
        val callback = FakeSynthesisCallback(maxBufferSize = maxBuf)
        val request = newRequest("a longer sentence to synthesize")

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
    fun onSynthesizeText_kittenVoiceRoutesToKittenEngine() {
        // Explicit kitten voiceName on the request — service must dispatch
        // to the kitten engine, never touching kokoro.
        fakeEngine.nextPcm = ShortArray(1024) { 0 }

        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello kitten", "kitten:Bella")

        service.onSynthesizeText(request, callback)

        assertEquals(
            "kitten:* voiceName should hit the kitten engine exactly once",
            1,
            fakeEngine.calls.size,
        )
        assertEquals(
            "kitten:* voiceName should NOT touch the kokoro engine",
            0,
            fakeKokoroEngine.calls.size,
        )
        // The voice id passed to the engine must round-trip unchanged.
        assertEquals("kitten:Bella", fakeEngine.calls.single().second)
    }

    @Test
    fun onSynthesizeText_kokoroVoiceRoutesToKokoroEngine() {
        // Explicit kokoro voiceName on the request — service must dispatch
        // to the kokoro engine, never touching kitten.
        fakeKokoroEngine.nextPcm = ShortArray(1024) { 0 }

        val callback = FakeSynthesisCallback()
        val request = newRequestWithVoice("hello kokoro", "kokoro:bm_lewis")

        service.onSynthesizeText(request, callback)

        assertEquals(
            "kokoro:* voiceName should hit the kokoro engine exactly once",
            1,
            fakeKokoroEngine.calls.size,
        )
        assertEquals(
            "kokoro:* voiceName should NOT touch the kitten engine",
            0,
            fakeEngine.calls.size,
        )
        assertEquals("kokoro:bm_lewis", fakeKokoroEngine.calls.single().second)
    }

    @Test
    fun onLoadVoice_acceptsBothEnginesAndRejectsUnknown() {
        // Both engines' voices must round-trip — required for the system
        // TTS picker to enumerate them through Settings → Languages → TTS.
        assertEquals(TextToSpeech.SUCCESS, service.onLoadVoice("kitten:Bella"))
        assertEquals(TextToSpeech.SUCCESS, service.onLoadVoice("kokoro:af_bella"))
        // Unknown engines (or junk) are rejected so the system falls back
        // to the language-level default rather than us silently swallowing
        // a bad voice request.
        assertEquals(TextToSpeech.ERROR, service.onLoadVoice("piper:Alan"))
        assertEquals(TextToSpeech.ERROR, service.onLoadVoice(""))
        assertEquals(TextToSpeech.ERROR, service.onLoadVoice(null))
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

    private fun newRequest(text: String): SynthesisRequest {
        // Public ctor (String, Bundle) is API 28+; the project's minSdk is 28
        // (see app/build.gradle.kts). voiceName is null on this path, so the
        // service falls back to KokoroVoiceCatalog.DEFAULT_VOICE_ID
        // (Kokoro became the recommended-default engine in v0.1.9).
        val req = SynthesisRequest(text, Bundle())
        assertNotNull(req)
        return req
    }

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

    val events: MutableList<Event> = mutableListOf()
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
// FakeKittenEngine — JVM-safe subclass of KittenEngine that returns a fixed
// PCM ShortArray (or throws a configured exception) without touching the
// Sherpa-ONNX JNI bridge. KittenEngine is declared `open` solely to enable
// this test double — see the prod-code comment above the class declaration.
// ---------------------------------------------------------------------------

internal class FakeKittenEngine(ctx: Context) : KittenEngine(ctx) {

    /** PCM to return from synthesize(); ignored if synthesizeException is set. */
    var nextPcm: ShortArray = ShortArray(0)

    /** If non-null, synthesize() throws this instead of returning audio. */
    var synthesizeException: Throwable? = null

    /** Track invocations for any test that wants to assert how synthesize was called. */
    val calls: MutableList<Triple<String, String, Float>> = mutableListOf()

    override val sampleRate: Int get() = 24_000

    override fun isInstalled(): Boolean = synthesizeException !is EngineNotInstalledException

    override suspend fun synthesize(text: String, voiceId: String, speed: Float): SynthAudio {
        calls += Triple(text, voiceId, speed)
        synthesizeException?.let { throw it }
        return SynthAudio(pcm = nextPcm, sampleRate = sampleRate)
    }
}

// ---------------------------------------------------------------------------
// FakeKokoroEngine — JVM-safe subclass of KokoroEngine that mirrors the
// FakeKittenEngine pattern. KokoroEngine is declared `open` solely to enable
// this test double — same reasoning as KittenEngine.
// ---------------------------------------------------------------------------

internal class FakeKokoroEngine(ctx: Context) : KokoroEngine(ctx) {

    /** PCM to return from synthesize(); ignored if synthesizeException is set. */
    var nextPcm: ShortArray = ShortArray(0)

    /** If non-null, synthesize() throws this instead of returning audio. */
    var synthesizeException: Throwable? = null

    /** Track invocations for any test that wants to assert how synthesize was called. */
    val calls: MutableList<Triple<String, String, Float>> = mutableListOf()

    override val sampleRate: Int get() = 24_000

    override fun isInstalled(): Boolean = synthesizeException !is EngineNotInstalledException

    override suspend fun synthesize(text: String, voiceId: String, speed: Float): SynthAudio {
        calls += Triple(text, voiceId, speed)
        synthesizeException?.let { throw it }
        return SynthAudio(pcm = nextPcm, sampleRate = sampleRate)
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
// settings.enabledRules("kitten") via runBlocking; everything else routes
// to the parent's no-op DataStore (never collected in this test).
// ---------------------------------------------------------------------------

internal class FakePreprocessSettings(
    initialRules: Set<String> = EngineProfiles.defaultsFor("kitten"),
) : SettingsRepository(NoOpPreferencesDataStoreForService) {
    private val rules = MutableStateFlow(initialRules)
    override fun enabledRules(engineName: String): Flow<Set<String>> = rules
    override suspend fun setEnabledRules(engineName: String, rules: Set<String>) {
        this.rules.value = rules
    }
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
