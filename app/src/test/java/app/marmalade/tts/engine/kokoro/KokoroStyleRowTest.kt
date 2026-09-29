package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.audio.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Test

/** [kokoroStyleRow]: which voices.bin row a chunk's model calls use (T6). */
class KokoroStyleRowTest {

    @Test
    fun wholeChunkUsesItsOwnTokenCount() {
        assertEquals(129, kokoroStyleRow(chunkTokens = 129, sentenceTokens = null))
    }

    @Test
    fun pieceCutFromASentenceUsesTheWholeSentencesRow() {
        // The first piece of a 145-token sentence (34 tokens) must not get the
        // short-utterance row 34.
        assertEquals(145, kokoroStyleRow(chunkTokens = 34, sentenceTokens = 145))
    }

    @Test
    fun runOnPastTheTableUsesTheLastRow() {
        assertEquals(509, kokoroStyleRow(chunkTokens = 200, sentenceTokens = 715))
        assertEquals(509, kokoroStyleRow(chunkTokens = 715, sentenceTokens = null))
    }

    @Test
    fun everyPieceOfAPlannedSentenceSharesOneRow() {
        val clause = "今天天气很好我们，"
        val sentence = clause.repeat(11) + "今天天气很好我们。"
        val count: (String) -> Int = { it.length * 3 }
        val budget = TextChunker.TokenBudget(mergeFloor = 90, target = 200, firstPiece = 40, growth = 1.5)
        val chunks = TextChunker.planByTokens(sentence, budget, count = count)
        val rows = chunks.map { kokoroStyleRow(it.tokens, it.rowTokens) }.toSet()
        assertEquals(setOf(kokoroStyleRow(count(sentence), null)), rows)
    }
}
