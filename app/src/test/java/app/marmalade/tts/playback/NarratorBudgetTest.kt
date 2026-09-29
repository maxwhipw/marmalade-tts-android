package app.marmalade.tts.playback

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.playback.NarratorHarness.Companion.CLOUD_ENGINE
import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.NarratorHarness.Companion.sentence
import app.marmalade.tts.playback.NarratorHarness.Companion.voice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 scenarios about how far ahead the Narrator prepares, when it starts,
 * memory, and bookkeeping (6, 12, 13, 14, 23, 24, 25, 26), plus Max's §6
 * answers on read-ahead (Q2, Q3) and cloud speed (Q4).
 */
class NarratorBudgetTest {

    /** Distinct text per segment (up to 60) so renders are countable per segment. */
    private fun article(n: Int, sentences: Int = 2) = List(n) { paragraph(sentences, fill = 'A' + it) }

    private fun TestScope.harness(config: NarratorConfig = NarratorConfig()) = NarratorHarness(this, config)

    private fun NarratorHarness.done() {
        checkInvariants()
        narrator.close()
    }

    /** Played ms rendered but not yet heard, from the fake's point of view. */
    private fun NarratorHarness.renderedAheadMs(segs: List<String>, heardUntilSeg: Int): Double =
        segs.drop(heardUntilSeg + 1).sumOf { s -> kokoro.completedRendersOf(s).size * 3_630.0 }

    @Test
    fun `Q3 - keeps preparing while paused, up to about a minute`() = runTest {
        val h = harness()
        val segs = article(30) // 7.26 s each
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.narrator.pause()
        h.run(10 * 60_000L) // 6 - pause 10 min at the budget
        val ahead = h.renderedAheadMs(segs, 0)
        assertTrue("prepared ahead $ahead ms", ahead in 50_000.0..60_000.0 + 2 * 3_630.0)
        assertTrue("stopped at the budget", h.kokoro.renders.none { it.endMs < 0 })
        assertEquals(Status.Paused(PausedBy.User), h.article.status)
        assertTrue("residency still held", h.residency.held.isNotEmpty())
        h.done()
    }

    @Test
    fun `6 - a Navigation pause stops preparing at once, residency still held`() = runTest {
        val h = harness()
        val segs = article(30)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(500)
        h.narrator.pause(PauseReason.Navigation)
        h.settle()
        val renders = h.kokoro.renders.size
        h.run(5 * 60_000L)
        assertEquals("nothing rendered after leaving", renders, h.kokoro.renders.size)
        assertTrue(h.kokoro.renders.none { it.endMs < 0 })
        assertTrue(h.residency.held.isNotEmpty())
        // Coming back resumes where it was.
        h.narrator.play()
        h.runUntil { h.article.status == Status.Playing }
        h.done()
    }

    @Test
    fun `Q2 - a cloud voice prepares only this segment and the next`() = runTest {
        val h = harness()
        h.articleVoice = voice(engine = CLOUD_ENGINE, cloud = true)
        val segs = article(6)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.narrator.pause()
        h.run(5 * 60_000L)
        val rendered = segs.indices.filter { h.cloudEngine.rendersOf(segs[it]).isNotEmpty() }
        assertEquals(listOf(0, 1), rendered)
        h.done()
    }

    @Test
    fun `Q4 - a cloud speed change is stretched on the phone - no re-render, no gap`() = runTest {
        val h = harness()
        h.articleVoice = voice(engine = CLOUD_ENGINE, cloud = true)
        val segs = article(2, sentences = 3)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(1_000)
        h.narrator.setSpeed(1.5f)
        h.runUntil { h.article.status == Status.Finished }
        assertEquals(segs.sumOf { h.cloudEngine.chunksOf(it).size }, h.cloudEngine.renders.size)
        assertTrue(h.cloudEngine.requests.all { it.third == 1f })
        assertEquals(0.0, h.gapsMs(), 0.0)
        h.done()
    }

    @Test
    fun `12 - hot phone at 1x - start within the cap, total silence is the physical deficit`() = runTest {
        val h = harness()
        h.kokoro.msPerToken = 66.0 // RTF 1.2: 4356 ms of render per 3630 ms chunk
        val segs = article(6)
        h.open(segs)
        h.runUntil(maxMs = 200_000) { h.article.status == Status.Finished }
        val id = h.narrator.articleId()!!
        val ttfa = h.firstHeard(id, 0)!!
        assertTrue("TTFA $ttfa within 2 chunks", ttfa <= 2 * 4_356 + 20)
        val chunks = 12
        val totalRender = chunks * 4_356.0
        val totalAudio = chunks * 3_630.0
        val finish = h.output.events.last { it.second is OutputEvent.SessionEnded }.first
        val silence = finish - totalAudio
        // The last chunk can't be heard before it renders: the floor is the
        // render time of everything plus the last chunk's own audio.
        val floor = totalRender + 3_630.0 - totalAudio
        assertTrue("silence $silence vs physical floor $floor", silence in floor - 50..floor + 250)
        h.done()
    }

