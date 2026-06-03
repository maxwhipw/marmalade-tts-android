package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.engine.kitten.PAD_TOKEN
import app.marmalade.tts.engine.kitten.encodePhonemes
import java.io.File

// -----------------------------------------------------------------------------
// LexiconZh — sherpa-style Mandarin phonemization for Kokoro
// -----------------------------------------------------------------------------
//
// Kokoro v1.0's Mandarin voices were trained against misaki + pypinyin output,
// not espeak-cmn. Espeak's Mandarin voice has no proper word segmentation and
// produces broken IPA for CJK input ("chinese letter chinese letter ..." for
// runs it can't resolve — see sherpa-onnx issue #2248). The right fix is to
// pre-bake the misaki + pypinyin Han-character → IPA mapping into a flat
// lexicon file, then do a fast O(1) lookup at runtime.
//
// That's exactly what sherpa-onnx does in `csrc/kokoro-multi-lang-lexicon.cc`:
//
//   1. Split input by `[一-鿿]+` (CJK Unified Ideographs).
//   2. CJK runs → greedy longest-prefix match against lexicon-zh.txt.
//   3. Non-CJK runs → espeak en-us (loanwords / numbers / Latin in mixed text).
//
// The lexicon (~68k entries, ~2.3 MB) ships with the kokoro-direct bundle.
// Each line is `<Han char(s)> <space-separated IPA phonemes incl. tone arrows
// ↗ ↘ → ↓>`. Sherpa generates it offline from misaki.zh.ZHG2P + pypinyin
// (with longest-prefix phrase entries baked in) — no jieba at runtime.
//
// We resolve every phoneme to its Kokoro token ID at load time (via
// IpaTokenVocab), so per-call lookup returns an IntArray ready to splice into
// the model's input tensor. 68k × ~5 phonemes per entry × 4 bytes = ~1.4 MB
// heap for the IntArrays plus the HashMap key overhead. Acceptable; lives for
// the lifetime of the engine.
//
// All 38 unique IPA phonemes used by lexicon-zh.txt are already in the
// shared 95-entry IpaTokenVocab — no vocab additions needed for zh.
// -----------------------------------------------------------------------------

/**
 * Pre-resolved Mandarin lexicon for KokoroDirect.
 *
 * Construct once at engine load with the path to `lexicon-zh.txt` from the
 * engine bundle. Call [match] per CJK run; non-CJK runs go through espeak.
 */
internal class LexiconZh(file: File) {

    /** Han(s) → token IDs, fully resolved at load time. */
    private val entries: HashMap<String, IntArray>

    /** Longest entry key length, in Java chars. Bound for the greedy matcher. */
    val maxKeyLen: Int

    init {
        val map = HashMap<String, IntArray>(80_000)  // sherpa's lexicon is ~68k entries
        var longest = 0
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val firstSpace = line.indexOf(' ')
                if (firstSpace <= 0 || firstSpace >= line.length - 1) continue
                val key = line.substring(0, firstSpace)
                // Phonemes are space-separated single-char IPA glyphs + tone
                // arrows. Per the analysis: each phoneme is exactly 1 Java
                // char in the bundle's lexicon-zh.txt (no multi-char IPA
                // sequences in the zh inventory). We use the existing Kitten/
                // Kokoro `encodePhonemes` (char-by-char vocab lookup) on the
                // de-spaced phoneme string — spaces between phonemes inside a
                // syllable are layout noise that doesn't appear in the model's
                // training tokens.
                val phonemes = line.substring(firstSpace + 1).replace(" ", "")
                if (phonemes.isEmpty()) continue
                map[key] = encodePhonemes(phonemes)
                if (key.length > longest) longest = key.length
            }
        }
        entries = map
        maxKeyLen = longest.coerceAtLeast(1)
    }

    /** Total entries — diagnostic. */
    fun size(): Int = entries.size

    /**
     * Encode a single CJK run via greedy longest-prefix matching. The input
     * must already be filtered to CJK ideographs (`[一-鿿]+`) — the
     * caller does the regex split.
     *
     * For every position in [run], we try substring lengths from
     * `min(maxKeyLen, remaining)` down to 1; the first hit wins and advances
     * the cursor past the matched phrase. Unmatched chars (shouldn't happen
     * if the lexicon covers the input's Han range) fall back to [PAD_TOKEN]
     * to preserve sequence length.
     *
     * Sherpa's matcher in `kokoro-multi-lang-lexicon.cc:212-281` works the
     * same way against the same lexicon — we match its output token-for-token.
     */
    fun match(run: String): IntArray {
        if (run.isEmpty()) return IntArray(0)
        // Primitive int buffer (no ArrayList<Int> boxing). Sized to a rough
        // upper bound — average phrase is ~5 phonemes per char — and grown
        // by doubling on the rare occasion it under-fills.
        var buf = IntArray(run.length * 8)
        var pos = 0
        var i = 0
        while (i < run.length) {
            var matchedLen = 0
            var matchedIds: IntArray? = null
            val maxTry = minOf(maxKeyLen, run.length - i)
            for (len in maxTry downTo 1) {
                val ids = entries[run.substring(i, i + len)]
                if (ids != null) {
                    matchedLen = len
                    matchedIds = ids
                    break
                }
            }
            val ids = matchedIds
            if (ids != null) {
                if (pos + ids.size > buf.size) {
                    buf = buf.copyOf(maxOf(buf.size * 2, pos + ids.size))
                }
                ids.copyInto(buf, pos)
                pos += ids.size
                i += matchedLen
            } else {
                // Unmapped Han — emit PAD so the model still sees a slot where
                // a phoneme belongs (preserves sequence length). Shouldn't
                // happen if the lexicon covers the input's Han range.
                if (pos + 1 > buf.size) buf = buf.copyOf(buf.size * 2)
                buf[pos++] = PAD_TOKEN
                i++
            }
        }
        return buf.copyOf(pos)
    }

    companion object {
        /**
         * Regex match for runs of CJK Unified Ideographs (U+4E00..U+9FFF).
         * Matches sherpa's `expr_chinese = "([一-鿿]+)"` in
         * `kokoro-multi-lang-lexicon.cc:90`. Used by the engine to split
         * input into CJK runs (this lexicon) and non-CJK runs (espeak).
         *
         * Does not cover Extension A/B/C/D/E/F ideographs above U+9FFF;
         * sherpa's lexicon doesn't cover those either, and pre-modern /
         * rare chars outside BMP-CJK are vanishingly rare in TTS input.
         */
        val CJK_RUN_PATTERN: Regex = Regex("[一-鿿]+")
    }
}
