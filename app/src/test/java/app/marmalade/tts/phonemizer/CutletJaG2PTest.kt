package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CutletJaG2P] — the katakana-reading → IPA conversion that
 * matches Kokoro v1.0's cutlet training G2P.
 *
 * Each oracle was captured from misaki's real `cutlet` output (see the v0.3.0
 * -alpha.10.V investigation). We feed the converter a *controlled* katakana
 * reading and assert the cutlet IPA — this isolates the conversion logic we
 * ported from the separate (accepted) concern that Open JTalk's naist-jdic
 * readings can differ from cutlet's fugashi+unidic readings for some words
 * (e.g. 日本語 → ニホンゴ vs ニッポンゴ).
 */
class CutletJaG2PTest {

    /** Build a single word node carrying [pron] as its katakana reading. */
    private fun word(pron: String, pos: String = "名詞") =
        NjdNode(string = pron, read = pron, pron = pron, acc = 0, moraSize = 0, chainFlag = 0, pos = pos)

    private fun ipaOf(pron: String): String = CutletJaG2P.convert(listOf(word(pron)))

    @Test
    fun basicWord_konnichiwa() {
        // コンニチワ → ん before に assimilates to ɲ; わ → βa.
        assertEquals("koɲɲiʨiβa", ipaOf("コンニチワ"))
    }

    @Test
    fun moraicN_beforeKG_isVelar() {
        // ゲンキ: ん before き (kʲi) → ŋ.
        assertEquals("ɡeŋkʲi", ipaOf("ゲンキ"))
        // リンゴ: ん before ご (ɡo) → ŋ.
        assertEquals("ɾʲiŋɡo", ipaOf("リンゴ"))
    }

    @Test
    fun moraicN_beforeLabial_isM() {
        // シンブン: first ん before ぶ (bɯ) → m; final ん → ɴ.
        assertEquals("ɕimbɯɴ", ipaOf("シンブン"))
        // サンマ: ん before ま (ma) → m.
        assertEquals("samma", ipaOf("サンマ"))
    }

    @Test
    fun moraicN_finalOrBeforeVowel_isUvular() {
        // センセー: ん before せ (se) → ɴ (s not in the n-assimilation set); ー → ː.
        assertEquals("seɴseː", ipaOf("センセー"))
    }

    @Test
    fun sokuon_becomesGlottalStop() {
        // ニッポンゴ: っ → ʔ; ん before ご → ŋ.
        assertEquals("ɲiʔpoŋɡo", ipaOf("ニッポンゴ"))
        // ガッコー: っ → ʔ; ー → ː.
        assertEquals("ɡaʔkoː", ipaOf("ガッコー"))
    }

    @Test
    fun longVowelMark_becomesColon() {
        // トーキョー: ー → ː; きょ digraph → kʲo.
        assertEquals("toːkʲoː", ipaOf("トーキョー"))
    }

    @Test
    fun digraphs_palatalAndLong() {
        // ギューニュー: ギュ → ɡʲɨ, ー → ː, ニュ → ɲɨ, ー → ː.
        assertEquals("ɡʲɨːɲɨː", ipaOf("ギューニュー"))
    }

    @Test
    fun punctuation_gluesAndSpaces() {
        // Word, comma, word — comma glues to the preceding token, space after.
        val nodes = listOf(
            word("ゲンキ"),
            NjdNode("、", "、", "、", 0, 0, 0, "記号"),
            word("デス"),
        )
        assertEquals("ɡeŋkʲi, desɨ", CutletJaG2P.convert(nodes))
    }

    @Test
    fun asciiSurfacePassesThrough() {
        // Latin loanword node (Open JTalk reads ASCII char-by-char).
        val node = NjdNode("OK", "OK", "", 0, 0, 0, "名詞")
        assertEquals("OK", CutletJaG2P.convert(listOf(node)))
    }

    @Test
    fun multiWordSentence_spacing() {
        // こんにちは、元気ですか — full sentence with the readings Open JTalk gives.
        val nodes = listOf(
            word("コンニチワ", "感動詞"),
            NjdNode("、", "、", "、", 0, 0, 0, "記号"),
            word("ゲンキ"),
            word("デス", "助動詞"),
            word("カ", "助詞"),
            NjdNode("？", "？", "？", 0, 0, 0, "記号"),
        )
        // Words space-separated; punctuation glued; trailing space trimmed.
        assertEquals("koɲɲiʨiβa, ɡeŋkʲi desɨ ka?", CutletJaG2P.convert(nodes))
    }

    @Test
    fun fullwidthAsciiPunctuationFolded() {
        // Open JTalk normalizes ASCII punct to fullwidth: . → ．(U+FF0E),
        // ? → ？(U+FF1F). These must fold to ASCII (real vocab tokens), not
        // fall through to PAD. ？ glues + trailing-space (sentence stop).
        val nodes = listOf(
            word("ゲンキ"),
            NjdNode("．", "．", "．", 0, 0, 0, "記号"), // fullwidth period
        )
        assertEquals("ɡeŋkʲi.", CutletJaG2P.convert(nodes))

        val q = listOf(
            word("ゲンキ"),
            NjdNode("？", "？", "？", 0, 0, 0, "記号"), // fullwidth question
        )
        assertEquals("ɡeŋkʲi?", CutletJaG2P.convert(q))
    }

    @Test
    fun emptyInput_isEmpty() {
        assertEquals("", CutletJaG2P.convert(emptyList()))
    }

    @Test
    fun devoicingMarkStripped() {
        // Open JTalk marks devoiced vowels with ’ (U+2019); cutlet readings
        // don't have it, so we strip before conversion. デス’ → desɨ.
        assertEquals("desɨ", ipaOf("デス’"))
    }
}
