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
//   4. Last-resort word splits if a single sentence exceeds `maxChars`.
//
// Per-engine `maxChars` comes from `TtsEngine.maxInputChars`. Sherpa-
// onnx-backed engines use a loose cap (~4000) — the underlying
// `OfflineTts.generateWithCallback` already splits internally per
// sentence and streams audio per sentence, so our chunker is mainly a
// safety net for pathological inputs. Pocket uses ~120 (its bundle
// caps at 50 tokens, ≈ 150 chars).
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
     * word-split); the engine's phoneme-count guard handles pathology.
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
     *   word-wrapped. The engine still has to cope (Kitten's
     *   MAX_PHONEMES_PER_CHUNK truncates worst case), but the resulting
     *   audio never has mid-word stutter.
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
