package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePauseTest {

    @Test
    fun `the stops and their paragraph pauses are Max's 1·5x rule`() {
        assertEquals(listOf(0, 250, 500, 1000), SentencePause.entries.map { it.sentenceMs })
        assertEquals(listOf(0, 375, 750, 1500), SentencePause.entries.map { it.paragraphMs })
    }

    @Test
    fun `a stored value maps to its stop, an odd one to the nearest`() {
        assertEquals(SentencePause.NATURAL, SentencePause.fromMs(0))
        assertEquals(SentencePause.NATURAL, SentencePause.fromMs(-5))
        assertEquals(SentencePause.MEDIUM, SentencePause.fromMs(500))
        assertEquals(SentencePause.SHORT, SentencePause.fromMs(300))
        assertEquals(SentencePause.LONG, SentencePause.fromMs(5000))
        assertEquals(750, SentencePause.paragraphMsFor(500))
    }

    @Test
    fun `every on-device engine takes it, cloud voices don't`() {
        assertTrue(SentencePause.appliesTo(KokoroDirectVoiceCatalog.ENGINE))
        assertTrue(SentencePause.appliesTo(PocketVoiceCatalog.ENGINE))
        assertFalse(SentencePause.appliesTo(CloudApiVoiceCatalog.ENGINE))
    }
}
