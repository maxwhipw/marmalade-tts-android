package app.marmalade.tts.ui.reader

import app.marmalade.tts.reader.ArticleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// -----------------------------------------------------------------------------
// The two rules the contents button rests on: when it exists at all, and which
// section it says you're in. Both are plain functions over the article's
// blocks, so neither needs a screen.
// -----------------------------------------------------------------------------

class ReaderTocTest {

    @Test
    fun `an article with no headings has no contents`() {
        val blocks = listOf<ArticleBlock>(
            ArticleBlock.Paragraph("One."),
            ArticleBlock.Paragraph("Two."),
        )

        assertTrue(tocEntriesOf(blocks).isEmpty())
    }

    /** A lone subhead is a page with a subhead, not a page with sections. */
    @Test
    fun `a single heading is not a structure`() {
        val blocks = listOf(
            ArticleBlock.Heading(2, "Intro"),
            ArticleBlock.Paragraph("One."),
        )

        assertTrue(tocEntriesOf(blocks).isEmpty())
    }

    @Test
    fun `headings become entries pointing at their own block`() {
        val entries = tocEntriesOf(article())

        assertEquals(listOf(0, 2, 4), entries.map { it.blockIndex })
        assertEquals(listOf("Intro", "Details", "Outro"), entries.map { it.text })
    }

    /** Depth is relative: an all-h2 page renders flush, not indented once. */
    @Test
    fun `depth is measured from the shallowest heading in the article`() {
        val flat = tocEntriesOf(
            listOf(ArticleBlock.Heading(2, "A"), ArticleBlock.Heading(2, "B")),
        )
        assertEquals(listOf(0, 0), flat.map { it.depth })

        val nested = tocEntriesOf(
            listOf(
                ArticleBlock.Heading(2, "A"),
                ArticleBlock.Heading(3, "A.1"),
                ArticleBlock.Heading(4, "A.1.a"),
            ),
        )
        assertEquals(listOf(0, 1, 2), nested.map { it.depth })
    }

    @Test
    fun `the current section is the last heading at or before the spoken block`() {
        val entries = tocEntriesOf(article())

        assertEquals(0, currentTocEntry(entries, 0)?.blockIndex)
        assertEquals(0, currentTocEntry(entries, 1)?.blockIndex)
        assertEquals(2, currentTocEntry(entries, 2)?.blockIndex)
        assertEquals(2, currentTocEntry(entries, 3)?.blockIndex)
        assertEquals(4, currentTocEntry(entries, 5)?.blockIndex)
    }

    @Test
    fun `text before the first heading belongs to no section`() {
        val entries = tocEntriesOf(
            listOf(
                ArticleBlock.Paragraph("Standfirst."),
                ArticleBlock.Heading(2, "Intro"),
                ArticleBlock.Heading(2, "Outro"),
            ),
        )

        assertNull(currentTocEntry(entries, 0))
        assertEquals(1, currentTocEntry(entries, 1)?.blockIndex)
    }

    @Test
    fun `nothing being spoken highlights nothing`() {
        assertNull(currentTocEntry(tocEntriesOf(article()), null))
    }

    /** Headings at 0, 2 and 4; paragraphs in between. */
    private fun article() = listOf(
        ArticleBlock.Heading(2, "Intro"),
        ArticleBlock.Paragraph("One."),
        ArticleBlock.Heading(2, "Details"),
        ArticleBlock.Paragraph("Two."),
        ArticleBlock.Heading(3, "Outro"),
        ArticleBlock.Paragraph("Three."),
    )
}
