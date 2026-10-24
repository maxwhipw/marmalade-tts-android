package app.marmalade.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceGapsTest {

    @Test
    fun `a real sentence end takes the pause`() {
        assertTrue(SentenceGaps.isSentenceBreak("It was late.", "The end"))
        assertTrue(SentenceGaps.isSentenceBreak("\"Stop!\"", "He ran"))
        assertTrue(SentenceGaps.isSentenceBreak("Really?)", "Yes."))
        assertTrue(SentenceGaps.isSentenceBreak("And then…", "Nothing."))
        assertTrue(SentenceGaps.isSentenceBreak("終わり。", "次"))
        assertTrue(SentenceGaps.isSentenceBreak("यह है।", "वह"))
        assertTrue(SentenceGaps.isSentenceBreak("Yes.", "“No,” she said."))
    }

    @Test
    fun `clause marks, word cuts and the text's end take none`() {
        assertFalse(SentenceGaps.isSentenceBreak("First, the eggs;", "Then"))
        assertFalse(SentenceGaps.isSentenceBreak("He said:", "Go"))
        assertFalse(SentenceGaps.isSentenceBreak("apples,", "Pears"))
        assertFalse(SentenceGaps.isSentenceBreak("a sentence cut at a word", "Boundary"))
        assertFalse(SentenceGaps.isSentenceBreak("It was late.", "  "))
        assertFalse(SentenceGaps.isSentenceBreak("   ", "A"))
    }

    @Test
    fun `abbreviations, initials and a lower-case or digit start take none`() {
        assertFalse(SentenceGaps.isSentenceBreak("See Fig.", "3 for details."))
        assertFalse(SentenceGaps.isSentenceBreak("as in Fig.", "Three"))
        assertFalse(SentenceGaps.isSentenceBreak("It was No.", "5 on the list."))
        assertFalse(SentenceGaps.isSentenceBreak("on p.", "12."))
        assertFalse(SentenceGaps.isSentenceBreak("It weighs approx.", "5 kg."))
        assertFalse(SentenceGaps.isSentenceBreak("I met Dr.", "Smith."))
        assertFalse(SentenceGaps.isSentenceBreak("(e.g.", "Rome)"))
        assertFalse(SentenceGaps.isSentenceBreak("by J.", "Smith"))
        assertFalse(SentenceGaps.isSentenceBreak("The U.S.", "Army came."))
        assertFalse(SentenceGaps.isSentenceBreak("(i.e.", "Rome)"))
        assertFalse(SentenceGaps.isSentenceBreak("Well…", "maybe not."))
        assertFalse(SentenceGaps.isSentenceBreak("“Wait!”", "she said."))
    }

    @Test
    fun `at Natural byParagraph is the one plan, every pause 0`() {
        val text = "One. Two.\n\nThree."
        val plan = SentenceGaps.byParagraph(text, 0) { t, first -> listOf("$first:$t") }
        assertEquals(listOf("true:$text" to 0), plan)
    }

    @Test
    fun `byParagraph chunks each paragraph and pauses at real breaks and blank lines`() {
        val text = "One. See Fig.\n3 here. Two!\n\n  \n\nThree."
        val plan = SentenceGaps.byParagraph(text, 500) { t, _ -> t.split(Regex("(?<=[.!])\\s+")) }
        assertEquals(
            listOf("One." to 500, "See Fig." to 0, "3 here." to 500, "Two!" to 750, "Three." to 0),
            plan,
        )
    }

    @Test
    fun `the follower reads each piece's pause off the text after it`() {
        val text = "It was late.  See Fig. 3 now.\n\nNext para, here. Last…"
        val f = SentenceGaps.Follower(text, 250)
        assertEquals(250, f.pauseAfter("It was late."))
        assertEquals(0, f.pauseAfter("See Fig."))
        assertEquals(375, f.pauseAfter("3 now."))
        assertEquals(0, f.pauseAfter("Next para,"))
        assertEquals(250, f.pauseAfter("here."))
        assertEquals(0, f.pauseAfter("Last…"))
    }

    @Test
    fun `the follower matches pieces the chunker trimmed and joined`() {
        val f = SentenceGaps.Follower("Yes.\n No.   Maybe.\n\nNext.", 500)
        assertEquals(500, f.pauseAfter("Yes. No.")) // merged sentences advance as one piece
        assertEquals(750, f.pauseAfter("Maybe."))
        assertEquals(0, f.pauseAfter("Next."))
    }

    @Test
    fun `a piece the follower can't place falls back to the sentence rule`() {
        val f = SentenceGaps.Follower("Hello there. General.", 500)
        assertEquals(500, f.pauseAfter("Rewritten text."))
        assertEquals(0, f.pauseAfter("a clause,"))
    }

    @Test
    fun `at Natural the follower adds nothing`() {
        val f = SentenceGaps.Follower("One.\n\nTwo.", 0)
        assertEquals(0, f.pauseAfter("One."))
    }

    @Test
    fun `inner breaks list each boundary inside a piece in order`() {
        assertEquals(listOf(true, false, true, true), SentenceGaps.innerBreaks("Yes. See Fig. 3 here. Done! Now."))
        assertEquals(emptyList<Boolean>(), SentenceGaps.innerBreaks("It cost 3.5 dollars."))
        assertEquals(listOf(true), SentenceGaps.innerBreaks("文。文。"))
    }

    @Test
    fun `silence is added zeros at the rate and no resume point`() {
        val s = SentenceGaps.silence(250, 24_000)
        assertEquals(6_000, s.pcm.size)
        assertTrue(s.pcm.all { it == 0.toShort() })
        assertFalse(s.resumePoint)
        assertEquals(6_000, s.addedSilence)
        assertEquals(0.0, s.renderedMs, 0.0)
    }
}
