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
 *
 * Touch exploration (TalkBack and friends) switches it off altogether: a
 * screen-reader user moves through the article by accessibility focus, and a
 * list scrolling itself on every block boundary drags that focus away from
 * whatever they were exploring. Their spoken position comes from the audio,
 * not from the viewport.
 */
object ReaderAutoScroll {

    /** How long a user drag suppresses auto-scroll for. */
    const val USER_SCROLL_GRACE_MS = 4_000L

    /**
     * @param nowMillis monotonic clock reading.
     * @param lastUserScrollMillis when the user last dragged the list, or 0 if
     *   they never have.
     * @param userIsScrolling true while a drag/fling is in progress.
     * @param touchExplorationEnabled true while a touch-exploration
     *   accessibility service (TalkBack) is running.
     */
    fun shouldAutoScroll(
        nowMillis: Long,
        lastUserScrollMillis: Long,
        userIsScrolling: Boolean,
        touchExplorationEnabled: Boolean,
    ): Boolean {
        if (touchExplorationEnabled) return false
        if (userIsScrolling) return false
        if (lastUserScrollMillis == 0L) return true
        return nowMillis - lastUserScrollMillis >= USER_SCROLL_GRACE_MS
    }
}
