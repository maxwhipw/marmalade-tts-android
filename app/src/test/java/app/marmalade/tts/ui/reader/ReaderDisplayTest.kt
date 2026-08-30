package app.marmalade.tts.ui.reader

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