    @Test
    fun `13 - thermal drift 0_44 to 1_2 - the RTF estimate follows within about three chunks`() = runTest {
        val h = harness()
        val segs = article(10)
        var hot = false
        h.kokoro.rtfScale = { if (hot) 1.2 / 0.436 else 1.0 }
        h.open(segs)
        h.runUntil { h.kokoro.renders.count { it.endMs >= 0 } >= 3 }
        assertEquals(0.436, h.narrator.articleRtf()!!, 0.01)
        hot = true
        val flip = h.now()
        h.runUntil { h.kokoro.renders.count { it.startMs >= flip && it.endMs >= 0 } >= 3 }
        h.settle()
        assertTrue("estimate ${h.narrator.articleRtf()}", h.narrator.articleRtf()!! > 1.2 * 0.85)
        h.done()
    }

    @Test
    fun `14 - a CJK over-cap sentence arrives as pieces, audio after piece 1, stop lands between pieces`() = runTest {
        val h = harness()
        h.kokoro.tokensPerChar = 3.4
        h.kokoro.msPerToken = 10.0
        val zh = "中".repeat(210) + "。" // 211 chars ≈ 717 tokens > 500
        assertEquals(2, h.kokoro.chunksOf(zh).size)
        h.open(listOf(zh, "文".repeat(30) + "。"))
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        val piece1 = h.kokoro.rendersOf(zh)[0]
        assertTrue("first audio after piece 1", h.firstHeard(id, 0)!! - piece1.endMs in 0..20)
        assertTrue("piece 2 still rendering", h.kokoro.rendersOf(zh).getOrNull(1)?.endMs == -1L)
        h.narrator.stop()
        h.settle()
        val t = h.now()
        h.run(5_000)
        assertTrue(h.kokoro.rendersOf(zh)[1].aborted)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 10), 0.0)
        h.done()
    }

    @Test
    fun `23 - an emoji segment renders whole first, then prosody, and live speed applies`() = runTest {
        val h = harness()
        val segs = listOf(paragraph(1, fill = 'a'), paragraph(3, fill = 'b').replace("b.", "b 😁.").let { it })
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 1) != null }
        val stripped = app.marmalade.tts.preprocessing.EmojiProsody.stripEmojis(segs[1])
        val lastRender = h.kokoro.rendersOf(stripped).maxOf { it.endMs }
        assertTrue("whole segment rendered before it is heard", h.firstHeard(id, 1)!! >= lastRender)
        assertEquals("one chunk", 1, h.heardStarts().count { it.second.segment == 1 })
        val t = h.now()
        h.narrator.setSpeed(2f)
        h.runUntil { h.article.status == Status.Finished }
        val played = h.output.events.last().first - t
        // ≈ 3 × 3630 ms of audio at 2x.
        assertTrue("played $played", played < 3 * 3_630 * 0.6)
        h.done()
    }

    @Test
    fun `24 - one 10k-char segment stays within the memory bound`() = runTest {
        val h = harness()
        val huge = List(166) { sentence(60, fill = 'a' + (it % 26)) }.joinToString(" ")
        assertTrue(huge.length >= 10_000)
        h.open(listOf(huge))
        var maxBytes = 0L
        repeat(240) {
            h.run(1_000)
            maxBytes = maxOf(maxBytes, h.narrator.bufferedBytes())
        }
        val bytesPerMs = 48.0 // 24 kHz mono PCM16
        val bound = (60_000 + 60_000 + 3 * 3_630) * bytesPerMs
        assertTrue("max buffered $maxBytes bytes > bound $bound", maxBytes <= bound)
        assertTrue("still reading", h.article.status == Status.Playing)
        h.done()
    }

    @Test
    fun `25 - session speed replaces the alias speed, alias effect and language kept`() = runTest {
        val h = harness()
        val cave = listOf(EffectBlock.Reverb(reverberance = 60f))
        h.articleVoice = voice(effects = cave, language = "fr", aliasSpeed = 1.3f)
        h.open(article(1), speed = 2f)
        h.runUntil { h.article.status == Status.Playing }
        val begin = h.output.commands.map { it.second }.filterIsInstance<OutputCommand.Begin>().first()
        assertEquals(2f, begin.tempo)
        assertEquals(cave, begin.effectBlocks)
        assertEquals("fr", h.kokoro.requests.first().second)
        assertEquals("engine renders at 1.0; speed is the writer's", 1f, h.kokoro.requests.first().third)
        assertEquals(2f, h.article.speed)
        h.done()
    }

    @Test
    fun `26 - one latency sample per render job, RTF never from a chunk collected after a permit wait`() = runTest {
        val h = harness(NarratorConfig(budgetMs = 10_000.0))
        h.kokoro.internalBuffer = true // renders on while the permit is closed
        val segs = article(4, sentences = 4)
        h.open(segs)
        h.runUntil(maxMs = 300_000) { h.article.status == Status.Finished }
        assertEquals("one TTFA sample per render job", segs.size, h.latencies.size)
        assertTrue("some RTF recorded", h.rtfs.isNotEmpty())
        for ((_, rtf) in h.rtfs) assertEquals(0.436, rtf, 0.02)
        h.done()
    }
}
