package app.marmalade.tts.engine.pocket

import java.io.File
import java.text.Normalizer

// -----------------------------------------------------------------------------
// SentencePiece Unigram tokenizer for Pocket TTS.
//
// The bundle's `tokenizer.model` is a standard SentencePiece protobuf
// (https://github.com/google/sentencepiece). We need just enough of it
// to run Unigram tokenization with byte fallback:
//   - Parse the ModelProto's `pieces` (repeated SentencePiece messages).
//   - Each piece has a UTF-8 string, a float log-prob score, and a type
//     enum (NORMAL=1, UNKNOWN=2, CONTROL=3, USER_DEFINED=4, BYTE=6).
//   - Run Viterbi over the input bytes, picking the highest-score
//     segmentation. Fall back to single-byte tokens (`<0xXX>`) where
//     no real piece matches.
//
// Why hand-roll instead of pulling a SentencePiece JNI library:
//   - Zero supply-chain exposure for a ~300-line algorithm.
//   - The pieces table is small (~10k entries for Kyutai's model) so
//     the O(n × max_piece_bytes) inner loop runs in microseconds for
//     typical 50-token inputs.
//   - No native-lib packaging overhead in the APK.
//
// What's intentionally NOT supported:
//   - BPE mode (the bundle is Unigram; if a future bundle ships BPE we
//     re-evaluate).
//   - User-defined normalizer rules from the protobuf (we hardcode NFKC,
//     which matches what Kyutai's models train against).
//   - Special-prefix handling beyond the U+2581 word boundary trick.
// -----------------------------------------------------------------------------

