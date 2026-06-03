package app.marmalade.tts.engine.kokoro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [LexiconZh] — the sherpa-style Mandarin phrase matcher.
 *
 * Uses a tiny hand-written fixture (5 entries) rather than the real ~68k-entry
 * lexicon-zh.txt so the test is fast, deterministic, and doesn't depend on a
 * bundle download. The token IDs below are the real IpaTokenVocab assignments:
 *   n=56  i=51  x=66  a=43  u=63  ʂ=130  ɨ=101  ʨ=21  j=52  e=47
 *   ↓=169  →=171  ↘=173
 */
class LexiconZhTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Fixture lexicon. `你好` deliberately uses level tone `→` (171) where the
     * per-character `你`+`好` entries use falling tone `↓` (169) — so a greedy
     * 2-char phrase match produces a *different* token sequence than char-by-
     * char, proving the matcher prefers the longer key.
     */
    private fun fixture(): File {
        val f = tmp.newFile("lexicon-zh.txt")
        f.writeText(
            """
            你 n i ↓
            好 x a u ↓
            你好 n i → x a u →
            世 ʂ ɨ ↘
            界 ʨ j e ↘
            """.trimIndent() + "\n",
        )
        return f
    }

    @Test
    fun parsesAllEntries() {
        val lex = LexiconZh(fixture())
        assertEquals(5, lex.size())
        assertEquals("longest key is the 2-char phrase", 2, lex.maxKeyLen)
    }

    @Test
    fun singleCharLookupResolvesPhonemeTokens() {
        val lex = LexiconZh(fixture())
        // 你 → n i ↓ → [56, 51, 169]
        assertArrayEquals(intArrayOf(56, 51, 169), lex.match("你"))
        // 好 → x a u ↓ → [66, 43, 63, 169]
        assertArrayEquals(intArrayOf(66, 43, 63, 169), lex.match("好"))
    }

    @Test
    fun greedyMatchPrefersLongerPhrase() {
        val lex = LexiconZh(fixture())
        // 你好 has its own entry with level tones (→ = 171), distinct from
        // 你 + 好 (which would give ↓ = 169). The matcher must pick the phrase.
        assertArrayEquals(intArrayOf(56, 51, 171, 66, 43, 63, 171), lex.match("你好"))
    }

    @Test
    fun phraseThenCharFallsBackCorrectly() {
        val lex = LexiconZh(fixture())
        // 你好你: greedy 2-char phrase 你好, then single char 你 (no 3-char entry).
        // Phrase tokens (→ 171) followed by char tokens (↓ 169).
        assertArrayEquals(
            intArrayOf(56, 51, 171, 66, 43, 63, 171, /* 你 */ 56, 51, 169),
            lex.match("你好你"),
        )
    }

    @Test
    fun multiCharNonPhraseSplitsPerChar() {
        val lex = LexiconZh(fixture())
        // 世界 has no phrase entry — falls back to 世 + 界.
        assertArrayEquals(
            intArrayOf(/* 世 */ 130, 101, 173, /* 界 */ 21, 52, 47, 173),
            lex.match("世界"),
        )
    }

    @Test
    fun unmappedHanEmitsPadToken() {
        val lex = LexiconZh(fixture())
        // 龘 is a valid CJK char but absent from the fixture — emits PAD (0).
        assertArrayEquals(intArrayOf(0), lex.match("龘"))
    }

    @Test
    fun emptyRunIsEmpty() {
        val lex = LexiconZh(fixture())
        assertArrayEquals(IntArray(0), lex.match(""))
    }

    @Test
    fun cjkRunPatternExtractsHanRuns() {
        // The engine uses this to split mixed text; only Han runs hit the lexicon.
        val matches = LexiconZh.CJK_RUN_PATTERN.findAll("Hello 你好, world 世界!")
            .map { it.value }
            .toList()
        assertEquals(listOf("你好", "世界"), matches)
    }

    @Test
    fun cjkRunPatternIgnoresPureLatin() {
        assertTrue(
            "no Han chars means no CJK runs",
            !LexiconZh.CJK_RUN_PATTERN.containsMatchIn("Hello, world!"),
        )
    }
}
