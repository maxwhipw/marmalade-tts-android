package app.marmalade.tts.reader

/**
 * An extracted article, held by [ReaderPlaybackController] for as long as it
 * is the one being read.
 *
 * The controller needs the block *texts* to speak; it keeps the typed blocks
 * (and the title/byline) as well so the reader screen can be rebuilt from
 * memory when the user comes back to it — reopening from the notification, or
 * re-sharing the same link mid-playback, must not refetch the page. Still
 * nothing on disk (design point 8): this dies with the process, and a reader
 * opened after that fetches again.
 */
data class ReaderArticle(
    /** The article URL — the identity the reader and the controller agree on. */
    val url: String,
    val title: String?,
    val byline: String?,
    val blocks: List<ArticleBlock>,
    /** See [ExtractionResult.Success.totalTextChars]. */
    val totalTextChars: Int,
)
