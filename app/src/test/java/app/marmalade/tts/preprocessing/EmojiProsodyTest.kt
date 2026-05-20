package app.marmalade.tts.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for EmojiProsody — the emoji-to-emotion parser ported from the
 * marmalade-tts CLI's preprocessing layer.
 *
 * Tested behavior:
 *  - The 11-emoji table matches the upstream "paige" speaker checkpoint.
 *  - Empty-/no-emoji text produces Neutral with 0 intensity.
 *  - Intensity scales as N/3, clamped at 1.0.
 *  - Tie-breaking ("last one wins") is decided by latest occurrence in
 *    the string, not by enum order or insertion order.
 *  - stripEmojis() leaves non-emoji text unchanged and produces single-
 *    space-collapsed output when emojis are removed.
 *  - isEmotionEmoji() distinguishes the 11 recognized emojis from
 *    similarly-shaped but unrecognized emojis (📱) and ordinary chars.
 *
 * Not tested here (out of scope for this module — see STUBS.md):
 *  - Integration with the audio-modulation layer (v0.2 work).
 *  - End-to-end TTS dispatch using the detected emotion.
 */
class EmojiProsodyTest {

    // ── detect: no emoji ────────────────────────────────────────────

    @Test
    fun `detect on plain text returns Neutral with zero intensity`() {
        val hint = EmojiProsody.detect("Hello")
        assertEquals(Emotion.Neutral, hint.emotion)
        assertEquals(0f, hint.intensity, 0f)
    }

    @Test
    fun `detect on empty string returns Neutral with zero intensity`() {
        val hint = EmojiProsody.detect("")
        assertEquals(Emotion.Neutral, hint.emotion)
        assertEquals(0f, hint.intensity, 0f)
    }

    @Test
    fun `detect ignores unrecognized emojis like phone`() {
        // 📱 is a valid emoji but is not in the emotion set — should be
        // treated as no-emotion content.
        val hint = EmojiProsody.detect("My 📱 is dead")
        assertEquals(Emotion.Neutral, hint.emotion)
        assertEquals(0f, hint.intensity, 0f)
    }

    // ── detect: single emoji ─────────────────────────────────────────

    @Test
    fun `detect single amused emoji yields intensity one third`() {
        val hint = EmojiProsody.detect("Hello 🤣")
        assertEquals(Emotion.Amused, hint.emotion)
        // 1/3 ≈ 0.3333… — allow a small float tolerance.
        assertEquals(1f / 3f, hint.intensity, 1e-4f)
    }

    @Test
    fun `detect single sad emoji maps to Sad`() {
        val hint = EmojiProsody.detect("I miss you 😭")
        assertEquals(Emotion.Sad, hint.emotion)
    }

    @Test
    fun `detect single angry emoji maps to Angry`() {
        val hint = EmojiProsody.detect("I am so 😡")
        assertEquals(Emotion.Angry, hint.emotion)
    }

    // ── detect: intensity scaling ────────────────────────────────────

    @Test
    fun `detect three identical emojis caps intensity at one`() {
        val hint = EmojiProsody.detect("Hello 🤣🤣🤣")
        assertEquals(Emotion.Amused, hint.emotion)
        assertEquals(1f, hint.intensity, 0f)
    }

    @Test
    fun `detect more than three identical emojis stays at one`() {
        // Five copies — intensity must not exceed 1.0.
        val hint = EmojiProsody.detect("Hello 🤣🤣🤣🤣🤣")
        assertEquals(Emotion.Amused, hint.emotion)
        assertEquals(1f, hint.intensity, 0f)
    }

    // ── detect: tie-breaking ─────────────────────────────────────────

    @Test
    fun `detect tied counts last occurrence wins, sad over amused`() {
        val hint = EmojiProsody.detect("Hello 🤣 then 😭")
        assertEquals(Emotion.Sad, hint.emotion)
        // Two distinct emojis, each count=1 → 1/3 intensity for the
        // winner. (The losing emotion's count does not contribute.)
        assertEquals(1f / 3f, hint.intensity, 1e-4f)
    }

    @Test
    fun `detect three way tie picks the latest emoji, angry`() {
        val hint = EmojiProsody.detect("Hello 🤣 then 😭 then 😡")
        assertEquals(Emotion.Angry, hint.emotion)
    }

    @Test
    fun `detect higher count beats latest occurrence`() {
        // Sad appears twice, amused once at the end. Strongest emotion
        // must win — recency only breaks ties, it does not override
        // counts.
        val hint = EmojiProsody.detect("😭 well 😭 ok 🤣")
        assertEquals(Emotion.Sad, hint.emotion)
        assertEquals(2f / 3f, hint.intensity, 1e-4f)
    }

    // ── stripEmojis ──────────────────────────────────────────────────

    @Test
    fun `stripEmojis removes recognized emoji and collapses whitespace`() {
        // The CLI collapses runs of whitespace and trims; we lock in
        // the single-space form here.
        assertEquals("Hello world", EmojiProsody.stripEmojis("Hello 🤣 world"))
    }

    @Test
    fun `stripEmojis leaves plain text unchanged`() {
        assertEquals("Plain text", EmojiProsody.stripEmojis("Plain text"))
    }

    @Test
    fun `stripEmojis leaves unrecognized emojis alone`() {
        // 📱 isn't in the emotion set — must survive stripping.
        assertEquals("My 📱 is dead", EmojiProsody.stripEmojis("My 📱 is dead"))
    }

    @Test
    fun `stripEmojis handles multiple emojis without leaving blank gaps`() {
        assertEquals(
            "I miss you so much",
            EmojiProsody.stripEmojis("I 😭 miss 😭 you 😭 so much"),
        )
    }

    // ── isEmotionEmoji ───────────────────────────────────────────────

    @Test
    fun `isEmotionEmoji true for recognized emoji`() {
        assertTrue(EmojiProsody.isEmotionEmoji("🤣"))
    }

    @Test
    fun `isEmotionEmoji false for ordinary letter`() {
        assertFalse(EmojiProsody.isEmotionEmoji("a"))
    }

    @Test
    fun `isEmotionEmoji false for non-emotion emoji like phone`() {
        // Phone is an emoji but not in the emotion-bearing set.
        assertFalse(EmojiProsody.isEmotionEmoji("📱"))
    }

    @Test
    fun `isEmotionEmoji recognizes the full upstream eleven`() {
        // The 11 emoji that the EmojiVoice "paige" checkpoint conditions
        // on. If any of these regress, the audio-modulation layer would
        // silently lose an emotion. Worth locking in.
        val all = listOf("😍", "😡", "😎", "😭", "🙄", "😁", "🙂", "🤣", "😮", "😅", "🤔")
        for (e in all) {
            assertTrue("Expected $e to be recognized", EmojiProsody.isEmotionEmoji(e))
        }
    }
}
