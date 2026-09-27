package app.marmalade.tts.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers the reader's display model: the preset→color mapping, the "unset
// background follows the app's brightness" rule, and the size arithmetic the
// stepper and the heading scale both depend on.
//
// Plain JVM — nothing here is a composable, which is the whole reason this
// logic lives outside ReaderScreen.kt.
// -----------------------------------------------------------------------------

class ReaderDisplayTest {

    @Test
    fun `unset background follows the app's own brightness`() {
        val prefs = ReaderDisplayPrefs()
        assertEquals(ReaderBackground.Black, prefs.resolveBackground(appIsDark = true))
        assertEquals(ReaderBackground.White, prefs.resolveBackground(appIsDark = false))
    }

    @Test
    fun `an explicit background overrides the app's brightness both ways`() {
        // The point of the setting: a light-app user reads on black, and a
        // dark-app user reads on paper, if that's what they picked.
        assertEquals(
            ReaderBackground.Black,
            ReaderDisplayPrefs(background = ReaderBackground.Black)
                .resolveBackground(appIsDark = false),
        )
        assertEquals(
            ReaderBackground.Paper,
            ReaderDisplayPrefs(background = ReaderBackground.Paper)
                .resolveBackground(appIsDark = true),
        )
    }

    @Test
    fun `every preset's highlight differs from its own background and text`() {
        // The current-block highlight has to stay visible on all three presets
        // without swallowing the text sitting on top of it.
        for (background in ReaderBackground.entries) {
            val palette = background.palette()
            assertNotEquals(
                "$background highlight must be visible against its page",
                palette.background,
                palette.highlight,
            )
            assertNotEquals(
                "$background highlight must not collide with its text",
                palette.text,
                palette.highlight,
            )
        }
    }

    @Test
    fun `every preset's text and muted colors clear WCAG AA on page and highlight`() {
        // Body text and muted text (quotes, byline) both land on the plain
        // page and, for the block being read, on the highlight.
        for (background in ReaderBackground.entries) {
            val palette = background.palette()
            for ((name, fg) in listOf("text" to palette.text, "muted" to palette.muted)) {
                for ((surfaceName, bg) in listOf(
                    "background" to palette.background,
                    "highlight" to palette.highlight,
                )) {
                    val ratio = contrastRatio(fg, bg)
                    assertTrue(
                        "$background $name on $surfaceName is %.2f:1, needs 4.5:1"
                            .format(ratio),
                        ratio >= 4.5,
                    )
                }
            }
        }
    }

    @Test
    fun `contrast helper matches the WCAG reference extremes`() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
    }

    /** WCAG 2.x contrast ratio, (L1 + 0.05) / (L2 + 0.05). */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG 2.x relative luminance of an sRGB color. */
    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    @Test
    fun `font families map to the platform faces, sans inherits the theme`() {
        assertNull("Sans keeps the app's own Manrope", ReaderFont.Sans.fontFamily())
        assertEquals(FontFamily.Serif, ReaderFont.Serif.fontFamily())
        assertEquals(FontFamily.Monospace, ReaderFont.Mono.fontFamily())
    }

    @Test
    fun `text scale is 1 at the default size and grows with it`() {
        assertEquals(1f, ReaderDisplayPrefs().textScale, 0.0001f)
        assertTrue(
            ReaderDisplayPrefs(fontSizeSp = ReaderDisplayPrefs.MAX_FONT_SIZE_SP).textScale > 1f,
        )
        assertTrue(
            ReaderDisplayPrefs(fontSizeSp = ReaderDisplayPrefs.MIN_FONT_SIZE_SP).textScale < 1f,
        )
    }

    @Test
    fun `clamping keeps stored or stepped sizes inside the supported range`() {
        assertEquals(ReaderDisplayPrefs.MIN_FONT_SIZE_SP, clampReaderFontSize(2))
        assertEquals(ReaderDisplayPrefs.MAX_FONT_SIZE_SP, clampReaderFontSize(999))
        assertEquals(18, clampReaderFontSize(18))
    }

    @Test
    fun `stored names parse back, and junk falls back rather than crashing`() {
        assertEquals(ReaderBackground.Paper, readerBackgroundOf("Paper"))
        assertNull(readerBackgroundOf(null))
        assertNull("an unknown preset is 'unset', not a crash", readerBackgroundOf("Sepia"))

        assertEquals(ReaderFont.Mono, readerFontOf("Mono"))
        assertEquals(ReaderFont.Sans, readerFontOf(null))
        assertEquals(ReaderFont.Sans, readerFontOf("Comic"))
    }
}
