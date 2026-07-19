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

    // CJK sentence enders (。！？, ideographic + fullwidth) take NO following
    // whitespace in Japanese/Chinese — "文。文。" — so they split as a
    // zero-width boundary after the ender rather than the ASCII `…\s+` rule.
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+|(?<=[。！？])")
    /** Stricter boundary for engines that want pause-only splits — `.!?;:` + newlines + CJK sentence enders. Commas and em-dashes do NOT trigger a split. */
    private val CLAUSE_END = Regex("(?<=[.!?:;])\\s+|(?<=[。！？])|\\n+")
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
     *   split. Commas, em-dashes do not. Combined with
     *   [allowWordSplits]=false this gives KittenDirect what it wants:
     *   never break mid-clause, never break mid-word, only split at
     *   clear sentence boundaries.
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
                    minChars, minCharsExemptFirst && i == 0,
                )
            }
        }

        // Step 3: sentence/clause splits.
        val boundary = if (sentenceOnly) CLAUSE_END else SENTENCE_END
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
