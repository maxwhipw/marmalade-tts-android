package app.marmalade.tts.ui.screen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.KittenDirectMiniVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.EnginePhaseTimings
import app.marmalade.tts.engine.PhaseSpan
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kitten.KittenDirectMiniEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.install.EngineCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Debug-only benchmark screen viewmodel.
//
// Runs the same input text across every selected engine, captures the
// timing breakdown returned by TtsEngine.synthesizeWithTimings, and
// surfaces the results as a row-per-engine table for visual A/B.
//
// Pocket gets a deep phase split (voice-encode / tokenize / flow-lm
// phases / decoder); the Kokoro/Kitten direct engines just get load +
// total + realtime ratio (their synth runs as one opaque ORT call from
// our side).
//
// No audio playback — pure measurement. Saves on AudioTrack latency
// confounding the numbers and keeps the screen quiet enough to A/B
// many runs in a row.
// -----------------------------------------------------------------------------

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val kokoroDirect: KokoroDirectEngine,
    private val kittenDirect: KittenDirectEngine,
    private val kittenDirectMini: KittenDirectMiniEngine,
    private val pocket: PocketEngine,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * engineName → (engine handle, default voice ID). This is the ONE place
     * that must know the concrete DI-provided engine instances — the handles
     * can't come from [EngineCatalog] (which is install metadata, not runtime
     * objects). Everything else (which engines exist, their display order and
     * names) is derived from [EngineCatalog.all] below, so the bench tracks
     * the catalog automatically — adding an engine there + a handle here is
     * all it takes to make it benchable.
     */
    private val engineHandles: Map<String, Pair<TtsEngine, String>> = mapOf(
        KokoroDirectVoiceCatalog.ENGINE to (kokoroDirect to KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID),
        KittenDirectVoiceCatalog.ENGINE to (kittenDirect to KittenDirectVoiceCatalog.DEFAULT_VOICE_ID),
        KittenDirectMiniVoiceCatalog.ENGINE to (kittenDirectMini to KittenDirectMiniVoiceCatalog.DEFAULT_VOICE_ID),
        PocketVoiceCatalog.ENGINE to (pocket to PocketVoiceCatalog.DEFAULT_VOICE_ID),
    )

    /**
     * Benchmark profiles, derived from [EngineCatalog.all] (the canonical
     * engine roster) so membership, order, and display names never drift from
     * the catalog. An engine appears here when it's both in the catalog and
     * has a runtime handle in [engineHandles]. Install status is layered on
     * separately — [installedEngineNames] / the per-row `isInstalled()` check
     * gate selection + running, so uninstalled engines render disabled.
     */
    val engineProfiles: List<EngineProfile> = EngineCatalog.all.mapNotNull { descriptor ->
        val handle = engineHandles[descriptor.name] ?: return@mapNotNull null
        EngineProfile(
            engineName = descriptor.name,
            displayName = descriptor.displayName,
            defaultVoiceId = handle.second,
            engine = handle.first,
        )
    }

    private val _state = MutableStateFlow(BenchmarkState())
    val state: StateFlow<BenchmarkState> = _state.asStateFlow()

    init {
        // Default selection: every installed engine. Re-evaluated lazily
        // each time the user opens the screen (engines come and go via
        // the Engines tab between runs).
        _state.update { it.copy(selectedEngines = installedEngineNames().toSet()) }
    }

    fun refreshInstalled() {
        // Recompute the selection set so newly-installed engines opt-in
        // automatically; previously-uninstalled engines drop out without
        // forcing the user to re-tick.
        _state.update { st ->
            val nowInstalled = installedEngineNames().toSet()
            val keep = st.selectedEngines.intersect(nowInstalled)
            val auto = nowInstalled.filter { it !in st.selectedEngines }
            st.copy(selectedEngines = keep + auto)
        }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
    }

    fun toggleEngine(engineName: String) {
        _state.update { st ->
            val next = if (engineName in st.selectedEngines) {
                st.selectedEngines - engineName
            } else {
                st.selectedEngines + engineName
            }
            st.copy(selectedEngines = next)
        }
    }

    /**
     * Run [BenchmarkState.text] against every selected + installed
     * engine in sequence, replacing the previous results. Stops early
     * if the user reselects the screen and triggers another run (the
     * old job is cancelled by viewModelScope's structured concurrency).
     */
    fun runBenchmark() {
        val current = _state.value
        if (current.running || current.text.isBlank()) return
        val targets = engineProfiles.filter {
            it.engineName in current.selectedEngines && it.engine.isInstalled()
        }
        if (targets.isEmpty()) return

        _state.update { it.copy(running = true, results = emptyList(), error = null) }

        viewModelScope.launch {
            val out = ArrayList<BenchmarkResult>(targets.size)
            for (target in targets) {
                _state.update { it.copy(currentlyRunning = target.displayName, results = out.toList()) }
                try {
                    val r = runOneStreaming(target, current.text)
                    out.add(r)
                } catch (t: Throwable) {
                    out.add(
                        BenchmarkResult(
                            engineDisplayName = target.displayName,
                            engineName = target.engineName,
                            voiceId = target.defaultVoiceId,
                            timings = EnginePhaseTimings(target.engineName, 0, 0),
                            audioSeconds = 0.0,
                            realtimeRatio = 0.0,
                            timeToFirstAudioMs = null,
                            chunkCount = null,
                            error = t.message ?: t::class.java.simpleName,
                        ),
                    )
                } finally {
                    // Release before moving to the next engine so only ONE model
                    // is resident at a time. Otherwise benching N engines piles N
                    // models into RAM (Kokoro Direct alone is ~480 MB) → zram
                    // thrash that under-reports every engine's real, single-engine
                    // speed (the Speak screen only ever holds one). The next
                    // engine reloads cleanly on its own ensureModelLoaded().
                    runCatching { target.engine.release() }
                }
            }
            _state.update {
                it.copy(
                    running = false,
                    currentlyRunning = null,
                    results = out,
                )
            }
        }
    }

    /**
     * Streaming-mode run: collects the engine's Flow, captures time
     * to first emission (TTFA — the headline streaming metric) plus
     * total time + chunk count. This is the only mode now — streaming
     * is the production path, so the bench measures it directly. The
     * old batched mode hid the chunking-correctness bug we hit on
     * realistic-length inputs, so the toggle's gone.
     */
    private suspend fun runOneStreaming(target: EngineProfile, text: String): BenchmarkResult {
        val loadStart = System.currentTimeMillis()
        target.engine.ensureModelLoaded()
        val loadMs = System.currentTimeMillis() - loadStart

        val streamStart = System.currentTimeMillis()
        var ttfaMs = -1L
        var chunks = 0
        var totalShorts = 0L
        var sampleRate = target.engine.sampleRate
        target.engine.synthesizeStream(
            text = text,
            voiceId = target.defaultVoiceId,
            speed = 1.0f,
        ).collect { chunk ->
            if (ttfaMs < 0L) {
                ttfaMs = System.currentTimeMillis() - streamStart
                sampleRate = chunk.sampleRate
            }
            chunks++
            totalShorts += chunk.pcm.size
        }
        val totalMs = System.currentTimeMillis() - streamStart
        val audioSeconds = totalShorts.toDouble() / sampleRate.toDouble()
        val realtimeRatio = if (totalMs > 0) {
            audioSeconds * 1000.0 / totalMs.toDouble()
        } else {
            0.0
        }
        return BenchmarkResult(
            engineDisplayName = target.displayName,
            engineName = target.engineName,
            voiceId = target.defaultVoiceId,
            timings = EnginePhaseTimings(
                engineName = target.engineName,
                totalMs = totalMs,
                loadMs = loadMs,
                phases = listOf(
                    PhaseSpan("time-to-first-audio", ttfaMs.coerceAtLeast(0L)),
                ),
            ),
            audioSeconds = audioSeconds,
            realtimeRatio = realtimeRatio,
            timeToFirstAudioMs = ttfaMs.coerceAtLeast(0L),
            chunkCount = chunks,
            error = null,
        )
    }

    /**
     * Kokoro quantisation bench — see [KokoroQuantBench]. Independent of the
     * engine bench above: it talks to ORT directly with side-loaded model
     * files, so no engine selection applies. Results stream in per variant
     * (the last variant can take the process down by design — see the q8f16
     * crash guard), so each one is published the moment it lands.
     */
    init {
        // Surface the previous quant-bench run — a native crash mid-bench
        // kills the process, so the disk copy is the only surviving record.
        val persisted = KokoroQuantBench.loadPersisted(appContext)
        if (persisted.isNotEmpty()) {
            _state.update { it.copy(quantResults = persisted) }
        }
    }

    fun runQuantBench() {
        if (_state.value.quantRunning) return
        _state.update {
            it.copy(quantRunning = true, quantResults = emptyList(), quantError = null, quantStatus = "starting…")
        }
        viewModelScope.launch {
            // Free the engine-held Kokoro session first — otherwise the bench's
            // own session doubles the ~310 MB fp32 footprint and every variant
            // measures a zram-thrashing phone instead of the model.
            runCatching { kokoroDirect.release() }
            val out = ArrayList<KokoroQuantBench.VariantResult>()
            val fatal = KokoroQuantBench.run(
                ctx = appContext,
                onProgress = { s -> _state.update { it.copy(quantStatus = s) } },
                onVariant = { r ->
                    out.add(r)
                    _state.update { it.copy(quantResults = out.toList()) }
                },
            )
            _state.update {
                it.copy(quantRunning = false, quantStatus = null, quantError = fatal)
            }
        }
    }

    private fun installedEngineNames(): List<String> =
        engineProfiles.filter { it.engine.isInstalled() }.map { it.engineName }
}

