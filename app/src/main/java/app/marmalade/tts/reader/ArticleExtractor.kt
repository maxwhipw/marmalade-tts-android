package app.marmalade.tts.reader

import app.marmalade.tts.service.SpeakDispatcher
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// -----------------------------------------------------------------------------
// ArticleExtractor — raw page bytes → typed reading blocks.
//
// Two-stage, both stages jsoup:
//   1. Parse the fetched bytes (Readability4J needs a Document, and jsoup's
//      stream parser is the only thing that gets the charset right — see
//      below), run Readability4J over it to strip nav/ads/comments.
//   2. Re-parse Readability's cleaned HTML and walk it into typed blocks,
//      then run ArticleCleanup over the list to drop the page furniture
//      Readability leaves behind (site headers, credits, references lists).
//
// The app NEVER renders the extracted HTML. The reader UI composes native
// Compose text from these blocks, which means no WebView, no page JS, no
// remote resource loading — the privacy posture the fetcher establishes
// isn't quietly undone at the render step. It also gives the playback
// pipeline exactly what it needs: an ordered list of speakable units.
//
// Text-only in v1: images are dropped, not downloaded (they live on
// third-party CDNs; skipping them keeps "we contact only the shared host"
// literally true).
//
// Every block fits in one speak request: a block longer than the service's
// per-request cap (SpeakDispatcher.MAX_TEXT_LENGTH) is split into several
// blocks of the same type, at sentence boundaries, so none of it is dropped.
// -----------------------------------------------------------------------------

/** One speakable unit of an extracted article, in document order. */
sealed class ArticleBlock {

    /** The block's text, whitespace-collapsed and trimmed. Never blank. */
    abstract val text: String

    /** `h1`–`h6`. [level] is 1–6. */
    data class Heading(val level: Int, override val text: String) : ArticleBlock()

    /** `p`. The workhorse — most of an article is these. */
    data class Paragraph(override val text: String) : ArticleBlock()

    /** `li`, from either an ordered or unordered list. */
    data class ListItem(override val text: String) : ArticleBlock()

    /** `blockquote`, flattened to its full text (nested `p`s included). */
    data class Quote(override val text: String) : ArticleBlock()
}

/** Outcome of an [ArticleExtractor.extract]. */
sealed class ExtractionResult {

    /**
     * Article extracted.
     *
     * [totalTextChars] is the sum of every surviving block's length — counted
     * after [ArticleCleanup], so it measures what will be read aloud. It is a
     * raw quality signal, nothing more: the "this is too short, offer
     * open-in-browser" threshold lives in ReaderViewModel, because how short is
     * too short depends on what the UI wants to do about it.
     */
    data class Success(
        val title: String?,
        val byline: String?,
        val blocks: List<ArticleBlock>,
        val totalTextChars: Int,
    ) : ExtractionResult()

    /** Readability found no article, or the cleaned content held no text. */
    object ExtractionFailed : ExtractionResult()
}

/**
 * Turns fetched page bytes into an ordered list of [ArticleBlock]s.
 *
 * Pure logic — no I/O, no Android dependencies — so it unit-tests against
 * inline HTML fixtures. Injected by constructor; stateless and thread-safe.
 */
@Singleton
open class ArticleExtractor @Inject constructor() {

    /**
     * Extract the article from [bytes], which were fetched from [finalUrl]
     * (post-redirect — it is the base URI for relative links and the URL
     * Readability4J uses for its own resolution).
     *
     * [bytes] must be the undecoded response body. The charset comes from, in
     * order: a BOM, the `charset=` parameter of the HTTP [contentType], then
     * the document's own `<meta charset>` — the precedence browsers use, and
     * the only way an ISO-8859-1 or Shift_JIS page comes out with its accents
     * intact. jsoup applies the BOM and meta steps itself.
     */
    open fun extract(
        bytes: ByteArray,
        finalUrl: String,
        contentType: String? = null,
    ): ExtractionResult {
        val article = try {
            // charsetName = null → detect from BOM / meta / default UTF-8. A
            // named charset skips the meta sniff but still loses to a BOM.
            val document = Jsoup.parse(
                ByteArrayInputStream(bytes),
                charsetOf(contentType),
                finalUrl,
            )
            Readability4J(finalUrl, document).parse()
        } catch (t: Throwable) {
            // jsoup and Readability4J both walk arbitrary hostile markup.
            // A parse blowing up is a failed extraction, not a crash.
            return ExtractionResult.ExtractionFailed
        }

        val cleanedHtml = article.contentWithDocumentsCharsetOrUtf8
        if (cleanedHtml.isNullOrBlank()) return ExtractionResult.ExtractionFailed

        val title = article.title?.let(::normalise)?.takeIf { it.isNotEmpty() }
        val byline = article.byline?.let(::normalise)?.takeIf { it.isNotEmpty() }

        val walked = mutableListOf<ArticleBlock>()
        collectBlocks(Jsoup.parse(cleanedHtml, finalUrl).body(), walked)
        // extract → title echo → junk filters → split → count. The count comes
        // last on purpose: it is the short-extraction signal, so it has to
        // describe what will be spoken, not what Readability handed over.
        val blocks = ArticleCleanup.clean(walked, title).flatMap(::splitOversized)

        if (blocks.isEmpty()) return ExtractionResult.ExtractionFailed
        return ExtractionResult.Success(
            title = title,
            byline = byline,
            blocks = blocks,
            totalTextChars = blocks.sumOf { it.text.length },
        )
    }

