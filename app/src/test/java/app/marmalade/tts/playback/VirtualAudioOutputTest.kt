package app.marmalade.tts.playback

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The harness's writer honours its own contract before any scenario leans on it. */
class VirtualAudioOutputTest {

    private fun tag(chunk: Int, epoch: Int = 1) = ChunkTag(sessionId = 7, epoch = epoch, segment = 0, chunk = chunk)

    private fun pcm(ms: Int) = ShortArray(24 * ms) { (it % 100).toShort() }

    @Test
    fun `side-band marks survive a live 2x Tempo - two 1 s chunks are heard in about 1 s`() = runTest {
        val out = VirtualAudioOutput(backgroundScope, { testScheduler.currentTime })
        val events = ArrayList<Pair<Long, OutputEvent>>()
        out.attach { events += testScheduler.currentTime to it }
        out.submit(OutputCommand.Begin(Track.Main, 7, 1, 24_000, emptyList(), tempo = 2f))
        out.submit(OutputCommand.Write(Track.Main, tag(0), pcm(1_000)))
        out.submit(OutputCommand.Write(Track.Main, tag(1), pcm(1_000)))
        out.submit(OutputCommand.End(Track.Main, 7, 1))
        out.submit(OutputCommand.Play(Track.Main))
        advanceTimeBy(3_000)
        runCurrent()
        val started = events.filter { it.second is OutputEvent.ChunkStarted }
        assertEquals(listOf(0, 1), started.map { (it.second as OutputEvent.ChunkStarted).tag.chunk })
        assertTrue("chunk 1 heard at ${started[1].first}", started[1].first in 480..560)
        val ended = events.single { it.second is OutputEvent.SessionEnded }.first
        assertTrue("ended at $ended", ended in 980..1_080)
    }

    @Test
    fun `a flush drops what is queued and any later item of an older epoch`() = runTest {
        val out = VirtualAudioOutput(backgroundScope, { testScheduler.currentTime })
        out.attach { }
        out.submit(OutputCommand.Begin(Track.Main, 7, 1, 24_000, emptyList(), tempo = 1f))
        out.submit(OutputCommand.Write(Track.Main, tag(0), pcm(2_000)))
        out.submit(OutputCommand.Play(Track.Main))
        advanceTimeBy(500)
        runCurrent()
        out.submit(OutputCommand.Flush(Track.Main, 7, minEpoch = 2))
        out.submit(OutputCommand.Write(Track.Main, tag(1, epoch = 1), pcm(500)))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, out.staleDropped)
        val heard = out.timeline.sumOf { it.audibleMs }
        assertTrue("heard $heard ms", heard in 490.0..520.0)
        assertTrue(out.timeline.none { it.stale })
    }
}
