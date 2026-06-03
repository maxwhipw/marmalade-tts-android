package app.marmalade.tts.phonemizer

// -----------------------------------------------------------------------------
// CutletJaG2P — Japanese reading → IPA, matching Kokoro v1.0's training G2P.
// -----------------------------------------------------------------------------
//
// Kokoro v1.0's Japanese voices were trained with misaki's `cutlet` G2P
// (kokoro/pipeline.py constructs `ja.JAG2P()` with the default
// version='cutlet'). cutlet is SEGMENTAL-ONLY: it emits IPA with no explicit
// pitch markers (e.g. こんにちは → `koɲɲiʨiβa`). The `_`/`-`/`^` pitch markers
// from misaki's *other* (pyopenjtalk) path are NOT in Kokoro v1.0's token
// vocab — that path targets a different model. Kokoro v1.0 learns Japanese
// pitch-accent implicitly from training audio; our job is faithful segmental
// detail.
//
// This is a clean-room Kotlin port of misaki/cutlet.py's conversion layer
// (MIT). It ports two pieces:
//   1. HEPBURN — the hiragana→IPA table (167 kana entries + 22 punct).
//   2. _get_single_mapping — context rules: ん→{m,n,ŋ,ɲ,ɴ} by following sound,
//      っ→ʔ (sokuon), ー→ː (chouonpu), digraph merging (きゃ→kʲa), sutegana.
//
// What we DON'T port: cutlet's fugashi(unidic) morphological analyzer. We
// substitute Open JTalk (naist-jdic) readings via [OpenJtalkPhonemizer] — the
// `pron` field is the katakana reading this converter consumes. unidic and
// naist-jdic agree on readings for common vocabulary; rare-word readings may
// differ. That's the one approximation vs upstream cutlet.
//
// All output IPA chars are present in Kokoro's tokens.txt (verified) except
// `[` `]` from 【】, which we remap to the same quotes cutlet uses for other
// brackets.
// -----------------------------------------------------------------------------

internal object CutletJaG2P {

    /** Devoicing mark Open JTalk inserts in `pron` (U+2019). cutlet's fugashi
     *  reading has no such mark, so we strip it before conversion. */
    private const val DEVOICE_MARK = '’'

    /** Small kana (sutegana) — combine with a preceding base kana into a
     *  digraph, or are dropped when orphaned. */
    private val SUTEGANA = "ゃゅょぁぃぅぇぉ".toSet()

    /** Iteration marks. Effectively never appear in Open JTalk `pron` (it
     *  resolves them to the actual reading), so handled minimally. */
    private val ODORI = "〃々ゝゞヽヾ".toSet()

