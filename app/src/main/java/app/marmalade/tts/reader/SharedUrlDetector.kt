package app.marmalade.tts.reader

// -----------------------------------------------------------------------------
// SharedUrlDetector — pull the article URL out of ACTION_SEND text/plain.
//
// Browsers are wildly inconsistent about what they put in EXTRA_TEXT: some
// share the bare URL, some share "Page title\nhttps://…", some paste a
// sentence with the link embedded. All three land in ShareIntentActivity as
// one plain string, so the reader entry point needs a single "is there an
// article link in here?" answer.
//
// Deliberately conservative: only http/https counts (a mailto:/ftp:/intent:
// string is not something the reader can fetch), and the FIRST such URL wins
// — for a title+URL share the title never contains a link, and for prose the
// first link is the one the user was talking about.
// -----------------------------------------------------------------------------

object SharedUrlDetector {

    /**
     * Matches an http(s) URL up to the first whitespace or bracketing
     * character. Trailing sentence punctuation is glued on by this pattern
     * on purpose — [trimTrailingPunctuation] strips it afterwards, where
     * bracket balancing can be taken into account.
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"' ]+""", RegexOption.IGNORE_CASE)

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