/** Static metadata about one engine for the benchmark UI. */
data class EngineProfile(
    val engineName: String,
    val displayName: String,
    val defaultVoiceId: String,
    val engine: TtsEngine,
)

/** Reactive screen state. */
data class BenchmarkState(
    val text: String = DEFAULT_TEXT_MEDIUM,
    val selectedEngines: Set<String> = emptySet(),
    val results: List<BenchmarkResult> = emptyList(),
    val running: Boolean = false,
    val currentlyRunning: String? = null,
    val error: String? = null,
    // -- Kokoro quant bench (independent section on the same screen) ----------
    val quantRunning: Boolean = false,
    /** Variant/text currently under measurement, for the button label. */
    val quantStatus: String? = null,
    val quantResults: List<KokoroQuantBench.VariantResult> = emptyList(),
    /** Set when the bench couldn't start at all (Kokoro Direct not installed). */
    val quantError: String? = null,
)

/** Per-engine outcome of one benchmark run. */
data class BenchmarkResult(
    val engineDisplayName: String,
    val engineName: String,
    val voiceId: String,
    val timings: EnginePhaseTimings,
    /** Length of the produced audio (seconds), used for the realtime-ratio column. */
    val audioSeconds: Double,
    /** audioSeconds / totalSeconds. >1 = faster than realtime, <1 = slower. */
    val realtimeRatio: Double,
    /**
     * Wall-clock from streaming start to the first emitted chunk.
     * Null when this run was batched (synthesize, not synthesizeStream).
     * For engines that don't override `synthesizeStream`, this equals
     * total time (single-shot semantics).
     */
    val timeToFirstAudioMs: Long?,
    /** Number of chunks emitted by the engine. Null on batched runs. */
    val chunkCount: Int?,
    /** Non-null if the run failed; shown in the row in place of timings. */
    val error: String?,
)

