package app.marmalade.tts.playback

import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.NarratorHarness.Companion.voice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #96 (Max, 2026-10-23): an alias's pause must not stack on a slow phone's
 * own wait for the next sentence. The pause is audio the writer plays while
 * the engine renders on, so the silence heard between two sentences is
 * max(pause, the wait), never the sum. Here the engine renders at RTF 0.73
 * (or 1.1), too slow for 2x, so at Natural the sentences keep the next one
 * waiting. The pause is either a chunk of its own or the end of the
 * sentence's last slice; either way it must not count as rendered speech,
 * or the Narrator starts playback with too little banked and the waits come
 * early (a Long pause made every join 850 ms where 500 was due).
 */
class NarratorPauseWaitTest {

    private fun NarratorHarness.done() {
        checkInvariants()
        narrator.close()
    }

    /** The heard silence at each join between two sentences, in virtual ms. */
    private fun TestScope.joinGaps(pauseMs: Int, inChunk: Boolean, msPerToken: Double = 40.0, speed: Float = 2f): List<Long> {
        val h = NarratorHarness(this, NarratorConfig())
        h.kokoro.msPerToken = msPerToken
        h.kokoro.pauseInChunk = inChunk
        h.articleVoice = voice(sentencePauseMs = pauseMs)
        h.open(listOf(paragraph(8)), speed = speed)
        h.runUntil(maxMs = 600_000) { h.article.status == Status.Finished }
        val writes = h.output.commands.map { it.second }.filterIsInstance<OutputCommand.Write>()
        // Each chunk's trailing added silence, in heard ms (Tempo plays it at [speed]).
        val trailingMs = writes.associate { w ->
            w.tag to (w.pcm.size - (w.pcm.indexOfLast { it != 0.toShort() } + 1)) * 1000L / 24_000 / speed.toLong().coerceAtLeast(1)
        }
        val speech = writes.filter { w -> w.pcm.any { it != 0.toShort() } }.map { it.tag }.toSet()
        val started = HashMap<ChunkTag, Long>()
        val finished = HashMap<ChunkTag, Long>()
        for ((t, e) in h.output.events) {
            if (e is OutputEvent.ChunkStarted) started.putIfAbsent(e.tag, t)
            if (e is OutputEvent.ChunkFinished) finished[e.tag] = t
        }
        val order = writes.map { it.tag }.filter { it in speech }
        // From the end of one sentence's speech to the start of the next one's.
        val gaps = ArrayList<Long>()
        for (i in 0 until order.size - 1) {
            val a = order[i]
            val b = order[i + 1]
            val speechEnd = finished.getValue(a) - (if (inChunk) trailingMs.getValue(a) else 0)
            gaps += started.getValue(b) - speechEnd
        }
        h.done()
        return gaps
    }

    private fun check(inChunk: Boolean, msPerToken: Double = 40.0) = runTest {
        val natural = joinGaps(0, inChunk, msPerToken)
        val long = joinGaps(1000, inChunk, msPerToken)
        assertEquals(natural.size, long.size)
        // Heard at 2x: a Long pause is 500 ms of silence.
        val pauseHeard = 500L
        assertTrue("the phone keeps the next sentence waiting at Natural: $natural", natural.drop(1).average() > 200)
        for ((i, g) in long.withIndex()) {
            assertTrue("join $i: at least the pause ($g ms)", g >= pauseHeard - 30)
            assertTrue(
                "join $i: $g ms heard, more than max(pause $pauseHeard, wait ${natural[i]}) — the pause stacked on the wait",
                g <= maxOf(pauseHeard, natural[i]) + 60,
            )
        }
    }

    @Test
    fun `a pause chunk of its own overlaps the wait (Pocket, VITS)`() = check(inChunk = false)

    @Test
    fun `a pause on the sentence's last slice overlaps the wait (Kokoro, Kitten)`() = check(inChunk = true)

    @Test
    fun `waits longer than the pause swallow it (separate chunk)`() = check(inChunk = false, msPerToken = 60.0)

    @Test
    fun `waits longer than the pause swallow it (on the last slice)`() = check(inChunk = true, msPerToken = 60.0)
}
