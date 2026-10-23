package app.marmalade.tts.data

/**
 * An alias's "Pause between sentences" (issue #96, GitHub #10): silence the
 * app adds after each sentence, on top of the pause the voice already makes.
 * A paragraph break gets [PARAGRAPH_FACTOR] times as much (Max, 2026-10-23:
 * Medium = +0.5 s between sentences, +0.75 s between paragraphs).
 *
 * The pause goes only BETWEEN sentences, never before the first or after the
 * last, so a one-sentence request (a TalkBack item, a notification) sounds
 * exactly as before. Like every other sound it is time-stretched with the
 * playback speed.
 *
 * Stored on [app.marmalade.tts.data.db.VoiceAlias.sentencePauseMs] as the
 * stop's added milliseconds. [NATURAL] (0) leaves every engine's rendering
 * untouched: the engines only take the new code path for a positive pause.
 */
enum class SentencePause(val sentenceMs: Int) {
    NATURAL(0),
    SHORT(250),
    MEDIUM(500),
    LONG(1000),
    ;

    /** The added pause at a paragraph break. */
    val paragraphMs: Int get() = sentenceMs * PARAGRAPH_NUM / PARAGRAPH_DEN

    companion object {
        private const val PARAGRAPH_NUM = 3
        private const val PARAGRAPH_DEN = 2

        /** A paragraph break's pause relative to a sentence's. */
        const val PARAGRAPH_FACTOR: Float = PARAGRAPH_NUM.toFloat() / PARAGRAPH_DEN

        /**
         * The stop for a stored value. A value that isn't a stop (a future
         * build's, a hand-edited row) maps to the nearest one, never above
         * [LONG].
         */
        fun fromMs(ms: Int): SentencePause =
            if (ms <= 0) NATURAL else entries.minBy { kotlin.math.abs(it.sentenceMs - ms) }

        /** The paragraph-break pause for a sentence pause of [sentenceMs]. */
        fun paragraphMsFor(sentenceMs: Int): Int = fromMs(sentenceMs).paragraphMs

        /**
         * Whether the pause applies to [engine]: every on-device engine. Cloud
         * voices come back as one block per request, and cutting them per
         * sentence would be a paid request per sentence, so the editor hides
         * the control for them and the service ignores it.
         */
        fun appliesTo(engine: String): Boolean = engine != CloudApiVoiceCatalog.ENGINE
    }
}
