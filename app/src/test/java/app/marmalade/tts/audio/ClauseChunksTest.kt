package app.marmalade.tts.audio

import app.marmalade.tts.audio.TextChunker.ClauseChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The F chunking rules (Max's 2026-08-07 ear-lab pick). Fixtures mirror
 * the lab's plans (`~/coding/scratch/kitten-clause-split/plans.json`,
 * variant `tunedGaps`) so the app provably chunks like the audited wavs.
 */
class ClauseChunksTest {

    private fun texts(chunks: List<ClauseChunk>) = chunks.map { it.text }

    @Test
    fun `lighthouse - quote-aware end + dialogue intro`() {
        val chunks = TextChunker.clauseChunks(
            "Then the lighthouse keeper said, \"The ship is coming too close " +
                "to the shoreline!\" Everyone ran for the rocks below: the horn " +
                "kept sounding; the beam swept the bay; and the crew finally " +
                "turned hard to starboard just before the shallows.",
        )
        assertEquals(
            listOf(
                "Then the lighthouse keeper said,",
                "\"The ship is coming too close to the shoreline!\"",
                "Everyone ran for the rocks below:",
                "the horn kept sounding;",
                "the beam swept the bay;",
                "and the crew finally turned hard to starboard just before the shallows.",
            ),
            texts(chunks),
        )
        // Dialogue intro = clause boundary; the quote ends its sentence.
        assertFalse(chunks[0].sentenceEnd)
        assertTrue(chunks[1].sentenceEnd)
        // Colon/semicolons = clause boundaries; nothing after the last chunk.
        assertEquals(listOf(false, false, false), chunks.subList(2, 5).map { it.sentenceEnd })
        assertFalse(chunks.last().sentenceEnd)
        // Every fragment keeps its pre-split sentence's row text.
        assertEquals(chunks[0].rowText, chunks[1].rowText)
        assertTrue(chunks[0].rowText.endsWith("shoreline!\""))
        assertTrue(chunks[3].rowText.startsWith("Everyone ran"))
        assertEquals(chunks[3].rowText, chunks[5].rowText)
    }

    @Test
    fun `attribution stays attached - lowercase after closing quote`() {
        assertEquals(
            listOf("\"Stop!\" he shouted.", "Then he ran."),
            texts(TextChunker.clauseChunks("\"Stop!\" he shouted. Then he ran.")),
        )
    }

    @Test
    fun `capitalized word after closing quote cuts`() {
        assertEquals(
            listOf("\"Stop!\"", "Marmalade said.", "Then he ran."),
            texts(TextChunker.clauseChunks("\"Stop!\" Marmalade said. Then he ran.")),
        )
    }

    @Test
    fun `psalm - every clause mark is a boundary, no merging`() {
        val chunks = TextChunker.clauseChunks(
            "The Lord is my shepherd; I shall not want. He maketh me to lie " +
                "down in green pastures: he leadeth me beside the still waters.",
        )
        assertEquals(
            listOf(
                "The Lord is my shepherd;",
                "I shall not want.",
                "He maketh me to lie down in green pastures:",
                "he leadeth me beside the still waters.",
            ),
            texts(chunks),
        )
        assertEquals(listOf(false, true, false, false), chunks.map { it.sentenceEnd })
        assertEquals("The Lord is my shepherd; I shall not want.", chunks[0].rowText)
        assertEquals(chunks[0].rowText, chunks[1].rowText)
    }

    @Test
    fun `list - newline splits, trailing comma is a clause boundary`() {
        val chunks = TextChunker.clauseChunks(
            "Pack the following supplies:\n" +
                "rope and carabiners,\n" +
                "three lanterns,\n" +
                "and the spare compass.\n" +
                "When everything is loaded, meet me at the dock.",
        )
        assertEquals(
            listOf(
                "Pack the following supplies:",
                "rope and carabiners,",
                "three lanterns,",
                "and the spare compass.",
                "When everything is loaded, meet me at the dock.",
            ),
            texts(chunks),
        )
        // Colon line + period line = sentence gaps; comma lines = clause gaps.
        assertEquals(listOf(true, false, false, true, false), chunks.map { it.sentenceEnd })
    }

    @Test
    fun `mid-sentence commas never split`() {
        val chunks = TextChunker.clauseChunks(
            "Yea, though I walk through the valley, I will fear no evil.",
        )
        assertEquals(1, chunks.size)
    }

    // #124: an abbreviation's period (SentenceGaps.isAbbreviation) ends no
    // sentence. Before, "Prof." was a chunk of its own with a sentence gap
    // after it, and its style row came from "Prof." alone.

    @Test
    fun `an abbreviation's period does not end the sentence`() {
        val chunks = TextChunker.clauseChunks(
            "Prof. Smith arrived. See Fig. 3 for details. J. R. Tolkien wrote it.",
        )
        assertEquals(
            listOf("Prof. Smith arrived.", "See Fig. 3 for details.", "J. R. Tolkien wrote it."),
            texts(chunks),
        )
        assertEquals("Prof. Smith arrived.", chunks[0].rowText)
        assertEquals(listOf(true, true, false), chunks.map { it.sentenceEnd })
    }

    @Test
    fun `a one-letter word that is no initial still ends the sentence`() {
        assertEquals(
            listOf("Neither did I.", "Then we left.", "See p. 12 here."),
            texts(TextChunker.clauseChunks("Neither did I. Then we left. See p. 12 here.")),
        )
    }

    @Test
    fun `a line break after an abbreviation still splits`() {
        assertEquals(
            listOf("Ask Prof.", "Smith knows."),
            texts(TextChunker.clauseChunks("Ask Prof.\nSmith knows.")),
        )
    }

    @Test
    fun `blank input yields no chunks`() {
        assertEquals(emptyList<ClauseChunk>(), TextChunker.clauseChunks("   "))
    }

    @Test
    fun `single sentence has no trailing gap`() {
        val chunks = TextChunker.clauseChunks("Hello there.")
        assertEquals(1, chunks.size)
        assertFalse(chunks.single().sentenceEnd)
    }
}
