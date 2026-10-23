package app.marmalade.tts.engine.pocket

import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.engine.PocketEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** #96: Pocket's chunk plan at Natural is exactly the pre-#96 one; with a pause every sentence is its own chunk. */
class PocketSentencePauseTest {

    private val texts = listOf(
        "Yes. No. Maybe. The storm reached the harbour just after midnight.",
        "By morning the old pier was gone, and the boats lay scattered along the beach. Still, everyone was safe.",
        "One line\nAnother line: with a colon; and a semicolon. Then a question? And an answer!",
        "Short.",
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
    fun `with a pause tiny sentences are no longer merged`() {
        assertEquals(
            listOf("Yes.", "No.", "Maybe.", "The storm reached the harbour just after midnight."),
            PocketEngine.pocketOuterChunks(texts[0], 500),
        )
        assertEquals(
            listOf("By morning the old pier was gone, and the boats lay scattered along the beach.", "Still, everyone was safe."),
            PocketEngine.pocketOuterChunks(texts[1], 250),
        )
    }
}
