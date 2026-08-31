package app.marmalade.tts.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ArticleExtractor] against inline HTML fixtures.
 *
 * Fixtures are padded with real sentences on purpose: Readability scores
 * candidate elements by text length and comma count, so a three-word
 * fixture gets discarded as boilerplate and tells us nothing.
 */
class ArticleExtractorTest {

    private val extractor = ArticleExtractor()

    private val BASE_URL = "https://example.com/articles/marmalade"

    private fun extract(html: String, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        extractor.extract(html.toByteArray(charset), BASE_URL)

    /** Enough prose that Readability treats the containing element as content. */
    private fun filler(n: Int) = (1..n).joinToString(" ") {
        "This is filler sentence number $it, long enough to carry weight, " +
            "with commas, so the scorer keeps it."
    }

    private fun page(title: String, body: String) = """
        <html>
          <head><title>$title</title></head>
          <body>
            <nav><a href="/">Home</a><a href="/about">About</a></nav>
            <article>$body</article>
            <footer><p>Copyright example.com</p></footer>
          </body>
        </html>
    """.trimIndent()

    // -- basic shape -------------------------------------------------------

    @Test
    fun `article becomes typed blocks in document order`() {
        val html = page(
            title = "Marmalade Ships",
            body = """
                <p>${filler(3)}</p>
                <h2>The second section</h2>
                <p>${filler(3)}</p>
                <ul><li>First point, which is short.</li><li>Second point, also short.</li></ul>
                <blockquote><p>Quoted wisdom, ${filler(1)}</p></blockquote>
            """.trimIndent(),
        )

        val result = extract(html)
        assertTrue("expected Success, got $result", result is ExtractionResult.Success)
        result as ExtractionResult.Success

        assertEquals("Marmalade Ships", result.title)
        val kinds = result.blocks.map { it::class.simpleName }
        assertEquals(
            listOf("Paragraph", "Heading", "Paragraph", "ListItem", "ListItem", "Quote"),
            kinds,
        )
        assertEquals(2, (result.blocks[1] as ArticleBlock.Heading).level)
        assertEquals("The second section", result.blocks[1].text)
        assertEquals("First point, which is short.", result.blocks[3].text)
        assertEquals(result.blocks.sumOf { it.text.length }, result.totalTextChars)
    }

    @Test
    fun `whitespace is collapsed and blank blocks are dropped`() {
        val html = page(
            title = "Whitespace",
            body = """
                <p>   Spread    over
                       several   lines.   ${filler(2)}</p>
                <p></p>
                <p>   </p>
                <p>${filler(2)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks[0].text.startsWith("Spread over several lines."))
        assertFalse(result.blocks.any { it.text.isBlank() })
    }

    // -- the two extraction gotchas Spike A found --------------------------

    @Test
    fun `a first paragraph echoing the title is dropped`() {
        val html = page(
            title = "Why Kotlin Won",
            body = """
                <p>Why Kotlin Won</p>
                <p>${filler(3)}</p>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        assertEquals("Why Kotlin Won", result.title)
        assertFalse(
            "title echo should not survive as a block",
            result.blocks.any { it.text == "Why Kotlin Won" },
        )
        assertEquals(2, result.blocks.size)
    }

    @Test
    fun `a near-duplicate title echo with trailing publisher chrome is dropped`() {
        val html = page(
            title = "Why Kotlin Won",
            body = """
                <h1>Why Kotlin Won!</h1>
                <p>${filler(3)}</p>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks.all { it is ArticleBlock.Paragraph })
    }

    @Test
    fun `a genuine first paragraph is not mistaken for the title`() {
        val html = page(
            title = "Why Kotlin Won",
            body = """
                <p>${filler(3)}</p>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        assertEquals(2, result.blocks.size)
    }

    @Test
    fun `a blockquote wrapping paragraphs emits one quote, not duplicates`() {
        val html = page(
            title = "Quoting",
            body = """
                <p>${filler(3)}</p>
                <blockquote>
                  <p>The first quoted line, which runs on for a while.</p>
                  <p>The second quoted line, which also runs on.</p>
                </blockquote>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        val quotes = result.blocks.filterIsInstance<ArticleBlock.Quote>()
        assertEquals(1, quotes.size)
        assertTrue(quotes[0].text.contains("The first quoted line"))
        assertTrue(quotes[0].text.contains("The second quoted line"))
        // The nested <p>s must not also appear as standalone paragraphs.
        assertFalse(
            result.blocks.filterIsInstance<ArticleBlock.Paragraph>()
                .any { it.text.contains("quoted line") },
        )
    }

    @Test
    fun `a title echo with a publisher suffix on the title is dropped`() {
        val html = page(
            title = "Why Kotlin Won | Ars Technica",
            body = """
                <p>Why Kotlin Won</p>
                <p>${filler(3)}</p>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        assertFalse(
            "echo behind a site suffix should not survive: ${result.blocks}",
            result.blocks.any { it.text == "Why Kotlin Won" },
        )
        assertEquals(2, result.blocks.size)
    }

    @Test
    fun `junk filtering happens before the character count`() {
        val html = page(
            title = "Counting",
            body = """
                <p>marmalade</p>
                <p>${filler(3)}</p>
                <p>Credit: Sony Pictures</p>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )

        val result = extract(html) as ExtractionResult.Success
        assertEquals(2, result.blocks.size)
        assertEquals(result.blocks.sumOf { it.text.length }, result.totalTextChars)
    }

    // -- charset -----------------------------------------------------------

    @Test
    fun `charset is honoured from the meta tag in the raw bytes`() {
        val html = """
            <html>
              <head>
                <meta charset="ISO-8859-1">
                <title>Café Culture</title>
              </head>
              <body><article>
                <p>Le café était très chaud, ${filler(3)}</p>
                <p>${filler(3)}</p>
              </article></body>
            </html>
        """.trimIndent()

        val result = extract(html, Charsets.ISO_8859_1) as ExtractionResult.Success
        val joined = result.blocks.joinToString(" ") { it.text }
        assertTrue("accents mangled: $joined", joined.contains("Le café était très chaud"))
        assertFalse("replacement char present: $joined", joined.contains('�'))
    }

    // -- failure modes -----------------------------------------------------

    @Test
    fun `empty input fails extraction`() {
        assertEquals(ExtractionResult.ExtractionFailed, extractor.extract(ByteArray(0), BASE_URL))
    }

    @Test
    fun `binary garbage fails extraction rather than throwing`() {
        val garbage = ByteArray(512) { (it * 31 % 256 - 128).toByte() }
        assertEquals(ExtractionResult.ExtractionFailed, extractor.extract(garbage, BASE_URL))
    }

    @Test
    fun `a page with no text content fails extraction`() {
        val html = "<html><head><title>Nothing</title></head><body><div></div></body></html>"
        assertEquals(ExtractionResult.ExtractionFailed, extract(html))
    }
}
