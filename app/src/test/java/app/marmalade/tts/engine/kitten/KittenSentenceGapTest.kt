package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertEquals
import org.junit.Test

/** #96: the alias's pause rides on Kitten's own gap after a chunk; with none, both gaps are as they were. */
class KittenSentenceGapTest {

    @Test
    fun `with no pause the gaps are the pre-#96 ones`() {
        assertEquals(260, kittenGapMs(sentenceEnd = true, pauseMs = 0))
        assertEquals(80, kittenGapMs(sentenceEnd = false, pauseMs = 0))
    }

    @Test
    fun `the pause that follows a chunk adds to its gap`() {
        assertEquals(760, kittenGapMs(sentenceEnd = true, pauseMs = 500))
        // A chunk the text says takes a pause (a paragraph's last line, say) gets it whatever its own gap.
        assertEquals(830, kittenGapMs(sentenceEnd = false, pauseMs = 750))
    }
}
