package app.marmalade.tts.engine.kitten

// -----------------------------------------------------------------------------
// IPA → Kokoro/Kitten token IDs
// -----------------------------------------------------------------------------
//
// Both Kokoro and Kitten consume the same 178-symbol vocabulary on their
// `input_ids` tensor. Each entry maps a single IPA character (or, for a
// handful of slots, a longer string) to its trained token ID.
//
// The vocab was derived from upstream `tokenizer.json` files distributed
// with each model. Gaps in the numbering are intentional and present in
// the trained vocab — some slots were reserved during training but never
// assigned a symbol. We leave them out of the lookup map and fall back
// to the pad token (id 0) on unknown input.
//
// Why this file lives under `engine/kitten/` and not `phonemizer/`:
// it's an engine-input encoding step, not a phonemizer concern. The
// phonemizer produces a phonetic transcription; the engine decides how
// to tokenize that transcription into integers. Right now only Kitten
// Direct consumes this; when Kokoro Direct lands it will too, at which
// point we'll lift the table to a shared engine package.
//
// The lone special case is at the engine boundary:
//   Kokoro wraps the encoded sequence as `[0, ...ids..., 0]`.
//   Kitten wraps it as           `[0, ...ids..., 10, 0]`.
// Both wrappings are the engine's responsibility, not this file's.
// -----------------------------------------------------------------------------

/** Pad / unknown token. Surrounds every encoded sequence at both ends. */
internal const val PAD_TOKEN: Int = 0

/** End-of-phoneme marker Kitten expects between the last phoneme and the trailing pad. */
internal const val KITTEN_END_TOKEN: Int = 10

/**
 * Single-character vocab. Multi-character IPA combining marks are
 * handled by [encodePhonemes] walking the input grapheme-by-grapheme.
 *
 * Source of truth for the IDs is the model's `tokenizer.json`. The
 * comment after each row gives the unicode escape for the symbol so
 * the table stays scannable without rendering the IPA glyphs.
 */
private val VOCAB: Map<Char, Int> = mapOf(
    // -- punctuation & whitespace -------------------------------------------
    '$' to 0, ';' to 1, ':' to 2, ',' to 3, '.' to 4,
    '!' to 5, '?' to 6,
    '—' to 9,   // em dash —
    '…' to 10,  // horizontal ellipsis …
    '"' to 11,
    '(' to 12, ')' to 13,
    '“' to 14,  // left double quote
    '”' to 15,  // right double quote
    ' ' to 16,

    // -- combining marks & rare phonemes ------------------------------------
    '̃' to 17,  // combining tilde
    'ʣ' to 18,  // ʣ
    'ʥ' to 19,  // ʥ
    'ʦ' to 20,  // ʦ
    'ʨ' to 21,  // ʨ
    'ᵝ' to 22,  // ᵝ
    'ꭧ' to 23,  // ꭧ

    // -- uppercase letters (subset that the model uses) ---------------------
    'A' to 24, 'I' to 25, 'O' to 31, 'Q' to 33, 'S' to 35,
    'T' to 36, 'W' to 39, 'Y' to 41,
    'ᵊ' to 42,  // ᵊ

    // -- ASCII lowercase ----------------------------------------------------
    'a' to 43, 'b' to 44, 'c' to 45, 'd' to 46, 'e' to 47,
    'f' to 48, 'g' to 49, 'h' to 50, 'i' to 51, 'j' to 52,
    'k' to 53, 'l' to 54, 'm' to 55, 'n' to 56, 'o' to 57,
    'p' to 58, 'q' to 59, 'r' to 60, 's' to 61, 't' to 62,
    'u' to 63, 'v' to 64, 'w' to 65, 'x' to 66, 'y' to 67,
    'z' to 68,

    // -- IPA vowels ---------------------------------------------------------
    'ɑ' to 69,  // ɑ
    'ɐ' to 70,  // ɐ
    'ɒ' to 71,  // ɒ
    'æ' to 72,  // æ
    'β' to 75,  // β
    'ɔ' to 76,  // ɔ
    'ɕ' to 77,  // ɕ
    'ç' to 78,  // ç
    'ɖ' to 80,  // ɖ
    'ð' to 81,  // ð
    'ʤ' to 82,  // ʤ
    'ə' to 83,  // ə
    'ɚ' to 85,  // ɚ
    'ɛ' to 86,  // ɛ
    'ɜ' to 87,  // ɜ
    'ɟ' to 90,  // ɟ
    'ɡ' to 92,  // ɡ
    'ɥ' to 99,  // ɥ
    'ɨ' to 101, // ɨ
    'ɪ' to 102, // ɪ
    'ʝ' to 103, // ʝ
    'ɰ' to 111, // ɰ
    'ŋ' to 112, // ŋ
    'ɳ' to 113, // ɳ
    'ɲ' to 114, // ɲ
    'ɴ' to 115, // ɴ
    'ø' to 116, // ø
    'ɸ' to 118, // ɸ
    'θ' to 119, // θ
    'œ' to 120, // œ

    // -- IPA consonants -----------------------------------------------------
    'ɹ' to 123, // ɹ
    'ɾ' to 125, // ɾ
    'ɻ' to 126, // ɻ
    'ʁ' to 128, // ʁ
    'ɽ' to 129, // ɽ
    'ʂ' to 130, // ʂ
    'ʃ' to 131, // ʃ
    'ʈ' to 132, // ʈ
    'ʧ' to 133, // ʧ
    'ʊ' to 135, // ʊ
    'ʋ' to 136, // ʋ
    'ʌ' to 138, // ʌ
    'ɣ' to 139, // ɣ
    'ɤ' to 140, // ɤ
    'χ' to 142, // χ
    'ʎ' to 143, // ʎ
    'ʒ' to 147, // ʒ

    // -- suprasegmentals & diacritics ---------------------------------------
    'ˈ' to 156, // ˈ primary stress
    'ˌ' to 157, // ˌ secondary stress
    'ː' to 158, // ː length mark
    'ʰ' to 162, // ʰ aspiration
    'ʲ' to 164, // ʲ palatalization
    '↓' to 169, // ↓ downstep
    '→' to 171, // → level
    '↗' to 172, // ↗ rise
    '↘' to 173, // ↘ fall
    'ᵻ' to 177,
    // ɯ and ʔ are 5.24% of Japanese phoneme output and were absent
    // entirely, so every one became PAD. IDs are the bundle's own.
    'ɯ' to 110,
    'ʔ' to 148,
)

/**
 * Map a phoneme string to its model token IDs — without any wrapping.
 * Engine code adds [PAD_TOKEN] / [KITTEN_END_TOKEN] around the result
 * according to its own input convention.
 *
 * Characters outside the vocab fall through to [PAD_TOKEN]. This is how
 * the upstream reference handles unknown symbols too; the alternative
 * (silent drop) would shorten the sequence and desynchronise prosody.
 */
internal fun encodePhonemes(phonemes: String): IntArray {
    val out = IntArray(phonemes.length)
    for ((i, c) in phonemes.withIndex()) {
        out[i] = VOCAB[c] ?: PAD_TOKEN
    }
    return out
}
