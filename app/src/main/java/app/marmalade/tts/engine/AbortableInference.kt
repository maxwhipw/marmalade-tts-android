package app.marmalade.tts.engine

import ai.onnxruntime.OrtSession
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * A per-run abort switch — the one piece of an inference call that
 * coroutine cancellation reaches. [OrtRunAbort] is the real one; the
 * seam exists so the wiring in [abortableInference] is testable on the JVM.
 */
interface RunAbort : AutoCloseable {
    /** Stop the run in flight (or make the next one fail at once). Any thread. */
    fun terminate()
}

/** [RunAbort] over ORT's `RunOptions` — pass [options] to `session.run`. */
class OrtRunAbort : RunAbort {
    val options = OrtSession.RunOptions()
    override fun terminate() = options.setTerminate(true)
    override fun close() = options.close()
}

/**
 * Run one blocking inference [block] so that cancelling the calling
 * coroutine aborts it instead of waiting it out.
 *
 * Why: the streaming engines check `ensureActive()` between chunks, but a
 * chunk already inside `session.run` used to run to completion — seconds
 * on a throttled phone — while still holding the engine's synthLock and
 * the service's synth mutex, so a reader tap paid for the abandoned chunk
 * before its own first chunk could start (8a, 2026-09-28: 5.8 s).
 *
 * How: a fresh [RunAbort] per run ([open]); a watcher child parked in
 * `awaitCancellation` calls [RunAbort.terminate] when the caller's job is
 * cancelled. ORT checks the terminate flag between graph nodes and fails
 * the run with an OrtException, which is rethrown here as a
 * [CancellationException] — never an engine error, so it cannot reach the
 * service's uncaught-exception handler. The watcher runs Unconfined, so
 * the terminate happens on the cancelling thread immediately rather than
 * queueing for a dispatcher thread while this one is blocked in native
 * code. [RunAbort.close] runs only after `coroutineScope` has joined the
 * watcher, so a terminate can never touch a closed handle.
 *
 * Races: cancelled before the run starts → `ensureActive` throws and
 * nothing is opened; cancelled after the run finishes but before the
 * watcher is dismissed → the terminate lands on a finished run and is a
 * no-op (the handle is per-run, so it can't leak into the next one), and
 * `coroutineScope` still throws CancellationException for the cancelled
 * job. [block] must therefore consume and close everything it produces
 * (e.g. `session.run(...).use { ... }`): its return value is dropped when
 * the job was cancelled.
 *
 * The caller keeps holding its synthLock across this call: the abort
 * shortens the wait, it doesn't make the session reentrant.
 */
suspend fun <A : RunAbort, R> abortableInference(
    engine: String,
    open: () -> A,
    block: (A) -> R,
): R {
    currentCoroutineContext().ensureActive()
    val abort = open()
    val finished = AtomicBoolean(false)
    val terminated = AtomicBoolean(false)
    try {
        return coroutineScope {
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) {
                        terminated.set(true)
                        // A failure here must not fail the scope as an
                        // engine error; the run just finishes normally.
                        runCatching { abort.terminate() }
                            .onFailure { Log.w(PERF_TAG, "$engine terminate failed", it) }
                    }
                }
            }
            try {
                block(abort)
            } catch (t: Throwable) {
                if (terminated.get()) {
                    Log.d(PERF_TAG, "$engine inference aborted (cancelled)")
                    throw CancellationException("$engine inference aborted").apply { initCause(t) }
                }
                throw t
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    } finally {
        abort.close()
    }
}

private const val PERF_TAG = "StreamPerf"
