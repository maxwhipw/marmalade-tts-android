package app.marmalade.tts.playback

/**
 * What to render next, and whether the render in flight may take another
 * chunk. Pure: the Narrator lists the segments still to be heard, in play
 * order, and asks.
 *
 * The ahead budget (Max, 2026-09-29, §6 Q2/Q3):
 *  - on-device voices prepare about [ON_DEVICE_BUDGET_MS] of listening ahead
 *    of the listener, across segment boundaries and into the next queued
 *    one-shot — also while paused (only a Navigation pause, which the Narrator
 *    leaves out of the list, stops it);
 *  - cloud voices prepare only as far as today: the rest of the listener's
 *    segment and the next one ([CLOUD_DEPTH_SEGMENTS]) — preparing further
 *    costs money and sends text to the provider for paragraphs the user may
 *    skip.
 * What the listener needs NOW (their own segment with nothing banked) is
 * always allowed.
 */
object RenderPlanner {

    const val ON_DEVICE_BUDGET_MS = 60_000.0

    /** Segments past the listener's that a cloud voice may prepare. */
    const val CLOUD_DEPTH_SEGMENTS = 1

    data class Item(
        val sessionId: Long,
        val segment: Int,
        /** Fully rendered, every chunk from the listener's position still held. */
        val complete: Boolean,
        val cloud: Boolean,
        /** 0 for the segment the listener is in (or will start with), 1 for the next… */
        val segmentsAhead: Int,
        /** Played ms buffered ahead of the listener BEFORE this item, along the play order. */
        val aheadMsBefore: Double,
        /** Played ms of this item's own buffered audio still ahead of the listener. */
        val ownAheadMs: Double,
    )

    /** The first item that still needs rendering and is inside the budget, or null. */
    fun next(items: List<Item>, budgetMs: Double = ON_DEVICE_BUDGET_MS): Item? =
        items.firstOrNull { !it.complete && allowed(it, budgetMs) }

    /** May the render job working on [item] render one more chunk? */
    fun mayContinue(item: Item, budgetMs: Double = ON_DEVICE_BUDGET_MS): Boolean {
        if (item.cloud) return item.segmentsAhead <= CLOUD_DEPTH_SEGMENTS
        val ahead = item.aheadMsBefore + item.ownAheadMs
        return ahead < budgetMs
    }

    private fun allowed(item: Item, budgetMs: Double): Boolean =
        if (item.cloud) {
            item.segmentsAhead <= CLOUD_DEPTH_SEGMENTS
        } else {
            (item.segmentsAhead == 0 && item.aheadMsBefore == 0.0) || item.aheadMsBefore < budgetMs
        }
}
