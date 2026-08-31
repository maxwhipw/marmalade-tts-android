package app.marmalade.tts.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule-by-rule tests for [ArticleCleanup], against block-list fixtures taken
 * from the junk Spike A actually saw on real pages.
 *
 * Each rule gets a must-fire case and a must-NOT-fire case. The second is the
 * important one: a spoken junk block costs the user a tap, a dropped real
 * paragraph costs them text they can never get back.
 */
class ArticleCleanupTest {

    private fun p(text: String) = ArticleBlock.Paragraph(text)

    private fun h(text: String, level: Int = 2) = ArticleBlock.Heading(level, text)

    private fun li(text: String) = ArticleBlock.ListItem(text)

    private fun prose(n: Int) = "Paragraph $n runs on for a while, with commas, " +
        "and ends like a sentence should."

    private fun clean(blocks: List<ArticleBlock>, title: String? = null) =
        ArticleCleanup.clean(blocks, title)

    // -- title echo ------------------------------------------------------------

    @Test
    fun `an exact title echo is dropped`() {
        val blocks = listOf(p("Why Kotlin Won"), p(prose(1)))
        assertEquals(listOf(p(prose(1))), clean(blocks, "Why Kotlin Won"))
    }

    @Test
    fun `a title echo is dropped when the title carries a pipe site suffix`() {
        val blocks = listOf(h("Why Kotlin Won", level = 1), p(prose(1)))
        assertEquals(
            listOf(p(prose(1))),
            clean(blocks, "Why Kotlin Won | Ars Technica"),
        )
    }

    @Test
    fun `en dash, em dash and hyphen site suffixes are stripped too`() {
        for (delimiter in listOf("–", "—", "-", "·")) {
            val title = "Why Kotlin Won $delimiter Ars Technica"
            assertEquals(
                "suffix '$delimiter' should strip",
                "Why Kotlin Won",
                ArticleCleanup.stripSiteSuffix(title),
            )
            assertTrue(
                "echo should be recognised for '$title'",
                ArticleCleanup.nearDuplicate("Why Kotlin Won", title),
            )
        }
    }

    @Test
    fun `a delimiter inside a real title is not treated as a site suffix`() {
        val title = "2001 - A Space Odyssey retrospective"
        assertEquals(title, ArticleCleanup.stripSiteSuffix(title))
        assertFalse(ArticleCleanup.nearDuplicate("2001", title))
    }

    @Test
    fun `hyphenated words are never split`() {
        val title = "The state-of-the-art"
        assertEquals(title, ArticleCleanup.stripSiteSuffix(title))
    }

    @Test
    fun `a genuine first paragraph is not mistaken for the title`() {
        val blocks = listOf(p(prose(1)), p(prose(2)))
        assertEquals(blocks, clean(blocks, "Why Kotlin Won"))
    }

    @Test
    fun `a title echo hiding behind a site-name header is still dropped`() {
        val blocks = listOf(p("marmalade"), p("Why Kotlin Won"), p(prose(1)))
        assertEquals(listOf(p(prose(1))), clean(blocks, "Why Kotlin Won"))
    }

    // -- trailing reference sections -------------------------------------------

    @Test
    fun `a references heading in the last half cuts the tail off`() {
        val blocks = listOf(
            p(prose(1)),
            p(prose(2)),
            h("References"),
            li("Smith, J. 1999."),
            li("Jones, A. 2004."),
        )
        assertEquals(listOf(p(prose(1)), p(prose(2))), clean(blocks))
    }

    @Test
    fun `every boilerplate heading spelling is recognised`() {
        val headings = listOf(
            "References", "External links", "Further reading", "See also",
            "Bibliography", "Notes", "Sources", "Related articles",
            "Related posts", "Footnotes",
        )
        for (heading in headings) {
            val blocks = listOf(p(prose(1)), p(prose(2)), h(heading), li("An entry."))
            assertEquals("'$heading' should cut", 2, clean(blocks).size)
        }
    }

