package app.marmalade.tts.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the cancellation wiring of [abortableInference]: a cancelled caller
 * terminates the blocking run, sees a CancellationException (never the
 * runtime's own error), and the per-run handle is closed after any
 * terminate. Plain JVM: android.util.Log is a no-op stub in unit tests.
 */
class AbortableInferenceTest {

    /** Stands in for ORT: a run blocks until terminated, then fails like ORT does. */
    private class FakeAbort : RunAbort {
        val terminated = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var terminatedAfterClose = false

        override fun terminate() {
            if (closed) terminatedAfterClose = true
            terminated.countDown()
        }

        override fun close() {
            closed = true
        }

        fun blockingRun(started: CountDownLatch): Int {
            started.countDown()
            if (!terminated.await(5, TimeUnit.SECONDS)) error("never terminated")
            throw IllegalStateException("Exiting due to terminate flag being set to true.")
        }
    }

    @Test
    fun `cancelling the caller aborts the run in flight`() = runBlocking {
        val abort = FakeAbort()
        val started = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            abortableInference("test", { abort }) { it.blockingRun(started) }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        // Returns promptly only because the blocked run was terminated.
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(0L, abort.terminated.count)
        assertTrue(abort.closed)
        assertFalse(abort.terminatedAfterClose)
    }

    @Test
    fun `the aborted run surfaces as CancellationException carrying the ORT error`() = runBlocking {
        val abort = FakeAbort()
        val started = CountDownLatch(1)
        var caught: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                abortableInference("test", { abort }) { it.blockingRun(started) }
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(caught is CancellationException)
        assertTrue(caught?.cause is IllegalStateException)
    }

    /** The terminate landed after the last graph node: the run succeeds anyway. */
    @Test
    fun `a run that finishes despite the terminate still ends cancelled`() = runBlocking {
        val abort = FakeAbort()
        val started = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            abortableInference("test", { abort }) {
                started.countDown()
                abort.terminated.await(5, TimeUnit.SECONDS)
                7
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(abort.closed)
        assertFalse(abort.terminatedAfterClose)
    }

    @Test
    fun `a terminate that throws does not turn into an engine error`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val abort = object : RunAbort {
            override fun terminate() {
                release.countDown()
                throw IllegalStateException("native handle gone")
            }
            override fun close() = Unit
        }
        var caught: Throwable? = null
        val job = launch(Dispatchers.Default) {
            try {
                abortableInference("test", { abort }) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("Exiting due to terminate flag being set to true.")
                }
            } catch (t: Throwable) {
                caught = t
                throw t
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(caught is CancellationException)
    }

    @Test
    fun `a run that finishes returns its value and is never terminated`() = runBlocking {
        val abort = FakeAbort()
        val result = abortableInference("test", { abort }) { 42 }
        assertEquals(42, result)
        assertEquals(1L, abort.terminated.count)
        assertTrue(abort.closed)
    }

    @Test
    fun `a genuine error without cancellation propagates unchanged`() = runBlocking {
        val abort = FakeAbort()
        try {
            abortableInference("test", { abort }) { throw IllegalArgumentException("bad input") }
            fail("expected the error")
        } catch (e: IllegalArgumentException) {
            // (Coroutine stack-trace recovery may hand back a copy, so no assertSame.)
            assertEquals("bad input", e.message)
        }
        assertEquals(1L, abort.terminated.count)
        assertTrue(abort.closed)
    }

    @Test
    fun `an already-cancelled caller never opens a run`() = runBlocking {
        var opened = false
        val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            cancel()
            abortableInference("test", { opened = true; FakeAbort() }) { 1 }
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(opened)
    }
}
