package app.marmalade.tts.playback

import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 transport scenarios on virtual time: the real Narrator, a fake engine
 * and a virtual writer running the real StreamingEffectChain. Numbers in the
 * test names are the plan's scenario numbers.
 *
 * Fake engine defaults ("cool"): a 60-char sentence = 66 tokens → 1584 ms of
 * render, 3630 ms of audio (RTF 0.44). "Hot" is 66 ms/token (RTF 1.2).
 */
class NarratorScenariosTest {

    /** Distinct text per segment so renders are countable per segment. */
    private fun article(n: Int = 3, sentences: Int = 2) = List(n) { paragraph(sentences, fill = 'a' + it) }

    private fun TestScope.harness(config: NarratorConfig = NarratorConfig()) = NarratorHarness(this, config)

    private fun NarratorHarness.done() {
        checkInvariants()
        narrator.close()
    }

    @Test
    fun `smoke - an article plays through to Finished, gapless, each chunk rendered once`() = runTest {
        val h = harness()
        val segs = article()
        h.open(segs)
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        val id = h.narrator.articleId()!!
        for (i in segs.indices) assertNotNull("segment $i heard", h.firstHeard(id, i))
        for (s in segs) assertEquals(h.kokoro.chunksOf(s).size, h.kokoro.completedRendersOf(s).size)
        assertEquals(0.0, h.gapsMs(), 0.0)
        assertEquals(1, h.keepaliveStarts)
        h.done()
    }

