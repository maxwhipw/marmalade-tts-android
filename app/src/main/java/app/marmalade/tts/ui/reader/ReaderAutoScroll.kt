package app.marmalade.tts.ui.reader

/**
 * The one rule behind the reader's auto-scroll, kept out of the composable so
 * it can be tested: follow the highlighted block, but never fight the reader.
 *
 * Someone who has just scrolled is looking at something — pulling the list out
 * from under them mid-gesture, or a beat after they stopped, is the classic
 * way an auto-scrolling reader becomes unusable. So a recent drag suppresses
 * the next auto-scroll entirely; the highlight still moves, and the next block
 * boundary after the grace period brings the view back.
 */
object ReaderAutoScroll {

    /** How long a user drag suppresses auto-scroll for. */
    const val USER_SCROLL_GRACE_MS = 4_000L

    /**
     * @param nowMillis monotonic clock reading.
     * @param lastUserScrollMillis when the user last dragged the list, or 0 if
     *   they never have.
     * @param userIsScrolling true while a drag/fling is in progress.
     */
    fun shouldAutoScroll(
        nowMillis: Long,
        lastUserScrollMillis: Long,
        userIsScrolling: Boolean,
    ): Boolean {
        if (userIsScrolling) return false
        if (lastUserScrollMillis == 0L) return true
        return nowMillis - lastUserScrollMillis >= USER_SCROLL_GRACE_MS
    }
}
