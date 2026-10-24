package app.marmalade.tts.engine

import app.marmalade.tts.data.SentencePause

/**
 * The shared pieces of an alias's pause between sentences (#96,
 * [SentencePause]). Each engine inserts the pause itself, where its own audio
 * has a sentence end: Kitten in its existing sentence gap, Pocket and VITS
 * between their sentence chunks, Kokoro inside a piece at the period's pause
 * ([app.marmalade.tts.engine.kokoro.KokoroSentencePause]). A paragraph break
 * (a blank line) inside the request gets the paragraph pause, so a client
 * sending several paragraphs makes one engine request. The reader's segments
 * are paragraphs already: it adds the paragraph pause between them itself.
 *
 * One rule decides where the pause goes ([isSentenceBreak]), so every engine
 * agrees on what counts as the end of a sentence.
 */
object SentenceGaps {

    /** Marks that end a sentence (ASCII, the ellipsis, CJK, the danda). */
    private const val ENDERS = ".!?…。！？।॥"

    /** Closing quotes/brackets that may follow a sentence's last mark. */
    private const val CLOSERS = "\"'”’)]»」』"

    /** Opening quotes/brackets that may come before the next sentence's first letter. */
    private const val OPENERS = "\"'“‘([«「『¿¡"

    /**
     * Words that end in a period without ending the sentence ("Fig. 3",
     * "Dr. Smith"). Lower case, without the period. A single letter (an
     * initial, "p. 12") counts too ([isAbbreviation]). Left out: words that
     * often end a sentence ("etc.", "Inc.", "sec.") and ones that are words
     * too ("No.", "Nr."): before a number, the usual case, the digit rule in
     * [isSentenceBreak] keeps their pause out anyway.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "gen", "col", "lt", "sgt", "capt", "rev",
        "fig", "figs", "vol", "ch", "eq", "pp", "approx", "ca", "cf", "vs", "al",
        "e.g", "i.e", "z.b", "bzw", "sra", "dra", "mme", "mlle", "pág", "núm",
    )

    /**
     * Whether the alias's pause belongs between [before] (a piece of text a
     * chunker cut) and [after] (the text that follows it): [before] ends with
     * a sentence mark and the next sentence really starts there. Not after a
     * clause mark, a word cut, an abbreviation or an initial ("Fig. 3",
     * "Dr. Smith", "J. Smith"), nor before a lower-case word or a digit (a
     * mid-sentence ellipsis, "“Wait!” she said", "approx. 5").
     */
    fun isSentenceBreak(before: String, after: String): Boolean {
        val head = before.trimEnd().trimEnd { it in CLOSERS }
        val last = head.lastOrNull() ?: return false
        if (last !in ENDERS) return false
        val next = after.trimStart().trimStart { it in OPENERS }.firstOrNull() ?: return false
        if (next.isLowerCase() || next.isDigit()) return false
        return !(last == '.' && isAbbreviation(head))
    }

    /** [head] (ending in its period) ends with an abbreviation or an initial. */
    private fun isAbbreviation(head: String): Boolean {
        val word = head.dropLast(1).takeLastWhile { !it.isWhitespace() }.trimStart { it in OPENERS }
        if (word.length == 1 && word[0].isLetter()) return true
        return word.lowercase() in ABBREVIATIONS
    }

    /** A paragraph break: whitespace holding a blank line. */
    fun isParagraphBreak(whitespace: CharSequence): Boolean = whitespace.count { it == '\n' } >= 2

