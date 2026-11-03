package app.marmalade.tts.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for [EngineResidency]. Uses the internal constructor with
 * fake release thunks + a manual clock, so nothing here touches ORT, the
 * filesystem, or DataStore — the eviction rules are pure logic and worth
 * pinning at that level.
 */
class EngineResidencyTest {

    private val kokoro = "kokoro-direct"
    private val kitten = "kitten-direct"
    private val pocket = "pocket"

    /** Records which engines got released, in order. */
    private class Releases {
        val names = mutableListOf<String>()
    }

    private var now = 1_000L
    private var mode = KeepaliveMode.Smart

    /**
     * [sweepScope] is the test's `backgroundScope`, so the fire-and-forget
     * sweeps that [EngineResidency.select] / [EngineResidency.beginSynth]
     * kick off queue on the test dispatcher and never race the explicit
     * [EngineResidency.sweepNow] calls each test makes.
     */
    private fun residency(released: Releases, sweepScope: CoroutineScope) = EngineResidency(
        releasers = linkedMapOf<String, () -> Unit>(
            kokoro to { released.names += kokoro },
            kitten to { released.names += kitten },
            pocket to { released.names += pocket },
        ),
        keepaliveMode = { mode },
        clock = { now },
        idleTimeoutMs = SMART_TIMEOUT_MS,
        scope = sweepScope,
    )

    @Test
    fun `untouched engines are released`() = runTest {
        val released = Releases()
        residency(released, backgroundScope).sweepNow()
        assertEquals(listOf(kokoro, kitten, pocket), released.names)
    }

    @Test
    fun `selected engine is never evicted`() = runTest {
        val released = Releases()
        val r = residency(released, backgroundScope)
        r.select(kokoro)
        r.sweepNow()
        assertEquals(listOf(kitten, pocket), released.names)
    }

    @Test
    fun `recently activated engine is kept`() = runTest {
        val released = Releases()
        val r = residency(released, backgroundScope)
        r.beginSynth(pocket)
        r.endSynth(pocket)
        now += SMART_TIMEOUT_MS / 2
        r.sweepNow()
        assertEquals(listOf(kokoro, kitten), released.names)
    }

    @Test
    fun `engine is evicted once the idle window expires`() = runTest {
        val released = Releases()
        val r = residency(released, backgroundScope)
        r.beginSynth(pocket)
        r.endSynth(pocket)
        now += SMART_TIMEOUT_MS + 1
        r.sweepNow()
        assertEquals(listOf(kokoro, kitten, pocket), released.names)
    }

    @Test
    fun `in-flight synthesis blocks eviction even past the window`() = runTest {
        val released = Releases()
        val r = residency(released, backgroundScope)
        r.beginSynth(pocket)
        now += SMART_TIMEOUT_MS + 1
        r.sweepNow()
        assertEquals(listOf(kokoro, kitten), released.names)

        // Utterance finishes → the window starts counting from the end.
        r.endSynth(pocket)
        now += SMART_TIMEOUT_MS + 1
        released.names.clear()
        r.sweepNow()
        assertEquals(listOf(kokoro, kitten, pocket), released.names)
    }

    @Test
    fun `persistent keepalive never evicts`() = runTest {
        val released = Releases()
        mode = KeepaliveMode.Persistent
        val r = residency(released, backgroundScope)
        now += SMART_TIMEOUT_MS * 10
        r.sweepNow()
        assertEquals(emptyList<String>(), released.names)
    }

    @Test
    fun `changing selection releases the engine that lost it`() = runTest {
        val released = Releases()
        val r = residency(released, backgroundScope)
        r.select(kokoro)
        r.sweepNow()
        released.names.clear()

        r.select(kitten)
        r.sweepNow()
        assertEquals(listOf(kokoro, pocket), released.names)
    }

    @Test
    fun `selection protects an engine whose idle window already expired`() = runTest {
        val released = Releases()
        val r = residency(released, backgroundScope)
        r.beginSynth(kitten)
        r.endSynth(kitten)
        now += SMART_TIMEOUT_MS + 1
        r.select(kitten)
        r.sweepNow()
        assertEquals(listOf(kokoro, pocket), released.names)
    }

    /**
     * #119 CORRECT-13: the sweep decided Pocket was idle under the lock, then
     * released it after letting go. A synthesis starting in that gap ran
     * while the release was still going, and PocketEngine.release() aborts
     * a running synthesis. beginSynth must wait until the release is over,
     * so the synthesis starts on a released engine and reloads it.
     */
    @Test
    fun `a synthesis starting during an eviction waits for the release to finish`() {
        val releaseEntered = CountDownLatch(1)
        val releaseMayFinish = CountDownLatch(1)
        val r = EngineResidency(
            releasers = linkedMapOf<String, () -> Unit>(
                pocket to {
                    releaseEntered.countDown()
                    releaseMayFinish.await()
                },
            ),
            keepaliveMode = { KeepaliveMode.Smart },
            clock = { now },
            idleTimeoutMs = SMART_TIMEOUT_MS,
            // Not part of this test: beginSynth's own fire-and-forget sweep.
            scope = CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        )
        val sweeper = thread { runBlocking { r.sweepNow() } }
        assertTrue("release never started", releaseEntered.await(5, TimeUnit.SECONDS))

        val synthStarted = CountDownLatch(1)
        val synth = thread {
            r.beginSynth(pocket)
            synthStarted.countDown()
        }
        assertFalse(
            "beginSynth returned while Pocket was still being released",
            synthStarted.await(300, TimeUnit.MILLISECONDS),
        )

        releaseMayFinish.countDown()
        assertTrue("beginSynth never returned", synthStarted.await(5, TimeUnit.SECONDS))
        sweeper.join(5_000)
        synth.join(5_000)
    }

    @Test
    fun `an engine in use when the sweep reaches it is not released`() {
        // The other side of the same race: a synthesis that begins before the
        // sweep's decision keeps the engine, even past the idle window.
        val released = Releases()
        val r = residency(released, CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }))
        now += SMART_TIMEOUT_MS + 1
        r.beginSynth(pocket)
        runBlocking { r.sweepNow() }
        assertEquals(listOf(kokoro, kitten), released.names)
    }
}
