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

    // -- planByTokens (token-sized plan, T3 + T4) ------------------------------

    /**
     * Fake encoder: one token per non-space character, three per CJK
     * character or mark (Kokoro measured ≈1.1 for English, ≈3.4 for
     * Chinese). Deterministic, so plans can be asserted exactly.
     */
    private val fakeTokens: (String) -> Int = { s ->
        s.fold(0) { n, c -> n + if (c.code >= 0x3000) 3 else if (c.isWhitespace()) 0 else 1 }
    }

    private val budget = TextChunker.TokenBudget(mergeFloor = 90, target = 200, firstPiece = 40, growth = 1.5)

    private fun plan(text: String, b: TextChunker.TokenBudget = budget) =
        TextChunker.planByTokens(text, b, fakeTokens)

    /** A clause of [words] four-letter words ending in [end]: 4 × words + 1 tokens. */
    private fun clause(words: Int, end: Char) = List(words) { "wxyz" }.joinToString(" ") + end

    private fun isHiragana(c: Char) = c in 'ぁ'..'ゟ'

    @Test
    fun planMergesTinySentencesButNotTheFirst() {
        assertEquals(
            listOf("First sentence.", "Second sentence. Third one."),
            plan("First sentence. Second sentence. Third one.").map { it.text },
        )
    }

    @Test
    fun planOnlyExemptsTheFirstParagraphsFirstSentence() {
        assertEquals(listOf("Hi.", "Ok. Fine."), plan("Hi.\n\nOk. Fine.").map { it.text })
    }

    @Test
    fun planMergeStopsAtTheTarget() {
        val big = clause(49, '.') // 197 tokens
        val within = clause(39, '.') // 157 tokens
        // 6 + 197 > 200: the tiny sentence stays alone rather than build a monster chunk.
        assertEquals(
            listOf("Opening line here.", "Brief.", big),
            plan("Opening line here. Brief. $big").map { it.text },
        )
        assertEquals(
            listOf("Opening line here.", "Brief. $within"),
            plan("Opening line here. Brief. $within").map { it.text },
        )
    }

    @Test
    fun planKeepsASentenceWithinToleranceWhole() {
        // 6 × 41 = 246 tokens: over the 200 target but within 1.35 × 200.
        val sentence = List(5) { clause(10, ',') }.joinToString(" ") + " " + clause(10, '.')
        assertEquals(listOf(sentence), plan(sentence).map { it.text })
    }

    @Test
    fun planCutsAnOverBudgetFirstSentenceSmallFirstThenGrows() {
        val first = List(7) { clause(10, ',') }.joinToString(" ") + " " + clause(10, '.') // 8 × 41 = 328
        val second = List(7) { clause(10, ',') }.joinToString(" ") + " " + clause(10, '.')
        val chunks = plan("$first $second")
        val firstPieces = chunks.takeWhile { !it.text.endsWith(".") } + chunks.first { it.text.endsWith(".") }
        // Small first piece = the first clause alone (41 tokens, over the 40 target: one clause is the floor).
        assertEquals(clause(10, ','), chunks[0].text)
        assertEquals(41, chunks[0].tokens)
        assertTrue(firstPieces.size >= 3)
        assertEquals(first, firstPieces.joinToString(" ") { it.text })
        assertTrue(chunks.all { it.tokens <= 200 })
        // The second over-budget sentence is not the request's first chunk: packed to target at once.
        val secondFirst = chunks[firstPieces.size]
        assertEquals(164, secondFirst.tokens)
        assertEquals(second, chunks.drop(firstPieces.size).joinToString(" ") { it.text })
    }

    @Test
    fun planCutsAChineseRunOnAtCommasWithASmallFirstPiece() {
        val clause = "今天天气很好我们，" // 27 tokens
        val sentence = clause.repeat(11) + "今天天气很好我们。" // 324 tokens
        val chunks = plan(sentence)
        assertTrue(chunks.size > 1)
        assertEquals(clause, chunks[0].text)
        assertTrue(chunks.all { it.text.endsWith("，") || it.text.endsWith("。") })
        assertTrue(chunks.all { it.tokens <= 200 })
        // No space is ever added to CJK text.
        assertEquals(sentence, chunks.joinToString("") { it.text })
    }

    @Test
    fun planSplitsSentencesAtFullWidthSemicolonAndColonAndJoinsCjkWithoutSpaces() {
        assertEquals(listOf("第一句；", "第二句：第三句。"), plan("第一句；第二句：第三句。").map { it.text })
        assertEquals(listOf("你好。", "谢谢。再见。"), plan("你好。谢谢。再见。").map { it.text })
    }

    @Test
    fun planNeverCutsJapaneseMidWordWhenAKanaKanjiBoundaryExists() {
        // No 、 at all: 97 chars ≈ 291 tokens, one clause over the target.
        val sentence = "東京に行きました".repeat(12) + "。"
        val chunks = plan(sentence)
        assertTrue(chunks.size > 1)
        for ((a, b) in chunks.zipWithNext()) {
            assertTrue("cut '${a.text}' | '${b.text}'", isHiragana(a.text.last()) && !isHiragana(b.text.first()))
        }
        assertEquals(sentence, chunks.joinToString("") { it.text })
    }

    @Test
    fun planHardCutsUnpunctuatedChineseWithoutDroppingText() {
        val sentence = "中文没有空格也没有标点".repeat(10) // 300 tokens, no marks, all Han
        val chunks = plan(sentence)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.tokens <= 200 })
        assertEquals(sentence, chunks.joinToString("") { it.text })
    }

    @Test
    fun planNeverCutsInsideAThousandsSeparator() {
        val sentence = List(80) { if (it % 10 == 5) "1,000" else "wxyz" }.joinToString(" ") + "."
        val chunks = plan(sentence)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.none { it.text.endsWith("1,") || it.text.startsWith("000") })
    }

    @Test
    fun planRejoinsARuntTail() {
        val tight = TextChunker.TokenBudget(mergeFloor = 0, target = 20, firstPiece = 20, growth = 1.0)
        val a = "a".repeat(19) + ","
        val b = "b".repeat(19) + ","
        assertEquals(listOf(a, "$b cc."), plan("$a $b cc.", tight).map { it.text })
    }

    @Test
    fun planOfBlankTextIsEmpty() {
        assertTrue(plan("  \n ").isEmpty())
    }
}