    /**
     * Walk [parent]'s children top-down, emitting a block for each
     * block-level element and recursing into everything else.
     *
     * The "don't descend into what you emitted" rule is what stops a
     * `<blockquote><p>…</p></blockquote>` appearing twice — once as a Quote
     * and again as a Paragraph. Readability's output is full of such
     * nesting, so this is not a theoretical case.
     *
     * Text sitting loose in a `<div>` with no block wrapper is dropped. That
     * costs nothing on real articles (Readability's output is p/li/h*-shaped)
     * and avoids emitting fragments of markup scaffolding as paragraphs.
     */
    private fun collectBlocks(parent: Element, out: MutableList<ArticleBlock>) {
        for (child in parent.children()) {
            val block = blockFor(child)
            if (block != null) {
                out.add(block)
            } else {
                collectBlocks(child, out)
            }
        }
    }

    /**
     * Build a block for [element] if its tag is one we speak, else `null`
     * (meaning "recurse into it"). Blank blocks return `null` too — an empty
     * `<p>` is a spacer, not something to read aloud — which also makes the
     * walker descend into it, harmlessly, since it has no text.
     */
    private fun blockFor(element: Element): ArticleBlock? {
        val tag = element.normalName()
        val text = normalise(element.text())
        if (text.isEmpty()) return null
        return when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" ->
                ArticleBlock.Heading(level = tag[1].digitToInt(), text = text)
            "p" -> ArticleBlock.Paragraph(text)
            "li" -> ArticleBlock.ListItem(text)
            "blockquote" -> ArticleBlock.Quote(text)
            else -> null
        }
    }

    /**
     * Collapse every run of whitespace — including the non-breaking spaces
     * jsoup's `text()` preserves — to a single space, and trim.
     */
    private fun normalise(s: String): String =
        s.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()

    /**
     * [block] as one or more blocks of the same type, each short enough for a
     * single speak request. The reader speaks one request per block, and the
     * service would silently cut anything past [MAX_BLOCK_CHARS].
     */
    private fun splitOversized(block: ArticleBlock): List<ArticleBlock> {
        if (block.text.length <= MAX_BLOCK_CHARS) return listOf(block)
        return splitText(block.text, MAX_BLOCK_CHARS).map { part ->
            when (block) {
                is ArticleBlock.Heading -> block.copy(text = part)
                is ArticleBlock.Paragraph -> block.copy(text = part)
                is ArticleBlock.ListItem -> block.copy(text = part)
                is ArticleBlock.Quote -> block.copy(text = part)
            }
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Longest block text the extractor emits — one speak request's worth. */
        internal const val MAX_BLOCK_CHARS = SpeakDispatcher.MAX_TEXT_LENGTH

        /**
         * A place a sentence ends: after `.!?…` (optionally followed by a
         * closing quote or bracket) and before whitespace, or straight after
         * CJK full-width terminators, which take no space.
         */
        private val SENTENCE_BREAK = Regex("""(?<=[.!?…]['"’”)\]]?)\s|(?<=[。！？])""")

        /**
         * Split [text] into pieces of at most [max] chars, preferring the last
         * sentence end that fits, then the last space, and only as a last
         * resort cutting mid-word. Pieces are trimmed; nothing else is lost.
         */
        internal fun splitText(text: String, max: Int): List<String> {
            val pieces = mutableListOf<String>()
            var rest = text
            while (rest.length > max) {
                // max + 1 so a break sitting right after a full-length piece
                // (the space at index max) still counts.
                val window = rest.substring(0, max + 1)
                val cut = SENTENCE_BREAK.findAll(window)
                    .map { it.range.first }
                    .lastOrNull { it in 1..max }
                    ?: window.lastIndexOf(' ').takeIf { it in 1..max }
                    // Never leave half a surrogate pair on either side.
                    ?: if (Character.isHighSurrogate(rest[max - 1])) max - 1 else max
                pieces += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) pieces += rest
            return pieces
        }

        /**
         * The `charset=` parameter of an HTTP Content-Type, or null when there
         * isn't one or the JVM can't decode it (null lets jsoup fall back to
         * the page's own `<meta charset>`).
         */
        internal fun charsetOf(contentType: String?): String? {
            val name = contentType?.split(';')
                ?.drop(1)
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"', '\'')
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return try {
                name.takeIf { Charset.isSupported(it) }
            } catch (e: IllegalArgumentException) {
                // IllegalCharsetNameException — a header full of junk.
                null
            }
        }
    }
}
