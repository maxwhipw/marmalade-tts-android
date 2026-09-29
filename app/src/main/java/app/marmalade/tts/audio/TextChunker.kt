package app.marmalade.tts.audio

// -----------------------------------------------------------------------------
// Text chunker for TTS pipelines.
//
// Splits an input string into a sequence of chunks ≤ `maxChars` each,
// preferring natural prosodic breaks. Three-level cascade ported from
// the CLI's `marmalade_tts.chunking.chunk_text`:
//
//   1. Whole text already fits → single chunk, return as-is.
//   2. Paragraph splits on blank lines (`\n\s*\n`); each paragraph
//      recursively chunked.
//   3. Sentence splits using lookbehind on `[.!?]` followed by
//      whitespace (keeps punctuation attached to the sentence).
//      Sentences greedily bin-packed up to `maxChars`.
//   4. Last-resort word splits if a single sentence exceeds `maxChars`
//      (unless the caller forbids them — see `allowWordSplits`).
//
// Per-engine `maxChars` comes from `TtsEngine.maxInputChars`; each
// engine chunks its own input. `planByTokens` is the token-sized
// alternative (Kokoro): same sentence discipline, limits counted by the
// engine's own encoder. `splitToFit` is the separate, model-aware
// fallback an engine applies to a chunk that overflows its token cap.
//
// Designed from first principles + paraphrased from our MIT-licensed
// CLI codebase. No GPL source consulted.
// -----------------------------------------------------------------------------

object TextChunker {

    /**
     * A chunk from [clauseChunks] with the prosody metadata the F rules
     * need (Max's 2026-08-07 ear-lab verdict, marmalade-tts-cli
     * `~/coding/scratch/kitten-clause-split`):
     *
     * @property text        what to synthesize.
     * @property rowText     the PRE-SPLIT sentence this fragment came
     *   from. Style-row lookups must use this, not [text] — a fragment's
     *   own (short) length would select the brisk interjection register
     *   mid-sentence (the audible "register shift" defect, lab variant D).
     * @property sentenceEnd true when a real sentence ends after this
     *   chunk → the engine inserts its full sentence gap. False = clause
     *   boundary (`;` `:`, dialogue intro, newline ending in a comma) →
     *   short comma-sized gap. Always false on the last chunk (no gap
     *   trails the utterance).
     */
    data class ClauseChunk(
        val text: String,
        val rowText: String,
        val sentenceEnd: Boolean,
    )

    /** Closing quotes/brackets that may sit between `.!?` and the space. */
    private const val TERMINAL_CLOSERS = "\"'”’)]"

    /** Opening quotes — a sentence may start with one after a closer. */
    private const val SENTENCE_OPENERS = "\"“‘'"

    /** Cut before an opening quote after a dialogue verb: `said, "…` / `said: "…`. */
    private val DIALOGUE_INTRO = Regex("(?<=[,:])\\s+(?=[\"“])")

    /** In-sentence clause cuts. `.!?` never appear here un-quoted — the
     *  sentence splitter already consumed them — and quoted ones
     *  (`"Stop!" he said`) must NOT cut, which the closer between the
     *  mark and the space guarantees. */
    private val CLAUSE_MARK = Regex("(?<=[:;])\\s+")

