package app.marmalade.tts.preprocessing

/*
 * Data flow:
 *
 *   caller text (String)
 *      │
 *      ├──► EmojiProsody.detect(text) ──► ProsodyHint(Emotion, intensity)
 *      │       walks code points, looks up each emoji in EMOJI_TO_EMOTION,
 *      │       tallies counts per emotion, picks the highest-count emotion
 *      │       (ties broken by most-recent occurrence), intensity = min(1, n/3)
 *      │
 *      └──► EmojiProsody.stripEmojis(text) ──► cleaned text
 *              walks code points, drops anything in EMOJI_TO_EMOTION,
 *              collapses resulting whitespace runs
 *
 * Downstream:
 *   - ProsodyHint feeds the v0.2 audio-modulation layer (pitch/rate/volume
 *     curves on the AudioTrack write path). For now it is data-only.
 *   - stripEmojis() output feeds non-emojivoice engines (kitten, etc.) so
 *     they do not phonemize emojis as "loudly crying face" via espeak.
 *
 * Source of truth:
 *   Ported from marmalade-tts/marmalade_tts/engines/emojivoice.py — the
 *   exact 11 emoji set verified for the upstream "paige" speaker checkpoint.
 *   Category names chosen to match the user-facing labels in the CLI README
 *   ("amused", "sad", "angry") and the semantic content of each emoji.
 */

/**
 * Emotion category produced by an emoji. Stable names — these are the
 * labels the audio-modulation layer (added later) reads to apply
 * pitch/rate/volume curves.
 *
 * The 11 non-Neutral entries correspond 1:1 to the 11 emoji baked into
 * the EmojiVoice "paige" speaker checkpoint (see emojivoice.py EMOJI_SPK).
 * Neutral is the no-emoji default.
 */
enum class Emotion {
    Neutral,
    Loving,      // 😍
    Angry,       // 😡
    Cool,        // 😎
    Sad,         // 😭
    Sarcastic,   // 🙄
    Happy,       // 😁
    Calm,        // 🙂
    Amused,      // 🤣
    Surprised,   // 😮
    Nervous,     // 😅
    Thoughtful,  // 🤔
}

/**
 * A prosody hint extracted from a span of input text. The audio-
 * modulation layer (v0.2+) will read these and apply curves to the
 * synthesized PCM. For now, this struct is data-only.
 *
 * @param emotion The dominant emotion of the span. Neutral if no
 *                recognized emoji was present.
 * @param intensity 0.0..1.0 — N copies of the same emoji → min(1, N/3).
 *                  Single emoji = 0.33, three or more = 1.0. Always 0
 *                  when [emotion] is Neutral.
 */
data class ProsodyHint(
    val emotion: Emotion,
    val intensity: Float,
)

object EmojiProsody {

    /**
     * The 11 emoji that the EmojiVoice "paige" checkpoint conditions on,
     * mapped to our stable Emotion labels. Ported verbatim from
     * `marmalade_tts/engines/emojivoice.py`. Do not add new emojis here
     * without a corresponding speaker id in the upstream checkpoint —
     * the audio-modulation layer keys on these labels.
     */
    private val EMOJI_TO_EMOTION: Map<String, Emotion> = mapOf(
        "😍" to Emotion.Loving,      // 😍 U+1F60D
        "😡" to Emotion.Angry,       // 😡 U+1F621
        "😎" to Emotion.Cool,        // 😎 U+1F60E
        "😭" to Emotion.Sad,         // 😭 U+1F62D
        "🙄" to Emotion.Sarcastic,   // 🙄 U+1F644
        "😁" to Emotion.Happy,       // 😁 U+1F601
        "🙂" to Emotion.Calm,        // 🙂 U+1F642
        "🤣" to Emotion.Amused,      // 🤣 U+1F923
        "😮" to Emotion.Surprised,   // 😮 U+1F62E
        "😅" to Emotion.Nervous,     // 😅 U+1F605
        "🤔" to Emotion.Thoughtful,  // 🤔 U+1F914
    )

    /**
     * Detect the dominant emotion from a string. If the string contains
     * no recognized emoji, returns Emotion.Neutral with intensity 0.
     *
     * Multiple distinct emojis: the most-frequent emotion wins; if two
     * or more emotions tie on count, the one whose occurrence is latest
     * in the string wins (most recent emotional context).
     *
     * Intensity: N occurrences of the winning emotion → min(1, N/3).
     */
    fun detect(text: String): ProsodyHint {
        // Tally counts and remember the index of the last occurrence of
        // each emotion so we can break count ties by recency.
        val counts = HashMap<Emotion, Int>()
        val lastIndex = HashMap<Emotion, Int>()

        forEachRecognizedEmoji(text) { emotion, indexInText ->
            counts[emotion] = (counts[emotion] ?: 0) + 1
            lastIndex[emotion] = indexInText
        }

        if (counts.isEmpty()) {
            return ProsodyHint(Emotion.Neutral, 0f)
        }

        // Highest count wins; ties broken by latest occurrence.
        var winner: Emotion = Emotion.Neutral
        var winnerCount = -1
        var winnerLastIndex = -1
        for ((emotion, count) in counts) {
            val idx = lastIndex.getValue(emotion)
            val takeIt = count > winnerCount ||
                (count == winnerCount && idx > winnerLastIndex)
            if (takeIt) {
                winner = emotion
                winnerCount = count
                winnerLastIndex = idx
            }
        }

        val intensity = minOf(1f, winnerCount / 3f)
        return ProsodyHint(winner, intensity)
    }

    /**
     * Strip recognized emotion-bearing emojis from text. Used for engines
     * other than emojivoice — they read the cleaned text while the
     * audio-modulation layer reads the original to extract prosody.
     *
     * Whitespace runs left behind by stripped emojis are collapsed to a
     * single space, then leading/trailing whitespace is trimmed. This
     * matches the CLI's behavior (see preprocessing.py: the `_emoji`
     * rule replaces with a space, and the final pass in `preprocess()`
     * collapses whitespace).
     */
    fun stripEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var stripped = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val unitLen = Character.charCount(cp)
            val emoji = text.substring(i, i + unitLen)
            if (EMOJI_TO_EMOTION.containsKey(emoji)) {
                sb.append(' ')
                stripped = true
            } else {
                sb.append(emoji)
            }
            i += unitLen
        }
        if (!stripped) return text
        // Collapse runs of whitespace to a single space, then trim ends.
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Whether a given codepoint sequence is a recognized emotion emoji.
     * Internal helper, but exposed for tests.
     */
    internal fun isEmotionEmoji(emoji: String): Boolean =
        EMOJI_TO_EMOTION.containsKey(emoji)

    /**
     * Walk [text] one Unicode code point at a time and invoke [block]
     * for every recognized emotion emoji, passing the matched [Emotion]
     * and the UTF-16 index of the emoji's first code unit (used as a
     * monotonic "position" for tie-breaking).
     *
     * Single-codepoint lookup is correct for our 11-emoji set — every
     * entry in EMOJI_TO_EMOTION is exactly one code point. Skin-tone
     * modifiers and ZWJ sequences are simply not in the map and so are
     * ignored, which is the desired behavior.
     */
    private inline fun forEachRecognizedEmoji(
        text: String,
        block: (Emotion, Int) -> Unit,
    ) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val unitLen = Character.charCount(cp)
            val token = text.substring(i, i + unitLen)
            val emotion = EMOJI_TO_EMOTION[token]
            if (emotion != null) {
                block(emotion, i)
            }
            i += unitLen
        }
    }
}
