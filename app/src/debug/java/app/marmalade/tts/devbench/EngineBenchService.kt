package app.marmalade.tts.devbench

import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import app.marmalade.tts.devbench.BenchSupport.TAG
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.service.TopAppHold
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// EngineBenchService — silent on-device engine benchmark, DEBUG BUILDS ONLY
// -----------------------------------------------------------------------------
//
// Renders a sentence list through one engine's synthesizeStream (speed 1.0,
// playbackRate 1.0 unless `rate` says otherwise — what the Narrator asks for) and writes each sentence to a
// WAV. Nothing is played: no AudioTrack, no focus, no notification sound.
// Timing lands in logcat (`B1Bench` lines here, plus each engine's own
// `StreamPerf` lines), so a run is attributable from logs alone.
//
// Any registry engine, plus kokoro-7m-dev (issue #1), which is outside it.
// KittenDirect (issue #10) and KokoroDirect (#13 "E", with the streaming
// bundle) render planned-once, decoded-in-slices themselves; for an A/B
// against Kokoro's unsplit model set the `kokoro.stream=false` engine knob
// (EngineDevKnobs).
//
// Started from adb only — the debug manifest guards it with
// android.permission.DUMP, which the shell holds and apps don't:
//
//   adb shell am start-foreground-service \
//     -n app.marmalade.tts.debug/app.marmalade.tts.devbench.EngineBenchService \
//     --es engine kokoro-7m-dev --es voice kokoro-7m-dev:af_msa \
//     --es sentences sentences.txt --es run k7m-cool --ei passes 1
//
// `sentences` is a file under `${filesDir}/bench/` (pushed with run-as); WAVs
// go to `${filesDir}/bench/out/<run>/<idx>.wav` (first pass only).
//
// Per run: release every on-device engine (a clean memory baseline), load the
// target and log the PSS delta, let its async warm-up finish, then render.
// Between sentences it waits out the audio it just rendered (`pace`, default
// on) so each sentence starts like a fresh Speak: the Kokoro engines size
// their short first piece from the audio they think is still playing
// (PlaybackHorizon), and back-to-back requests would skew TTFA.
//
// `--ei stopAfter <ms>` measures stopping instead (issue #10): each sentence's
// stream is cancelled that long after it starts, and a `stop` line logs how
// long the cancel took to land — until the engine's render in flight has
// actually ended and released its lock, which is what a reader tap waits for.
// No WAVs are written in this mode.
//
// Issue #13 adds what a memory/battery A/B needs. `--ez exit true` kills the
// process after the run (like OnnxBenchService), so the next run's load time
// and memory start from a fresh process. A sampler records peak PSS every
// 500 ms from just before the load to the end (`peakPssKb` on the done line,
// with VmHWM, the process's CPU time and the battery charge counter consumed
// while rendering: the cost of spinning threads). Engine session knobs for
// A/B runs come from `files/dev/engine-knobs.properties` (EngineDevKnobs).
// `--es dumpTokens <file>` (Kokoro engines only) renders nothing: it writes
// each sentence's token ids, one JSON line each, to `bench/<file>`, phonemized
// for `voice` or for `--es lang <code>` — training text for the generator
// statistics. `--ef rate <x>` passes x as the playback rate (what the
// Narrator asks for at a listening speed: the engines' pre-roll and
// first-piece sizing depend on it) and paces by the played length; a line of
// the sentences file may be a whole paragraph, rendered as one request.
// `--ei pauseMs <ms>` (issue #96) renders with that pause between sentences,
// as an alias set to it would (0, the default, is Natural).
// `--ez topApp true` (issue #52) holds the in-app reader's self TTS session
// (TopAppHold.selfSession) for the run, so the bench renders on the cores the
// reader now gets (top-app) rather than the screen-off service's.
// -----------------------------------------------------------------------------

@AndroidEntryPoint
class EngineBenchService : Service() {

