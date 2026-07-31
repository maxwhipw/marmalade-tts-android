package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [KittenTrim] — the duration-exact lead/tail trim ported from
 * the CLI daemon's `_trim_run`. Pure sequence ops, no model needed.
 */
class KittenTrimTest {

    private val frame = KittenTrim.FRAME_SAMPLES

    /** Wrapped ids for "speech": [pad, phoneme, phoneme, ',', end, pad]. */
    private val ids = intArrayOf(PAD_TOKEN, 50, 51, 3, KITTEN_END_TOKEN, PAD_TOKEN)

    private fun wav(totalFrames: Long) = FloatArray((totalFrames * frame).toInt()) { 1f }

    @Test
    fun trimsLeadPadToHeadKeep() {
        // BOS pad renders 18 frames; keep HEAD_KEEP (2), cut 16.
        val dur = longArrayOf(18, 4, 4, 2, 1, 5)
        val out = KittenTrim.trim(ids, wav(dur.sum()), dur)!!
        val cutLead = (18 - KittenTrim.HEAD_KEEP_FRAMES) * frame
        // Tail group = ',' (2) + end (1) + pad (5) = 8 frames, keep 3 → cut 5.
        val cutTail = (8 - KittenTrim.TAIL_KEEP_FRAMES) * frame
        assertEquals(wav(dur.sum()).size - cutLead - cutTail, out.size.toLong())
    }

    @Test
    fun tailGroupStopsAtFirstSpeechToken() {
        // No trailing punctuation: [pad, ph, ph, end, pad] — group is
        // end+pad only; the speech token's frames are untouched.
        val ids2 = intArrayOf(PAD_TOKEN, 50, 51, KITTEN_END_TOKEN, PAD_TOKEN)
        val dur = longArrayOf(2, 4, 6, 1, 4)
        val out = KittenTrim.trim(ids2, wav(dur.sum()), dur)!!
        // Lead: dur[0]=2 ≤ HEAD_KEEP → nothing cut. Tail group 5, keep 3.
        assertEquals((wav(dur.sum()).size - 2 * frame).toLong(), out.size.toLong())
    }

    @Test
    fun shortPadsAreLeftAlone() {
        // Lead and tail both within the keep margins → identity slice.
        val dur = longArrayOf(1, 4, 4, 1, 1, 1)
        val out = KittenTrim.trim(ids, wav(dur.sum()), dur)!!
        assertEquals(wav(dur.sum()).size, out.size)
    }

    @Test
    fun brokenDurationContractReturnsNull() {
        val dur = longArrayOf(18, 4, 4, 2, 1, 5)
        assertNull(KittenTrim.trim(ids, wav(dur.sum() + 1), dur))     // wrong length
        assertNull(KittenTrim.trim(ids, wav(10), longArrayOf(2, 8)))  // ids/dur mismatch
        assertNull(KittenTrim.trim(IntArray(0), FloatArray(0), LongArray(0)))
    }

    @Test
    fun allSilenceInputReturnsNullNotEmpty() {
        // Pathological: every token non-speech; start would cross end.
        val ids2 = intArrayOf(PAD_TOKEN, 3, KITTEN_END_TOKEN, PAD_TOKEN)
        val dur = longArrayOf(10, 2, 1, 10)
        assertNull(KittenTrim.trim(ids2, wav(dur.sum()), dur))
    }
}
