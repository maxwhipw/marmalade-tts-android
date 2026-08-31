package app.marmalade.tts.reader

// -----------------------------------------------------------------------------
// ArticleCleanup — the junk-block rules applied to an extracted block list.
//
// Readability strips nav and ads; what it leaves behind still carries page
// furniture that is fine to *look* at and wrong to *hear*: a bare site name
// above the headline, a comment widget's "Loading comments", an image credit
// dropped between two paragraphs, a hundred-entry references list.
//
// Every rule here is deliberately narrow. A dropped real paragraph is a much
// worse failure than a spoken junk one — the user can skip a junk block with
// one tap, but has no way to get back text we silently deleted. So each rule
// carries a shape constraint (position, length, punctuation) on top of its
// pattern, and the whole pass is a no-op unless something matches exactly.
//
// NOT attempted: paywall / subscription chrome. Spiegel's leaked "subscribe"
// paragraphs are ordinary prose in whatever language the site publishes in;
// keyword matching for them is locale-dependent and would eventually eat real
// sentences. The short-extraction banner (ReaderViewModel) is the mitigation
// for those pages instead.
// -----------------------------------------------------------------------------

/** Pure block-list rules, unit-tested directly on fixture block lists. */
internal object ArticleCleanup {

    /**
     * Order of operations, and why:
     *
     *   1. title echo        — the headline repeated as the first block
     *   2. trailing sections — "References" and friends, plus everything after
     *   3. leading cruft     — short unpunctuated blocks above the article
     *   4. title echo again  — the echo is sometimes the *second* block, sitting
     *                          behind a site-name header that step 3 just took
     *   5. image credits     — "Credit: Sony Pictures" anywhere in the body
     *
     * The caller computes `totalTextChars` from the result, AFTER filtering, so
     * the short-extraction signal reflects what will actually be read aloud.
     *
     * If the rules would empty the article, the original list is returned: a
     * page of nothing but short unpunctuated lines is a page we don't
     * understand, and rendering it is better than claiming extraction failed.
     */
    fun clean(blocks: List<ArticleBlock>, title: String?): List<ArticleBlock> {
        val working = blocks.toMutableList()
        dropTitleEcho(working, title)
        dropTrailingBoilerplate(working)
        dropLeadingCruft(working)
        dropTitleEcho(working, title)
        dropImageCredits(working)
        return if (working.isEmpty()) blocks else working
    }

    // -- 1/4. title echo -------------------------------------------------------

    /**
     * Publishers routinely repeat the headline as the first element of the
     * article body (Ars Technica does; Spike A caught it), and the reader UI
     * already shows the title in its header — so speaking it twice is a
     * consistent, avoidable annoyance. Only the *first* block is considered,
     * and only when it near-duplicates the title.
     */
    private fun dropTitleEcho(blocks: MutableList<ArticleBlock>, title: String?) {
        if (title.isNullOrEmpty() || blocks.isEmpty()) return
        if (nearDuplicate(blocks.first().text, title)) blocks.removeAt(0)
    }

