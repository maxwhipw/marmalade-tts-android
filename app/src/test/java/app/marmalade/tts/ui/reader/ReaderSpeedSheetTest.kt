package app.marmalade.tts.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The speed sheet's chip set. Reading starts at the primary alias's own
 * speed, which need not be one of the curated chips — the current speed must
 * still show as a selected chip.
 */
class ReaderSpeedSheetTest {

    @Test
    fun `a curated speed shows just the curated chips`() {
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f), readerSpeedChoices(1.25f, starting = 1.0f))
    }

    @Test
    fun `a non-standard alias speed gets its own chip in sorted position`() {
        assertEquals(listOf(0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 2.0f), readerSpeedChoices(1.1f, starting = 1.1f))
    }

    /**
     * After picking 1.5× the alias's own 1.1× must still be offered — it used
     * to vanish with the selection, leaving no way back to it.
     */
    @Test
    fun `the alias's own speed stays after picking another`() {
        assertEquals(listOf(0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 2.0f), readerSpeedChoices(1.5f, starting = 1.1f))
    }

    @Test
    fun `a speed outside the curated range lands at the matching end`() {
        assertEquals(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), readerSpeedChoices(0.5f, starting = 0.5f))
    }
}
