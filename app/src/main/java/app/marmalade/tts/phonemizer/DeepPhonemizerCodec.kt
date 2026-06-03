package app.marmalade.tts.phonemizer

// -----------------------------------------------------------------------------
// DeepPhonemizer input/output codec
// -----------------------------------------------------------------------------
//
// Encodes plain English text into the integer tokens consumed by the
// DeepPhonemizer seq2seq ONNX model, and decodes the model's argmax
// output back into an IPA string.
//
// The vocabularies and `charRepeats=3` are dictated by the trained
// model — change them and the model breaks. Everything else here
// (layout, names, helpers) is independent of the reference Python
// implementation.
//
// Model I/O (from inspecting open-phonemizer.onnx):
//   input  "text"   shape [batch, seq_len] INT64  — dynamic
//   output "logits" shape [1, seq_len, 64]  FLOAT — per-position vocab logits
//
// Algorithm:
//   1. Lowercase the word, drop characters outside the input vocab.
//   2. Emit `<en_us>` (language start token).
//   3. For each char, emit its id three times in a row (`charRepeats`).
//      This is DeepPhonemizer's training-time input convention — the
//      repeats give the seq2seq more positional latitude to align the
//      output phonemes to the input chars.
//   4. Emit `<end>`.
//   5. Run the model — get one phoneme-vocab logit per input position.
//   6. CTC-decode: argmax per position, collapse runs of identical
//      tokens, drop the blank id, stop at `<end>`.
//
// The CTC step is what turns the 3× input expansion back into a
// reasonable-length output — neighbouring positions usually predict
// the same phoneme, and the run collapse compresses them.
// -----------------------------------------------------------------------------

/** Blank/pad token id — emitted at silent positions, dropped during CTC decode. */
internal const val BLANK_ID: Int = 0

/** End-of-sequence sentinel. Stops the CTC decode. */
internal const val END_ID: Int = 2

/** Each input character is duplicated this many times before being fed to the model. */
internal const val CHAR_REPEATS: Int = 3

/**
 * Input vocabulary: ASCII letters + the two special tokens DeepPhonemizer
 * needs at the boundaries.
 *
 * Token id 0 ("_") is the pad/blank token. It's reserved — encode() never
 * emits it for a real character. The model still uses it internally as
 * the CTC blank symbol on the output side.
 */
private val INPUT_VOCAB: Map<Char, Int> = buildMap {
    put('_', 0)
    // Note: <en_us>=1 and <end>=2 are special tokens, handled by id below.
    var id = 3
    for (c in 'a'..'z') put(c, id++)
    for (c in 'A'..'Z') put(c, id++)
}

private const val LANG_START_ID = 1   // "<en_us>"
private const val END_TOKEN_ID  = 2   // "<end>"

/**
 * Output vocabulary: 64 entries the seq2seq model can emit.
 *
 * IDs 0/1/2 mirror the input side's reserved tokens — the CTC decoder
 * drops 0, breaks on 2, and ignores 1 (a stray language tag).
 *
 * The remaining IDs map to the IPA characters the DeepPhonemizer model
 * was trained to produce. Gaps are intentional: not every output-vocab
 * slot in the model is reachable by argmax for English, but they're
 * still in the array so id→symbol lookups don't need a sparse map.
 */
private val OUTPUT_VOCAB: Array<String> = Array(64) { "" }.apply {
    this[0]  = "_"        // blank / pad
    this[1]  = "<en_us>"  // language tag (skipped on decode)
    this[2]  = "<end>"    // sentinel
    // a-z lowercase IPA letters that overlap with ASCII
    this[3]  = "a"; this[4]  = "b"; this[5]  = "d"; this[6]  = "e"
    this[7]  = "f"; this[8]  = "g"; this[9]  = "h"; this[10] = "i"
    this[11] = "j"; this[12] = "k"; this[13] = "l"; this[14] = "m"
    this[15] = "n"; this[16] = "o"; this[17] = "p"; this[18] = "r"
    this[19] = "s"; this[20] = "t"; this[21] = "u"; this[22] = "v"
    this[23] = "w"; this[24] = "x"; this[25] = "y"; this[26] = "z"
    // IPA non-ASCII symbols
    this[27] = "æ"; this[28] = "ç"; this[29] = "ð"; this[30] = "ø"
    this[31] = "ŋ"; this[32] = "œ"; this[33] = "ɐ"; this[34] = "ɑ"
    this[35] = "ɔ"; this[36] = "ə"; this[37] = "ɛ"; this[38] = "ɜ"
    this[39] = "ɝ"; this[40] = "ɹ"; this[41] = "ɚ"; this[42] = "ɡ"
    this[43] = "ɪ"; this[44] = "ʁ"; this[45] = "ʃ"; this[46] = "ʊ"
    this[47] = "ʌ"; this[48] = "ʏ"; this[49] = "ʒ"; this[50] = "ʔ"
    // suprasegmentals
    this[51] = "ˈ"; this[52] = "ˌ"; this[53] = "ː"
    // combining marks
    this[54] = "̃"; this[55] = "̍"; this[56] = "̥"
    this[57] = "̩"; this[58] = "̯"; this[59] = "͡"
    // remaining IPA
    this[60] = "θ"; this[61] = "'"; this[62] = "ɾ"; this[63] = "ᵻ"
}

/**
 * Encode a single word into model input ids.
 *
 * Returns a tightly-sized LongArray — caller wraps it in an ONNX tensor.
 * The seq2seq model accepts arbitrary lengths (see top of file), so we
 * don't pad to a fixed window the way the reference Python impl does.
 *
 * Characters outside the input vocab (digits, hyphens, anything past
 * basic ASCII letters) are skipped silently — they should have been
 * normalized away before reaching this point.
 */
internal fun encodeWord(word: String): LongArray {
    val out = ArrayList<Long>(word.length * CHAR_REPEATS + 2)
    out.add(LANG_START_ID.toLong())
    for (ch in word) {
        val id = INPUT_VOCAB[ch] ?: continue
        repeat(CHAR_REPEATS) { out.add(id.toLong()) }
    }
    out.add(END_TOKEN_ID.toLong())
    return out.toLongArray()
}

/**
 * CTC decode a `[seq_len, 64]` logits slice (already at batch 0) into
 * an IPA string.
 *
 * Each row is argmax'd independently. The resulting id stream is then
 * compressed by the standard CTC rule: consecutive duplicates collapse
 * to one, and the blank id disappears entirely. The end sentinel halts
 * the walk.
 *
 * Stray language tags (rare but possible if the model hallucinates)
 * are skipped rather than emitted as literal "<en_us>".
 */
internal fun ctcDecode(logits: Array<FloatArray>): String {
    val sb = StringBuilder()
    var prev = -1
    for (row in logits) {
        // argmax across the 64-class output vocab
        var best = 0
        var bestScore = row[0]
        for (i in 1 until row.size) {
            if (row[i] > bestScore) {
                bestScore = row[i]
                best = i
            }
        }
        if (best == prev) continue            // CTC: drop run duplicates
        prev = best
        when (best) {
            BLANK_ID -> { /* blank — emit nothing */ }
            END_ID   -> return sb.toString()  // sentinel — stop here
            LANG_START_ID -> { /* stray language tag, skip */ }
            else -> {
                val sym = OUTPUT_VOCAB.getOrNull(best) ?: continue
                if (sym.isNotEmpty()) sb.append(sym)
            }
        }
    }
    return sb.toString()
}
