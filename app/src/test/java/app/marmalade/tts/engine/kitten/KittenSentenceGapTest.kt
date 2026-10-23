package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertEquals
import org.junit.Test

/** #96: the alias's pause rides on Kitten's sentence gap; Natural leaves both gaps as they were. */
class KittenSentenceGapTest {

    @Test
    fun `at Natural the gaps are the pre-#96 ones`() {
        assertEquals(260, kittenGapMs(sentenceEnd = true, sentencePauseMs = 0))
        assertEquals(80, kittenGapMs(sentenceEnd = false, sentencePauseMs = 0))
    }

    @Test
    fun `a pause lengthens the sentence gap only`() {
        assertEquals(760, kittenGapMs(sentenceEnd = true, sentencePauseMs = 500))
        assertEquals(80, kittenGapMs(sentenceEnd = false, sentencePauseMs = 500))
    }
}