    /**
     * The F chunking rules (Kitten's mode since the 2026-08-07 ear-lab):
     * quote-aware sentence ends, newline = sentence boundary, dialogue
     * intro + `:` `;` are clause boundaries, NO merging — the model
     * gives mid-render `,;:` only ~50 ms of pause vs 390 ms for `.`, so
     * every clause mark the listener should hear must be a real chunk
     * boundary. Gap sizing is the engine's job via [ClauseChunk.sentenceEnd].
     *
     * A sentence longer than [maxChars] is emitted whole (never
     * word-split); an engine whose phoneme cap it overflows re-splits it
     * via [splitToFit].
     */
    fun clauseChunks(text: String): List<ClauseChunk> {
        val out = ArrayList<ClauseChunk>()
        val sentences = sentencesQuoteAware(text.trim())
        for ((si, s) in sentences.withIndex()) {
            val frags = DIALOGUE_INTRO.split(s.text)
                .flatMap { CLAUSE_MARK.split(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for ((fi, f) in frags.withIndex()) {
                val lastOfSentence = fi == frags.lastIndex
                val lastOfText = si == sentences.lastIndex && lastOfSentence
                out.add(
                    ClauseChunk(
                        text = f,
                        rowText = s.text,
                        // A "sentence" ending in a comma is a newline-split
                        // continuation (list item) — comma pause, not period.
                        sentenceEnd = lastOfSentence && !lastOfText && !s.text.endsWith(","),
                    ),
                )
            }
        }
        return out
    }

    private data class QSentence(val text: String)

    /**
     * Quote-aware sentence split. `.!?` followed by optional closing
     * quotes/brackets ends a sentence; when closers are present the next
     * word must start uppercase/digit/opening-quote — a lowercase next
     * word is attribution (`"Stop!" he said`) and stays attached. Plain
     * `.!?` + whitespace always cuts (today's rule). Newlines and CJK
     * enders (。！？) are sentence boundaries too.
     */
    private fun sentencesQuoteAware(text: String): List<QSentence> {
        val out = ArrayList<QSentence>()
        var start = 0
        var i = 0
        fun emit(endExclusive: Int) {
            val t = text.substring(start, endExclusive).trim()
            if (t.isNotEmpty()) out.add(QSentence(t))
        }
        while (i < text.length) {
            when (val c = text[i]) {
                '\n' -> {
                    emit(i)
                    while (i < text.length && text[i].isWhitespace()) i++
                    start = i
                }
                '。', '！', '？' -> {
                    emit(i + 1)
                    i++
                    start = i
                }
                '.', '!', '?' -> {
                    var j = i + 1
                    while (j < text.length && text[j] in TERMINAL_CLOSERS) j++
                    var k = j
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (k > j && k < text.length) {
                        val nxt = text[k]
                        val plain = j == i + 1
                        if (plain || nxt.isUpperCase() || nxt.isDigit() || nxt in SENTENCE_OPENERS) {
                            emit(j)
                            start = k
                            i = k
                            continue
                        }
                    }
                    i = j
                }
                else -> i++
            }
        }
        emit(text.length)
        return out
    }

    // CJK sentence enders (。！？, ideographic + fullwidth) take NO following
    // whitespace in Japanese/Chinese — "文。文。" — so they split as a
    // zero-width boundary after the ender rather than the ASCII `…\s+` rule.
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+|(?<=[。！？])")
    /** Stricter boundary for engines that want pause-only splits — `.!?;:` + newlines + CJK sentence enders. Commas and em-dashes do NOT trigger a split. */
    private val CLAUSE_END = Regex("(?<=[.!?:;])\\s+|(?<=[。！？])|\\n+")
    /**
     * Terminal sentence marks + newlines ONLY — `:` and `;` stay inside
     * their sentence. KittenDirect's per-sentence style rows (R16) need
     * the split to match the row rule's idea of a sentence: a colon or
     * semicolon split would compute rows on sentence *fragments* and
     * re-register mid-sentence. A mark inside closing quotes does not
     * split (the lookbehind sees the quote), so dialogue keeps its
     * attribution — same behaviour as the CLI's run splitter.
     */
    private val SENTENCE_TERMINAL = Regex("(?<=[.!?])\\s+|(?<=[。！？])|\\n+")
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val WHITESPACE = Regex("\\s+")
    /**
     * [splitToFit]'s first cut level. ASCII marks need trailing whitespace
     * so "1,000" and "10:30" never cut; CJK marks (、 ， ； ：) take none.
     */
    private val SOFT_CLAUSE_CUT = Regex("[,;:]\\s+|[、，；：]\\s*")

    // -- token-sized streaming plan (Kokoro) ------------------------------------

    /**
     * One chunk of a [planByTokens] plan.
     *
     * @property text   what to synthesize.
     * @property tokens the planner's count of model tokens for [text] (the
     *   sum of its parts' counts when it was assembled from several).
     */
    data class TokenChunk(val text: String, val tokens: Int)

    /**
     * Sizes for [planByTokens], all in model tokens. Kokoro's cost is a
     * straight line in tokens (Pixel 8a: ~24 ms/token cool, ~52 hot; ~55 ms
     * of audio per token), so these are time budgets in disguise.
     *
     * @property mergeFloor tiny sentences merge while the chunk is below this
     *   (sherpa's tiny-sentence merge; ≈ the old 80 English chars).
     * @property target size a sentence is cut to when it is over
     *   [WHOLE_TOL] × target; also the most a merge of tiny sentences
     *   may grow to.
     * @property firstPiece the first piece's size when the request's first
     *   sentence is cut; later pieces grow by [growth] up to [target].
     */
    data class TokenBudget(
        val mergeFloor: Int,
        val target: Int,
        val firstPiece: Int,
        val growth: Double,
    )

    /**
     * Streaming plan sized by the model's own token count ([count]) rather
     * than characters — a Chinese character is ≈3.4 Kokoro tokens and a
     * Japanese one 2–3, against ≈1.1 for English, so character limits tuned
     * on English made CJK chunks three times too heavy (TTFA assessment
     * 2026-09-28, `docs/release/ttfa-chunking-lab.html`, T3 + T4).
     *
     * 1. Paragraphs, then sentences: `.!?;:` + whitespace, newlines, and the
     *    CJK marks 。！？；： (no whitespace needed). Commas never split here.
     * 2. Tiny sentences merge within a paragraph while the chunk is under
     *    [TokenBudget.mergeFloor] — but only while the result stays within
     *    the target (a 70-char + 95-char merge built a 240-token chunk that
     *    stalled 12 s). The request's first sentence never merges:
     *    its render time is the time-to-first-audio.
     * 3. A sentence over [WHOLE_TOL] × target is cut at clause marks
     *    (`,;:` + whitespace, em dash, 、，；：) and packed to target. A
     *    clause still over target breaks at word level ([wordAtoms]: spaces,
     *    and for Japanese the kana→kanji/katakana step that ends a particle or
     *    okurigana), and only then between characters. When that sentence is
     *    the request's first chunk, its first piece is small
     *    ([TokenBudget.firstPiece], never less than one clause) and pieces
     *    grow from there.
     *
     * Every chunk ends up ≤ [WHOLE_TOL] × target tokens by the planner's
     * count; the engine keeps its own cap check for the count drifting.
     */
    fun planByTokens(text: String, budget: TokenBudget, count: (String) -> Int): List<TokenChunk> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = ArrayList<TokenChunk>()
        for (paragraph in PARAGRAPH_BREAK.split(trimmed)) {
            val sentences = TOKEN_PLAN_BOUNDARY.split(paragraph)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { TokenChunk(it, count(it)) }
            if (sentences.isEmpty()) continue
            for (unit in mergeByTokens(sentences, budget, exemptFirst = out.isEmpty())) {
                out += if (unit.tokens > wholeLimit(budget.target)) {
                    cutByTokens(unit, budget, smallFirst = out.isEmpty(), count)
                } else {
                    listOf(unit)
                }
            }
        }
        return out
    }

    /**
     * A whole sentence (or merge) up to this many tokens stays one chunk —
     * a modest overshoot of the target beats a cut (the CLI's WHOLE_TOL).
     */
    const val WHOLE_TOL = 1.35

    /** A last piece under this many tokens rejoins its predecessor. */
    private const val RUNT_TOKENS = 12

    private fun wholeLimit(target: Int): Int = (target * WHOLE_TOL).toInt()

    /** [planByTokens]' sentence boundary; see step 1 there. */
    private val TOKEN_PLAN_BOUNDARY = Regex("(?<=[.!?:;])\\s+|(?<=[。！？；：])|\\n+")

    /**
     * In-sentence cut points for an over-budget sentence. ASCII marks need
     * trailing whitespace so "1,000" and "10:30" never cut; an em dash cuts
     * after itself (and any spaces); the CJK marks take none.
     */
    private val TOKEN_CLAUSE_CUT = Regex("[,;:]\\s+|—\\s*|[、，；：]\\s*")

    private fun mergeByTokens(
        units: List<TokenChunk>,
        budget: TokenBudget,
        exemptFirst: Boolean,
    ): List<TokenChunk> {
        val out = ArrayList<TokenChunk>()
        var cur: TokenChunk? = null
        for ((i, u) in units.withIndex()) {
            val c = cur
            cur = when {
                c == null -> u
                exemptFirst && i == 1 -> { out += c; u }
                c.tokens < budget.mergeFloor && c.tokens + u.tokens <= budget.target ->
                    TokenChunk(joinText(c.text, u.text), c.tokens + u.tokens)
                else -> { out += c; u }
            }
        }
        cur?.let { out += it }
        return out
    }

    /**
     * Join two trimmed pieces of text back into one chunk. CJK text takes
     * no space between sentences ("文。文。"), so none is added there.
     */
    private fun joinText(a: String, b: String): String =
        if (isCjk(a.last()) || isCjk(b.first())) a + b else "$a $b"

    private fun cutByTokens(
        unit: TokenChunk,
        budget: TokenBudget,
        smallFirst: Boolean,
        count: (String) -> Int,
    ): List<TokenChunk> {
        // Atoms keep their raw text (trailing whitespace included) so packed
        // pieces are exact substrings of the sentence.
        val atoms = cutAfter(unit.text, TOKEN_CLAUSE_CUT)
            .map { TokenChunk(it, count(it.trim())) }
            .flatMap { if (it.tokens > budget.target) wordAtoms(it, budget.target, count) else listOf(it) }
        return packRamp(atoms, budget, if (smallFirst) budget.firstPiece else budget.target)
    }

    /**
     * Greedy pack of [atoms] into pieces: the first piece's limit is
     * [firstLimit], each later one `growth ×` the previous limit (or the
     * previous piece, if that ran over it), capped at the target. A piece is at
     * least one atom. A piece still under half its limit takes one more atom
     * if that stays within [WHOLE_TOL] of the limit, rather than leaving a
     * runt; a runt last piece rejoins its predecessor.
     */
    private fun packRamp(atoms: List<TokenChunk>, budget: TokenBudget, firstLimit: Int): List<TokenChunk> {
        val out = ArrayList<TokenChunk>()
        var limit = firstLimit
        val cur = StringBuilder()
        var curTokens = 0
        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) {
                out += TokenChunk(t, curTokens)
                limit = minOf(budget.target, (maxOf(limit, curTokens) * budget.growth).toInt())
            }
            cur.clear()
            curTokens = 0
        }
        for (a in atoms) {
            val fits = curTokens + a.tokens <= limit
            val topUp = curTokens < limit / 2 && curTokens + a.tokens <= limit * WHOLE_TOL
            if (curTokens > 0 && !fits && !topUp) flush()
            cur.append(a.text)
            curTokens += a.tokens
        }
        flush()
        if (out.size >= 2 && out.last().tokens < RUNT_TOKENS) {
            val tail = out.removeAt(out.lastIndex)
            val prev = out.removeAt(out.lastIndex)
            if (prev.tokens + tail.tokens <= wholeLimit(budget.target)) {
                out += TokenChunk(joinText(prev.text, tail.text), prev.tokens + tail.tokens)
            } else {
                out += prev
                out += tail
            }
        }
        return out
    }

    /**
     * Word-level atoms of a clause that is still over [target] on its own:
     * cut after whitespace, at a Japanese kana→kanji/katakana step (the end
     * of a particle or okurigana — `東京に|行きました`, `新しい|図書館`), and
     * where CJK meets Latin letters or digits. Only an atom still over
     * [target] after that (all-Han Chinese, a URL) is cut between characters.
     */
    private fun wordAtoms(clause: TokenChunk, target: Int, count: (String) -> Int): List<TokenChunk> {
        val raw = clause.text
        val parts = ArrayList<String>()
        var start = 0
        for (i in 1 until raw.length) {
            if (isWordBoundary(raw[i - 1], raw[i])) {
                parts += raw.substring(start, i)
                start = i
            }
        }
        parts += raw.substring(start)
        return parts
            .filter { it.isNotBlank() }
            .map { TokenChunk(it, count(it.trim())) }
            .flatMap { part ->
                if (part.tokens <= target) {
                    listOf(part)
                } else {
                    hardSplit(part.text.trim()) { count(it) <= target }
                        .map { TokenChunk(it, count(it)) }
                }
            }
    }

    private fun isWordBoundary(prev: Char, cur: Char): Boolean {
        if (cur.isWhitespace()) return false
        if (prev.isWhitespace()) return true
        if (isHiragana(prev) && (isKatakana(cur) || isHan(cur))) return true
        val prevCjk = isHan(prev) || isKana(prev)
        val curCjk = isHan(cur) || isKana(cur)
        return prevCjk != curCjk && prev.isLetterOrDigit() && cur.isLetterOrDigit()
    }

    private fun isHiragana(c: Char) = c in 'ぁ'..'ゟ'
    private fun isKatakana(c: Char) = c in '゠'..'ヿ' || c in 'ㇰ'..'ㇿ' || c in 'ｦ'..'ﾟ'
    private fun isKana(c: Char) = isHiragana(c) || isKatakana(c)
    private fun isHan(c: Char) =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' || c in '々'..'〇'

    /** CJK scripts and their punctuation (ideographic + fullwidth forms). */
    private fun isCjk(c: Char) = c in '　'..'鿿' || c in '豈'..'﫿' || c in '＀'..'￯'

    /**
     * Split [text] into chunks ≤ [maxChars] each. Returns an empty list
     * for blank input. Single-chunk input (≤ maxChars after trim)
     * returns a one-element list.
     *
     * Whitespace is trimmed from each chunk. Punctuation stays attached
     * to its sentence (matters for TTS prosody — a sentence read
     * without its trailing "." sounds clipped).
     *
     * @param packSentences When true (default), greedily bin-packs
     *   consecutive sentences into one chunk up to [maxChars]. When
     *   false, every sentence becomes its own chunk regardless of how
     *   much room is left in [maxChars] — used by callers that need
     *   minimum-latency first-emit. Combine with [minChars] to merge
     *   runs of tiny sentences (sherpa's pattern).
     * @param sentenceOnly When true, only `.!?;:` + newlines trigger a
     *   split. Commas, em-dashes do not.
     * @param terminalMarksOnly When true (overrides [sentenceOnly]),
     *   only `.!?` + newlines split — `:` and `;` stay in-sentence.
     *   KittenDirect's mode: with [packSentences]=false every chunk is
     *   one whole sentence, so the per-sentence style row (indexed by
     *   the chunk's text length) matches upstream's register rule.
     * @param allowWordSplits When false, a single sentence that exceeds
     *   [maxChars] is emitted as one over-long chunk rather than
     *   word-wrapped. The engine still has to cope — Kokoro re-splits a
     *   chunk that overflows its token cap via [splitToFit] — but a
     *   sentence that fits the model is never broken up.
     * @param minChars When > 0 and [packSentences]=false, merges runs
     *   of adjacent sentence-chunks while the accumulator length is
     *   below this threshold. Once the accumulator reaches [minChars],
     *   it's emitted and a new accumulator starts. This is sherpa's
     *   "merge tiny sentences" pattern — chunks always end on a
     *   sentence boundary, but a 5-char "Yes." doesn't waste an entire
     *   ORT call as its own chunk. Ignored when [packSentences]=true
     *   (that path's maxChars already acts as the cap).
     * @param minCharsExemptFirst When true (and [minChars] applies), the
     *   very first sentence of the whole text is emitted as its own
     *   chunk even below [minChars]. Streaming callers use this so a
     *   short opening sentence starts playing immediately instead of
     *   waiting for a merged ≥[minChars] chunk to synthesize —
     *   first-chunk inference time is the time-to-first-audio.
     */
    fun chunk(
        text: String,
        maxChars: Int,
        packSentences: Boolean = true,
        sentenceOnly: Boolean = false,
        allowWordSplits: Boolean = true,
        minChars: Int = 0,
        minCharsExemptFirst: Boolean = false,
        terminalMarksOnly: Boolean = false,
    ): List<String> {
        require(maxChars > 0) { "maxChars must be positive (got $maxChars)" }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars && packSentences) return listOf(trimmed)

        // Step 2: paragraph splits.
        val paragraphs = PARAGRAPH_BREAK.split(trimmed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.size > 1) {
            // Only the first paragraph's first sentence is the global first
            // chunk — later paragraphs merge normally.
            return paragraphs.flatMapIndexed { i, p ->
                chunk(
                    p, maxChars, packSentences, sentenceOnly, allowWordSplits,
                    minChars, minCharsExemptFirst && i == 0, terminalMarksOnly,
                )
            }
        }

        // Step 3: sentence/clause splits.
        val boundary = when {
            terminalMarksOnly -> SENTENCE_TERMINAL
            sentenceOnly -> CLAUSE_END
            else -> SENTENCE_END
        }
        val sentences = boundary.split(trimmed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size > 1) {
            return if (packSentences) {
                packSentences(sentences, maxChars, allowWordSplits)
            } else {
                // One chunk per sentence; oversize sentences either word-split
                // (default) or emit as a single oversize chunk (strict mode).
                val perSentence = sentences.flatMap { s ->
                    when {
                        s.length <= maxChars -> listOf(s)
                        allowWordSplits -> splitByWords(s, maxChars)
                        else -> listOf(s)
                    }
                }
                if (minChars > 0) {
                    mergeUpToMin(perSentence, minChars, minCharsExemptFirst)
                } else {
                    perSentence
                }
            }
        }

        // Step 4: one long sentence with no internal breakpoint.
        return if (allowWordSplits) splitByWords(trimmed, maxChars) else listOf(trimmed)
    }

    /**
     * Greedy merge: walk the per-sentence list and concatenate adjacent
     * chunks while the accumulator length is below [minChars]. The
     * moment the accumulator reaches the threshold, emit it and start
     * a new accumulator. Chunks always end at a sentence boundary —
     * we only merge across already-split sentence breaks.
     *
     * This mirrors sherpa-onnx's `kokoro-multi-lang-lexicon.cc` logic
     * around the 50-token merge threshold, adapted to a character
     * count (50 phoneme tokens ≈ 50 source-text chars for English).
     */
    private fun mergeUpToMin(
        chunks: List<String>,
        minChars: Int,
        exemptFirst: Boolean = false,
    ): List<String> {
        if (chunks.isEmpty()) return chunks
        // The first sentence gates time-to-first-audio (inference time
        // scales with chunk length), so fattening it to minChars trades
        // exactly the latency the streaming path exists to avoid. Pass it
        // through unmerged; merging resumes from the second sentence.
        if (exemptFirst) {
            return listOf(chunks.first()) +
                mergeUpToMin(chunks.drop(1), minChars, exemptFirst = false)
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in chunks) {
            if (cur.isEmpty()) {
                cur.append(c)
                continue
            }
            if (cur.length < minChars) {
                cur.append(' ').append(c)
            } else {
                out.add(cur.toString())
                cur.clear()
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * Greedy bin-pack of sentences up to [maxChars] per bin. A single
     * sentence longer than [maxChars] cascades to word splits when
     * [allowWordSplits], otherwise sits in its own oversize bin.
     */
    private fun packSentences(
        sentences: List<String>,
        maxChars: Int,
        allowWordSplits: Boolean,
    ): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (s in sentences) {
            val candidate = if (cur.isEmpty()) s else "$cur $s"
            if (candidate.length <= maxChars) {
                cur = candidate
                continue
            }
            if (cur.isNotEmpty()) {
                out.add(cur)
                cur = ""
            }
            if (s.length <= maxChars) {
                cur = s
            } else if (allowWordSplits) {
                out.addAll(splitByWords(s, maxChars))
            } else {
                // Oversize sentence + no-word-splits → emit whole.
                out.add(s)
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * Split [text] into contiguous pieces that each satisfy [fits] — the
     * engines' fallback for a chunk that would overflow the model's
     * token cap (Kokoro/Kitten: 500 positions). Only a chunk that actually
     * overflows comes here, so normal sentences render exactly as chunked
     * (for Kokoro, whose [planByTokens] already keeps chunks far below the
     * cap, it is a safety net for the planner's count drifting).
     *
     * Cascade, each level tried only on a piece the previous one left
     * oversize: clause punctuation (`,` `;` `:` + whitespace, or CJK
     * `、，；：` with none needed) → whitespace → a hard cut between
     * characters (CJK prose with neither). Within a level, adjacent
     * pieces are greedily re-packed while they still fit, so an overflow
     * costs as few extra boundaries as possible. Nothing is dropped:
     * the pieces concatenate back to [text] up to whitespace at the cuts.
     * A piece fails [fits] only if it is a single character.
     *
     * [fits] is typically "phonemizes to ≤ cap tokens", so it's called
     * O(pieces) times per level, plus O(log n) per hard cut — fine for
     * the rare overflow, which is the only time this runs.
     */
    fun splitToFit(text: String, fits: (String) -> Boolean): List<String> =
        splitToFit(text.trim(), fits, level = 0)

    private fun splitToFit(text: String, fits: (String) -> Boolean, level: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (fits(text)) return listOf(text)
        val pieces = when (level) {
            0 -> cutAfter(text, SOFT_CLAUSE_CUT)
            1 -> cutAfter(text, WHITESPACE)
            else -> return hardSplit(text, fits)
        }
        val out = ArrayList<String>()
        var cur = ""
        for (p in pieces) {
            val candidate = cur + p
            if (fits(candidate.trim())) {
                cur = candidate
                continue
            }
            if (cur.isNotBlank()) {
                out.add(cur.trim())
                if (fits(p.trim())) {
                    cur = p
                    continue
                }
            }
            out.addAll(splitToFit(p.trim(), fits, level + 1))
            cur = ""
        }
        if (cur.isNotBlank()) out.add(cur.trim())
        return out
    }

    /** Contiguous pieces of [text], each ending just after a [boundary] match. */
    private fun cutAfter(text: String, boundary: Regex): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (m in boundary.findAll(text)) {
            val cut = m.range.last + 1
            if (cut < text.length) {
                out.add(text.substring(start, cut))
                start = cut
            }
        }
        out.add(text.substring(start))
        return out
    }

    /** Longest-fitting-prefix cuts (binary search), never inside a surrogate pair. */
    private fun hardSplit(text: String, fits: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty() && !fits(rest)) {
            var lo = 1
            var hi = rest.length - 1
            var best = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (fits(rest.substring(0, mid).trim())) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            var cut = if (best > 0) best else 1
            if (Character.isHighSurrogate(rest[cut - 1]) && cut < rest.length) {
                if (cut > 1) cut-- else cut++
            }
            val head = rest.substring(0, cut).trim()
            if (head.isNotEmpty()) out.add(head)
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    /**
     * Word-wrap [text] to ≤ [maxChars] per chunk. If a single word
     * exceeds [maxChars] (long URL, hash, unspaced foreign token), it
     * stays as its own chunk — engines are responsible for handling it
     * gracefully. Matches the CLI's behaviour.
     */
    private fun splitByWords(text: String, maxChars: Int): List<String> {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var cur = ""
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (candidate.length <= maxChars) {
                cur = candidate
            } else {
                if (cur.isNotEmpty()) {
                    out.add(cur)
                }
                // If `w` is itself longer than maxChars, it becomes its
                // own (over-long) chunk. The engine sees it and either
                // truncates or handles it. Matches the CLI.
                cur = w
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