    /**
     * [text] chunked by [chunk] (given each piece of text and whether it is
     * the first), with the pause after each chunk. With a pause set, each
     * paragraph is chunked on its own and its last chunk gets the paragraph
     * pause; inside a paragraph a chunk gets the sentence pause where
     * [isSentenceBreak] says so. At Natural this is `chunk(text, true)`, every
     * pause 0, so the engine renders exactly as it always did. For engines
     * whose text preparation drops blank lines (Pocket collapses whitespace).
     */
    fun byParagraph(text: String, sentencePauseMs: Int, chunk: (String, Boolean) -> List<String>): List<Pair<String, Int>> {
        if (sentencePauseMs <= 0) return chunk(text, true).map { it to 0 }
        val paragraphMs = SentencePause.paragraphMsFor(sentencePauseMs)
        val out = ArrayList<Pair<String, Int>>()
        for (paragraph in splitParagraphs(text)) {
            val chunks = chunk(paragraph, out.isEmpty())
            for ((i, c) in chunks.withIndex()) {
                val pause = if (i < chunks.lastIndex) {
                    if (isSentenceBreak(c, chunks[i + 1])) sentencePauseMs else 0
                } else {
                    paragraphMs
                }
                out += c to pause
            }
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().first to 0
        return out
    }

    /**
     * Walks [source] piece by piece, in order, for engines that cut it as it
     * is (Kitten, Kokoro): [pauseAfter] says what pause follows each piece,
     * from the source text after it, a blank line included. Pieces are matched
     * on their non-whitespace characters, so a chunker's trimming and joining
     * don't matter. A piece that doesn't match (a chunker that rewrote the
     * text) gets the sentence rule alone from then on, and no paragraph pause.
     */
    class Follower(private val source: String, private val sentencePauseMs: Int) {
        private var at = 0
        private var lost = false

        /** The pause after [piece], the next piece of the source; 0 at its end. */
        fun pauseAfter(piece: String): Int {
            if (sentencePauseMs <= 0) return 0
            if (!lost && !advance(piece)) lost = true
            if (lost) return if (isSentenceBreak(piece, "A")) sentencePauseMs else 0
            var i = at
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length) return 0
            if (isParagraphBreak(source.subSequence(at, i))) return SentencePause.paragraphMsFor(sentencePauseMs)
            return if (isSentenceBreak(piece, source.substring(i, minOf(source.length, i + LOOKAHEAD)))) sentencePauseMs else 0
        }

        /** Moves past [piece] in the source; false when it isn't there. */
        private fun advance(piece: String): Boolean {
            var i = at
            for (c in piece) {
                if (c.isWhitespace()) continue
                while (i < source.length && source[i].isWhitespace()) i++
                if (i >= source.length || source[i] != c) return false
                i++
            }
            at = i
            return true
        }

        private companion object {
            const val LOOKAHEAD = 8
        }
    }

    /**
     * The sentence boundaries inside [text] (a piece holding several
     * sentences), in order: for each sentence mark followed by more text,
     * whether the pause belongs there ([isSentenceBreak]). Kokoro matches
     * these to the sentence marks its phonemizer kept.
     */
    fun innerBreaks(text: String): List<Boolean> {
        val out = ArrayList<Boolean>()
        for (m in INNER_BOUNDARY.findAll(text)) {
            val end = m.range.last + 1
            if (text.substring(end).isBlank()) continue
            out += isSentenceBreak(text.substring(0, end), text.substring(end))
        }
        return out
    }

    /** A sentence mark (and any closers) followed by a space, or a CJK ender / danda followed by anything. */
    private val INNER_BOUNDARY = Regex("[.!?…]+[\"'”’)\\]»」』]*(?=\\s)|[。！？।॥]+[」』”’)]*")

    /** [ms] of audio at [sampleRate], in samples. */
    fun samples(ms: Int, sampleRate: Int): Int = (sampleRate.toLong() * ms / 1000).toInt()

    /**
     * [ms] of added silence ([SynthAudio.addedSilence]). Not a resume point:
     * a resume steps back to the sentence before it.
     */
    fun silence(ms: Int, sampleRate: Int): SynthAudio {
        val n = samples(ms, sampleRate)
        return SynthAudio(ShortArray(n), sampleRate, resumePoint = false, addedSilence = n)
    }

    /** The same paragraph rule as the Narrator's shared text: blank lines, blank paragraphs dropped. */
    fun splitParagraphs(text: String): List<String> =
        text.split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
}
