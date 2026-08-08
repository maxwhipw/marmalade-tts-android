package app.marmalade.tts.phonemizer

// -----------------------------------------------------------------------------
// KokoroEspeakG2P — misaki's espeak post-processing, ported for KokoroDirect.
//
// Kokoro was NOT trained on raw espeak IPA. Upstream misaki (EspeakG2P,
// misaki/espeak.py) phonemizes with espeak ties enabled and then rewrites
// every multi-character diphthong/affricate into a single custom token the
// model learned: aɪ→I, aʊ→W, eɪ→A, oʊ→O, əʊ→Q, ɔɪ→Y, ts→ʦ, dz→ʣ, tʃ→ʧ,
// dʒ→ʤ, ss→S. Feeding the model untied two-character sequences instead
// makes it render two separate phones — Italian affricates ("pizza",
// "ciao") and every diphthong come out wrong, which is most of why
// non-English speech sounded off ("doesn't sound that Italian",
// 2026-08-07). English is unaffected here: its primary upstream G2P is
// the misaki lexicon, and KokoroDirect's ear-accepted English path
// (untied espeak + EnPhonemeFixups) stays byte-identical.
//
// Port notes (source: misaki 0.9.x espeak.py, EspeakG2P, version=None —
// the mapping set Kokoro v1.0 was trained with; the version='2.0'
// nasal-vowel tokens are v1.1-only and must NOT be used with v1.0
// weights):
//  - misaki's parenthesis⇄guillemet dance exists to carry parentheses
//    through phonemizer's punctuation machinery. Our JNI shim preserves
//    punctuation itself (espeak_jni.c re-injects it), so that part has
//    nothing to do and is deliberately not ported.
//  - '-' removal is ported: espeak emits stray hyphens in some languages
//    (French "lə-"); misaki deletes them ("not sure what they mean").
// -----------------------------------------------------------------------------

internal object KokoroEspeakG2P {

    /**
     * Tied multi-character phonemes → the single vocab token Kokoro was
     * trained on. Keys use espeak's tie character `^` as produced by
     * [EspeakPhonemizer.phonemize] with `tie = true`.
     */
    private val TIED_TO_TOKEN: List<Pair<String, String>> = listOf(
        "a^ɪ" to "I",
        "a^ʊ" to "W",
        "d^z" to "ʣ",
        "d^ʒ" to "ʤ",
        "e^ɪ" to "A",
        "o^ʊ" to "O",
        "s^s" to "S",
        "t^s" to "ʦ",
        "t^ʃ" to "ʧ",
        "ə^ʊ" to "Q",
        "ɔ^ɪ" to "Y",
    )

    /**
     * Rewrite tied espeak IPA into Kokoro's trained token alphabet.
     * Any tie not covered by the map joins its characters (`^` deleted),
     * matching misaki.
     */
    fun postprocess(tiedIpa: String): String {
        var ps = tiedIpa
        for ((tied, token) in TIED_TO_TOKEN) ps = ps.replace(tied, token)
        return ps.replace("^", "").replace("-", "")
    }
}