    /**
     * hiragana (+ a few katakana variants ヷヸヹヺ) → IPA, plus fullwidth
     * punctuation. Generated verbatim from misaki/cutlet.py's HEPBURN dict.
     * Both single-kana and 2-kana digraph keys live here.
     */
    private val HEPBURN: Map<String, String> = mapOf(
        "ぁ" to "a", "あ" to "a", "ぃ" to "i", "い" to "i", "ぅ" to "ɯ",
        "う" to "ɯ", "ぇ" to "e", "え" to "e", "ぉ" to "o", "お" to "o",
        "か" to "ka", "が" to "ɡa", "き" to "kʲi", "ぎ" to "ɡʲi", "く" to "kɯ",
        "ぐ" to "ɡɯ", "け" to "ke", "げ" to "ɡe", "こ" to "ko", "ご" to "ɡo",
        "さ" to "sa", "ざ" to "ʣa", "し" to "ɕi", "じ" to "ʥi", "す" to "sɨ",
        "ず" to "zɨ", "せ" to "se", "ぜ" to "ʣe", "そ" to "so", "ぞ" to "ʣo",
        "た" to "ta", "だ" to "da", "ち" to "ʨi", "ぢ" to "ʥi", "つ" to "ʦɨ",
        "づ" to "zɨ", "て" to "te", "で" to "de", "と" to "to", "ど" to "do",
        "な" to "na", "に" to "ɲi", "ぬ" to "nɯ", "ね" to "ne", "の" to "no",
        "は" to "ha", "ば" to "ba", "ぱ" to "pa", "ひ" to "çi", "び" to "bʲi",
        "ぴ" to "pʲi", "ふ" to "ɸɯ", "ぶ" to "bɯ", "ぷ" to "pɯ", "へ" to "he",
        "べ" to "be", "ぺ" to "pe", "ほ" to "ho", "ぼ" to "bo", "ぽ" to "po",
        "ま" to "ma", "み" to "mʲi", "む" to "mɯ", "め" to "me", "も" to "mo",
        "ゃ" to "ja", "や" to "ja", "ゅ" to "jɯ", "ゆ" to "jɯ", "ょ" to "jo",
        "よ" to "jo", "ら" to "ɾa", "り" to "ɾʲi", "る" to "ɾɯ", "れ" to "ɾe",
        "ろ" to "ɾo", "ゎ" to "βa", "わ" to "βa", "ゐ" to "i", "ゑ" to "e",
        "を" to "o", "ゔ" to "vɯ", "ゕ" to "ka", "ゖ" to "ke",
        "ヷ" to "va", "ヸ" to "vʲi", "ヹ" to "ve", "ヺ" to "vo",
        "いぇ" to "je", "うぃ" to "βi", "うぇ" to "βe", "うぉ" to "βo",
        "きぇ" to "kʲe", "きゃ" to "kʲa", "きゅ" to "kʲɨ", "きょ" to "kʲo",
        "ぎゃ" to "ɡʲa", "ぎゅ" to "ɡʲɨ", "ぎょ" to "ɡʲo",
        "くぁ" to "kᵝa", "くぃ" to "kᵝi", "くぇ" to "kᵝe", "くぉ" to "kᵝo",
        "ぐぁ" to "ɡᵝa", "ぐぃ" to "ɡᵝi", "ぐぇ" to "ɡᵝe", "ぐぉ" to "ɡᵝo",
        "しぇ" to "ɕe", "しゃ" to "ɕa", "しゅ" to "ɕɨ", "しょ" to "ɕo",
        "じぇ" to "ʥe", "じゃ" to "ʥa", "じゅ" to "ʥɨ", "じょ" to "ʥo",
        "ちぇ" to "ʨe", "ちゃ" to "ʨa", "ちゅ" to "ʨɨ", "ちょ" to "ʨo",
        "ぢゃ" to "ʥa", "ぢゅ" to "ʥɨ", "ぢょ" to "ʥo",
        "つぁ" to "ʦa", "つぃ" to "ʦʲi", "つぇ" to "ʦe", "つぉ" to "ʦo",
        "てぃ" to "tʲi", "てゅ" to "tʲɨ", "でぃ" to "dʲi", "でゅ" to "dʲɨ",
        "とぅ" to "tɯ", "どぅ" to "dɯ",
        "にぇ" to "ɲe", "にゃ" to "ɲa", "にゅ" to "ɲɨ", "にょ" to "ɲo",
        "ひぇ" to "çe", "ひゃ" to "ça", "ひゅ" to "çɨ", "ひょ" to "ço",
        "びゃ" to "bʲa", "びゅ" to "bʲɨ", "びょ" to "bʲo",
        "ぴゃ" to "pʲa", "ぴゅ" to "pʲɨ", "ぴょ" to "pʲo",
        "ふぁ" to "ɸa", "ふぃ" to "ɸʲi", "ふぇ" to "ɸe", "ふぉ" to "ɸo",
        "ふゅ" to "ɸʲɨ", "ふょ" to "ɸʲo",
        "みゃ" to "mʲa", "みゅ" to "mʲɨ", "みょ" to "mʲo",
        "りゃ" to "ɾʲa", "りゅ" to "ɾʲɨ", "りょ" to "ɾʲo",
        "ゔぁ" to "va", "ゔぃ" to "vʲi", "ゔぇ" to "ve", "ゔぉ" to "vo",
        "ゔゅ" to "bʲɨ", "ゔょ" to "bʲo",
        "・" to " ", "゚" to "", "゙" to "",
        // -- fullwidth punctuation --
        "。" to ".", "、" to ",", "？" to "?", "！" to "!",
        "「" to "“", "」" to "”", "『" to "“", "』" to "”",
        "：" to ":", "；" to ";", "（" to "(", "）" to ")",
        "《" to "(", "》" to ")", "【" to "(", "】" to ")",
        "，" to ",", "～" to "—", "〜" to "—", "—" to "—",
        "«" to "“", "»" to "”",
    )

    /**
     * Convert Open JTalk NJD nodes to a cutlet-style IPA string. Words use
     * their katakana `pron` reading; symbol nodes (pos=記号) map punctuation;
     * ASCII surfaces pass through. Words are space-separated (the space token
     * cues the model's inter-word timing); punctuation glues to the preceding
     * token with a trailing space after sentence stops.
     */
    fun convert(nodes: List<NjdNode>): String {
        val sb = StringBuilder()
        for (node in nodes) {
            val piece = nodeToIpa(node)
            if (piece.isEmpty()) continue
            val isPunct = node.pos == POS_SYMBOL
            if (sb.isNotEmpty() && !isPunct && !sb.endsWith(" ")) sb.append(' ')
            sb.append(piece)
            if (isPunct && piece.last() in PUNCT_STOPS) sb.append(' ')
        }
        return WHITESPACE.replace(sb, " ").trim()
    }

