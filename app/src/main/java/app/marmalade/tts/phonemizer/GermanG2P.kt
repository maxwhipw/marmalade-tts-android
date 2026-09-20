package app.marmalade.tts.phonemizer

// -----------------------------------------------------------------------------
// GermanG2P — DEG2P-parity orchestrator for the German Kokoro path.
//
// Mirrors misaki.de.DEG2P (via the executable spec app_german_g2p in
// scratch/kokoro-german-lab/fixtures/gen_fixtures_de.py):
//   1. normalize the text (GermanTextNormalizer).
//   2. Walk override-word matches. Non-override stretches become espeak
//      spans; a punctuation-only stretch (no speakable char) is passed
//      through literally instead of hitting espeak — espeak returns nothing
//      for it, and a sentence-final period after a trailing override word
//      would otherwise be lost.
//   3. Espeak spans phonemize through [phonemizeSpan] (tie mode) then
//      KokoroEspeakG2P.postprocess.
//   4. Render: fragments joined with single spaces, except a fragment
//      starting with trailing punctuation attaches tightly to the previous
//      one (DEG2P._render / _TRAILING_PUNCT).
//   5. Apply the German vocab substitution ʏ->y (KokoroLangSubstitutions).
//
// [phonemizeSpan] takes span text and returns raw tied espeak IPA (the
// value nativePhonemize(text, tie = true) produces). Injected so tests can
// drive the pipeline with captured vectors and no native espeak; the
// production wiring is [forEspeak].
// -----------------------------------------------------------------------------

internal class GermanG2P(private val phonemizeSpan: (String) -> String) {

    private sealed interface Part {
        data class Espeak(val text: String) : Part
        data class Override(val phonemes: String) : Part
    }

    fun phonemes(text: String): String {
        val normalized = GermanTextNormalizer.normalize(text)

        val parts = ArrayList<Part>()
        var cursor = 0
        for (m in GermanOverrides.OVERRIDE_WORD_RE.findAll(normalized)) {
            val ph = GermanOverrides.overrideFor(m.value) ?: continue
            val preceding = normalized.substring(cursor, m.range.first)
            if (preceding.isNotBlank()) parts.add(Part.Espeak(preceding))
            parts.add(Part.Override(ph))
            cursor = m.range.last + 1
        }
        if (cursor == 0) {
            parts.clear()
            parts.add(Part.Espeak(normalized))
        } else {
            val trailing = normalized.substring(cursor)
            if (trailing.isNotBlank()) parts.add(Part.Espeak(trailing))
        }

        val rendered = StringBuilder()
        for (part in parts) {
            val frag = when (part) {
                is Part.Espeak ->
                    if (!HAS_SPEAKABLE.containsMatchIn(part.text)) {
                        part.text.trim() // punctuation-only span: pass through for prosody
                    } else {
                        KokoroEspeakG2P.postprocess(phonemizeSpan(part.text))
                    }
                is Part.Override -> part.phonemes
            }.trim()
            if (frag.isEmpty()) continue
            when {
                rendered.isEmpty() -> rendered.append(frag)
                frag[0] in TRAILING_PUNCT -> rendered.append(frag)
                else -> rendered.append(' ').append(frag)
            }
        }
        return KokoroLangSubstitutions.apply(rendered.toString(), "de")
    }

    companion object {
        /** Characters that attach tightly to the preceding fragment. */
        private val TRAILING_PUNCT: Set<Char> = ".,!?;:%)]}»”".toSet()

        /** Any speakable character (matches the override word alphabet). */
        private val HAS_SPEAKABLE = Regex("[0-9A-Za-zÀ-ÖØ-öø-ÿß]")

        /** Production wiring: raw tied espeak-de IPA for each span. */
        fun forEspeak(espeak: EspeakPhonemizer): GermanG2P =
            GermanG2P { span -> espeak.phonemize(span, voice = "de", tie = true) }
    }
}

/**
 * Per-language repairs applied after KokoroEspeakG2P.postprocess for the
 * espeak-only Kokoro languages. These are measured out-of-vocabulary
 * substitutions: the only symbols espeak de/bg emit that are absent from
 * Kokoro's 114-token vocab.
 *   - de: ʏ -> y
 *   - bg: ɫ -> l
 */
internal object KokoroLangSubstitutions {
    fun apply(phonemes: String, language: String): String = when (language) {
        "de" -> phonemes.replace("ʏ", "y")
        "bg" -> phonemes.replace("ɫ", "l")
        else -> phonemes
    }
}