    @Test
    fun `the first boilerplate heading wins when there are several`() {
        val body = (1..4).map { p(prose(it)) }
        val blocks = body + listOf(
            h("See also"),
            li("Another article."),
            h("References"),
            li("Smith, J. 1999."),
        )
        assertEquals(body, clean(blocks))
    }

    @Test
    fun `a boilerplate heading in the first half is left alone`() {
        val blocks = listOf(
            h("Notes"),
            p(prose(1)),
            p(prose(2)),
            p(prose(3)),
            p(prose(4)),
        )
        assertEquals(blocks, clean(blocks))
    }

    @Test
    fun `a paragraph that merely mentions references is kept`() {
        val blocks = listOf(
            p(prose(1)),
            p("References are the backbone of the encyclopedia, and this one is no exception."),
        )
        assertEquals(blocks, clean(blocks))
    }

    // -- leading site-header cruft ---------------------------------------------

    @Test
    fun `a bare site-name block above the article is dropped`() {
        val blocks = listOf(p("marmalade"), h("Getting started"), p(prose(1)))
        assertEquals(listOf(h("Getting started"), p(prose(1))), clean(blocks))
    }

    @Test
    fun `comment-widget cruft is dropped`() {
        val blocks = listOf(
            p("Loading comments"),
            p("Getting the conversation ready..."),
            p(prose(1)),
            p(prose(2)),
        )
        assertEquals(listOf(p(prose(1)), p(prose(2))), clean(blocks))
    }

    @Test
    fun `a short opening sentence is kept`() {
        val blocks = listOf(p("It began with a fire."), p(prose(1)))
        assertEquals(blocks, clean(blocks))
    }

    @Test
    fun `a leading heading stops the cruft scan`() {
        val blocks = listOf(h("Intro"), p("marmalade"), p(prose(1)))
        assertEquals(blocks, clean(blocks))
    }

    @Test
    fun `no more than five leading blocks are ever dropped`() {
        val cruft = (1..8).map { p("Widget $it") }
        val cleaned = clean(cruft + p(prose(1)))
        assertEquals(listOf(p("Widget 6"), p("Widget 7"), p("Widget 8"), p(prose(1))), cleaned)
    }

    @Test
    fun `an article of nothing but short lines is left intact`() {
        val blocks = listOf(p("marmalade"), p("Loading comments"))
        assertEquals(blocks, clean(blocks))
    }

    // -- image credits ----------------------------------------------------------

    @Test
    fun `credit lines are dropped wherever they appear`() {
        val blocks = listOf(
            p(prose(1)),
            p("Credit: Sony Pictures"),
            p(prose(2)),
            p("Photo: Getty Images"),
            p(prose(3)),
        )
        assertEquals(listOf(p(prose(1)), p(prose(2)), p(prose(3))), clean(blocks))
    }

    @Test
    fun `every credit keyword and both delimiters are recognised`() {
        val credits = listOf(
            "Credit: Sony Pictures",
            "credit. Sony Pictures",
            "Photo credit: Aurich Lawson",
            "Image credit: Aurich Lawson",
            "Photograph: Reuters",
            "Illustration: Aurich Lawson",
            "Image: NASA",
            "Source: Reuters",
        )
        for (credit in credits) {
            val blocks = listOf(p(prose(1)), p(credit), p(prose(2)))
            assertEquals("'$credit' should be dropped", 2, clean(blocks).size)
        }
    }

    @Test
    fun `a sentence that opens with the word credit is kept`() {
        val long = "The credit: a short history of the ledger, from Renaissance " +
            "Florence to the modern bank, told in five objects."
        val blocks = listOf(p(prose(1)), p(long), p(prose(2)))
        assertEquals(blocks, clean(blocks))
    }

    @Test
    fun `a credit keyword mid-paragraph is not a match`() {
        val blocks = listOf(p(prose(1)), p("The image: credit where it is due."))
        assertEquals(blocks, clean(blocks))
    }

    @Test
    fun `headings and list items are never treated as credit lines`() {
        val blocks = listOf(p(prose(1)), h("Sources: how we counted"), li("Credit: A. Smith"))
        assertEquals(blocks, clean(blocks))
    }
}
