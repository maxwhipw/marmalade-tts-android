package app.marmalade.tts.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceGapsTest {

    @Test
    fun `a sentence or line end takes the pause, a clause mark doesn't`() {
        assertTrue(SentenceGaps.endsSentence("It was late."))
        assertTrue(SentenceGaps.endsSentence("\"Stop!\""))
        assertTrue(SentenceGaps.endsSentence("Really?)"))
        assertTrue(SentenceGaps.endsSentence("終わり。"))
        assertTrue(SentenceGaps.endsSentence("यह है।"))
        assertTrue(SentenceGaps.endsSentence("Chapter one"))
        assertFalse(SentenceGaps.endsSentence("First, the eggs;"))
        assertFalse(SentenceGaps.endsSentence("He said:"))
        assertFalse(SentenceGaps.endsSentence("apples,"))
        assertFalse(SentenceGaps.endsSentence("   "))
    }

    @Test
    fun `the tiny-sentence merge stays at Natural and is off with a pause`() {
        assertEquals(40, SentenceGaps.mergeFloor(40, 0))
        assertEquals(0, SentenceGaps.mergeFloor(40, 250))
    }

    @Test
    fun `silence is zeros at the rate and no resume point`() {
        val s = SentenceGaps.silence(750, 24_000)
        assertEquals(18_000, s.pcm.size)
        assertTrue(s.pcm.all { it == 0.toShort() })
        assertFalse(s.resumePoint)
    }

    @Test
    fun `at Natural, or with one paragraph, the text reaches the engine as one call`() = runTest {
        val calls = ArrayList<String>()
        val flow = flowOf(SynthAudio(ShortArray(3) { 1 }, 24_000))
        val render: (String) -> Flow<SynthAudio> = { calls += it; flow }
        assertSame(flow, SentenceGaps.paragraphs("One.\n\nTwo.", 0, render))
        assertSame(flow, SentenceGaps.paragraphs("One. Two.", 500, render))
        assertEquals(listOf("One.\n\nTwo.", "One. Two."), calls)
    }

    @Test
    fun `paragraphs render one per call with the paragraph pause between`() = runTest {
        val calls = ArrayList<String>()
        val out = SentenceGaps.paragraphs("One.\n\n  \n\nTwo.\n \nThree.", 500) {
            calls += it
            flowOf(SynthAudio(ShortArray(10) { 1 }, 24_000))
        }.toList()
        assertEquals(listOf("One.", "Two.", "Three."), calls)
        assertEquals(listOf(10, 18_000, 10, 18_000, 10), out.map { it.pcm.size })
    }

    @Test
    fun `the batched path joins paragraphs with the same pause`() = runTest {
        val joined = SentenceGaps.paragraphsWhole("One.\n\nTwo.", 1000) { SynthAudio(ShortArray(4) { 7 }, 24_000) }
        assertEquals(4 + 36_000 + 4, joined.pcm.size)
        assertEquals(7.toShort(), joined.pcm.last())
        val natural = SentenceGaps.paragraphsWhole("One.\n\nTwo.", 0) { SynthAudio(ShortArray(it.length), 24_000) }
        assertEquals("One.\n\nTwo.".length, natural.pcm.size)
    }
}