    /**
     * True when two strings are the same modulo punctuation, case, and a short
     * trailing addition.
     *
     * The shape that motivates this is "Why Kotlin won" (block) vs "Why Kotlin
     * won | Ars Technica" (document title): a raw prefix-overlap ratio scores
     * that 14/27 and misses it, so the site suffix comes off the *title* side
     * first — see [stripSiteSuffix] — and the overlap rule then decides.
     */
    fun nearDuplicate(blockText: String, title: String): Boolean {
        val block = comparisonKey(blockText)
        if (block.isEmpty()) return false
        for (candidate in listOf(title, stripSiteSuffix(title))) {
            val key = comparisonKey(candidate)
            if (key.isEmpty()) continue
            if (block == key) return true
            val (shorter, longer) = if (block.length <= key.length) block to key else key to block
            if (longer.startsWith(shorter) &&
                shorter.length >= longer.length * TITLE_ECHO_MIN_OVERLAP
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Drop a trailing " | Site" / " – Site" / " — Site" / " - Site" segment
     * from a document title.
     *
     * Guarded three ways, because these delimiters also appear *inside* real
     * titles ("2001 - A Space Odyssey retrospective", "state-of-the-art"): the
     * delimiter must be free-standing (whitespace on both sides), the tail must
     * be the last segment, and it must be short — never longer than the
     * headline it follows, and a masthead's worth of characters at most.
     */
    fun stripSiteSuffix(title: String): String {
        val index = title.indices.lastOrNull { i ->
            title[i] in SITE_SUFFIX_DELIMITERS &&
                i > 0 && i < title.lastIndex &&
                title[i - 1].isWhitespace() && title[i + 1].isWhitespace()
        } ?: return title
        val head = title.substring(0, index).trim()
        val tail = title.substring(index + 1).trim()
        if (head.isEmpty() || tail.isEmpty()) return title
        if (tail.length > head.length || tail.length > SITE_SUFFIX_MAX_TAIL_CHARS) return title
        return head
    }

    // -- 2. trailing reference / boilerplate sections --------------------------

    /**
     * Wikipedia's references list arrives as several hundred ListItems, and the
     * same shape shows up as "Further reading" or "Related posts" elsewhere.
     * Cut at the first such heading and drop the rest of the article.
     *
     * Only in the last half of the block list, and only on an exact heading
     * match: an article *about* references, or one whose second paragraph
     * mentions sources, keeps every word.
     */
    private fun dropTrailingBoilerplate(blocks: MutableList<ArticleBlock>) {
        val cut = blocks.indices.firstOrNull { i ->
            i >= blocks.size / 2 &&
                blocks[i] is ArticleBlock.Heading &&
                comparisonKey(blocks[i].text) in BOILERPLATE_HEADINGS
        } ?: return
        blocks.subList(cut, blocks.size).clear()
    }

    // -- 3. leading site-header cruft -------------------------------------------

    /**
     * Strip the short, unpunctuated blocks some pages emit above the article
     * proper: a bare site name ("marmalade" on a docs page), a comment
     * widget's "Loading comments" / "Getting the conversation ready…".
     *
     * Stops at the first block that looks like writing — any heading, anything
     * long, anything that ends a sentence — so a legitimately short opening
     * line ("It began with a fire.") is never touched. Capped at
     * [MAX_LEADING_CRUFT_BLOCKS] so a badly-extracted page can't lose its
     * opening section one line at a time.
     */
    private fun dropLeadingCruft(blocks: MutableList<ArticleBlock>) {
        var dropped = 0
        while (dropped < MAX_LEADING_CRUFT_BLOCKS && blocks.isNotEmpty()) {
            val block = blocks.first()
            val droppable = (
                block is ArticleBlock.Paragraph || block is ArticleBlock.ListItem
                ) &&
                block.text.length < LEADING_CRUFT_MAX_CHARS &&
                !endsSentence(block.text)
            if (!droppable) return
            blocks.removeAt(0)
            dropped++
        }
    }

    /**
     * Does [text] end a sentence?
     *
     * A trailing ellipsis does not count: "Getting the conversation ready…" is
     * a spinner label, not prose, and it is exactly the leading cruft the rule
     * above exists to remove.
     */
    fun endsSentence(text: String): Boolean {
        val trimmed = text.trimEnd(*TRAILING_WRAPPERS)
        if (trimmed.endsWith('…') || trimmed.endsWith("...")) return false
        return trimmed.isNotEmpty() && trimmed.last() in SENTENCE_ENDINGS
    }

    // -- 5. image credits ------------------------------------------------------

    /**
     * Ars-style "Credit: Sony Pictures" lines survive Readability as ordinary
     * paragraphs. Match only at the very start of a *short* paragraph: "The
     * credit: a short history of the ledger" neither starts with the keyword
     * nor is short, and a paragraph that merely mentions a photo credit is
     * never a substring match, because the pattern is anchored.
     */
    private fun dropImageCredits(blocks: MutableList<ArticleBlock>) {
        blocks.removeAll { block ->
            block is ArticleBlock.Paragraph &&
                block.text.length < CREDIT_MAX_CHARS &&
                IMAGE_CREDIT.containsMatchIn(block.text)
        }
    }

    // -- shared --------------------------------------------------------------

    /** Collapse to lowercase alphanumerics + single spaces for comparison only. */
    private fun comparisonKey(s: String): String =
        s.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("\\s+")

    /** A leading block must be ≥90% of the title to count as its echo. */
    private const val TITLE_ECHO_MIN_OVERLAP = 0.9

    /** Delimiters publishers put between a headline and their masthead. */
    private val SITE_SUFFIX_DELIMITERS = charArrayOf('|', '–', '—', '-', '·', '»')

    /**
     * A masthead is a few words. Paired with "no longer than the headline it
     * follows", this is what keeps "2001 - A Space Odyssey retrospective"
     * whole.
     */
    private const val SITE_SUFFIX_MAX_TAIL_CHARS = 40

    /** Exact (normalised) headings that end an article's readable body. */
    private val BOILERPLATE_HEADINGS = setOf(
        "references",
        "external links",
        "further reading",
        "see also",
        "bibliography",
        "notes",
        "sources",
        "related articles",
        "related posts",
        "footnotes",
    )

    /** Above this, a leading block is prose whatever it looks like. */
    private const val LEADING_CRUFT_MAX_CHARS = 60

    /** Site headers are one or two lines; more than this is an article. */
    private const val MAX_LEADING_CRUFT_BLOCKS = 5

    /** A credit line is a caption's worth of text, never a paragraph's. */
    private const val CREDIT_MAX_CHARS = 120

    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?', '。', '！', '？')

    /** Closers that can sit after the sentence-ending punctuation. */
    private val TRAILING_WRAPPERS = charArrayOf(
        ' ', '"', '\'', '”', '’', ')', ']', '»',
    )

    private val IMAGE_CREDIT = Regex(
        "^(?:photo credit|image credit|photograph|illustration|photo|image|credit|source)" +
            "\\s*[:.]\\s+\\S",
        RegexOption.IGNORE_CASE,
    )
}
