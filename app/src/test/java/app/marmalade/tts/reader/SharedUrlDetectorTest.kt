package app.marmalade.tts.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM unit tests for [SharedUrlDetector]. */
class SharedUrlDetectorTest {

    @Test
    fun `bare url is returned unchanged`() {
        assertEquals(
            "https://example.com/article/2026/kotlin",
            SharedUrlDetector.findUrl("https://example.com/article/2026/kotlin"),
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            "https://example.com/a",
            SharedUrlDetector.findUrl("  https://example.com/a\n"),
        )
    }

    @Test
    fun `title plus newline plus url is the common browser share shape`() {
        val shared = "Why Kotlin Won — Ars Technica\nhttps://arstechnica.com/why-kotlin-won"
        assertEquals("https://arstechnica.com/why-kotlin-won", SharedUrlDetector.findUrl(shared))
    }

    @Test
    fun `url embedded mid prose is found`() {
        val shared = "have a look at http://example.org/posts/42 when you get a sec"
        assertEquals("http://example.org/posts/42", SharedUrlDetector.findUrl(shared))
    }

    @Test
    fun `first http url wins`() {
        val shared = "https://first.example/one and also https://second.example/two"
        assertEquals("https://first.example/one", SharedUrlDetector.findUrl(shared))
    }

    @Test
    fun `trailing sentence punctuation is trimmed`() {
        assertEquals(
            "https://example.com/a",
            SharedUrlDetector.findUrl("Read https://example.com/a."),
        )
        assertEquals(
            "https://example.com/a",
            SharedUrlDetector.findUrl("Read https://example.com/a, then reply"),
        )
        assertEquals(
            "https://example.com/a",
            SharedUrlDetector.findUrl("(see https://example.com/a)"),
        )
    }

    @Test
    fun `balanced parentheses inside the url are kept`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Mercury_(planet)",
            SharedUrlDetector.findUrl("https://en.wikipedia.org/wiki/Mercury_(planet)"),
        )
    }

    @Test
    fun `text with no url returns null`() {
        assertNull(SharedUrlDetector.findUrl("just some text to read aloud, no link here"))
    }

    @Test
    fun `blank and null input return null`() {
        assertNull(SharedUrlDetector.findUrl(null))
        assertNull(SharedUrlDetector.findUrl("   "))
    }

    @Test
    fun `non http schemes are not urls we can fetch`() {
        assertNull(SharedUrlDetector.findUrl("ftp://example.com/file.txt"))
        assertNull(SharedUrlDetector.findUrl("mailto:someone@example.com"))
        assertNull(SharedUrlDetector.findUrl("Grab it from ftp://files.example.org/doc.pdf"))
    }

    @Test
    fun `an http url later in text still wins over an earlier non http scheme`() {
        val shared = "mirror at ftp://files.example.org/doc and https://example.com/doc"
        assertEquals("https://example.com/doc", SharedUrlDetector.findUrl(shared))
    }

    @Test
    fun `scheme with no host is rejected`() {
        assertNull(SharedUrlDetector.findUrl("https://"))
    }

    @Test
    fun `url in japanese prose ends at the ideographic full stop`() {
        assertEquals(
            "https://example.com/a",
            SharedUrlDetector.findUrl("「記事はこちら：https://example.com/a。続きは…」"),
        )
    }

    @Test
    fun `url ends at the ideographic comma and fullwidth bracket`() {
        assertEquals(
            "https://example.com/b",
            SharedUrlDetector.findUrl("（https://example.com/b）を見て"),
        )
        assertEquals(
            "https://example.com/c",
            SharedUrlDetector.findUrl("リンク https://example.com/c、それから"),
        )
    }

    @Test
    fun `unicode spaces end a url`() {
        assertEquals(
            "https://example.com/d",
            SharedUrlDetector.findUrl("see https://example.com/d now"),
        )
        assertEquals(
            "https://example.com/e",
            SharedUrlDetector.findUrl("https://example.com/e　次"),
        )
        assertEquals(
            "https://example.com/f",
            SharedUrlDetector.findUrl("https://example.com/f thin"),
        )
    }

    // -- findLinkShare: is the share essentially just a link? -----------------

    @Test
    fun `a bare url is a link share`() {
        assertEquals("https://example.com/a", SharedUrlDetector.findLinkShare("https://example.com/a"))
        assertEquals("https://example.com/a", SharedUrlDetector.findLinkShare("\n https://example.com/a \n"))
    }

    @Test
    fun `title and url shapes are link shares`() {
        val url = "https://arstechnica.com/why-kotlin-won"
        for (shared in listOf(
            "Why Kotlin Won — Ars Technica\n$url",
            "Why Kotlin Won — Ars Technica $url",
            "Why Kotlin Won\n\n$url",
            "$url\nWhy Kotlin Won",
            "Why Kotlin Won $url via @feedly",
            "Why Kotlin Won\n$url\nShared via Some App",
        )) {
            assertEquals(shared, url, SharedUrlDetector.findLinkShare(shared))
        }
    }

    /** The R16 case: a post or paragraph that happens to contain a link. */
    @Test
    fun `prose containing a link is not a link share`() {
        val paragraph = "We spent the whole weekend testing the new release and honestly it is " +
            "a big step up from the last one. Full write-up here: https://example.com/review " +
            "— let me know what you think."
        assertNull(SharedUrlDetector.findLinkShare(paragraph))
    }

    @Test
    fun `a multi-line message with a link is not a link share`() {
        val message = "Saw this and thought of you\nhttps://example.com/a\nCall me later?\nx"
        assertNull(SharedUrlDetector.findLinkShare("Line one\nline two https://example.com/a"))
        assertNull(SharedUrlDetector.findLinkShare(message))
    }

    @Test
    fun `the label limit is inclusive`() {
        val url = "https://example.com/a"
        val atLimit = "t".repeat(SharedUrlDetector.MAX_LABEL_CHARS)
        assertEquals(url, SharedUrlDetector.findLinkShare("$atLimit $url"))
        assertNull(SharedUrlDetector.findLinkShare("${atLimit}t $url"))
    }

    @Test
    fun `no link means no link share`() {
        assertNull(SharedUrlDetector.findLinkShare("Just words."))
        assertNull(SharedUrlDetector.findLinkShare(null))
    }

    /** Punctuation trimmed off the URL is part of the label, not lost. */
    @Test
    fun `a link share returns the trimmed url`() {
        assertEquals("https://example.com/a", SharedUrlDetector.findLinkShare("Read this: https://example.com/a."))
    }

    @Test
    fun `cjk letters in an iri path are kept`() {
        assertEquals(
            "https://ja.wikipedia.org/wiki/日本",
            SharedUrlDetector.findUrl("https://ja.wikipedia.org/wiki/日本"),
        )
    }
}
