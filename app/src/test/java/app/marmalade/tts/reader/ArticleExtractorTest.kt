package app.marmalade.tts.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** A latin-1 page whose only charset signal is the HTTP header. */
    private val latin1NoMeta = """
        <html>
          <head><title>Café Culture</title></head>
          <body><article>
            <p>Le café était très chaud, ${filler(3)}</p>
            <p>${filler(3)}</p>
          </article></body>
        </html>
    """.trimIndent()

    @Test
    fun `charset is honoured from the http content type`() {
        val result = extractor.extract(
            latin1NoMeta.toByteArray(Charsets.ISO_8859_1),
            BASE_URL,
            contentType = "text/html; charset=ISO-8859-1",
        ) as ExtractionResult.Success
        val joined = result.blocks.joinToString(" ") { it.text }
        assertTrue("accents mangled: $joined", joined.contains("Le café était très chaud"))
    }

    @Test
    fun `a byte order mark beats the http content type`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val result = extractor.extract(
            bom + latin1NoMeta.toByteArray(Charsets.UTF_8),
            BASE_URL,
            contentType = "text/html; charset=ISO-8859-1",
        ) as ExtractionResult.Success
        val joined = result.blocks.joinToString(" ") { it.text }
        assertTrue("BOM ignored: $joined", joined.contains("Le café était très chaud"))
    }

    @Test
    fun `charsetOf reads the charset parameter`() {
        assertEquals("ISO-8859-1", ArticleExtractor.charsetOf("text/html; charset=ISO-8859-1"))
        assertEquals("utf-8", ArticleExtractor.charsetOf("text/html;Charset=\"utf-8\""))
        assertEquals(
            "Shift_JIS",
            ArticleExtractor.charsetOf("text/html; foo=bar; charset=Shift_JIS"),
        )
    }

    @Test
    fun `charsetOf ignores missing, unknown and malformed charsets`() {
        assertNull(ArticleExtractor.charsetOf(null))
        assertNull(ArticleExtractor.charsetOf("text/html"))
        assertNull(ArticleExtractor.charsetOf("text/html; charset="))
        assertNull(ArticleExtractor.charsetOf("text/html; charset=no-such-charset"))
        assertNull(ArticleExtractor.charsetOf("text/html; charset=bad name!"))
    }

    // -- oversized blocks --------------------------------------------------

    @Test
    fun `a paragraph over the speak cap is split at sentences and nothing is lost`() {
        val sentence = "This sentence is one of very many in an enormous paragraph. "
        val huge = sentence.repeat(ArticleExtractor.MAX_BLOCK_CHARS / sentence.length * 2 + 3)
            .trim()
        val html = page(title = "Long", body = "<p>$huge</p>")

        val result = extract(html) as ExtractionResult.Success

        assertTrue("expected a split, got ${result.blocks.size}", result.blocks.size >= 3)
        assertTrue(result.blocks.all { it is ArticleBlock.Paragraph })
        assertTrue(result.blocks.all { it.text.length <= ArticleExtractor.MAX_BLOCK_CHARS })
        assertTrue(
            "every piece should end on a sentence",
            result.blocks.all { it.text.endsWith(".") },
        )
        assertEquals(huge, result.blocks.joinToString(" ") { it.text })
    }

    @Test
    fun `splitText falls back to spaces, then to a hard cut`() {
        assertEquals(
            listOf("aaa bbb", "ccc"),
            ArticleExtractor.splitText("aaa bbb ccc", max = 8),
        )
        assertEquals(
            listOf("abcd", "efgh", "ij"),
            ArticleExtractor.splitText("abcdefghij", max = 4),
        )
    }

    @Test
    fun `splitText breaks after cjk full stops without needing a space`() {
        assertEquals(
            listOf("一二三。", "四五六。", "七八"),
            ArticleExtractor.splitText("一二三。四五六。七八", max = 5),
        )
    }

    @Test
    fun `splitText leaves text under the cap alone`() {
        assertEquals(listOf("Short. Text."), ArticleExtractor.splitText("Short. Text.", max = 50))
    }

    // -- reference / navigation furniture ------------------------------------

    /**
     * The shape of a Wikipedia article (Seville orange, emulator run
     * 2026-09-28): an infobox whose cells became spoken blocks ("C. ×
     * aurantium", ", 1753", "List", a synonym list), a hatnote, heading edit
     * links, citation superscripts, and a navbox at the bottom.
     */
    private val wikiPage = page(
        title = "Bitter orange",
        body = """
            <div class="hatnote">For the tree used as rootstock, see Trifoliate orange.</div>
            <table class="infobox biota">
              <tr><th>Bitter orange</th></tr>
              <tr><td><p><i>C. × aurantium</i></p></td></tr>
              <tr><td><p>, 1753</p></td></tr>
              <tr><td><div class="collapsible">List</div>
                <ul><li>Citrus bigaradia Loisel.</li><li>Citrus vulgaris Risso</li></ul></td></tr>
            </table>
            <p>The bitter orange is a hybrid citrus tree.<sup class="reference"><a href="#cite_note-1">[1]</a></sup> ${filler(3)}</p>
            <h2>Production<span class="mw-editsection"><span>[</span><a href="/edit">edit</a><span>]</span></span></h2>
            <p>${filler(2)} Brazil grew the most, followed by China and Mexico.<sup class="reference"><a href="#cite_note-2">[2]</a></sup></p>
            <p>${filler(2)} It was first described in 1753.<sup class="noprint Inline-Template">[<i><a href="/wiki/Citation_needed"><span>citation needed</span></a></i>]</sup></p>
            <table class="navbox"><tr><td><ul><li>Citron</li><li>Pomelo</li><li>Mandarin orange</li></ul></td></tr></table>
        """.trimIndent(),
    )

    @Test
    fun `infoboxes, navboxes and hatnotes are not read out`() {
        val result = extract(wikiPage) as ExtractionResult.Success
        val texts = result.blocks.map { it.text }

        for (furniture in listOf("aurantium", "bigaradia", "Trifoliate", "Pomelo")) {
            assertTrue("'$furniture' leaked into $texts", texts.none { furniture in it })
        }
        assertFalse(texts.any { it == ", 1753" || it == "List" || it == "Bitter orange" })
        assertTrue(texts.first().startsWith("The bitter orange is a hybrid citrus tree. This"))
    }

    @Test
    fun `citation markers and edit links are stripped`() {
        val result = extract(wikiPage) as ExtractionResult.Success
        val texts = result.blocks.map { it.text }

        assertTrue("$texts", texts.none { "[1]" in it || "[2]" in it || "citation needed" in it })
        assertTrue("$texts", texts.any { it.endsWith("followed by China and Mexico.") })
        assertTrue("$texts", texts.any { it.endsWith("It was first described in 1753.") })
        assertTrue("${result.blocks}", ArticleBlock.Heading(2, "Production") in result.blocks)
    }

    @Test
    fun `only a lone bracketed superscript counts as a footnote marker`() {
        val html = page(
            title = "Arrays",
            body = """
                <p>${filler(3)} Read arr[1] before writing it.<sup><a href="#fn3">[3]</a></sup></p>
                <p>${filler(3)} The room is 12 m<sup>2</sup> and the list [a] stays.</p>
            """.trimIndent(),
        )
        val texts = (extract(html) as ExtractionResult.Success).blocks.map { it.text }

        assertTrue(texts.any { it.endsWith("Read arr[1] before writing it.") })
        assertTrue(texts.any { it.endsWith("The room is 12 m2 and the list [a] stays.") })
    }

    // -- page landmarks and link furniture ----------------------------------

    /**
     * The shape of a news page whose full body sits behind a login (a
     * national broadcaster's site, device run 2026-10-03; text made up here):
     * a title, a date line and a two-sentence teaser, surrounded by a section
     * menu, a keyword tag cloud, "read next" / "in depth" link lists, a
     * sidebar of duplicated widget headings, a breadcrumb, a consent modal
     * and a footer. Readability settles on a wrapper around most of it.
     */
    private fun linkFurnitureItem(n: Int, headline: String) =
        """<li><a href="/news/$n"><div><div><img src="/img/$n.jpg" alt="$headline"></div>""" +
            """<div><p>$headline</p><p>3 October 16:0$n</p></div></div></a></li>"""

    private val newsPage = """
        <html>
          <head><title>Harbour festival returns after six years, and the town is ready | Example News</title></head>
          <body>
            <dialog aria-label="Menu"><div><ul></ul></div></dialog>
            <div>
              <header><div><button><span>Menu</span></button></div></header>
              <div>
                <nav><div><a href="/news">News</a><ul>
                  <li><a href="/latest">Latest</a></li><li><a href="/society">Society</a></li>
                  <li><a href="/politics">Politics</a></li><li><a href="/business">Business</a></li>
                  <li><a href="/world">World</a></li><li><a href="/science">Science &amp; culture</a></li>
                  <li><a href="/sport">Sport</a></li><li><a href="/living">Living</a></li>
                  <li><a href="/depth">In depth</a></li><li><a href="/regions">Regions</a></li>
                  <li><a href="/video">Video &amp; shows</a></li>
                </ul></div></nav>
                <main><div><div>
                  <div>
                    <div><div>
                      <h1>Harbour festival returns after six years</h1>
                      <div><div><time>3 October 2026 5:02</time><button><span>Share</span></button></div>
                        <a href="/topics/harbour">Harbour</a></div>
                    </div></div>
                    <p>The harbour festival opens on Saturday for the first time in six years, and organisers expect boats from all along the coast to join the…</p>
                    <div><h3>Key words</h3><div><a href="/t/harbour">Harbour</a><a href="/t/festivals">Festivals</a></div></div>
                  </div>
                  <div><h2>Read next</h2><ul>
                    ${linkFurnitureItem(1, "Council votes to repaint the old lighthouse in its original colours")}
                    ${linkFurnitureItem(2, "Ferry timetable changes for the winter season announced")}
                    ${linkFurnitureItem(3, "Local bakery wins a regional prize for its seaweed bread")}
                    ${linkFurnitureItem(4, "School choir to sing at the opening of the new library")}
                  </ul></div>
                  <div><h2>In depth</h2><div><ul>
                    ${linkFurnitureItem(5, "Why the fishing fleet keeps shrinking")}
                    ${linkFurnitureItem(6, "The family that has kept the tide tables for a century")}
                    ${linkFurnitureItem(7, "What a quieter harbour sounds like at night")}
                  </ul><span><a href="/depth">More in-depth stories</a></span></div></div>
                  <div>
                    <div><div><h2>Latest video</h2></div><div><h2>Latest video</h2></div>
                      <span><a href="/video">Watch the videos</a></span></div>
                    <div><h2>Weather</h2><h2>Weather</h2><span><a href="/weather">Check the forecast</a></span></div>
                    <div><h2>Latest news</h2><h2>Latest news</h2><ul>
                      ${linkFurnitureItem(8, "Road closed after a landslip near the quarry")}
                      ${linkFurnitureItem(9, "Rowing club celebrates its fiftieth year")}
                    </ul><span><a href="/latest">All the latest news</a></span></div>
                    <div><h2>Local news</h2><h2>Local news</h2>
                      <div><svg viewBox="0 0 10 10"><title>Map</title><g><path d="M0,0h10v10z"></path></g></svg>
                        <p>Choose on the map</p></div></div>
                  </div>
                </div></div></main>
                <nav aria-label="Breadcrumb"><ol>
                  <li><a href="/">Example News home</a></li><li><a href="/world">World news list</a></li>
                  <li>Harbour festival returns after six years</li>
                </ol></nav>
              </div>
              <dialog>
                <h2>Before you continue</h2>
                <p>Example News is free for everyone to read, but some services need an account, and you can read more about how we use your data on our policy pages before you continue.</p>
                <h3>Services you can use</h3><p>Live streams of every programme</p>
                <p>Tick the box to continue</p>
              </dialog>
              <footer><p>Copyright Example News. All rights reserved.</p></footer>
            </div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun `nav, footer and dialog landmarks are never read out`() {
        val texts = (extract(newsPage) as ExtractionResult.Success).blocks.map { it.text }

        for (chrome in listOf("Sport", "Regions", "Video & shows", "World news list")) {
            assertFalse("menu item '$chrome' leaked into $texts", chrome in texts)
        }
        assertTrue("$texts", texts.none { "Before you continue" in it || "Tick the box" in it })
        assertTrue("$texts", texts.none { "Copyright" in it })
        // The article itself survives: headline, date line, teaser.
        assertEquals("Harbour festival returns after six years", texts[0])
        assertEquals("3 October 2026 5:02", texts[1])
        assertTrue("$texts", texts[2].startsWith("The harbour festival opens on Saturday"))
    }

    @Test
    fun `aside and navigation or complementary roles inside the article are dropped`() {
        val html = page(
            title = "Landmarks",
            body = """
                <p>${filler(3)}</p>
                <aside><p>Sponsored: a word from our partners, who make fine boats.</p></aside>
                <div role="complementary"><p>Related: another story you might enjoy reading.</p></div>
                <div role="navigation"><p>Previous page, next page, back to the top.</p></div>
                <p>${filler(3)}</p>
            """.trimIndent(),
        )
        val texts = (extract(html) as ExtractionResult.Success).blocks.map { it.text }

        assertEquals("$texts", 2, texts.size)
        assertTrue(texts.all { it.startsWith("This is filler sentence") })
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