    @Inject lateinit var registry: EngineRegistry
    @Inject lateinit var kokoro7m: Kokoro7mDevEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BenchDevice.startForeground(this, NOTIFICATION_ID, "Engine benchmark running")
        if (intent == null || running) {
            Log.w(TAG, "ignored start (intent=${intent != null}, running=$running)")
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val engineName = intent.getStringExtra(EXTRA_ENGINE)
        val engine = engineName?.let(::engineFor)
        val voice = intent.getStringExtra(EXTRA_VOICE)
        val sentencesFile = intent.getStringExtra(EXTRA_SENTENCES)?.let { File(benchDir(), it) }
        if (engine == null || voice == null || sentencesFile == null || !sentencesFile.isFile) {
            Log.e(TAG, "bad request engine=$engineName voice=$voice sentences=$sentencesFile")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val config = RunConfig(
            run = intent.getStringExtra(EXTRA_RUN) ?: "run",
            engine = engine,
            voice = voice,
            sentences = BenchSupport.parseSentences(sentencesFile.readText()),
            passes = intent.getIntExtra(EXTRA_PASSES, 1).coerceAtLeast(1),
            pace = intent.getBooleanExtra(EXTRA_PACE, true),
            stopAfterMs = intent.getIntExtra(EXTRA_STOP_AFTER, 0).toLong(),
            exit = intent.getBooleanExtra(EXTRA_EXIT, false),
            rate = intent.getFloatExtra(EXTRA_RATE, 1f).takeIf { it > 0f } ?: 1f,
            pauseMs = intent.getIntExtra(EXTRA_PAUSE_MS, 0).coerceAtLeast(0),
        )
        running = true
        val topApp = if (intent.getBooleanExtra(EXTRA_TOP_APP, false)) TopAppHold.selfSession(this) else null
        val dumpTokens = intent.getStringExtra(EXTRA_DUMP_TOKENS)
        if (dumpTokens != null) {
            scope.launch {
                try {
                    dumpTokens(config, File(benchDir(), dumpTokens), intent.getStringExtra(EXTRA_LANG))
                } catch (t: Throwable) {
                    Log.e(TAG, "failed run=${config.run}: ${t.message}", t)
                } finally {
                    running = false
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        scope.launch {
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marmalade:b1bench")
            wake.acquire(WAKE_TIMEOUT_MS)
            try {
                runBench(config)
            } catch (t: Throwable) {
                Log.e(TAG, "failed run=${config.run}: ${t.message}", t)
            } finally {
                if (wake.isHeld) wake.release()
                topApp?.let { withContext(Dispatchers.Main + NonCancellable) { it.shutdown() } }
                running = false
                stopSelf(startId)
                if (config.exit) {
                    // stopSelf cancels this scope in onDestroy: the wait must not be cancellable.
                    withContext(NonCancellable) { delay(1_000) } // let logcat drain the done line
                    Process.killProcess(Process.myPid())
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class RunConfig(
        val run: String,
        val engine: TtsEngine,
        val voice: String,
        val sentences: List<String>,
        val passes: Int,
        val pace: Boolean,
        val stopAfterMs: Long,
        val exit: Boolean,
        val rate: Float,
        val pauseMs: Int,
    )

    private fun engineFor(name: String): TtsEngine? = when (name) {
        Kokoro7mDevEngine.ENGINE -> kokoro7m
        else -> registry.find(name)
    }

    private fun benchDir(): File = File(filesDir, "bench")

    private suspend fun runBench(c: RunConfig) {
        val name = c.engine.engineName
        Log.i(TAG, "start run=${c.run} engine=$name voice=${c.voice} sentences=${c.sentences.size} passes=${c.passes} rate=${c.rate} pace=${c.pace} stopAfter=${c.stopAfterMs} ${thermal()}")

        // Clean baseline: no engine resident, then load only the target.
        (registry.onDevice + kokoro7m).forEach { it.release() }
        System.gc()
        delay(1_500)
        val pssBeforeKb = Debug.getPss()
        val peak = PeakPss.start(scope)
        val t0 = System.nanoTime()
        c.engine.ensureModelLoaded()
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        delay(WARMUP_SETTLE_MS) // the engine's async warm-up synth holds its lock meanwhile
        val pssAfterKb = Debug.getPss()
        Log.i(TAG, "load run=${c.run} engine=$name loadMs=$loadMs pssBeforeKb=$pssBeforeKb pssAfterKb=$pssAfterKb pssDeltaKb=${pssAfterKb - pssBeforeKb}")

        val cpuStartMs = Process.getElapsedCpuTime()
        val chargeStartUah = chargeCounterUah()
        val renderStartNs = System.nanoTime()
        val outDir = File(benchDir(), "out/${c.run}").apply { mkdirs() }
        for (pass in 1..c.passes) {
            val results = ArrayList<BenchSupport.SentenceResult>()
            for ((idx, text) in c.sentences.withIndex()) {
                if (c.stopAfterMs > 0) {
                    measureStop(c, pass, idx, text)
                    delay(PACE_EXTRA_MS)
                    continue
                }
                val pcm = ArrayList<ShortArray>()
                val start = System.nanoTime()
                var firstNs = 0L
                c.engine.synthesizeStream(text, c.voice, 1f, null, c.rate, sentencePauseMs = c.pauseMs).collect {
                    if (firstNs == 0L) firstNs = System.nanoTime()
                    pcm.add(it.pcm)
                }
                val totalMs = (System.nanoTime() - start) / 1_000_000
                val merged = ShortArray(pcm.sumOf { it.size }).also { out ->
                    var pos = 0
                    for (p in pcm) { p.copyInto(out, pos); pos += p.size }
                }
                val audioMs = merged.size * 1000L / c.engine.sampleRate
                val r = BenchSupport.SentenceResult(
                    index = idx,
                    chars = text.length,
                    ttfaMs = if (firstNs == 0L) -1 else (firstNs - start) / 1_000_000,
                    totalMs = totalMs,
                    audioMs = audioMs,
                )
                results += r
                Log.i(TAG, BenchSupport.sentenceLine(c.run, name, pass, r))
                if (pass == 1) {
                    File(outDir, String.format(Locale.ROOT, "%02d.wav", idx))
                        .writeBytes(BenchSupport.wavBytes(merged, c.engine.sampleRate))
                }
                if (c.pace) delay((audioMs / c.rate).toLong() + PACE_EXTRA_MS)
            }
            Log.i(TAG, BenchSupport.passSummaryLine(c.run, name, pass, results) + " ${thermal()}")
        }
        val renderMs = (System.nanoTime() - renderStartNs) / 1_000_000
        val cpuMs = Process.getElapsedCpuTime() - cpuStartMs
        val chargeUah = chargeStartUah - chargeCounterUah()
        Log.i(
            TAG,
            "done run=${c.run} engine=$name pssKb=${Debug.getPss()} peakPssKb=${peak.stop()} " +
                "vmHwmKb=${vmHwmKb()} renderWallMs=$renderMs cpuMs=$cpuMs chargeUah=$chargeUah ${thermal()}",
        )
    }

    private suspend fun dumpTokens(c: RunConfig, out: File, lang: String?) {
        val kokoro = c.engine as? KokoroDirectEngine ?: error("dumpTokens needs a Kokoro engine, not ${c.engine.engineName}")
        val voiceKey = c.voice.substringAfter(':')
        out.bufferedWriter().use { w ->
            for ((idx, text) in c.sentences.withIndex()) {
                val ids = kokoro.benchTokens(text, voiceKey, lang)
                val quoted = text.replace("\\", "\\\\").replace("\"", "\\\"")
                w.write("{\"idx\":$idx,\"text\":\"$quoted\",\"ids\":[${ids.joinToString(",")}]}\n")
            }
        }
        Log.i(TAG, "done run=${c.run} engine=${c.engine.engineName} tokens=${c.sentences.size} lang=$lang file=${out.name}")
    }

    /** Start [text], cancel it [RunConfig.stopAfterMs] in, and log how long the cancel takes to land. */
    private suspend fun measureStop(c: RunConfig, pass: Int, idx: Int, text: String) = coroutineScope {
        var samples = 0L
        val job = launch {
            c.engine.synthesizeStream(text, c.voice, 1f, null, 1f).collect { samples += it.pcm.size }
        }
        delay(c.stopAfterMs)
        val finishedFirst = job.isCompleted
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val stopMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(
            TAG,
            "stop run=${c.run} engine=${c.engine.engineName} pass=$pass idx=$idx chars=${text.length} " +
                "stopAfterMs=${c.stopAfterMs} stopMs=$stopMs audioBeforeStopMs=${samples * 1000 / c.engine.sampleRate} " +
                "finishedFirst=$finishedFirst",
        )
    }

    private fun thermal(): String = BenchDevice.thermal(this)

    private fun chargeCounterUah(): Long =
        getSystemService(BatteryManager::class.java).getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

    /** Peak resident set of this process so far (fresh per run with `exit`). */
    private fun vmHwmKb(): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }
            ?.filter(Char::isDigit)?.toLongOrNull() ?: -1

    /** Highest PSS seen every [PEAK_SAMPLE_MS] until [stop]. */
    private class PeakPss private constructor(private val job: kotlinx.coroutines.Job, private val max: AtomicLong) {
        fun stop(): Long {
            job.cancel()
            return max.get()
        }

        companion object {
            fun start(scope: CoroutineScope): PeakPss {
                val max = AtomicLong(0)
                val job = scope.launch {
                    while (true) {
                        max.accumulateAndGet(Debug.getPss(), ::maxOf)
                        delay(PEAK_SAMPLE_MS)
                    }
                }
                return PeakPss(job, max)
            }
        }
    }

    companion object {
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SENTENCES = "sentences"
        const val EXTRA_RUN = "run"
        const val EXTRA_PASSES = "passes"
        const val EXTRA_PACE = "pace"
        const val EXTRA_STOP_AFTER = "stopAfter"
        const val EXTRA_EXIT = "exit"
        const val EXTRA_DUMP_TOKENS = "dumpTokens"
        const val EXTRA_LANG = "lang"
        const val EXTRA_RATE = "rate"
        const val EXTRA_TOP_APP = "topApp"
        const val EXTRA_PAUSE_MS = "pauseMs"

        private const val NOTIFICATION_ID = 0xB1
        private const val WARMUP_SETTLE_MS = 3_000L
        private const val PACE_EXTRA_MS = 250L
        private const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L
        private const val PEAK_SAMPLE_MS = 500L
    }
}
