package app.marmalade.tts.perf

import android.util.Log
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   OnboardingViewModel
//     │  (after the baked Kitten seed has landed)
//     ▼
//   DeviceProbeSource.probe()
//     │
//     ├── Tier 1: CpuClusterDetector.readClusters() ──► compute score
//     │            (synchronous, sysfs only, always available)
//     │
//     └── Tier 2: SettingsRepository.kittenRtfMeasurement
//                   │
//                   ├── present ──► use it (benchmark already ran)
//                   └── absent  ──► KittenDirectEngine.synthesize(BENCH_TEXT)
//                                     │  wall-clock ÷ audio duration = RTF
//                                     └──► SettingsRepository.setKittenRtfMeasurement
//     │
//     ▼
//   DeviceProbe ──► EngineRecommender.recommend()
// -----------------------------------------------------------------------------

/**
 * What we know about this device's synthesis throughput.
 *
 * @property measuredKittenRtf Real-time factor from the on-device Kitten
 *   benchmark (inference wall-clock ÷ audio produced). Null when the
 *   benchmark hasn't run or failed — the compute score is then the only signal.
 * @property computeScore Tier-1 heuristic: Σ over non-efficiency CPU clusters
 *   of `cores × maxGHz`. Null when sysfs is unreadable, which means we know
 *   nothing at all and must not guess.
 */
data class DeviceProbe(
    val measuredKittenRtf: Double?,
    val computeScore: Double?,
)

/** A stored Kitten benchmark result. See [SettingsRepository.kittenRtfMeasurement]. */
data class KittenRtfMeasurement(
    /** Inference wall-clock ÷ audio duration. Lower is faster. */
    val rtf: Double,
    val measuredAtMillis: Long,
)

/**
 * Seam for "how fast is this phone at synthesis".
 *
 * An interface rather than a concrete dependency so ViewModel unit tests
 * can hand in a canned [DeviceProbe] — the real implementation needs an ORT
 * session and sysfs, neither of which exists on a plain JVM.
 */
interface DeviceProbeSource {
    /**
     * Measure (once per install) and report this device's synthesis
     * capability. Safe to call concurrently; safe to call repeatedly —
     * only the first call on a given install pays for the benchmark.
     *
     * The caller must ensure the baked Kitten engine is on disk first
     * (see `OnboardingViewModel`'s baked-seed wait); a probe run before
     * that degrades to Tier 1 rather than failing.
     */
    suspend fun probe(): DeviceProbe
}

/**
 * Measures what this device can actually do, in two tiers.
 *
 * **Tier 1** is a synchronous CPU-topology heuristic: sum `cores × maxGHz`
 * across the non-efficiency clusters. It costs a few sysfs reads and is
 * always available, including before any engine is installed. It's a proxy,
 * not a measurement — it can't see memory bandwidth or thermal behaviour.
 *
 * **Tier 2** is a real synthesis benchmark on the baked Kitten engine, which
 * is the one engine guaranteed present on a fresh install. That gives a true
 * RTF for this silicon running our actual ORT graph, and
 * [EngineRecommender] extrapolates the heavier engines from it. Runs at most
 * once per install (the result is persisted) and is capped so a wedged
 * session can't stall onboarding.
 */