// -----------------------------------------------------------------------------
// Preset inputs for the bench. Realistic lengths matching actual TTS use:
//   Short  ≈ a single Bible verse                  (~30 words / ~40 tokens)
//   Medium ≈ one short chapter / one paragraph    (~120 words / ~160 tokens)
//   Long   ≈ one full chapter                     (~270 words / ~360 tokens)
//
// Pocket's `max_token_per_chunk = 50` means Medium + Long will skip words
// or mangle output until the sentence chunker lands (Pocket can natively
// only handle the Short preset). The Kokoro/Kitten direct engines handle
// arbitrary lengths natively.
//
// Picked text: John 3:16 / Psalm 23 / 1 Corinthians 13. Public-domain
// (KJV), familiar enough for ear-testing across many runs, varied
// punctuation (commas, colons, em-dashes for the longer chapters).
// -----------------------------------------------------------------------------

const val DEFAULT_TEXT_SHORT =
    "For God so loved the world, that he gave his only begotten Son, " +
        "that whosoever believeth in him should not perish, but have everlasting life."

const val DEFAULT_TEXT_MEDIUM =
    "The Lord is my shepherd; I shall not want. " +
        "He maketh me to lie down in green pastures: he leadeth me beside the still waters. " +
        "He restoreth my soul: he leadeth me in the paths of righteousness for his name's sake. " +
        "Yea, though I walk through the valley of the shadow of death, I will fear no evil: " +
        "for thou art with me; thy rod and thy staff they comfort me. " +
        "Thou preparest a table before me in the presence of mine enemies: thou anointest my head with oil; " +
        "my cup runneth over. Surely goodness and mercy shall follow me all the days of my life: " +
        "and I will dwell in the house of the Lord for ever."

const val DEFAULT_TEXT_LONG =
    "Though I speak with the tongues of men and of angels, and have not charity, " +
        "I am become as sounding brass, or a tinkling cymbal. " +
        "And though I have the gift of prophecy, and understand all mysteries, and all knowledge; " +
        "and though I have all faith, so that I could remove mountains, and have not charity, " +
        "I am nothing. And though I bestow all my goods to feed the poor, " +
        "and though I give my body to be burned, and have not charity, it profiteth me nothing. " +
        "Charity suffereth long, and is kind; charity envieth not; " +
        "charity vaunteth not itself, is not puffed up, doth not behave itself unseemly, " +
        "seeketh not her own, is not easily provoked, thinketh no evil; " +
        "rejoiceth not in iniquity, but rejoiceth in the truth; " +
        "beareth all things, believeth all things, hopeth all things, endureth all things. " +
        "Charity never faileth: but whether there be prophecies, they shall fail; " +
        "whether there be tongues, they shall cease; whether there be knowledge, it shall vanish away. " +
        "For we know in part, and we prophesy in part. " +
        "But when that which is perfect is come, then that which is in part shall be done away. " +
        "When I was a child, I spake as a child, I understood as a child, I thought as a child: " +
        "but when I became a man, I put away childish things. " +
        "For now we see through a glass, darkly; but then face to face: " +
        "now I know in part; but then shall I know even as also I am known. " +
        "And now abideth faith, hope, charity, these three; but the greatest of these is charity."
