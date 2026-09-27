package app.marmalade.tts.reader

// -----------------------------------------------------------------------------
// SharedUrlDetector — pull the article URL out of ACTION_SEND text/plain.
//
// Apps are wildly inconsistent about what they put in EXTRA_TEXT. Chrome and
// Firefox share the bare URL (the page title goes in EXTRA_SUBJECT, which we
// don't read); others share "Page title\nhttps://…", "Page title https://…"
// or "Title https://… via @app"; and a post, message or paragraph shared as
// text may just happen to contain a link. All of them land in
// ShareIntentActivity as one plain string.
//
// Two questions, two functions:
//  - findUrl: is there an article link in here at all? Only http/https counts
//    (a mailto:/ftp:/intent: string is not something the reader can fetch),
//    and the FIRST such URL wins — a title never contains a link, and in
//    prose the first link is the one the user was talking about.
//  - findLinkShare: is this share essentially just a link? That, and only
//    that, is what opens the reader; prose that contains a link is read aloud
//    as text. See findLinkShare for the rule.
// -----------------------------------------------------------------------------

object SharedUrlDetector {

    /**
     * The most text a link share may carry besides its URL — room for a page
     * title (with a " | Site name" or " - Site" suffix) and a short "via @app"
     * tag. News headlines run well under this; a post or paragraph that
     * happens to contain a link runs over it.
     */
    internal const val MAX_LABEL_CHARS = 120

    /**
     * The URL to open in the reader when [sharedText] is essentially a link,
     * or `null` when it isn't (no link at all, or prose with a link in it).
     *
     * The rule: take the first URL ([findUrl]); what is left on either side of
     * it is the label. The share is a link share when that label is
     *  - empty (a bare URL, whitespace allowed), or
     *  - short: at most [MAX_LABEL_CHARS] characters in all, and no line
     *    break inside the text before the URL nor inside the text after it.
     *
     * So "https://…", "Title\nhttps://…", "Title https://…" and
     * "Title\nhttps://…\nvia @app" all open the reader, while a paragraph, a
     * multi-line message, or a long post with a link in it is spoken.
     */
    fun findLinkShare(sharedText: String?): String? {
        val url = findUrl(sharedText) ?: return null
        val text = sharedText.orEmpty()
        val start = text.indexOf(url)
        val before = text.substring(0, start).trim()
        val after = text.substring(start + url.length).trim()
        val isLabel = before.length + after.length <= MAX_LABEL_CHARS &&
            before.none(::isLineBreak) &&
            after.none(::isLineBreak)
        return url.takeIf { isLabel }
    }

    private fun isLineBreak(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '

    /**
     * Matches an http(s) URL up to the first whitespace or bracketing
     * character. Trailing sentence punctuation is glued on by this pattern
     * on purpose — [trimTrailingPunctuation] strips it afterwards, where
     * bracket balancing can be taken into account.
     *
     * "Whitespace" is Unicode's, not just ASCII `\s`: `\p{Z}` covers NBSP and
     * the ideographic space. CJK prose never puts a space after a link either,
     * so the CJK symbols-and-punctuation block (U+3000–U+303F: 。、「」) and the
     * fullwidth forms (U+FF00–U+FFEF: ：（）) end a URL too — none of them is a
     * legal unescaped URL character. CJK letters are NOT terminators: an IRI
     * path like `/wiki/日本` is a real link.
     */
    private val URL_PATTERN = Regex(
        """https?://[^\s\p{Z}<>"'\u3000-\u303F\uFF00-\uFFEF]+""",
        RegexOption.IGNORE_CASE,
    )

    /** Punctuation that is never meaningfully the last character of a URL. */
    private const val TRAILING_PUNCTUATION = ".,;:!?\"'…"

    /** Closing brackets, stripped only when the URL has no matching opener. */
    private val CLOSING_BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')

    /**
     * Returns the first http/https URL in [sharedText], or `null` when there
     * isn't one. The returned string has trailing sentence punctuation
     * removed ("see https://example.com/a." → "https://example.com/a") but is
     * otherwise unmodified — no normalisation, no scheme rewriting.
     */
    fun findUrl(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        for (match in URL_PATTERN.findAll(sharedText)) {
            val trimmed = trimTrailingPunctuation(match.value)
            // A bare "https://" (or "http://.") survives the regex but has no
            // host — keep scanning rather than handing the fetcher garbage.
            if (hasHost(trimmed)) return trimmed
        }
        return null
    }

    /**
     * Strip characters that a human's sentence contributed rather than the
     * URL: full stops, commas, quotes, and *unbalanced* closing brackets.
     * Wikipedia-style URLs legitimately end in `)` — e.g.
     * `…/Mercury_(planet)` — so a closing bracket only comes off when the
     * URL contains no matching opener.
     */
    private fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val drop = when {
                TRAILING_PUNCTUATION.indexOf(c) >= 0 -> true
                c in CLOSING_BRACKETS -> {
                    val opener = CLOSING_BRACKETS.getValue(c)
                    val body = url.substring(0, end - 1)
                    body.count { it == opener } <= body.count { it == c }
                }
                else -> false
            }
            if (!drop) break
            end--
        }
        return url.substring(0, end)
    }

    /** True when something follows the `scheme://` prefix. */
    private fun hasHost(url: String): Boolean {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        val host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return host.isNotEmpty() && host.any { it.isLetterOrDigit() }
    }
}
