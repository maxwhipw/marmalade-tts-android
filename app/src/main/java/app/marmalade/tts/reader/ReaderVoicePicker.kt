package app.marmalade.tts.reader

import app.marmalade.tts.lang.LanguageVoiceSelector
import app.marmalade.tts.lang.VoiceChoice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the voice a freshly loaded article is read in — once per article,
 * before the first block, so the voice never flips mid-read; a rebind to an
 * article already held keeps it. The rule is shared with the share route:
 * see app/marmalade/tts/lang/VoiceForLanguage.kt.
 *
 * An interface so the reader's ViewModel tests can fake the choice.
 */
fun interface ReaderVoicePicker {
    suspend fun voiceFor(article: ReaderArticle): VoiceChoice
}

@Singleton
class LanguageAwareReaderVoicePicker @Inject constructor(
    private val selector: LanguageVoiceSelector,
) : ReaderVoicePicker {

    override suspend fun voiceFor(article: ReaderArticle): VoiceChoice =
        selector.select(detectionSample(article), subject = "article", logTag = TAG)

    companion object {
        private const val TAG = "ReaderVoice"

        /**
         * How much article text language detection looks at. Plenty for the
         * script check and the trigram model, and it keeps a very long
         * article from costing anything noticeable.
         */
        const val DETECTION_SAMPLE_CHARS = 2_000

        /** The start of [article] — title, then blocks in order — capped at [DETECTION_SAMPLE_CHARS]. */
        fun detectionSample(article: ReaderArticle): String = buildString {
            article.title?.let { append(it).append('\n') }
            for (block in article.blocks) {
                if (length >= DETECTION_SAMPLE_CHARS) break
                append(block.text).append('\n')
            }
        }.take(DETECTION_SAMPLE_CHARS)
    }
}
