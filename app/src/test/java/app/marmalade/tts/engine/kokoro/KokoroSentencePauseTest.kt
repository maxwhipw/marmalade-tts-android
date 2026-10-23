package app.marmalade.tts.engine.kokoro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroSentencePauseTest {

    // Kokoro's vocab: 0 pad, 3 ',', 4 '.', 5 '!', 6 '?', 16 space; above 16 are sounds.
    private val two = intArrayOf(0, 50, 51, 4, 16, 52, 53, 4, 0) // "ab. cd."
    private val twoDur = longArrayOf(1, 5, 5, 6, 4, 5, 5, 8, 2)

    @Test
    fun `a pause goes mid-way through a sentence end's pause run with speech after it`() {
        // The run "., space" spans frames 11..21: its middle is frame 16.
        assertEquals(listOf(16), KokoroSentencePause.spliceFrames(two, twoDur))
    }

    @Test
    fun `no splice at a comma, at the piece's end, or before any speech`() {
        val comma = intArrayOf(0, 50, 3, 16, 51, 4, 0)
        assertEquals(emptyList<Int>(), KokoroSentencePause.spliceFrames(comma, LongArray(comma.size) { 3 }))
        val leading = intArrayOf(0, 4, 16, 50, 0)
        assertEquals(emptyList<Int>(), KokoroSentencePause.spliceFrames(leading, LongArray(leading.size) { 3 }))
    }

    @Test
    fun `a piece ends a sentence when its trailing pause run holds a sentence end`() {
        assertTrue(KokoroSentencePause.endsSentence(two))
        assertTrue(KokoroSentencePause.endsSentence(intArrayOf(0, 50, 6, 15, 0)))
        assertFalse(KokoroSentencePause.endsSentence(intArrayOf(0, 50, 3, 0)))
        assertFalse(KokoroSentencePause.endsSentence(intArrayOf(0, 50, 0)))
    }

    @Test
    fun `at Natural a piece gets nothing, so it renders as before`() {
        assertNull(KokoroSentencePause.forPiece(two, twoDur, 0, lastPiece = false, sampleRate = 24_000))
    }

    @Test
    fun `a piece's pause holds its splices and, unless it is the last, the trailing pause`() {
        val p = KokoroSentencePause.forPiece(two, twoDur, 500, lastPiece = false, sampleRate = 24_000)!!
        assertEquals(listOf(16 * 600), p.spliceSamples)
        assertEquals(12_000, p.silence)
        assertTrue(p.trailing)
        assertFalse(KokoroSentencePause.forPiece(two, twoDur, 500, lastPiece = true, sampleRate = 24_000)!!.trailing)
        val oneSentence = intArrayOf(0, 50, 51, 4, 0)
        assertNull(KokoroSentencePause.forPiece(oneSentence, LongArray(5) { 2 }, 500, lastPiece = true, sampleRate = 24_000))
    }

    @Test
    fun `silence lands at the quietest cut near the splice`() {
        val pcm = ShortArray(1000) { 1000 }
        for (i in 590..610) pcm[i] = 0
        val out = KokoroSentencePause.insertSilence(pcm, offset = 0, atSamples = listOf(500), silence = 50)
        assertEquals(1050, out.size)
        // The cut moved into the quiet run (both neighbours 0), at the point nearest 500.
        assertArrayEquals(pcm.copyOfRange(0, 591), out.copyOfRange(0, 591))
        assertTrue(out.copyOfRange(591, 641).all { it == 0.toShort() })
        assertArrayEquals(pcm.copyOfRange(591, 1000), out.copyOfRange(641, 1050))
    }

    @Test
    fun `a splice outside this slice leaves it untouched`() {
        val pcm = ShortArray(600) { 5 }
        assertSame(pcm, KokoroSentencePause.insertSilence(pcm, offset = 600, atSamples = listOf(300, 1300), silence = 50))
        assertEquals(650, KokoroSentencePause.insertSilence(pcm, offset = 600, atSamples = listOf(900), silence = 50).size)
    }

    @Test
    fun `the trailing pause follows the piece's last audio only`() {
        val p = KokoroSentencePause.Piece(emptyList(), silence = 10, trailing = true)
        val pcm = ShortArray(20) { 3 }
        assertEquals(20, p.apply(pcm, 0, last = false).size)
        assertEquals(30, p.apply(pcm, 0, last = true).size)
    }
}
