package app.marmalade.tts.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TextChunker], focused on the CJK sentence-splitting added in
 * alpha.10.X plus regression coverage that the ASCII behaviour is unchanged.
 */
class TextChunkerTest {

    // -- CJK splitting (alpha.10.X) -------------------------------------------

    @Test
    fun japaneseSentencesSplitOnIdeographicStop_noWhitespace() {
        // 。 takes no following space in Japanese — must still split.
        val chunks = TextChunker.chunk(
            text = "これはテストです。元気ですか。さようなら。",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
        )
        assertEquals(
            listOf("これはテストです。", "元気ですか。", "さようなら。"),
            chunks,
        )
    }

    @Test
    fun japaneseFullwidthQuestionAndBangSplit() {
        val chunks = TextChunker.chunk(
            text = "元気ですか？はい！",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
        )
        assertEquals(listOf("元気ですか？", "はい！"), chunks)
    }

    @Test
    fun japaneseCommaDoesNotSplit() {
        // 、 is a clause comma, not a sentence end — stays in one chunk
        // (mirrors ASCII comma behaviour in sentenceOnly mode).
        val chunks = TextChunker.chunk(
            text = "こんにちは、元気ですか。",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
        )
        assertEquals(listOf("こんにちは、元気ですか。"), chunks)
    }

    // -- ASCII regression -----------------------------------------------------

    @Test
    fun englishStillSplitsOnPeriodSpace() {
        val chunks = TextChunker.chunk(
            text = "First sentence. Second sentence. Third one.",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
        )
        assertEquals(
            listOf("First sentence.", "Second sentence.", "Third one."),
            chunks,
        )
    }

    @Test
    fun shortSingleSentenceIsOneChunk() {
        assertEquals(
            listOf("Hello there."),
            TextChunker.chunk("Hello there.", maxChars = 255),
        )
    }

    @Test
    fun blankInputIsEmpty() {
        assertTrue(TextChunker.chunk("   ", maxChars = 255).isEmpty())
    }

    // -- minChars merge + first-chunk exemption (TTFA) ------------------------

    @Test
    fun minCharsMergesTinySentences() {
        val chunks = TextChunker.chunk(
            text = "Yes. Sure thing. Absolutely, whenever you like it best.",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = 20,
        )
        assertEquals(
            listOf("Yes. Sure thing. Absolutely, whenever you like it best."),
            chunks,
        )
    }

    @Test
    fun exemptFirstEmitsShortOpeningSentenceAlone() {
        val chunks = TextChunker.chunk(
            text = "Yes. Sure thing. Absolutely, whenever you like it best.",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = 20,
            minCharsExemptFirst = true,
        )
        assertEquals(
            listOf("Yes.", "Sure thing. Absolutely, whenever you like it best."),
            chunks,
        )
    }

    @Test
    fun exemptFirstAppliesOnlyToFirstParagraph() {
        val chunks = TextChunker.chunk(
            text = "Hi. Second sentence here padding along.\n\nOk. Later paragraph sentence padding.",
            // Force past the single-chunk fast path so paragraphs split.
            maxChars = 60,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = 20,
            minCharsExemptFirst = true,
        )
        assertEquals(
            listOf(
                "Hi.",
                "Second sentence here padding along.",
                "Ok. Later paragraph sentence padding.",
            ),
            chunks,
        )
    }

    @Test
    fun exemptFirstIsNoOpWhenFirstSentenceAlreadyLong() {
        val long = "This opening sentence is comfortably longer than the merge floor."
        val chunks = TextChunker.chunk(
            text = "$long Short tail.",
            maxChars = 255,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = 20,
            minCharsExemptFirst = true,
        )
        assertEquals(listOf(long, "Short tail."), chunks)
    }
}
