package app.marmalade.tts.reader

import org.jsoup.nodes.Element

// -----------------------------------------------------------------------------
// LinkFurniture — the link-shaped page furniture Readability leaves in.
//
// On a thin page (a teaser in front of a login wall, say) Readability settles
// on a wrapper that also holds the site's "read next" and "latest news" lists,
// a keyword tag cloud and the headings over them — all of it links to other
// pages. Its cleaned HTML has lost the class names but kept the <a> tags, so
// these rules key on how much of an element's text is link text. Nothing here
// knows about any particular site.
//
// As in ArticleCleanup, every rule is narrow, because a dropped real sentence
// is worse than a spoken junk one: a paragraph with a few inline links, a list
// of plain text, and a list of links that a paragraph introduces with a colon
// ("The founding members were:") are all kept.
// -----------------------------------------------------------------------------

internal object LinkFurniture {

    /** One step of the extractor's walk over Readability's output, in document order. */
    sealed interface Walked {

        /** A block to speak, plus what the link rules need to know about its element. */
        data class Block(
            val block: ArticleBlock,
            /** Every letter of the element's text is inside a link. See [isLinkOnly]. */
            val linkOnly: Boolean,
            /** How many links the element holds. */
            val links: Int,
        ) : Walked

        /**
         * Link furniture removed at this point. Kept as a marker so a heading
         * standing over nothing but furniture can go with it.
         */
        object Removed : Walked
    }

    /** [block], emitted for [element], with the link facts [drop] needs. */
    fun walked(block: ArticleBlock, element: Element): Walked.Block =
        Walked.Block(block, isLinkOnly(element), element.select("a").size)

    /**
     * Is [list] (a `ul`/`ol`) a list of links to elsewhere — a menu, "read
     * next", "latest news"? At least [LINK_LIST_MIN_ITEMS] items, and at least
     * [LINK_LIST_MIN_DENSITY] of its letters inside links: a headline linked
     * with an unlinked date beside it still counts, an item that links a word
     * or two of a sentence does not.
     *
     * Not when the element just before it ends with a colon: "The founding
     * members were:" introduces a list that belongs to the article, however
     * many of its entries are links.
     */
    fun isLinkList(list: Element): Boolean {
        if (list.children().count { it.normalName() == "li" } < LINK_LIST_MIN_ITEMS) return false
        val letters = letters(list.text())
        if (letters == 0) return false
        val linked = list.select("a").sumOf { letters(it.text()) }
        if (linked < letters * LINK_LIST_MIN_DENSITY) return false
        val intro = list.previousElementSibling()?.text()?.trimEnd().orEmpty()
        return !intro.endsWith(':') && !intro.endsWith('：')
    }

    /**
     * Is [element] a container of nothing but links, with no paragraph, list
     * item or heading inside — a tag cloud's row of anchors, a "more stories"
     * link? The walker drops loose text like this anyway; marking it lets the
     * heading above it go too.
     *
     * Not a link inside a run of loose text ("see <a>the map</a> below"):
     * its parent holds words of its own, so it is part of a sentence.
     */
    fun isLooseLinkRow(element: Element): Boolean =
        element.parent()?.ownText().isNullOrBlank() &&
            element.selectFirst(BLOCK_TAGS) == null &&
            isLinkOnly(element)

    /**
     * Does every letter or digit of [element]'s text sit inside a link?
     * Spaces and separators (" · ", " | ") between links don't count.
     */
    private fun isLinkOnly(element: Element): Boolean {
        val letters = letters(element.text())
        return letters > 0 && element.select("a").sumOf { letters(it.text()) } == letters
    }

    /**
     * The blocks of [walked] with the link furniture gone:
     *
     *   1. link rows   — a paragraph or heading that is nothing but
     *                    [LINK_ROW_MIN_LINKS]+ links ("Harbour · Festivals")
     *   2. link runs   — [LINK_RUN_MIN_BLOCKS]+ consecutive short paragraphs or
     *                    headings that are each a single link (a menu that
     *                    isn't a list)
     *   3. headings over nothing but furniture — up to the next heading of
     *                    the same or a higher level, or the end. Walked
     *                    backwards, so a stack of widget headings goes
     *                    together.
     *
     * Rule 3 keeps a heading when real text follows the furniture: that text
     * belongs to the heading's section, whatever sat in between. It also
     * keeps the headings ArticleCleanup cuts the rest of the article at
     * ("Related posts", "See also"): dropping one here would save whatever
     * comes after it, comments included, from that cut.
     */
    fun drop(walked: List<Walked>): List<ArticleBlock> {
        val steps = walked.toMutableList()

        for (i in steps.indices) {
            val step = steps[i] as? Walked.Block ?: continue
            if (step.isProseShaped() && step.linkOnly && step.links >= LINK_ROW_MIN_LINKS) {
                steps[i] = Walked.Removed
            }
        }

        var start = 0
        while (start < steps.size) {
            var end = start
            while (end < steps.size && steps[end].isShortLink()) end++
            if (end - start >= LINK_RUN_MIN_BLOCKS) {
                for (i in start until end) steps[i] = Walked.Removed
            }
            start = maxOf(end, start + 1)
        }

        for (i in steps.indices.reversed()) {
            val heading = (steps[i] as? Walked.Block)?.block as? ArticleBlock.Heading ?: continue
            if (ArticleCleanup.isBoilerplateHeading(heading.text)) continue
            var next = i + 1
            while (next < steps.size && steps[next] is Walked.Removed) next++
            if (next == i + 1) continue
            val after = (steps.getOrNull(next) as? Walked.Block)?.block
            if (after == null || (after is ArticleBlock.Heading && after.level <= heading.level)) {
                steps[i] = Walked.Removed
            }
        }

        return steps.filterIsInstance<Walked.Block>().map { it.block }
    }

    /** Paragraphs and headings; list items are judged as a whole list by [isLinkList]. */
    private fun Walked.Block.isProseShaped() =
        block is ArticleBlock.Paragraph || block is ArticleBlock.Heading

    private fun Walked.isShortLink() =
        this is Walked.Block && isProseShaped() && linkOnly &&
            block.text.length <= LINK_RUN_MAX_CHARS

    private fun letters(s: String): Int = s.count(Char::isLetterOrDigit)

    private const val BLOCK_TAGS = "p, li, h1, h2, h3, h4, h5, h6, blockquote"

    /** A pair of links is already a list; one linked line in a ul is not. */
    private const val LINK_LIST_MIN_ITEMS = 2

    /**
     * Share of a list's letters inside links. A headline list with an
     * unlinked timestamp per item is typically 0.75–0.8; a menu or a tag list
     * is 1.0; a list of sentences with a linked name in each is well under 0.5.
     */
    private const val LINK_LIST_MIN_DENSITY = 0.7

    /** One link alone in a paragraph ("Read the full report") is not a row. */
    private const val LINK_ROW_MIN_LINKS = 2

    /** Two linked lines in a row happen in articles; three short ones are a menu. */
    private const val LINK_RUN_MIN_BLOCKS = 3

    /** A menu entry or a "see more" link is a few words. */
    private const val LINK_RUN_MAX_CHARS = 40
}
