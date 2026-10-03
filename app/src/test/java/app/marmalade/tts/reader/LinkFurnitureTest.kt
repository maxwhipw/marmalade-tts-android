package app.marmalade.tts.reader

import app.marmalade.tts.reader.LinkFurniture.Walked
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [LinkFurniture]'s rules, on hand-built walks and tiny DOMs. */
class LinkFurnitureTest {

    private fun heading(level: Int, text: String) =
        Walked.Block(ArticleBlock.Heading(level, text), linkOnly = false, links = 0)

    private fun prose(text: String) =
        Walked.Block(ArticleBlock.Paragraph(text), linkOnly = false, links = 0)

    private fun link(text: String, links: Int = 1) =
        Walked.Block(ArticleBlock.Paragraph(text), linkOnly = true, links = links)

    private val removed = Walked.Removed

    private fun texts(walked: List<Walked>) = LinkFurniture.drop(walked).map { it.text }

    @Test
    fun `a stack of headings over nothing but furniture goes, up to a higher heading`() {
        val walked = listOf(
            heading(2, "Story"), prose("Text."),
            heading(3, "Key words"), removed,
            heading(2, "Read next"), removed,
            heading(2, "Videos"), heading(2, "Videos"), removed,
        )
        assertEquals(listOf("Story", "Text."), texts(walked))
    }

    @Test
    fun `a heading stays when text follows the furniture or a lower heading does`() {
        val walked = listOf(
            heading(2, "Members"), removed, prose("They meet once a year."),
            heading(2, "History"), removed, heading(3, "Early years"), prose("It began small."),
        )
        assertEquals(
            listOf("Members", "They meet once a year.", "History", "Early years", "It began small."),
            texts(walked),
        )
    }

    @Test
    fun `a heading with nothing under it but no furniture either is left alone`() {
        val walked = listOf(heading(2, "Part one"), heading(2, "Chapter 1"), prose("Text."))
        assertEquals(listOf("Part one", "Chapter 1", "Text."), texts(walked))
    }

    @Test
    fun `three short link-only blocks in a row go, two stay`() {
        assertEquals(
            listOf("Text."),
            texts(listOf(link("Home"), link("News"), link("Sport"), prose("Text."))),
        )
        assertEquals(
            listOf("Home", "News", "Text."),
            texts(listOf(link("Home"), link("News"), prose("Text."))),
        )
    }

    @Test
    fun `a paragraph of two or more links and nothing else goes, a single link stays`() {
        val walked = listOf(link("Read the full report"), link("Boats · Harbour", links = 2))
        assertEquals(listOf("Read the full report"), texts(walked))
    }

    @Test
    fun `link lists are judged by the share of their letters inside links`() {
        fun isLinkList(html: String) = LinkFurniture.isLinkList(Jsoup.parse(html).selectFirst("ul, ol")!!)

        // Headlines, one with an unlinked "2 days ago": still a link list.
        assertTrue(
            isLinkList(
                "<ul><li><a>Lighthouse to be repainted</a> 2 days ago</li>" +
                    "<li><a>Ferry times change</a></li></ul>",
            ),
        )
        // Sentences with a linked name in each: content.
        assertFalse(
            isLinkList(
                "<ul><li><a>The bakery</a>, open from seven until late.</li>" +
                    "<li><a>The café</a>, which serves soup all day.</li></ul>",
            ),
        )
        assertFalse(isLinkList("<ul><li><a>Only one link</a></li></ul>"))
        assertFalse(isLinkList("<ul><li>Plain text.</li><li>More plain text.</li></ul>"))
        // Introduced by a colon, ASCII or full-width.
        assertFalse(
            isLinkList("<div><p>Members:</p><ul><li><a>Portwenn</a></li><li><a>Gullhaven</a></li></ul></div>"),
        )
        assertFalse(isLinkList("<div><p>加盟国：</p><ol><li><a>甲</a></li><li><a>乙</a></li></ol></div>"))
    }

    @Test
    fun `a loose row of links counts, a link inside loose prose does not`() {
        val doc = Jsoup.parse(
            """<div id="tags"><div id="row"><a>Harbour</a> · <a>Boats</a></div></div>""" +
                """<div id="prose">See <span id="inline"><a>the map</a></span> below</div>""" +
                """<div id="block"><p><a>Harbour</a></p></div>""",
        )
        assertTrue(LinkFurniture.isLooseLinkRow(doc.getElementById("row")!!))
        assertFalse(LinkFurniture.isLooseLinkRow(doc.getElementById("inline")!!))
        assertFalse(LinkFurniture.isLooseLinkRow(doc.getElementById("block")!!))
    }
}
