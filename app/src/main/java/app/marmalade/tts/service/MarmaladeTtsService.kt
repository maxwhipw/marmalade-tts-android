package app.marmalade.tts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.audio.PipelineResult
import app.marmalade.tts.audio.runSynthesisPipeline
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.KittenMiniEngine
import app.marmalade.tts.engine.KittenNanoEngine
import app.marmalade.tts.engine.KokoroV10Engine
import app.marmalade.tts.engine.KokoroV11Engine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.preprocessing.Preprocessor
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Android system  ── onSynthesizeText(request, callback) ──▶  this service
//      │
//      ├── request.voiceName  →  pickVoiceFromRequest  ──► voiceId
//      │      │                                           e.g. "kokoro:af_bella"
//      │      ▼                                           or "kitten:Bella"
//      │   VoiceMetaDao.findById(voiceId)  (suspend, runBlocking)
//      │      │
//      │      ▼                                           fallback when no
//      │   engineNameFor(voiceId)  ──► "kokoro" | "kitten"   match: kokoro
//      │
//      ├── if no explicit voiceName: per-app routing fires
//      │      packageManager.getNameForUid(request.callerUid) ──► pkg?
//      │      TtsRouter.resolveAlias(pkg)  (suspend, runBlocking)
//      │        ├── per-app mapping (com.spotify → "narrator") wins, OR
//      │        ├── primary alias is the next fallback, OR
//      │        └── null → engine default voice (existing behaviour)
//      │      Resolved alias supplies voiceId + speed + effectPreset.
//      │
//      ├── request.charSequenceText  → String (raw, may contain emojis)
//      │      │
//      │      ├── EmojiProsody.detect(raw) ──► ProsodyHint(emotion, intensity)
//      │      ├── settings.enabledRules(engineName).first()  (runBlocking)
//      │      │     ──► Set<String> of enabled preprocessing rule names
//      │      ├── Preprocessor.apply(raw, enabled)  ──► normalized text
//      │      │     (HTML decode, currency, numbers→words, etc.)
//      │      └── EmojiProsody.stripEmojis(normalized) ──► cleaned text
//      │              for the engine (Sherpa-ONNX can't phonemize 🤣;
//      │              espeak would read it as "rolling on the floor
//      │              laughing face").
//      ▼
//   callback.start(sampleRateFor(engineName), ENCODING_PCM_16BIT, mono)
//      │
//      ▼
//   synthesizeForEngine(engineName, text, voiceId)   (runBlocking)
//      │   ├── "kokoro" → KokoroEngine.synthesize(...)
//      │   └── "kitten" → KittenEngine.synthesize(...)
//      │  produces SynthAudio(pcm: ShortArray, sampleRate)
//      ▼
//   ProsodyApplier.apply(pcm, sampleRate, detectedEmotion)
//      │  pitch/rate/volume/extras per emotion; Neutral is identity.
//      ▼
//   EffectChain.apply(pcm, sampleRate, effectPreset)
//      │  reverb/robotization/bandpass per resolved alias's effectPreset.
//      │  NONE is identity (no allocation).
//      ▼
//   pcm → little-endian ByteArray → chunked writes via
//          callback.audioAvailable(buf, offset, n)  while
//          n <= callback.maxBufferSize on each iteration.
//      │
//      ▼
//   callback.done()   (or callback.error() on any exception)
//
//   Voice negotiation (onLoadVoice / onIsLanguageAvailable) accepts any
//   voice belonging to a known engine (kitten or kokoro) so the system
//   can enumerate both catalogs through Settings → Languages → TTS.
//
//   onLoadLanguage additionally fires a background warm-up — calling
//   ensureModelLoaded() on both engines off-thread — so that the first
//   onSynthesizeText doesn't pay the ~2–5 s cold-start tax for loading
//   model bytes + espeak-ng-data via JNI. Engines that aren't installed
//   throw EngineNotInstalledException which the warm-up silently absorbs.
// -----------------------------------------------------------------------------

