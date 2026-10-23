package app.marmalade.tts.engine

import app.marmalade.tts.data.SentencePause
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The shared pieces of an alias's pause between sentences (#96,
 * [SentencePause]). The engines insert the sentence pause themselves, each
 * where its own audio has a sentence end: Kitten in its existing sentence
 * gap, Pocket and VITS between their sentence chunks, Kokoro inside a piece
 * at the period's pause ([app.marmalade.tts.engine.kokoro.KokoroSentencePause]).
 * Paragraph breaks are the caller's: the Narrator adds them between its
 * segments, system TTS through [paragraphs].
 */
object SentenceGaps {

    /** Clause marks a chunker may cut at that are not a sentence end. */
    private const val CLAUSE_MARKS = ",;:、，；：—–-"

    /** Closing quotes/brackets that may follow a sentence's last mark. */
    private const val CLOSERS = "\"'”’)]»"

    /**
     * Whether the pause belongs after [chunk], a piece a sentence chunker cut
     * at a sentence end (`.!?…`, CJK enders, the danda), a line break, or a
     * clause mark: true for all but the clause mark.
     */
    fun endsSentence(chunk: String): Boolean {
        val last = chunk.trimEnd().trimEnd { it in CLOSERS }.lastOrNull() ?: return false
        return last !in CLAUSE_MARKS
    }

    /**
     * The tiny-sentence merge floor a sentence chunker uses ([minChars]):
     * unchanged at Natural; off with a pause, so every sentence is its own
     * chunk and gets its pause ("Yes. No." would otherwise run together).
     */
    fun mergeFloor(minChars: Int, sentencePauseMs: Int): Int = if (sentencePauseMs > 0) 0 else minChars

    /** [ms] of audio at [sampleRate], in samples. */
    fun samples(ms: Int, sampleRate: Int): Int = (sampleRate.toLong() * ms / 1000).toInt()

    /**
     * [ms] of silence. Not a resume point: a resume steps back to the
     * sentence before it.
     */
    fun silence(ms: Int, sampleRate: Int): SynthAudio =
        SynthAudio(ShortArray(samples(ms, sampleRate)), sampleRate, resumePoint = false)

    /**
     * [text] rendered by [render] a paragraph (blank-line separated) at a
     * time, with the paragraph pause for [sentencePauseMs] between them. At
     * Natural, or for a single paragraph, it is exactly `render(text)`, so
     * the request reaches the engine as it always did.
     */
    fun paragraphs(text: String, sentencePauseMs: Int, render: (String) -> Flow<SynthAudio>): Flow<SynthAudio> {
        if (sentencePauseMs <= 0) return render(text)
        val parts = splitParagraphs(text)
        if (parts.size <= 1) return render(text)
        val gapMs = SentencePause.paragraphMsFor(sentencePauseMs)
        return flow {
            var rate = 0
            for ((i, p) in parts.withIndex()) {
                if (i > 0 && rate > 0) emit(silence(gapMs, rate))
                render(p).collect {
                    rate = it.sampleRate
                    emit(it)
                }
            }
        }
    }

    /** [paragraphs] for a caller that wants the whole audio at once (system TTS's batched path). */
    suspend fun paragraphsWhole(text: String, sentencePauseMs: Int, render: suspend (String) -> SynthAudio): SynthAudio {
        if (sentencePauseMs <= 0) return render(text)
        val parts = splitParagraphs(text)
        if (parts.size <= 1) return render(text)
        val audio = parts.map { render(it) }
        val rate = audio.first().sampleRate
        val gap = samples(SentencePause.paragraphMsFor(sentencePauseMs), rate)
        val out = ShortArray(audio.sumOf { it.pcm.size } + gap * (audio.size - 1))
        var at = 0
        for ((i, a) in audio.withIndex()) {
            if (i > 0) at += gap
            a.pcm.copyInto(out, at)
            at += a.pcm.size
        }
        return SynthAudio(out, rate)
    }

    /** The same paragraph rule as the Narrator's shared text: blank lines, blank paragraphs dropped. */
    fun splitParagraphs(text: String): List<String> =
        text.split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
}
