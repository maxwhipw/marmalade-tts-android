package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.KittenMiniVoiceCatalog
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.KokoroV10VoiceCatalog
import app.marmalade.tts.data.KokoroV11VoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.EnginePhaseTimings
import app.marmalade.tts.engine.KittenMiniEngine
import app.marmalade.tts.engine.KittenNanoEngine
import app.marmalade.tts.engine.KokoroV10Engine
import app.marmalade.tts.engine.KokoroV11Engine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.TtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
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
// phases / decoder); sherpa engines just get load + total + realtime
// ratio (their synth runs as one opaque ORT call from our side).
//
// No audio playback — pure measurement. Saves on AudioTrack latency
// confounding the numbers and keeps the screen quiet enough to A/B
// many runs in a row.
// -----------------------------------------------------------------------------

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val kokoroV10: KokoroV10Engine,
    private val kokoroV11: KokoroV11Engine,
    private val kittenNano: KittenNanoEngine,
    private val kittenMini: KittenMiniEngine,
    private val pocket: PocketEngine,
) : ViewModel() {

    /**
     * Static list of (engineName, displayName, default voice ID, engine
     * handle) for every engine the app knows about. The UI cross-checks
     * `engine.isInstalled()` per row at render time so uninstalled
     * engines are visible but visually disabled.
     */
    val engineProfiles: List<EngineProfile> = listOf(
        EngineProfile(
            engineName = KokoroV10VoiceCatalog.ENGINE,
            displayName = "Kokoro v1.0",
            defaultVoiceId = KokoroV10VoiceCatalog.DEFAULT_VOICE_ID,
            engine = kokoroV10,
        ),
        EngineProfile(
            engineName = KokoroV11VoiceCatalog.ENGINE,
            displayName = "Kokoro v1.1",
            defaultVoiceId = KokoroV11VoiceCatalog.DEFAULT_VOICE_ID,
            engine = kokoroV11,
        ),
        EngineProfile(
            engineName = KittenNanoVoiceCatalog.ENGINE,
            displayName = "Kitten Nano",
            defaultVoiceId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
            engine = kittenNano,
        ),
        EngineProfile(
            engineName = KittenMiniVoiceCatalog.ENGINE,
            displayName = "Kitten Mini",
            defaultVoiceId = KittenMiniVoiceCatalog.DEFAULT_VOICE_ID,
            engine = kittenMini,
        ),
        EngineProfile(
            engineName = PocketVoiceCatalog.ENGINE,
            displayName = "Pocket TTS",
            defaultVoiceId = PocketVoiceCatalog.DEFAULT_VOICE_ID,
            engine = pocket,
        ),
    )

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
                    val timed = target.engine.synthesizeWithTimings(
                        text = current.text,
                        voiceId = target.defaultVoiceId,
                        speed = 1.0f,
                    )
                    val audioSeconds = timed.audio.pcm.size.toDouble() /
                        timed.audio.sampleRate.toDouble()
                    val realtimeRatio = if (timed.timings.totalMs > 0) {
                        audioSeconds * 1000.0 / timed.timings.totalMs.toDouble()
                    } else {
                        0.0
                    }
                    out.add(
                        BenchmarkResult(
                            engineDisplayName = target.displayName,
                            engineName = target.engineName,
                            voiceId = target.defaultVoiceId,
                            timings = timed.timings,
                            audioSeconds = audioSeconds,
                            realtimeRatio = realtimeRatio,
                            error = null,
                        ),
                    )
                } catch (t: Throwable) {
                    out.add(
                        BenchmarkResult(
                            engineDisplayName = target.displayName,
                            engineName = target.engineName,
                            voiceId = target.defaultVoiceId,
                            timings = EnginePhaseTimings(target.engineName, 0, 0),
                            audioSeconds = 0.0,
                            realtimeRatio = 0.0,
                            error = t.message ?: t::class.java.simpleName,
                        ),
                    )
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
    /** Non-null if the run failed; shown in the row in place of timings. */
    val error: String?,
)

/** Preset inputs for one-tap selection. Tuned to hit the bundle's max_token_per_chunk=50 boundary. */
const val DEFAULT_TEXT_SHORT = "Hello, this is a test."
const val DEFAULT_TEXT_MEDIUM =
    "The quick brown fox jumps over the lazy dog, twice for good measure."
const val DEFAULT_TEXT_LONG =
    "When in the course of human events, it becomes necessary for one people " +
        "to dissolve the political bands which have connected them with another."