/**
 * System TTS engine implementation.
 *
 * Registered in `AndroidManifest.xml` with the
 * `android.intent.action.TTS_SERVICE` intent-filter so Android Settings
 * → Languages → Text-to-speech can select Marmalade as the device TTS
 * engine.
 *
 * Wires the system synthesis callback contract to [KittenEngine] and
 * [KokoroEngine], routing each request to the engine the requested voice
 * belongs to:
 *
 * 1. `onSynthesizeText` resolves the requested voice against [VoiceMetaDao],
 *    picks the engine from `voice.engine`, asks that engine for PCM, and
 *    streams it through the callback in `callback.maxBufferSize` chunks.
 * 2. `onIsLanguageAvailable` / `onLoadLanguage` honour English variants
 *    only — both installable engines are English-only today.
 * 3. `onLoadVoice` accepts any voice ID seeded for either engine.
 *
 * Failures (missing model, JNI crash, etc.) surface as `callback.error()`
 * with a logged reason. The system retries or falls back to its default
 * engine, which is the documented behaviour and never produces fake
 * audio.
 */
@AndroidEntryPoint
class MarmaladeTtsService : TextToSpeechService() {

    @Inject lateinit var kittenNano: KittenNanoEngine
    @Inject lateinit var kittenMini: KittenMiniEngine
    @Inject lateinit var kokoroV10: KokoroV10Engine
    @Inject lateinit var kokoroV11: KokoroV11Engine
    @Inject lateinit var pocket: PocketEngine

    @Inject lateinit var voiceDao: VoiceMetaDao

    @Inject lateinit var preprocessor: Preprocessor

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var router: TtsRouter

    // Bound to the service lifecycle (cancelled in onDestroy). Used for the
    // background model warm-up kicked off from onLoadLanguage. Dispatchers.IO
    // because ensureModelLoaded() reads ~44 MB off disk before handing to JNI.
    private val serviceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Hot-path caches -----------------------------------------------------
    //
    // Pre-resolved at onCreate / onLoadLanguage to keep onSynthesizeText off
    // the four-runBlocking spin it used through v0.1.15. On cold start with
    // Room/DataStore checkpointing under load, that spin flirted with
    // Android's ~10 s synthesis watchdog; the cache lookups are O(1) and
    // never block. A single defensive runBlocking fallback remains in
    // resolveSynthParams for voices that arrive after the service started
    // (e.g. a freshly-installed engine seeds new rows), so correctness is
    // preserved even on a cache miss.
    //
    // voiceId → engine name, populated from voiceDao.getAll() in onCreate.
    private val voiceEngineCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // engineName → enabled preprocessing rules. Populated lazily from
    // onLoadLanguage (the framework's documented warm-up hook). Reads in
    // onSynthesizeText fall back to the default set if the cache hasn't
    // been populated yet, then trigger an async refresh.
    private val rulesCache: ConcurrentHashMap<String, Set<String>> = ConcurrentHashMap()

    override fun onCreate() {
        super.onCreate()
        // Mirror voice catalog into the engine-routing cache. Collection runs
        // on the service scope so it stays in sync with later upserts (e.g.
        // an engine install seeds new rows after launch).
        serviceScope.launch {
            voiceDao.getAll().collect { voices ->
                // Rebuild rather than incrementally update — the catalog is
                // tiny (≤ 20 entries) and a full replace keeps removals
                // honest if a future revision deletes voices.
                voiceEngineCache.clear()
                for (v in voices) {
                    voiceEngineCache[v.id] = v.engine
                }
            }
        }
    }