class PocketTokenizer private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    /** UTF-8 piece string → id. Used by the Viterbi inner loop. */
    private val pieceByString: HashMap<String, Int>,
    /** id of `<unk>` (the unknown-token piece). Always present in well-formed models. */
    private val unkId: Int,
    /** id of the BYTE-type piece for raw byte `b`, or -1 if no byte fallback exists. */
    private val byteFallbackId: IntArray,
    /** Longest piece by UTF-16 char count — bounds the Viterbi inner loop. */
    private val maxPieceChars: Int,
) {

    /** Total vocab size. Useful for sanity checks against the text_conditioner embedding. */
    val vocabSize: Int get() = pieces.size

    /**
     * Tokenize [text] into a sequence of piece IDs.
     *
     * Preprocessing applied here (in order):
     *  1. Caller-supplied normalization (see [preprocessForPocket] in the
     *     engine — handles whitespace + capitalization + trailing dot).
     *  2. NFKC normalization (matches the Kyutai training pipeline).
     *  3. Replace ASCII spaces with U+2581 (`▁`, the SentencePiece word
     *     boundary). A boundary is also prepended at the start so the
     *     first word is treated as starting after a space — standard
     *     Unigram behavior.
     */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        // SentencePiece treats the input as if prefixed by a word
        // boundary; explicitly prepend U+2581 and swap spaces for it.
        val withBoundaries = buildString(normalized.length + 1) {
            append('▁')
            for (ch in normalized) {
                if (ch == ' ') append('▁') else append(ch)
            }
        }
        return viterbi(withBoundaries)
    }

    /** Viterbi best-segmentation over UTF-16 characters (sentencepiece-equivalent). */
    private fun viterbi(input: String): IntArray {
        val n = input.length
        // score[i] = highest total log-prob to reach position i (consume input[0..i-1]).
        // Initialise with a value low enough to be beaten by any real path
        // but finite so byte-fallback addition stays in the float range.
        val score = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val parent = IntArray(n + 1) { -1 }
        val chosen = IntArray(n + 1) { -1 }
        score[0] = 0.0

        // Byte-fallback penalty: large enough that any real piece beats
        // a byte fallback over the same span, but finite so unreachable
        // positions still get a valid path. Matches the magnitude of
        // SentencePiece's `min_score - 10` convention.
        val bytePenalty = -1e6

        for (i in 0 until n) {
            if (score[i] == Double.NEGATIVE_INFINITY) continue

            // Try every real piece starting at i.
            val maxLen = minOf(maxPieceChars, n - i)
            // Walk by substring length. Strings are cheap to hash; the
            // pieceByString table is the dominant cost.
            for (len in 1..maxLen) {
                val sub = input.substring(i, i + len)
                val pieceId = pieceByString[sub] ?: continue
                val newScore = score[i] + scores[pieceId]
                if (newScore > score[i + len]) {
                    score[i + len] = newScore
                    parent[i + len] = i
                    chosen[i + len] = pieceId
                }
            }

            // Byte fallback: consume the next UTF-16 code unit's UTF-8 bytes
            // as raw byte tokens. We consume one char per byte-fallback step,
            // which emits one OR MORE byte tokens depending on how that char
            // encodes to UTF-8. Each byte token is recovered at backtrack
            // time by re-encoding input[parent..i].
            val newScore = score[i] + bytePenalty
            if (newScore > score[i + 1]) {
                score[i + 1] = newScore
                parent[i + 1] = i
                chosen[i + 1] = -1 // sentinel: byte fallback for input[i..i+1]
            }
        }

        // Backtrack from position n.
        val out = ArrayList<Int>(n)
        var pos = n
        while (pos > 0) {
            val from = parent[pos]
            val pieceId = chosen[pos]
            if (pieceId >= 0) {
                out.add(pieceId)
            } else {
                // Byte fallback: emit byte tokens for the UTF-8 encoding
                // of input[from..pos].
                val span = input.substring(from, pos)
                val utf8 = span.toByteArray(Charsets.UTF_8)
                // Iterate in REVERSE because we're walking backwards
                // through the lattice — they'll be reversed back below.
                for (k in utf8.indices.reversed()) {
                    val b = utf8[k].toInt() and 0xFF
                    val tokenId = byteFallbackId[b]
                    out.add(if (tokenId >= 0) tokenId else unkId)
                }
            }
            pos = from
        }
        out.reverse()
        return out.toIntArray()
    }

    // -- companion: parse the SentencePiece model ----------------------------

    companion object {

        /**
         * SentencePiece's wire format: ModelProto contains a repeated
         * `pieces` field (tag 1, wire type 2 = length-delimited). Each
         * SentencePiece sub-message has a string `piece` (tag 1), a float
         * `score` (tag 2, wire type 5 = fixed32), and an enum `type`
         * (tag 3, wire type 0 = varint). We skip every other field — the
         * model spec metadata (TrainerSpec, NormalizerSpec) isn't needed
         * because we hardcode NFKC and U+2581 as the boundary char.
         */
        fun load(file: File): PocketTokenizer {
            val bytes = file.readBytes()
            val pieces = ArrayList<String>(16384)
            val scores = ArrayList<Float>(16384)
            val types = ArrayList<Int>(16384)

            val reader = ProtoReader(bytes)
            while (reader.hasMore()) {
                val tag = reader.readTag()
                val field = tag ushr 3
                val wire = tag and 0x7
                if (field == 1 && wire == 2) {
                    // pieces submessage
                    val len = reader.readVarint().toInt()
                    val end = reader.position + len
                    var piece = ""
                    var score = 0f
                    var type = 1 // default NORMAL
                    while (reader.position < end) {
                        val subTag = reader.readTag()
                        val subField = subTag ushr 3
                        val subWire = subTag and 0x7
                        when {
                            subField == 1 && subWire == 2 -> {
                                val subLen = reader.readVarint().toInt()
                                piece = String(bytes, reader.position, subLen, Charsets.UTF_8)
                                reader.position += subLen
                            }
                            subField == 2 && subWire == 5 -> {
                                score = Float.fromBits(reader.readFixed32())
                            }
                            subField == 3 && subWire == 0 -> {
                                type = reader.readVarint().toInt()
                            }
                            else -> reader.skip(subWire)
                        }
                    }
                    pieces.add(piece)
                    scores.add(score)
                    types.add(type)
                } else {
                    reader.skip(wire)
                }
            }

            require(pieces.isNotEmpty()) { "tokenizer.model contains no pieces" }

            // Special-token discovery. Convention in Kyutai's models
            // (verified by inspection of the english_2026-04 bundle):
            //  - id 0 is `<unk>` (type UNKNOWN=2)
            //  - id 1 is `<s>` BOS (type CONTROL=3)
            //  - id 2 is `</s>` EOS (type CONTROL=3)
            // We look up <unk> by string for robustness against ordering
            // changes; fall back to id 0 if not found.
            val pieceByString = HashMap<String, Int>(pieces.size * 2)
            var maxLen = 0
            for ((i, piece) in pieces.withIndex()) {
                pieceByString[piece] = i
                val len = piece.length
                if (len > maxLen) maxLen = len
            }
            val unkId = pieceByString["<unk>"] ?: 0

            // Byte-fallback table: SentencePiece BYTE pieces look like
            // `<0xXX>` for each of the 256 byte values. Build a fast
            // lookup by raw byte int.
            val byteFallbackId = IntArray(256) { -1 }
            for ((i, piece) in pieces.withIndex()) {
                if (types[i] != 6) continue // not a BYTE piece
                // Expected form: "<0xHH>"
                if (piece.length != 6 || !piece.startsWith("<0x") || !piece.endsWith(">")) continue
                val hex = piece.substring(3, 5)
                val b = hex.toIntOrNull(16) ?: continue
                if (b in 0..255) byteFallbackId[b] = i
            }

            return PocketTokenizer(
                pieces = pieces.toTypedArray(),
                scores = scores.toFloatArray(),
                pieceByString = pieceByString,
                unkId = unkId,
                byteFallbackId = byteFallbackId,
                maxPieceChars = maxLen,
            )
        }
    }
}

/**
 * Minimal protobuf wire-format reader. Only handles the wire types the
 * SentencePiece ModelProto actually uses: varint (0), fixed64 (1),
 * length-delimited (2), fixed32 (5). Wire types 3+4 (deprecated start/end
 * group) are skipped as no-ops, which is wrong if they ever appear but
 * they don't in any current-generation proto-encoded SentencePiece file.
 */
private class ProtoReader(private val data: ByteArray) {
    var position: Int = 0

    fun hasMore(): Boolean = position < data.size

    fun readTag(): Int = readVarint().toInt()

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = data[position].toInt() and 0xFF
            position++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 64) throw IllegalStateException("varint overflow at pos $position")
        }
    }

    fun readFixed32(): Int {
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        val b2 = data[position + 2].toInt() and 0xFF
        val b3 = data[position + 3].toInt() and 0xFF
        position += 4
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readFixed64(): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((data[position + i].toLong() and 0xFF) shl (i * 8))
        }
        position += 8
        return v
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> readFixed64()
            2 -> {
                val len = readVarint().toInt()
                position += len
            }
            5 -> readFixed32()
            else -> {
                // 3/4 (start/end group) — deprecated, don't appear in
                // sentencepiece protos. Throw rather than silently skip.
                throw IllegalStateException("Unhandled wire type $wire at pos $position")
            }
        }
    }
}