@Singleton
class DeviceCapability @Inject constructor(
    private val kitten: KittenDirectEngine,
    private val settings: SettingsRepository,
) : DeviceProbeSource {

    /** Serialises concurrent [probe] callers so the benchmark runs once. */
    private val benchLock = Mutex()

    /**
     * Set when the benchmark came back empty-handed in this process, so the
     * reader and alias screens — which probe on every open — don't pay for
     * another model load and synthesis each time. In memory on purpose: what
     * makes it fail (the seed not landed yet, a timeout on a busy device, an
     * ORT init failure) is usually transient, and persisting the failure
     * would pin the device to Tier 1 for good. One attempt per app start.
     * Guarded by [benchLock].
     */
    private var benchmarkFailed = false

    /**
     * Off the caller's dispatcher: the callers are ViewModels on Main, and
     * the benchmark's model load (`ensureModelLoaded`, a `runBlocking` over
     * file I/O and ORT session setup) plus the sysfs reads would otherwise
     * run on the UI thread.
     */
    override suspend fun probe(): DeviceProbe = withContext(Dispatchers.IO) {
        DeviceProbe(
            measuredKittenRtf = measureKittenRtf(),
            computeScore = computeScore(),
        )
    }

    /**
     * Tier 1. Σ over non-efficiency clusters of `coreCount × maxFreqGhz`,
     * or null when sysfs is unreadable (some manufacturers restrict it) or
     * reports no frequencies — null means "no signal", which callers must
     * not confuse with "slow". See [computeScoreOf].
     *
     * Single-cluster devices have no efficiency tier to exclude, so every
     * core counts; that's the same rule [CpuClusterDetector] applies.
     */
    fun computeScore(): Double? {
        val clusters = CpuClusterDetector.readClusters()
        val score = computeScoreOf(clusters)
        if (score == null) {
            Log.i(TAG, "Tier 1 compute score: no signal (${clusters.size} clusters)")
        } else {
            Log.i(TAG, "Tier 1 compute score: %.2f (%d clusters)".format(score, clusters.size))
        }
        return score
    }

    /**
     * Tier 2. Returns the persisted benchmark if it has already run on this
     * install, otherwise synthesizes [BENCH_TEXT] once and stores the result.
     *
     * Null on any failure — engine not installed, ORT init blowup, or the
     * [BENCH_TIMEOUT_MS] cap elapsing. Callers fall back to Tier 1.
     */
    suspend fun measureKittenRtf(): Double? = benchLock.withLock {
        settings.kittenRtfMeasurement.first()?.let { return@withLock it.rtf }
        if (benchmarkFailed) return@withLock null
        val rtf = runBenchmark() ?: run {
            benchmarkFailed = true
            return@withLock null
        }
        settings.setKittenRtfMeasurement(rtf, System.currentTimeMillis())
        rtf
    }

    /**
     * One benchmark synthesis to a discarded buffer — nothing is played.
     *
     * Model load is deliberately excluded from the timed span: it's a
     * one-off cost dominated by file I/O, while what we're predicting is
     * per-utterance throughput. Warm-path RTF is the number
     * [EngineRecommender]'s anchors are expressed in.
     */
    private suspend fun runBenchmark(): Double? = withTimeoutOrNull(BENCH_TIMEOUT_MS) {
        runCatching {
            // On a truly fresh install the baked Kitten seed may still be
            // copying when onboarding starts the probe — racing it turned a
            // fast phone into a Tier-1 "may be slow" verdict (seen on the
            // Pixel 8a, 2026-08-08). Wait for the seed inside the benchmark
            // cap; a genuine seed failure still falls through to Tier 1.
            withTimeoutOrNull(SEED_WAIT_MS) {
                settings.bakedDefaultSeeded.firstOrNull { it }
            }
            if (!kitten.isInstalled()) {
                Log.i(TAG, "Kitten benchmark skipped: engine not installed")
                return@runCatching null
            }
            kitten.ensureModelLoaded()
            val startNanos = System.nanoTime()
            val audio = kitten.synthesize(
                text = BENCH_TEXT,
                voiceId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
                speed = 1.0f,
            )
            val inferenceMs = (System.nanoTime() - startNanos) / 1_000_000.0
            val audioMs = audio.pcm.size * 1000.0 / audio.sampleRate
            // A silent or near-silent result means the synth didn't really
            // happen; dividing by it would manufacture an absurd RTF.
            if (audioMs < MIN_BENCH_AUDIO_MS) return@runCatching null
            val rtf = inferenceMs / audioMs
            Log.i(TAG, "Kitten benchmark: RTF %.3f (%.0f ms inference, %.0f ms audio)"
                .format(rtf, inferenceMs, audioMs))
            rtf
        }.getOrElse { err ->
            Log.w(TAG, "Kitten benchmark failed; falling back to Tier 1", err)
            null
        }
    }

    private companion object {
        const val TAG = "DeviceCapability"

        /**
         * Fixed English sentence, ~2 s of audio at speed 1.0. Fixed because
         * the number only means anything compared against other devices
         * running the identical workload — never localize this, and never
         * vary it per run.
         */
        const val BENCH_TEXT =
            "The quick brown fox jumps over the lazy dog near the river."

        /**
         * Hard cap on the whole benchmark. Generous: a device slow enough to
         * blow through 30 s on two seconds of Kitten audio has answered the
         * capability question anyway, and Tier 1 covers the timeout path.
         */
        const val BENCH_TIMEOUT_MS = 30_000L

        /** Below this the synth clearly produced nothing usable. */
        const val MIN_BENCH_AUDIO_MS = 200.0

        /**
         * How long the benchmark will wait for the baked Kitten seed on a
         * fresh install before giving up and letting Tier 1 answer. Inside
         * [BENCH_TIMEOUT_MS], so the overall cap still holds.
         */
        const val SEED_WAIT_MS = 15_000L
    }
}

/**
 * Tier 1's score for [clusters]: Σ over non-efficiency clusters of
 * `coreCount × maxFreqGhz`, or null when there is no signal — no clusters,
 * or none reporting a frequency (an emulator lists its policies with a
 * `cpuinfo_max_freq` of 0, which used to score a flat 0.00, i.e. "slowest
 * phone ever" rather than "unknown").
 */
internal fun computeScoreOf(clusters: List<CpuClusterDetector.Cluster>): Double? {
    val known = clusters.filter { it.maxFreqKhz > 0 }
    if (known.isEmpty()) return null
    val minFreq = known.minOf { it.maxFreqKhz }
    val perf = known.filter { it.maxFreqKhz > minFreq }.ifEmpty { known }
    return perf.sumOf { it.cpuCount * (it.maxFreqKhz / 1_000_000.0) }
}
