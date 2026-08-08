package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port-fidelity vectors captured on desktop 2026-08-07: `tied` is raw
 * espeak-ng 1.52 output with espeakPHONEMES_TIE('^') (exactly what
 * `nativePhonemize(text, tie = true)` returns), `expected` is real
 * misaki 0.9 `EspeakG2P(version=None)` output for the same text — the
 * G2P Kokoro v1.0 was trained with. Generator:
 * ~/coding/scratch/lang-accent-lab/ (kokoro venv).
 */
class KokoroEspeakG2PTest {

    private fun check(tied: String, expected: String) =
        assertEquals(expected, KokoroEspeakG2P.postprocess(tied))

    @Test
    fun `italian affricates collapse to trained tokens`() {
        // "pizza e ciao ragazzi" — ʦ and ʧ are single vocab tokens
        check("pˈit^sːa e t^ʃˈao raɡˈat^sːɪ", "pˈiʦːa e ʧˈao raɡˈaʦːɪ")
        // "le regioni del distretto"
        check("le red^ʒˈonɪ del distrˈetːo", "le reʤˈonɪ del distrˈetːo")
    }

    @Test
    fun `french stray hyphens are dropped and nasals kept`() {
        // "les trois régions du rapport" — espeak emits "le-"/"dy-"
        check("le- tʁwˈa ʁeʒjˈɔ̃ dy- ʁapˈɔʁ", "le tʁwˈa ʁeʒjˈɔ̃ dy ʁapˈɔʁ")
    }

    @Test
    fun `spanish without multi-char phonemes passes through`() {
        check("el kɾˌeθimjˈɛnto sˌostenˈiðo", "el kɾˌeθimjˈɛnto sˌostenˈiðo")
    }

    @Test
    fun `portuguese diphthongs collapse to trained tokens`() {
        // "o relatório trimestral mostra um crescimento" — aʊ→W, eɪ→A
        check(
            "ʊ xˌelatˈɔɾjʊ trˌimestrˈa^ʊ mˈɔstræ ũŋ krˌesimˈe^ɪŋtʊ",
            "ʊ xˌelatˈɔɾjʊ trˌimestrˈW mˈɔstræ ũŋ krˌesimˈAŋtʊ",
        )
    }

    @Test
    fun `uncovered ties just lose the tie character`() {
        check("a^b", "ab")
    }
}
