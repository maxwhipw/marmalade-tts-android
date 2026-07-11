package app.marmalade.tts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectResolver
import app.marmalade.tts.audio.PipelineResult
import app.marmalade.tts.audio.runSynthesisPipeline
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kitten.KittenDirectMiniEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KittenDirectMiniVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.audio.StreamingEffectChain
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.preprocessing.EmojiProsody
import app.marmalade.tts.preprocessing.Emotion
import app.marmalade.tts.preprocessing.Preprocessor
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
//      ├── if voiceName is absent OR merely echoes our advertised default
//      │    (clients auto-fill it from onGetDefaultVoiceNameFor): routing fires
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
//      │              for the engine (espeak can't phonemize 🤣; it
//      │              would read it as "rolling on the floor laughing
//      │              face").
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
//   EffectChain.applyChain(pcm, sampleRate, effectBlocks)
//      │  the chain resolved from the alias's effectId via EffectResolver.
//      │  An empty chain is identity (no allocation).
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

    @Inject lateinit var kittenDirect: KittenDirectEngine
    @Inject lateinit var kittenDirectMini: KittenDirectMiniEngine
    @Inject lateinit var kokoroDirect: KokoroDirectEngine
    @Inject lateinit var pocket: PocketEngine

    @Inject lateinit var voiceDao: VoiceMetaDao

    @Inject lateinit var preprocessor: Preprocessor

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var router: TtsRouter

    @Inject lateinit var effectResolver: EffectResolver

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

    /** One-shot guard for [warmUpEngine]'s collect-forever rule collectors. */
    private val rulesCacheSeeded = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Job driving the in-flight [onSynthesizeText] runBlocking, if any.
     * [onStop] cancels it from the binder thread. One synthesis at a time
     * per the TextToSpeechService contract, so a plain field suffices.
     */
    @Volatile
    private var synthJob: Job? = null

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
        // toggle re-emits, refreshing the entry). Once per service
        // lifetime: warmUpEngine fires on EVERY positive onLoadLanguage,
        // and these are collect-forever coroutines — without the guard
        // they accumulated on serviceScope for as long as the service
        // lived. (The model warm-up below stays unguarded on purpose: an
        // engine installed after the first call still gets warmed by a
        // later onLoadLanguage, and ensureModelLoaded is idempotent.)
        if (rulesCacheSeeded.compareAndSet(false, true)) {
            val knownEngines = listOf(
                KokoroDirectVoiceCatalog.ENGINE,
                KittenDirectVoiceCatalog.ENGINE,
                KittenDirectMiniVoiceCatalog.ENGINE,
                PocketVoiceCatalog.ENGINE,
            )
            for (engineName in knownEngines) {
                serviceScope.launch {
                    settings.enabledRules(engineName).collect { rules ->
                        rulesCache[engineName] = rules
                    }
                }
            }
        }
        serviceScope.launch {
            // Warm-up: try to load every installed engine so the first synth
            // call after service start doesn't pay the model-mmap cost.
            // ensureModelLoaded() throws EngineNotInstalledException for
            // engines without a bundle on disk — that's fine, just skip.
            val loaders: List<Pair<String, () -> Unit>> = listOf(
                KokoroDirectVoiceCatalog.ENGINE to kokoroDirect::ensureModelLoaded,
                KittenDirectVoiceCatalog.ENGINE to kittenDirect::ensureModelLoaded,
                KittenDirectMiniVoiceCatalog.ENGINE to kittenDirectMini::ensureModelLoaded,
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
        // English request → the user's primary alias voice when one is
        // set, falling back to the catalog-recommended default. Clients
        // auto-fill SynthesisRequest.voiceName from this answer, so
        // advertising the hardcoded Kokoro default here while the user
        // had a (say) Kitten primary alias made every request arrive
        // pre-stamped with Kokoro Bella — and the primary never fired.
        // runBlocking is fine: this is a rare negotiation callback on a
        // binder thread, not the per-utterance synthesis hot path.
        return if (onIsLanguageAvailable(lang, country, variant)
            != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            runBlocking { router.resolveAlias(null)?.voiceId }
                ?: KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID
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
                "speed=${params.speed} effectBlocks=${params.effectBlocks.size} " +
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
            // Streaming eligibility mirrors Synthesizer.speak: effects run
            // on the streaming path (StreamingEffectChain carries DSP state
            // across chunk seams); only non-neutral emotion still needs the
            // batched path, because ProsodyApplier shapes the whole PCM and
            // has no streaming form yet.
            val emotion = EmojiProsody.detect(rawText).emotion
            runBlocking {
                synthJob = coroutineContext[Job]
                try {
                    if (emotion == Emotion.Neutral) {
                        streamingSynthesis(callback, rawText, engineName, params, enabled)
                    } else {
                        batchedSynthesis(callback, rawText, engineName, params, enabled)
                    }
                } finally {
                    synthJob = null
                }
            }
        } catch (e: CancellationException) {
            // onStop() fired mid-synthesis. Not an error — close the
            // callback cleanly; the framework discards anything after stop.
            Log.d(TAG, "Synthesis stopped by client")
            callback.done()
        } catch (e: ChunkRejectedException) {
            // audioAvailable started returning non-SUCCESS mid-stream. In
            // practice that's a client stop/disconnect (e.g. backing out of
            // Settings during the example) — the framework has already torn
            // the request down and error() is a no-op. A genuine sink
            // failure (file-synthesis IO error) takes the same path, where
            // error() does report it. Either way it's routine, not a
            // synthesis fault — one warning line, no stack trace.
            Log.w(TAG, "${e.message} — aborting synthesis (client stop or sink failure)")
            callback.error()
        } catch (e: Exception) {
            // Covers IllegalStateException from either engine (assets
            // missing / corrupt / JNI failure) and any other synthesis
            // exception. The system retries or falls back to its default
            // engine — never produces fake audio.
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    /**
     * Streaming synthesis (the common, emotion-neutral case): collect the
     * engine's chunk flow and hand each chunk to the framework as soon as
     * it exists. First audio reaches the client after the first chunk's
     * inference instead of after the whole utterance, the utterance never
     * sits on the heap in full, and [onStop] gets a real cancellation
     * point between chunks (per AR frame on Pocket).
     */
    private suspend fun streamingSynthesis(
        callback: SynthesisCallback,
        rawText: String,
        engineName: String,
        params: SynthParams,
        enabledRules: Set<String>,
    ) {
        val preprocessed = preprocessor.apply(rawText, enabledRules)
        val stripped = EmojiProsody.stripEmojis(preprocessed)
        if (stripped.isBlank()) {
            // Input collapsed to nothing speakable (e.g. emoji-only text).
            callback.done()
            return
        }
        // The chain is built lazily on the first chunk (its filter
        // coefficients need the sample rate). Empty chain = pass-through.
        var chain: StreamingEffectChain? = null
        var sr = 0
        streamForEngine(engineName, stripped, params.voiceId, params.speed, params.phonemizationLanguage)
            .collect { audio ->
                val c = chain ?: StreamingEffectChain(params.effectBlocks, audio.sampleRate)
                    .also { chain = it; sr = audio.sampleRate }
                val shaped = c.process(audio.pcm)
                if (shaped.isNotEmpty()) streamPcm(callback, shaped)
            }
        // Drain the effect tail (e.g. reverb ring-out) as a final chunk.
        chain?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
            streamPcm(callback, tail)
        }
        callback.done()
    }

    /**
     * Batched synthesis — only for non-neutral emotion, where
     * ProsodyApplier needs the complete PCM before shaping.
     */
    private suspend fun batchedSynthesis(
        callback: SynthesisCallback,
        rawText: String,
        engineName: String,
        params: SynthParams,
        enabledRules: Set<String>,
    ) {
        val result = runSynthesisPipeline(
            rawText = rawText,
            voiceId = params.voiceId,
            speed = params.speed,
            enabledRules = enabledRules,
            effectBlocks = params.effectBlocks,
            preprocessor = preprocessor,
            synthesize = { t, v, s -> synthesizeForEngine(engineName, t, v, s, params.phonemizationLanguage) },
        )
        when (result) {
            is PipelineResult.Empty -> callback.done()
            is PipelineResult.Audio -> {
                streamPcm(callback, result.pcm)
                callback.done()
            }
        }
    }

    // Widened to public for MarmaladeTtsServiceTest, like onSynthesizeText.
    public override fun onStop() {
        // Cancels the runBlocking job driving the current synthesis.
        // Engines observe cancellation between chunks (Pocket checks per
        // AR frame), so a long utterance stops burning CPU within ~one
        // chunk instead of running to completion.
        Log.d(TAG, "onStop: client requested stop")
        synthJob?.cancel()
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
        val effectBlocks: List<EffectBlock>,
        /** See [app.marmalade.tts.data.db.VoiceAlias.phonemizationLanguage]. Null = engine default. */
        val phonemizationLanguage: String? = null,
    )

    /**
     * Resolve the voice/speed/effect bundle for this synthesis request.
     *
     * Order of precedence (most-specific wins):
     *   1. **Per-app mapping** for the caller's package name — voice +
     *      speed + effect from the mapped alias. Beats the caller-
     *      specified voice on purpose: most TTS clients auto-fill
     *      `request.voiceName` from system Settings, so without this
     *      precedence the user's "this app gets this voice" choice
     *      would never fire for ordinary apps.
     *   2. Caller-specified [SynthesisRequest.getVoiceName] when it is a
     *      **deliberate pick** — engine-default speed (1.0×) and no
     *      effect, because the caller asked for that exact voice. A
     *      requested voice is NOT deliberate when it merely echoes what
     *      we advertise as the default (the primary alias's voice, or the
     *      catalog default while a primary exists): clients auto-fill
     *      `voiceName` from [onGetDefaultVoiceNameFor] / system Settings,
     *      and before this distinction the auto-filled Kokoro default
     *      outranked the primary alias on every request, so a Kitten
     *      primary could never fire ("it keeps speaking Bella Kokoro").
     *   3. Primary alias — same fields from the user-designated primary.
     *      Also taken when the caller requested exactly the primary's
     *      voice, so the alias's speed/effect ride along.
     *   4. Engine default voice + 1.0× speed + no effect (the v0.1.10
     *      pre-routing behaviour). Also honors a caller-requested voice
     *      when no primary alias is set.
     *
     * Runs blocking on the synth-worker thread per the TextToSpeechService
     * contract (the system explicitly calls `onSynthesizeText` off the
     * main thread).
     */
    internal fun resolveSynthParams(request: SynthesisRequest): SynthParams {
        val requested = request.voiceName?.takeIf { it.isNotBlank() }
        val callerPackage = resolveCallerPackage(request)

        // 1. Per-app mapping wins over the caller-specified voice. Single
        // runBlocking that also covers the caller-voice DAO lookup on a
        // cache miss, so we still issue one blocking pull at most.
        // resolveCallerPackage returns null when the UID can't be resolved
        // (shared UID, etc.); resolvePerApp returns null for that too.
        val resolved: SynthParams? = runBlocking {
            router.resolvePerApp(callerPackage)?.let { perApp ->
                return@runBlocking SynthParams(
                    voiceId = perApp.voiceId,
                    speed = perApp.speed,
                    effectBlocks = effectResolver.blocksFor(perApp.effectId),
                    phonemizationLanguage = perApp.phonemizationLanguage,
                )
            }

            // Resolve the primary up front — it decides whether the
            // caller-supplied voiceName is a deliberate pick or an
            // auto-filled echo of our advertised default.
            val primary: VoiceAlias? = router.resolveAlias(callerPackage)

            // 2. Caller-specified voice, when deliberate (see kdoc). With
            // no primary set, every requested voice is honored — there is
            // nothing for the auto-fill to mask. Cache-first so the common
            // "request specifies a voice that was seeded at install" path
            // stays off the synth-worker's blocking road.
            val deliberate = requested != null && (
                primary == null ||
                    (requested != primary.voiceId &&
                        requested != KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID)
                )
            if (requested != null && deliberate) {
                val cachedEngine = voiceEngineCache[requested]
                if (cachedEngine != null && isKnownEngine(cachedEngine)) {
                    return@runBlocking SynthParams(
                        voiceId = requested,
                        speed = 1.0f,
                        effectBlocks = emptyList(),
                    )
                }
                val hit = voiceDao.findById(requested)
                if (hit != null && isKnownEngine(hit.engine)) {
                    voiceEngineCache[hit.id] = hit.engine
                    return@runBlocking SynthParams(
                        voiceId = hit.id,
                        speed = 1.0f,
                        effectBlocks = emptyList(),
                    )
                }
            }

            // 3. Primary alias fallback.
            if (primary != null) {
                SynthParams(
                    voiceId = primary.voiceId,
                    speed = primary.speed,
                    effectBlocks = effectResolver.blocksFor(primary.effectId),
                    phonemizationLanguage = primary.phonemizationLanguage,
                )
            } else {
                null
            }
        }
        if (resolved != null) return applyClientRate(request, resolved)

        // 4. Absolute fallback — the engine's default voice with
        // unchanged speed and no effect. Matches v0.1.10 behaviour.
        return applyClientRate(
            request,
            SynthParams(
                voiceId = KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID,
                speed = 1.0f,
                effectBlocks = emptyList(),
            ),
        )
    }

    /**
     * Scale the resolved speed by the client's requested speech rate
     * (Android Settings → Text-to-speech → Speech rate, or a client's
     * `TextToSpeech.setSpeechRate`). 100 = normal per the framework
     * contract; <= 0 (unset on a hand-built request) is treated as
     * normal. The multiplier composes with the alias speed — "this
     * alias speaks at 1.2x" and "the user's system rate is 2x" stack —
     * and the product is clamped to a range every engine tolerates.
     *
     * `request.pitch` remains unhonored: no engine exposes a native
     * pitch knob, and a DSP pitch-shift stage would add latency for a
     * parameter few clients set. Documented gap.
     */
    private fun applyClientRate(request: SynthesisRequest, params: SynthParams): SynthParams {
        val rate = request.speechRate.let { if (it > 0) it / 100f else 1f }
        if (rate == 1f) return params
        return params.copy(speed = (params.speed * rate).coerceIn(MIN_SPEED, MAX_SPEED))
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
     * Defaults to Kokoro Direct when the form doesn't match — the catch-all
     * keeps the synthesis path robust against junk input from third-party
     * TTS clients that bypass the voice negotiation contract.
     */
    private fun engineNameFor(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        if (sep <= 0) return KokoroDirectVoiceCatalog.ENGINE
        val name = voiceId.substring(0, sep)
        return if (isKnownEngine(name)) name else KokoroDirectVoiceCatalog.ENGINE
    }

    /** Per-engine sample rate. All engines ship 24 kHz today. */
    private fun sampleRateFor(engineName: String): Int = when (engineName) {
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect.sampleRate
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect.sampleRate
        KittenDirectMiniVoiceCatalog.ENGINE -> kittenDirectMini.sampleRate
        PocketVoiceCatalog.ENGINE -> pocket.sampleRate
        else -> kokoroDirect.sampleRate
    }

    /**
     * Route synthesis to the engine identified by [engineName]. Suspending
     * because each engine hands off to `Dispatchers.Default` internally,
     * which the caller `runBlocking`s on the system-TTS worker thread.
     *
     * Unknown engine names degrade to Kokoro Direct — same fallback as
     * [pickVoiceFromRequest]; the upstream voice-negotiation paths already
     * filtered out engines we don't know about.
     */
    private suspend fun synthesizeForEngine(
        engineName: String,
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String? = null,
    ): SynthAudio = when (engineName) {
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect.synthesize(text, voiceId, speed, phonemizationLanguage)
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect.synthesize(text, voiceId, speed, phonemizationLanguage)
        KittenDirectMiniVoiceCatalog.ENGINE -> kittenDirectMini.synthesize(text, voiceId, speed, phonemizationLanguage)
        PocketVoiceCatalog.ENGINE -> pocket.synthesize(text, voiceId, speed, phonemizationLanguage)
        else -> kokoroDirect.synthesize(text, voiceId, speed, phonemizationLanguage)
    }

    /**
     * Streaming counterpart of [synthesizeForEngine] — same routing, but
     * returns the engine's chunk flow for [streamingSynthesis] to collect.
     */
    private fun streamForEngine(
        engineName: String,
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String? = null,
    ): Flow<SynthAudio> = when (engineName) {
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        KittenDirectMiniVoiceCatalog.ENGINE -> kittenDirectMini.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        PocketVoiceCatalog.ENGINE -> pocket.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
        else -> kokoroDirect.synthesizeStream(text, voiceId, speed, phonemizationLanguage)
    }

    /** True for any engine the catalog ships. */
    private fun isKnownEngine(name: String): Boolean =
        name == KokoroDirectVoiceCatalog.ENGINE ||
            name == KittenDirectVoiceCatalog.ENGINE ||
            name == KittenDirectMiniVoiceCatalog.ENGINE ||
            name == PocketVoiceCatalog.ENGINE

    /**
     * Stream [pcm] through the synthesis callback in chunks of at most
     * `callback.maxBufferSize` bytes (the system-imposed cap). The audio
     * is encoded little-endian per the `ENCODING_PCM_16BIT` contract.
     * Throws [ChunkRejectedException] as soon as the callback stops
     * accepting audio (client stop/disconnect, or a sink failure).
     */
    private fun streamPcm(callback: SynthesisCallback, pcm: ShortArray) {
        val bytes = pcm16ToLittleEndianBytes(pcm)
        val chunkSize = callback.maxBufferSize.coerceAtLeast(2) // never zero
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(chunkSize, bytes.size - offset)
            val written = callback.audioAvailable(bytes, offset, n)
            if (written != TextToSpeech.SUCCESS) {
                throw ChunkRejectedException(written)
            }
            offset += n
        }
    }

    /**
     * `callback.audioAvailable` returned non-SUCCESS mid-stream. The
     * framework returns the same code for "client stopped listening" and
     * "audio sink failed", so this is handled as a routine abort (single
     * warning, `callback.error()`), not a synthesis failure with a stack
     * trace — see the catch in [onSynthesizeText].
     */
    private class ChunkRejectedException(code: Int) :
        RuntimeException("audioAvailable rejected chunk: $code")

    companion object {
        private const val TAG = "MarmaladeTtsService"

        /**
         * Bounds for the alias-speed × client-rate product. The framework
         * slider alone spans 0.1–6.0x; engines produce artifacts well
         * before those extremes, so clamp to a range they all tolerate.
         */
        private const val MIN_SPEED = 0.25f
        private const val MAX_SPEED = 4.0f

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
