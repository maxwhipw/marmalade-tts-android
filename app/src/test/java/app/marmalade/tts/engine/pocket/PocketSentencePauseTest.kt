package app.marmalade.tts.engine.pocket

import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.engine.PocketEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** #96: Pocket's chunk plan at Natural is exactly the pre-#96 one; with a pause, tiny chunks merge only short of a real sentence end. */
class PocketSentencePauseTest {

    private val texts = listOf(
        "Yes. No. Maybe. The storm reached the harbour just after midnight.",
        "By morning the old pier was gone, and the boats lay scattered along the beach. Still, everyone was safe.",
        "One line\nAnother line: with a colon; and a semicolon. Then a question? And an answer!",
        "Short.",
        "See Fig. 3 for the data. It is clear.",
        "Note: this; that. Next one here.",
        "The U.S. Army came. They left.",
    )

    /** The call PocketEngine made before #96, verbatim. */
    private fun before(text: String) = TextChunker.chunk(
        text = text,
        maxChars = 255,
        packSentences = false,
        sentenceOnly = true,
        allowWordSplits = false,
        minChars = 40,
    )

    @Test
    fun `at Natural the chunk plan is unchanged`() {
        for (t in texts) assertEquals(t, before(t), PocketEngine.pocketOuterChunks(t, 0))
    }

    @Test
    fun `with a pause tiny sentences no longer merge across a sentence end`() {
        assertEquals(
            listOf("Yes.", "No.", "Maybe.", "The storm reached the harbour just after midnight."),
            PocketEngine.pocketOuterChunks(texts[0], 500),
        )
        assertEquals(
            listOf("By morning the old pier was gone, and the boats lay scattered along the beach.", "Still, everyone was safe."),
            PocketEngine.pocketOuterChunks(texts[1], 250),
        )
    }

    @Test
    fun `with a pause abbreviations and clause marks still merge`() {
        assertEquals(listOf("See Fig. 3 for the data.", "It is clear."), PocketEngine.pocketOuterChunks(texts[4], 500))
        assertEquals(listOf("Note: this; that.", "Next one here."), PocketEngine.pocketOuterChunks(texts[5], 500))
        assertEquals(listOf("The U.S. Army came.", "They left."), PocketEngine.pocketOuterChunks(texts[6], 500))
        // At Natural both are one chunk, as before.
        assertEquals(listOf(texts[4]), PocketEngine.pocketOuterChunks(texts[4], 0))
    }
}