    // Widened to public so MarmaladeTtsServiceTest can exercise the
    // language-negotiation logic without subclassing the framework type.
    public override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val l = lang?.lowercase()
        val c = country?.uppercase()
        return when {
            // Match ISO-639-1 ("en") and -3 ("eng"); the system uses both in
            // different paths.
            l == "en" || l == "eng" -> when (c) {
                null, "" -> TextToSpeech.LANG_AVAILABLE
                "US", "USA" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
                else -> TextToSpeech.LANG_AVAILABLE
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /**
     * Called by the framework when the user selects this engine in
     * Settings → Languages → Text-to-speech, and again on engine start.
     *
     * Returns the same `LANG_*` code as [onIsLanguageAvailable] (the
     * contract is identical — same inputs, same outputs). On any positive
     * match we additionally fire a background warm-up so the first
     * [onSynthesizeText] does not pay the ~2–5 s cold-start tax of loading
     * ~25 MB of model + 19 MB of espeak-ng-data via JNI. Some clients
     * (screen readers especially) treat a slow `callback.start()` as an
     * engine fault.
     */
    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        val supported = result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (supported) {
            warmUpEngine()
        }
        return result
    }

    /**
     * Pre-load every installed engine on a background coroutine so the
     * next [onSynthesizeText] is fast. Safe to call repeatedly —
     * each engine's `ensureModelLoaded` is idempotent. Failures are logged
     * and swallowed; the synthesis path's own catch will still surface
     * `EngineNotInstalledException` correctly if a warm-up failed.
     *
     * We warm both Kitten and Kokoro speculatively. If the user only has
     * one installed, the other's `isInstalled()` returns false and
     * `ensureModelLoaded()` throws `EngineNotInstalledException`, which
     * the catch silently absorbs.
     */
    private fun warmUpEngine() {
        // Seed the per-engine preprocessing-rule cache off the synth-worker
        // thread so onSynthesizeText doesn't have to runBlocking on the
        // DataStore round-trip. Each engine is collected independently and
        // the cache stays live for later edits (a Settings → Engine detail
        // toggle re-emits, refreshing the entry).
        val knownEngines = listOf(
            KokoroV10VoiceCatalog.ENGINE,
            KokoroV11VoiceCatalog.ENGINE,
            KittenNanoVoiceCatalog.ENGINE,
            KittenMiniVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
        )
        for (engineName in knownEngines) {
            serviceScope.launch {
                settings.enabledRules(engineName).collect { rules ->
                    rulesCache[engineName] = rules
                }
            }
        }
        serviceScope.launch {
            // Warm-up: try to load every installed engine so the first synth
            // call after service start doesn't pay the model-mmap cost.
            // ensureModelLoaded() throws EngineNotInstalledException for
            // engines without a bundle on disk — that's fine, just skip.
            val loaders: List<Pair<String, () -> Unit>> = listOf(
                KokoroV10VoiceCatalog.ENGINE to kokoroV10::ensureModelLoaded,
                KokoroV11VoiceCatalog.ENGINE to kokoroV11::ensureModelLoaded,
                KittenNanoVoiceCatalog.ENGINE to kittenNano::ensureModelLoaded,
                KittenMiniVoiceCatalog.ENGINE to kittenMini::ensureModelLoaded,
                PocketVoiceCatalog.ENGINE to pocket::ensureModelLoaded,
            )
            for ((name, load) in loaders) {
                try {
                    load()
                    Log.d(TAG, "$name engine warm-up complete")
                } catch (ex: Exception) {
                    Log.w(TAG, "$name engine warm-up skipped/failed", ex)
                }
            }
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    /**
     * Called by the system when a voice is selected. Accept any voice ID
     * the catalog knows about (kitten or kokoro); reject the rest so the
     * system falls back to the language-level default.
     */
    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName.isNullOrEmpty()) return TextToSpeech.ERROR
        // Cache-first: every voice the catalog ships gets seeded into
        // voiceEngineCache by the onCreate collector. Defensive runBlocking
        // fallback covers the race where the framework asks about a voice
        // that arrived since the service started.
        val cached = voiceEngineCache[voiceName]
        val engineName = cached ?: runBlocking { voiceDao.findById(voiceName) }
            ?.also { voiceEngineCache[it.id] = it.engine }
            ?.engine
        return if (engineName != null && isKnownEngine(engineName)) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int = onLoadVoice(voiceName)

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String {
        // English request → the catalog-recommended default voice.
        // Kokoro is the recommended engine starting v0.1.9; the picker UX
        // also tracks isRecommended, so this stays in sync without a
        // separate source of truth.
        return if (onIsLanguageAvailable(lang, country, variant)
            != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            KokoroV10VoiceCatalog.DEFAULT_VOICE_ID
        } else {
            ""
        }
    }

    // Widened to public so MarmaladeTtsServiceTest can exercise the
    // synthesis orchestration without subclassing the framework type.
    public override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val rawText = request.charSequenceText?.toString().orEmpty()

        // Voice negotiation. Order of precedence (most-specific wins):
        //   1. Caller-specified voice (request.voiceName, if known).
        //   2. Per-app mapping for the caller's package name.
        //   3. Primary alias.
        //   4. Engine default voice (current behaviour).
        // resolveSynthParams encodes this; see the helper kdoc.
        val params = resolveSynthParams(request)
        val voiceId = params.voiceId
        val engineName = engineNameFor(voiceId)
        val activeSampleRate = sampleRateFor(engineName)

        if (rawText.isBlank()) {
            // Open + close so the system isn't left waiting on us.
            callback.start(activeSampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // Hot path: prefer the cache (populated by the onLoadLanguage warm-up
        // collector). Cache miss means either (a) onLoadLanguage hasn't fired
        // yet for this engine, or (b) the engine name is unknown. In either
        // case fall back to a single runBlocking pull so correctness is
        // preserved even on first synthesis after install. Rules are
        // looked up per-engine so a user who curates kokoro's ruleset
        // separately from kitten's gets the right behaviour.
        val enabled = rulesCache[engineName]
            ?: runBlocking { settings.enabledRules(engineName).first() }
                .also { rulesCache[engineName] = it }

        Log.d(
            TAG,
            "onSynthesizeText: voice=$voiceId engine=$engineName " +
                "speed=${params.speed} effect=${params.effect} " +
                "chars=${rawText.length}",
        )

        val startResult = callback.start(
            activeSampleRate,
            AudioFormat.ENCODING_PCM_16BIT,
            1, // mono
        )
        if (startResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "callback.start() returned $startResult — aborting")
            callback.error()
            return
        }

        try {
            val result = runBlocking {
                runSynthesisPipeline(
                    rawText = rawText,
                    voiceId = voiceId,
                    speed = params.speed,
                    enabledRules = enabled,
                    effect = params.effect,
                    preprocessor = preprocessor,
                    synthesize = { t, v, s -> synthesizeForEngine(engineName, t, v, s) },
                )
            }
            when (result) {
                is PipelineResult.Empty -> {
                    // Input collapsed to nothing speakable (e.g. emoji-only
                    // text stripped to ""). Close the callback cleanly so
                    // the system isn't left waiting.
                    callback.done()
                }
                is PipelineResult.Audio -> {
                    streamPcm(callback, result.pcm)
                    callback.done()
                }
            }
        } catch (e: Exception) {
            // Covers IllegalStateException from either engine (assets
            // missing / corrupt / JNI failure) and any other synthesis
            // exception. The system retries or falls back to its default
            // engine — never produces fake audio.
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    // TODO: STUBS.md — onStop cancellation
    override fun onStop() {
        // Nothing to cancel: synthesis is synchronous and the system will
        // simply ignore any pcm we'd write after onStop returns. Future
        // work (chunked streaming generation) will set a cancel flag here.
    }

    override fun onDestroy() {
        // Tear down the warm-up scope before the framework releases us.
        serviceScope.cancel()
        super.onDestroy()
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Bundle of synthesis parameters resolved for the current request.
     * Returned from [resolveSynthParams] so the precedence rule lives in
     * exactly one place.
     */
    internal data class SynthParams(
        val voiceId: String,
        val speed: Float,
        val effect: EffectPreset,
    )

    /**
     * Resolve the voice/speed/effect bundle for this synthesis request.
     *
     * Order of precedence (most-specific wins):
     *   1. Caller-specified [SynthesisRequest.getVoiceName] when it
     *      matches a seeded voice — engine-default speed (1.0×) and
     *      NONE effect, because the caller asked for that exact voice.
     *   2. Per-app mapping for the caller's package name — voice + speed
     *      + effect all come from the mapped alias.
     *   3. Primary alias — same fields from the user-designated primary.
     *   4. Engine default voice + 1.0× speed + NONE effect (the v0.1.10
     *      pre-routing behaviour).
     *
     * Runs blocking on the synth-worker thread per the TextToSpeechService
     * contract (the system explicitly calls `onSynthesizeText` off the
     * main thread).
     */
    internal fun resolveSynthParams(request: SynthesisRequest): SynthParams {
        val requested = request.voiceName?.takeIf { it.isNotBlank() }
        val callerPackage = resolveCallerPackage(request)

        // 1. Honor an explicit, known voice request — cache-first so the
        // common "request specifies a voice that was seeded at install" path
        // stays off the synth-worker's blocking road. Falls through to the
        // combined router pass below on cache miss; that block does one
        // runBlocking that covers both the voice-id lookup and the alias
        // resolution (down from two serial blocks in v0.1.15).
        val cachedEngine = requested?.let { voiceEngineCache[it] }
        if (requested != null && cachedEngine != null && isKnownEngine(cachedEngine)) {
            return SynthParams(
                voiceId = requested,
                speed = 1.0f,
                effect = EffectPreset.NONE,
            )
        }

        // 2 + 3 + 1-fallback. Single defensive runBlocking that covers:
        //   - the voice-id DAO lookup (when the cache missed — voice may
        //     have arrived after onCreate's collector started),
        //   - the per-app router → primary alias resolution.
        // resolveCallerPackage returns null when the UID can't be resolved
        // (shared UID, etc.) which TtsRouter.resolveAlias handles by
        // skipping straight to the primary lookup.
        val resolved: SynthParams? = runBlocking {
            if (requested != null) {
                val hit = voiceDao.findById(requested)
                if (hit != null && isKnownEngine(hit.engine)) {
                    // Seed the cache so the next request takes the fast path.
                    voiceEngineCache[hit.id] = hit.engine
                    return@runBlocking SynthParams(
                        voiceId = hit.id,
                        speed = 1.0f,
                        effect = EffectPreset.NONE,
                    )
                }
            }
            val alias: VoiceAlias? = router.resolveAlias(callerPackage)
            if (alias != null) {
                SynthParams(
                    voiceId = alias.voiceId,
                    speed = alias.speed,
                    effect = decodeEffect(alias.effectPreset),
                )
            } else {
                null
            }
        }
        if (resolved != null) return resolved

        // 4. Absolute fallback — the engine's default voice with
        // unchanged speed/effect. Matches v0.1.10 behaviour.
        return SynthParams(
            voiceId = KokoroV10VoiceCatalog.DEFAULT_VOICE_ID,
            speed = 1.0f,
            effect = EffectPreset.NONE,
        )
    }

    /**
     * Look up the calling app's package name from
     * [SynthesisRequest.getCallerUid]. Returns null when the UID is
     * shared between multiple apps (PackageManager returns null in that
     * case) — the router treats null as "skip per-app routing, use the
     * primary directly".
     *
     * Wrapped in a try/catch because PackageManager can throw on hostile
     * input; we degrade gracefully rather than crashing the synth worker.
     */
    private fun resolveCallerPackage(request: SynthesisRequest): String? {
        return try {
            packageManager.getNameForUid(request.callerUid)
        } catch (t: Throwable) {
            Log.w(TAG, "getNameForUid failed for uid=${request.callerUid}", t)
            null
        }
    }

    /**
     * Map the persisted effect-preset string back to the enum. Defaults
     * to NONE for unknown values — keeps the synth path robust against
     * a future enum addition that lands in DataStore before the runtime
     * code knows about it.
     */
    private fun decodeEffect(raw: String): EffectPreset =
        EffectPreset.entries.firstOrNull { it.name == raw } ?: EffectPreset.NONE

    /**
     * Resolve the voice ID for this synthesis request (legacy shape).
     *
     * Retained as a thin wrapper over [resolveSynthParams] so existing
     * call sites that only want the voice ID don't have to deal with the
     * triple-bundle. Tests still pin this via reflection on the older
     * shape — see MarmaladeTtsServiceTest.
     */
    internal fun pickVoiceFromRequest(request: SynthesisRequest): String =
        resolveSynthParams(request).voiceId

    /**
     * Engine name embedded in [voiceId] (everything before the first `:`).
     * Defaults to Kokoro v1.0 when the form doesn't match — the catch-all
     * keeps the synthesis path robust against junk input from third-party
     * TTS clients that bypass the voice negotiation contract.
     */
    private fun engineNameFor(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        if (sep <= 0) return KokoroV10VoiceCatalog.ENGINE
        val name = voiceId.substring(0, sep)
        return if (isKnownEngine(name)) name else KokoroV10VoiceCatalog.ENGINE
    }

    /** Per-engine sample rate. All five engines ship 24 kHz today. */
    private fun sampleRateFor(engineName: String): Int = when (engineName) {
        KokoroV10VoiceCatalog.ENGINE -> kokoroV10.sampleRate
        KokoroV11VoiceCatalog.ENGINE -> kokoroV11.sampleRate
        KittenNanoVoiceCatalog.ENGINE -> kittenNano.sampleRate
        KittenMiniVoiceCatalog.ENGINE -> kittenMini.sampleRate
        PocketVoiceCatalog.ENGINE -> pocket.sampleRate
        else -> kokoroV10.sampleRate
    }

    /**
     * Route synthesis to the engine identified by [engineName]. Suspending
     * because each engine hands off to `Dispatchers.Default` internally,
     * which the caller `runBlocking`s on the system-TTS worker thread.
     *
     * Unknown engine names degrade to Kokoro v1.0 — same fallback as
     * [pickVoiceFromRequest]; the upstream voice-negotiation paths already
     * filtered out engines we don't know about.
     */
    private suspend fun synthesizeForEngine(
        engineName: String,
        text: String,
        voiceId: String,
        speed: Float,
    ): SynthAudio = when (engineName) {
        KokoroV10VoiceCatalog.ENGINE -> kokoroV10.synthesize(text, voiceId, speed)
        KokoroV11VoiceCatalog.ENGINE -> kokoroV11.synthesize(text, voiceId, speed)
        KittenNanoVoiceCatalog.ENGINE -> kittenNano.synthesize(text, voiceId, speed)
        KittenMiniVoiceCatalog.ENGINE -> kittenMini.synthesize(text, voiceId, speed)
        PocketVoiceCatalog.ENGINE -> pocket.synthesize(text, voiceId, speed)
        else -> kokoroV10.synthesize(text, voiceId, speed)
    }

    /** True for any engine the catalog ships. */
    private fun isKnownEngine(name: String): Boolean =
        name == KokoroV10VoiceCatalog.ENGINE ||
            name == KokoroV11VoiceCatalog.ENGINE ||
            name == KittenNanoVoiceCatalog.ENGINE ||
            name == KittenMiniVoiceCatalog.ENGINE ||
            name == PocketVoiceCatalog.ENGINE

    /**
     * Stream [pcm] through the synthesis callback in chunks of at most
     * `callback.maxBufferSize` bytes (the system-imposed cap). The audio
     * is encoded little-endian per the `ENCODING_PCM_16BIT` contract.
     */
    private fun streamPcm(callback: SynthesisCallback, pcm: ShortArray) {
        val bytes = pcm16ToLittleEndianBytes(pcm)
        val chunkSize = callback.maxBufferSize.coerceAtLeast(2) // never zero
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(chunkSize, bytes.size - offset)
            val written = callback.audioAvailable(bytes, offset, n)
            if (written != TextToSpeech.SUCCESS) {
                Log.w(TAG, "callback.audioAvailable returned $written — aborting stream")
                throw RuntimeException("audioAvailable rejected chunk: $written")
            }
            offset += n
        }
    }

    companion object {
        private const val TAG = "MarmaladeTtsService"

        /** Convert PCM16 samples to a little-endian byte array. */
        internal fun pcm16ToLittleEndianBytes(pcm: ShortArray): ByteArray {
            val out = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val s = pcm[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
