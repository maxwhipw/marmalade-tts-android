package app.marmalade.tts.playback

import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.NarratorHarness.Companion.voice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #96: the alias's pause between sentences in the reader. The engine adds it
 * inside a segment (it is handed the alias's value); the renderer adds 1.5× of
 * it after every segment but the last, as a chunk of its own.
 */
class NarratorSentencePauseTest {

    private fun TestScope.harness() = NarratorHarness(this, NarratorConfig())

    private fun NarratorHarness.writes() =
        output.commands.map { it.second }.filterIsInstance<OutputCommand.Write>()

    /** Each segment's last chunk written. */
    private fun NarratorHarness.lastChunks() =
        writes().groupBy { it.tag.segment }.mapValues { (_, w) -> w.maxBy { it.tag.chunk } }

    private fun OutputCommand.Write.silent() = pcm.isNotEmpty() && pcm.all { it == 0.toShort() }

    private fun NarratorHarness.done() {
        checkInvariants()
        narrator.close()
    }

    @Test
    fun `a paragraph break gets 1·5x the alias's pause and the article's end none`() = runTest {
        val h = harness()
        h.articleVoice = voice(sentencePauseMs = 500)
        h.open(List(3) { paragraph(2, fill = 'A' + it) })
        h.runUntil { h.article.status == Status.Finished }
        val last = h.lastChunks()
        for (seg in 0..1) {
            val pause = last.getValue(seg)
            assertTrue("segment $seg ends in silence", pause.silent())
            assertEquals("750 ms at 24 kHz", 18_000, pause.pcm.size)
            assertFalse("a pause is not a resume point", pause.pieceStart)
        }
        assertFalse("nothing after the last paragraph", last.getValue(2).silent())
        assertEquals(listOf(500), h.kokoro.sentencePauseRequests.distinct())
        h.done()
    }

    @Test
    fun `at Natural nothing is added and the engine is asked for no pause`() = runTest {
        val h = harness()
        h.open(List(3) { paragraph(2, fill = 'A' + it) })
        h.runUntil { h.article.status == Status.Finished }
        assertTrue(h.writes().none { it.silent() })
        assertEquals(listOf(0), h.kokoro.sentencePauseRequests.distinct())
        h.done()
    }
}
