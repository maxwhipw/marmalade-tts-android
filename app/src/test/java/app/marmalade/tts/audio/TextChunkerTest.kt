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

    // -- terminalMarksOnly (R16 per-sentence mode) ----------------------------

    @Test
    fun terminalMarksOnlyKeepsColonAndSemicolonInSentence() {
        // Rows are computed per sentence; a `:`/`;` split would compute
        // them on fragments and re-register mid-sentence.
        val chunks = TextChunker.chunk(
            text = "Look at the panel: is it blinking; or not? All good.",
            maxChars = 255,
            packSentences = false,
            allowWordSplits = false,
            terminalMarksOnly = true,
        )
        assertEquals(
            listOf("Look at the panel: is it blinking; or not?", "All good."),
            chunks,
        )
    }

    @Test
    fun terminalMarksOnlySplitsEverySentence() {
        val chunks = TextChunker.chunk(
            text = "Stop! Don't touch that wire. Take three steps back, slowly.",
            maxChars = 255,
            packSentences = false,
            allowWordSplits = false,
            terminalMarksOnly = true,
        )
        assertEquals(
            listOf("Stop!", "Don't touch that wire.", "Take three steps back, slowly."),
            chunks,
        )
    }

    @Test
    fun terminalMarksOnlySplitsOnNewlines() {
        val chunks = TextChunker.chunk(
            text = "First line\nsecond line",
            maxChars = 255,
            packSentences = false,
            allowWordSplits = false,
            terminalMarksOnly = true,
        )
        assertEquals(listOf("First line", "second line"), chunks)
    }

    @Test
    fun terminalMarksOnlyKeepsMarkInsideClosingQuoteAttached() {
        // '?" she asked.' — the mark sits inside the quotes, so the
        // quoted sentence stays with its attribution (CLI run-split
        // behaviour: the lookbehind sees the quote, not the mark).
        val chunks = TextChunker.chunk(
            text = "\"Where did you put the keys?\" she asked. He shrugged.",
            maxChars = 255,
            packSentences = false,
            allowWordSplits = false,
            terminalMarksOnly = true,
        )
        assertEquals(
            listOf("\"Where did you put the keys?\" she asked.", "He shrugged."),
            chunks,
        )
    }

    // -- splitToFit (engine token-cap fallback) --------------------------------

    /** Stand-in for "phonemizes to ≤ cap tokens": a plain char budget. */
    private fun fitsIn(n: Int): (String) -> Boolean = { it.length <= n }

    @Test
    fun splitToFitReturnsTextThatFitsUntouched() {
        assertEquals(listOf("Short enough."), TextChunker.splitToFit("  Short enough. ", fitsIn(50)))
    }

    @Test
    fun splitToFitPrefersCommasAndRepacksClauses() {
        val text = "one two three, four five six, seven eight nine, ten eleven twelve."
        val pieces = TextChunker.splitToFit(text, fitsIn(32))
        // Clauses re-pack while they fit; every cut lands after a comma.
        assertEquals(
            listOf("one two three, four five six,", "seven eight nine,", "ten eleven twelve."),
            pieces,
        )
    }

    @Test
    fun splitToFitDoesNotCutDigitCommasOrClockColons() {
        val text = "It cost 1,000 dollars at 10:30 and nobody minded at all"
        val pieces = TextChunker.splitToFit(text, fitsIn(30))
        assertTrue(pieces.none { it.endsWith("1,") || it.endsWith("10:") })
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun splitToFitCutsChineseAtFullwidthCommaWithoutWhitespace() {
        val text = "今天天气很好，我们去公园散步，然后回家吃饭。"
        val pieces = TextChunker.splitToFit(text, fitsIn(8))
        assertEquals(listOf("今天天气很好，", "我们去公园散步，", "然后回家吃饭。"), pieces)
    }

    @Test
    fun splitToFitCutsJapaneseAtIdeographicComma() {
        val text = "雨が降っていたので、傘を持って出かけたが、途中で止んだ。"
        val pieces = TextChunker.splitToFit(text, fitsIn(12))
        assertEquals(listOf("雨が降っていたので、", "傘を持って出かけたが、", "途中で止んだ。"), pieces)
    }

    @Test
    fun splitToFitHardSplitsUnpunctuatedCjkWithoutDroppingText() {
        val text = "中文没有空格也没有标点的一段很长的句子需要硬切分才能放进模型"
        val pieces = TextChunker.splitToFit(text, fitsIn(10))
        assertTrue(pieces.all { it.length <= 10 })
        assertEquals(text, pieces.joinToString(""))
    }

    @Test
    fun splitToFitFallsBackToWordsForAnUnpunctuatedRunOn() {
        val text = List(40) { "word$it" }.joinToString(" ")
        val pieces = TextChunker.splitToFit(text, fitsIn(50))
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 50 })
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun splitToFitNeverSplitsASurrogatePair() {
        val text = "😀".repeat(9)
        val pieces = TextChunker.splitToFit(text, fitsIn(5))
        assertTrue(pieces.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        assertEquals(text, pieces.joinToString(""))
    }
}
