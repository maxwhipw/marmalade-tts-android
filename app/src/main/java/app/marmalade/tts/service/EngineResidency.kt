package app.marmalade.tts.service

import android.os.SystemClock
import android.util.Log
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.PocketDevEngine
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides which engines are allowed to stay loaded, and releases the rest.
 *
 * A phone cannot hold every engine's ORT session + weights resident at once
 * (Kokoro alone is ~150 MB mapped), and nothing in the app used to release
 * an engine once loaded — so a user who tried three engines paid for all
 * three for the life of the process, and the speculative warm-all paths
 * made that the *default* rather than the exception.
 *
 * An engine stays resident iff any of:
 *  - it is the Speak screen's currently selected engine ([select]) — the
 *    user is one tap away from using it, so a cold load there is felt;
 *  - a synthesis activated it within [idleTimeoutMs] ([beginSynth] /
 *    [endSynth]) — covers system-TTS and share-sheet routes, which have no
 *    in-app selection at all;
 *  - keepalive is [KeepaliveMode.Persistent] — the user has explicitly
 *    bought "everything warm forever" and the RAM cost is spelled out on
 *    the toggle.
 *
 * Everything else gets [app.marmalade.tts.engine.TtsEngine.release]d on the
 * next [sweep]. The cloud engine is deliberately absent: it holds no model,
 * so there is nothing to evict.
 *
 * Thread model: [beginSynth] / [endSynth] / [select] are called from
 * arbitrary dispatchers (system-TTS binder threads, the Speak ViewModel,
 * the share-sheet service) and only touch in-memory state under a lock.
 * The eviction itself runs off those threads, serialized — `release()`
 * uses `runBlocking` internally, so it must never run on Dispatchers.Main.
 */
@Singleton
class EngineResidency internal constructor(
    /** Engine name → its release thunk. Iteration order is only cosmetic (logs). */
    private val releasers: Map<String, () -> Unit>,
    /** Current keepalive mode; suspending because the real source is DataStore. */
    private val keepaliveMode: suspend () -> KeepaliveMode,
    /** Monotonic clock, injectable so tests can jump forward without sleeping. */
    private val clock: () -> Long,
    private val idleTimeoutMs: Long = SMART_TIMEOUT_MS,
    /**
     * Where fire-and-forget sweeps run. Application-lifetime by default —
     * never cancelled, because a sweep must survive whichever caller
     * triggered it going away. IO, never Main: `release()` blocks.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Inject
    constructor(
        settings: SettingsRepository,
        kokoroDirect: KokoroDirectEngine,
        kittenDirect: KittenDirectEngine,
        pocket: PocketEngine,
        pocketDev: PocketDevEngine,
    ) : this(
        releasers = linkedMapOf(
            KokoroDirectVoiceCatalog.ENGINE to kokoroDirect::release,
            KittenDirectVoiceCatalog.ENGINE to kittenDirect::release,
            PocketVoiceCatalog.ENGINE to pocket::release,
            PocketDevVoiceCatalog.ENGINE to pocketDev::release,
        ),
        keepaliveMode = { settings.keepaliveMode.first() },
        clock = SystemClock::elapsedRealtime,
    )

    /** Serializes sweeps so two triggers can't both decide to release the same engine. */
    private val sweepLock = Mutex()

    private val stateLock = Any()

    /** Engine name → last time a synthesis touched it, on the [clock] timebase. */
    private val lastActivated = HashMap<String, Long>()

    /** Engine name → number of syntheses currently running on it. */
    private val inFlight = HashMap<String, Int>()

    /** The Speak screen's selected engine, or null before any selection. */
    private var selected: String? = null

    /**
     * A synthesis is starting on [engineName]. Marks the engine active and
     * sweeps — a new synthesis is exactly the moment the *other* engines
     * became least likely to be needed.
     */
    fun beginSynth(engineName: String) {
        synchronized(stateLock) {
            inFlight[engineName] = (inFlight[engineName] ?: 0) + 1
            lastActivated[engineName] = clock()
        }
        sweep()
    }

    /**
     * A synthesis on [engineName] finished (or failed, or was cancelled —
     * callers must call this from a `finally`). Re-stamps so the idle window
     * measures from the END of the utterance, not its start.
     */
    fun endSynth(engineName: String) {
        synchronized(stateLock) {
            val n = (inFlight[engineName] ?: 0) - 1
            if (n <= 0) inFlight.remove(engineName) else inFlight[engineName] = n
            lastActivated[engineName] = clock()
        }
    }

    /**
     * The Speak screen selected a voice backed by [engineName]. Loading is
     * the caller's job (`Synthesizer.preload`); this only records which
     * engine is now protected — and evicts whoever just lost that status.
     */
    fun select(engineName: String) {
        synchronized(stateLock) { selected = engineName }
        sweep()
    }

    /** Fire-and-forget eviction pass. Safe to call from any thread. */
    fun sweep() {
        scope.launch { sweepNow() }
    }

    /**
     * The eviction pass proper. Suspending + internal so tests can await it;
     * production callers go through [sweep].
     */
    internal suspend fun sweepNow() = sweepLock.withLock {
        if (keepaliveMode() == KeepaliveMode.Persistent) return@withLock
        val now = clock()
        for ((name, release) in releasers) {
            val idleMs = synchronized(stateLock) {
                if (name == selected) return@synchronized null
                // Re-checked here, inside the serialized section, and not at
                // the top of the sweep: PocketEngine.release() CANCELS an
                // in-flight synthesis, so a stale "idle" reading would cut a
                // live utterance off mid-word.
                if ((inFlight[name] ?: 0) > 0) return@synchronized null
                val last = lastActivated[name] ?: return@synchronized Long.MAX_VALUE
                now - last
            } ?: continue
            if (idleMs <= idleTimeoutMs) continue
            val why = if (idleMs == Long.MAX_VALUE) "never activated" else "idle ${idleMs / 1000}s"
            Log.i(TAG, "evicting $name: $why, selected=$selected")
            // Idempotent on an unloaded engine (two uncontended mutexes, then
            // a no-op), so an engine this process never touched costs nothing.
            runCatching { release() }
                .onFailure { Log.w(TAG, "release($name) failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "EngineResidency"
    }
}