    private fun nodeToIpa(node: NjdNode): String {
        // Symbol/punctuation. Open JTalk normalizes ASCII punctuation to its
        // fullwidth forms (. → ．U+FF0E, ? → ？U+FF1F), so fold fullwidth ASCII
        // (U+FF01..U+FF5E) back to ASCII (U+0021..U+007E) before the HEPBURN
        // lookup. Without this, ． etc. fall through to PAD (not in Kokoro's
        // vocab) and leave a silent artifact. After folding, the HEPBURN punct
        // entries (or plain ASCII passthrough) all land on real vocab tokens.
        if (node.pos == POS_SYMBOL) {
            val sb = StringBuilder()
            for (c in node.string) {
                val folded = if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c
                sb.append(HEPBURN[folded.toString()] ?: folded.toString())
            }
            return sb.toString()
        }
        // ASCII (Latin loanwords, numbers already read by Open JTalk): pass through.
        if (node.string.all { it.code < 128 }) return node.string
        // Word: convert the katakana reading.
        val reading = node.pron.replace(DEVOICE_MARK.toString(), "")
        if (reading.isEmpty()) return ""
        return romajiHira(kata2hira(reading))
    }

    /** Map a katakana reading to hiragana. Chars outside the katakana block
     *  (ー chouonpu, ・, etc.) pass through unchanged — getSingleMapping
     *  handles ー specially. */
    private fun kata2hira(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            val code = c.code
            // Katakana ァ(U+30A1)..ヶ(U+30F6) → hiragana, offset -0x60.
            if (code in 0x30A1..0x30F6) out.append((code - 0x60).toChar())
            else out.append(c)
        }
        return out.toString()
    }

    private fun romajiHira(hira: String): String {
        val sb = StringBuilder()
        for (i in hira.indices) {
            val pk = if (i > 0) hira[i - 1] else null
            val kk = hira[i]
            val nk = if (i < hira.length - 1) hira[i + 1] else null
            sb.append(getSingleMapping(pk, kk, nk))
        }
        return sb.toString()
    }

    /**
     * Port of cutlet's `_get_single_mapping`. Returns the IPA for one hiragana
     * given its neighbours. Digraphs (きゃ) are emitted when processing the
     * SECOND kana via the [pk]+[kk] lookup; the first kana returns "" to defer.
     */
    private fun getSingleMapping(pk: Char?, kk: Char, nk: Char?): String {
        if (kk in ODORI) {
            // Iteration marks — practically unreachable from Open JTalk pron.
            if (kk == 'ゞ' || kk == 'ヾ') {
                val voiced = pk?.let { addDakuten(it) }
                return voiced?.let { HEPBURN[it.toString()] } ?: ""
            }
            if (kk == 'ゝ' || kk == 'ヽ') return pk?.let { HEPBURN[it.toString()] } ?: ""
            return "" // 々 〃
        }
        // Digraph: emit when we reach the second kana (pk+kk known).
        if (pk != null) HEPBURN["$pk$kk"]?.let { return it }
        // Current+next forms a known digraph → defer (emitted at next step).
        if (nk != null && HEPBURN.containsKey("$kk$nk")) return ""
        // Next is a small kana but kk+nk isn't a table digraph: splice.
        if (nk != null && nk in SUTEGANA) {
            if (kk == 'っ') return ""
            val base = HEPBURN[kk.toString()] ?: return ""
            val tail = HEPBURN[nk.toString()] ?: return ""
            return base.dropLast(1) + tail
        }
        if (kk in SUTEGANA) return ""
        if (kk == 'ー') return "ː"   // chouonpu / long vowel
        if (kk == 'っ') return "ʔ"   // sokuon / geminate
        if (kk == 'ん') return moraicN(nk)
        return HEPBURN[kk.toString()] ?: ""
    }

    /**
     * Moraic ん assimilation by the following sound (cutlet's rule, mirroring
     * Japanese phonology): m before m/p/b, ŋ before k/ɡ, ɲ before ɲ/ʨ/ʥ,
     * n before n/t/d/ɾ/z, ɴ otherwise (utterance-final or before vowels).
     */
    private fun moraicN(nk: Char?): String {
        val tnk = nk?.let { HEPBURN[it.toString()] }
        if (tnk != null && tnk.isNotEmpty()) {
            val c0 = tnk[0]
            return when {
                c0 == 'm' || c0 == 'p' || c0 == 'b' -> "m"
                c0 == 'k' || c0 == 'ɡ' -> "ŋ"
                tnk.startsWith("ɲ") || tnk.startsWith("ʨ") || tnk.startsWith("ʥ") -> "ɲ"
                c0 == 'n' || c0 == 't' || c0 == 'd' || c0 == 'ɾ' || c0 == 'z' -> "n"
                else -> "ɴ"
            }
        }
        return "ɴ"
    }

    /** Add a dakuten (voicing mark) to a kana, for ゞ/ヾ iteration marks. */
    private fun addDakuten(kk: Char): Char? {
        val voiceless = "かきくけこさしすせそたちつてとはひふへほ"
        val voiced = "がぎぐげござじずぜぞだぢづでどばびぶべぼ"
        val idx = voiceless.indexOf(kk)
        return if (idx >= 0) voiced[idx] else null
    }

    private const val POS_SYMBOL = "記号"
    private val PUNCT_STOPS = "!),.:;?”".toSet()
    private val WHITESPACE = Regex("\\s+")
}