    @Test
    fun `1 - Next with the next block prepared is audible within one slice, no re-render`() = runTest {
        val h = harness()
        val segs = article(4)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null && h.kokoro.completedRendersOf(segs[1]).size == 2 }
        h.run(1_000)
        val t = h.now()
        h.narrator.next()
        h.runUntil(what = "segment 1 heard") { h.firstHeard(id, 1) != null }
        assertTrue("TTFA ${h.firstHeard(id, 1)!! - t}", h.firstHeard(id, 1)!! - t <= 20)
        assertEquals(1, h.article.index)
        assertEquals(2, h.kokoro.rendersOf(segs[1]).size)
        h.done()
    }

    @Test
    fun `2 - Next during the next block's render keeps it, TTFA is its remaining time`() = runTest {
        val h = harness()
        h.kokoro.msPerToken = 66.0 // hot: RTF 1.2
        val segs = article(3)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.kokoro.rendersOf(segs[1]).any { it.endMs < 0 } && h.firstHeard(id, 0) != null }
        val inFlight = h.kokoro.rendersOf(segs[1]).first()
        val t = h.now()
        h.narrator.next()
        h.runUntil(what = "segment 1 heard") { h.firstHeard(id, 1) != null }
        assertTrue("render kept", !inFlight.aborted)
        val heard = h.firstHeard(id, 1)!!
        assertTrue("TTFA ${heard - t} vs remaining ${inFlight.endMs - t}", heard - inFlight.endMs in 0..20)
        h.done()
    }

    @Test
    fun `3 - tap an unprepared far block aborts the render, TTFA is chunk 0`() = runTest {
        val h = harness()
        val segs = article(20)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(700) // mid-chunk of a look-ahead render
        val inFlight = h.kokoro.renders.last()
        assertTrue(inFlight.endMs < 0)
        val t = h.now()
        h.narrator.jumpTo(15, play = true)
        h.runUntil(what = "segment 15 heard") { h.firstHeard(id, 15) != null }
        assertTrue("in-flight render aborted", inFlight.aborted)
        val ttfa = h.firstHeard(id, 15)!! - t
        // Chunk 0 renders in 1584 ms; cool → no hold beyond it.
        assertTrue("TTFA $ttfa", ttfa in 1_584..1_584 + 20)
        h.done()
    }

    @Test
    fun `4 - non-abortable engine - the tap waits exactly the in-flight chunk`() = runTest {
        val h = harness()
        h.kokoro.abortable = false
        val segs = article(20)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(700)
        val inFlight = h.kokoro.renders.last()
        val t = h.now()
        h.narrator.jumpTo(15, play = true)
        h.runUntil(what = "segment 15 heard") { h.firstHeard(id, 15) != null }
        val expected = (inFlight.endMs - t) + 1_584
        val ttfa = h.firstHeard(id, 15)!! - t
        assertTrue("TTFA $ttfa, expected $expected", ttfa in expected..expected + 20)
        h.done()
    }

    @Test
    fun `5 - Next and Previous while paused stay paused and silent, resume starts the target`() = runTest {
        val h = harness()
        val segs = article(4)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(500)
        h.narrator.pause()
        h.run(50)
        val pausedAt = h.now()
        h.narrator.next()
        h.narrator.next()
        h.narrator.previous()
        h.run(3_000)
        assertEquals(Status.Paused(PausedBy.User), h.article.status)
        assertEquals(1, h.article.index)
        assertEquals(0.0, h.audibleMs(Track.Main, from = pausedAt), 0.0)
        val t = h.now()
        h.narrator.play()
        h.runUntil { h.firstHeard(id, 1, after = t) != null }
        assertTrue(h.firstHeard(id, 1, after = t)!! - t <= 20)
        h.done()
    }

    @Test
    fun `7 - live speed 1 to 2 to 0_75 mid-block - no re-render, audible within a slice`() = runTest {
        val h = harness()
        val segs = article(2, sentences = 3)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        val renders = h.kokoro.renders.size
        h.run(500)
        h.narrator.setSpeed(2f)
        h.runUntil { h.heardStarts().any { it.second.chunk == 1 } }
        val c0 = h.heardStarts().first { it.second.chunk == 0 }.first
        val c1 = h.heardStarts().first { it.second.chunk == 1 }.first
        // 500 ms at 1x, the remaining 3130 ms at 2x ≈ 1565 ms: ≈ 2065 ms, not 3630.
        assertTrue("chunk 0 lasted ${c1 - c0}", c1 - c0 in 2_000..2_200)
        h.narrator.setSpeed(0.75f)
        h.runUntil { h.heardStarts().any { it.second.chunk == 2 } }
        val c2 = h.heardStarts().first { it.second.chunk == 2 }.first
        assertTrue("chunk 1 lasted ${c2 - c1}", c2 - c1 in 4_700..4_950)
        assertEquals(0.75f, h.article.speed)
        h.runUntil { h.article.status == Status.Finished }
        assertTrue(renders > 0)
        assertEquals("no chunk re-rendered", segs.sumOf { h.kokoro.chunksOf(it).size }, h.kokoro.renders.size)
        h.done()
    }

    @Test
    fun `11 - stale writer events and writes are dropped by epoch`() = runTest {
        val h = harness()
        val segs = article(5)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null && h.kokoro.completedRendersOf(segs[4]).size == 2 }
        val old = h.narrator.articleEpoch()!!
        val from = h.article.index
        h.narrator.next()
        h.narrator.next()
        h.settle()
        val epoch = h.narrator.articleEpoch()!!
        assertTrue(epoch > old)
        // Events of the old epoch still queued: none may move the cursor.
        repeat(5) { h.output.inject(OutputEvent.ChunkStarted(Track.Main, ChunkTag(id, old, 4, 1))) }
        h.output.inject(OutputEvent.SessionEnded(Track.Main, id, old))
        // A late write of the old epoch reaches the writer after the flush.
        h.output.submit(OutputCommand.Write(Track.Main, ChunkTag(id, old, 0, 1), ShortArray(2_400)))
        h.run(3_000)
        assertEquals(from + 2, h.article.index)
        assertEquals(Status.Playing, h.article.status)
        assertEquals(1, h.output.staleDropped)
        h.done()
    }

    @Test
    fun `17 - a new article replaces the playing one, the same article reopened changes nothing`() = runTest {
        val h = harness()
        val first = article(3)
        h.open(first, key = "one")
        val id1 = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id1, 0) != null }
        h.open(first, key = "one")
        assertEquals(id1, h.narrator.articleId())
        assertEquals(1, h.resolutions)
        val t = h.now()
        h.open(List(2) { paragraph(2, fill = 'x' + it) }, key = "two")
        val id2 = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id2, 0) != null }
        h.run(500)
        assertTrue("old article silent after replacement",
            h.output.timeline.none { it.atMs > t + 10 && it.tag?.sessionId == id1 && it.audibleMs > 0 })
        assertEquals("two", h.article.key)
        h.done()
    }

    @Test
    fun `18 - Previous inside 2 s of the heard start goes back and reuses its audio, outside restarts`() = runTest {
        val h = harness()
        val segs = article(3)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 1) != null }
        h.run(1_000)
        h.narrator.previous()
        val t = h.now()
        h.runUntil { h.firstHeard(id, 0, after = t) != null }
        assertEquals(0, h.article.index)
        assertTrue(h.firstHeard(id, 0, after = t)!! - t <= 20)
        assertEquals("previous block reused", 2, h.kokoro.rendersOf(segs[0]).size)
        h.runUntil { h.firstHeard(id, 1, after = t) != null }
        h.run(3_000)
        val t2 = h.now()
        h.narrator.previous()
        h.runUntil { h.firstHeard(id, 1, after = t2) != null }
        assertEquals("outside the window: restart the block", 1, h.article.index)
        assertEquals(2, h.kokoro.rendersOf(segs[1]).size)
        h.done()
    }

    @Test
    fun `18b - the window runs from the first HEARD frame, not from when the block was queued`() = runTest {
        val h = harness()
        h.kokoro.msPerToken = 66.0 // hot: block 1 is heard well after it was asked for
        val segs = article(3)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.narrator.jumpTo(1, play = true)
        h.runUntil { h.firstHeard(id, 1) != null }
        h.run(1_500) // 1.5 s heard, >4 s since the jump
        h.narrator.previous()
        h.settle()
        assertEquals(0, h.article.index)
        h.done()
    }

    @Test
    fun `19 - backing out during TTFA - nothing is ever audible`() = runTest {
        val h = harness()
        h.open(article(3))
        h.run(500)
        h.narrator.pause(PauseReason.Navigation)
        h.run(10_000)
        assertEquals(0.0, h.audibleMs(Track.Main), 0.0)
        assertEquals(Status.Paused(PausedBy.Navigation), h.article.status)
        assertTrue("render cancelled", h.kokoro.renders.all { it.endMs >= 0 })
        assertEquals(1, h.kokoro.renders.size)
        h.done()
    }

    @Test
    fun `10 - Stop mid-inference cancels the render, flushes at once, keeps the cursor`() = runTest {
        val h = harness()
        h.kokoro.msPerToken = 66.0
        val segs = article(3)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(1_000)
        val inFlight = h.kokoro.renders.last()
        h.narrator.stop()
        h.settle()
        val t = h.now()
        h.run(5_000)
        assertTrue(inFlight.aborted)
        assertEquals(Status.Idle, h.article.status)
        assertEquals(0, h.article.index)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 10), 0.0)
        assertTrue(h.residency.held.isEmpty())
        h.done()
    }

    @Test
    fun `10b - Stop during voice resolution - no late VoiceResolved acted on`() = runTest {
        val h = harness()
        h.resolveDelayMs = 1_000
        h.open(article(2))
        h.run(500)
        h.narrator.stop()
        h.run(5_000)
        assertEquals(Status.Idle, h.article.status)
        assertEquals(0, h.kokoro.renders.size)
        assertEquals(0.0, h.audibleMs(Track.Main), 0.0)
        h.done()
    }

    @Test
    fun `no Previous or Next for shared text`() = runTest {
        val h = harness()
        val handle = h.narrator.speakOneShot(
            OneShotRequest(List(3) { paragraph(1, fill = 'm' + it) }.joinToString("\n\n"), OneShotVoice.LanguageAware, Origin.External),
        )
        h.runUntil { h.state.oneShot?.status == Status.Playing }
        h.narrator.next()
        h.narrator.previous()
        h.runUntil { handle.outcome.isCompleted }
        assertEquals(Outcome.Played, handle.outcome.getCompleted())
        assertNull(h.state.article)
        assertEquals(0.0, h.gapsMs(), 0.0)
        h.done()
    }
}
