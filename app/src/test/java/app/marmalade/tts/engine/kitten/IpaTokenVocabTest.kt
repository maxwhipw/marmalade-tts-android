package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the IPA → token table against silent drops.
 *
 * `encodePhonemes` maps anything it doesn't recognise to [PAD_TOKEN]
 * rather than failing, which is the right runtime behaviour and a
 * terrible failure mode to debug: a missing entry doesn't crash, doesn't
 * log, and doesn't even sound broken — the phoneme simply isn't spoken.
 * Kokoro and Kitten share this table, so one bad entry hits both.
 *
 * The language audit (docs/LANGUAGE-AUDIT-2026-07.md, 2026-07-27) found
 * three, all confirmed against the shipped bundle's own `tokens.txt`.
 */
class IpaTokenVocabTest {

    @Test
    fun `every IPA character espeak actually emits is mappable`() {
        // Every character here was observed in real espeak-ng / Open JTalk
        // output across the nine supported languages AND exists in the
        // bundle's tokens.txt. Both halves matter: a character the model
        // has no token for (ɫ, for one) belongs nowhere near this list —
        // it isn't a gap in the table, it's a sound this model doesn't
        // have, and asserting on it would fail forever for no reason.
        val emitted = "ðɪkwaɪbɹaʊnfɒksdʒʌmpsoʊvɚleɪziɡɔːɑːɜːeəʊɪəuːʃʒŋθæʌɐɾʔɯɲʎçβɣɸʝ"
        val unmapped = emitted.toSet().filter { encodePhonemes(it.toString())[0] == PAD_TOKEN }
        assertEquals("These phonemes encode to PAD and are silently dropped", emptyList<Char>(), unmapped)
    }

    @Test
    fun `the barred-i entry is the real character, not its lookalike`() {
        // Was `'ᴻ' to 177, // ᵻ` — U+1D3B MODIFIER LETTER CAPITAL REVERSED N
        // keyed at the id belonging to U+1D7B LATIN SMALL LETTER I WITH
        // STROKE, which is what espeak emits and what the bundle's
        // tokens.txt calls 177. The comment named the right character; only
        // the key was wrong, so it read as correct for as long as nobody
        // compared the two glyphs. espeak emits it in ~5% of American
        // English IPA, so every "roses"/"wanted" vowel became PAD.
        assertEquals(177, encodePhonemes("ᵻ")[0])
        assertNotEquals(
            "U+1D3B is not in the model's vocabulary and must not be a key",
            177,
            encodePhonemes("ᴻ")[0],
        )
    }

    @Test
    fun `japanese-only phonemes are present`() {
        // ɯ (unrounded u) and ʔ (the geminate/sokuon stop) are 5.24% of
        // Japanese phoneme output and were absent outright. Ids are the
        // bundle's own, not guesses.
        assertEquals(110, encodePhonemes("ɯ")[0])
        assertEquals(148, encodePhonemes("ʔ")[0])
    }
}
