package app.marmalade.tts.engine

/**
 * An engine's estimate of how much of the audio it has already emitted is
 * still waiting to be heard — what decides whether a new request is a
 * "cold start" worth cutting its first sentence for (T8, TTFA assessment
 * 2026-09-28, `docs/release/ttfa-chunking-lab.html`).
 *
 * The services play one engine's output back to back, so emitted audio is
 * modelled as a queue that drains in real time from the moment each chunk
 * is sent: `busyUntil = max(busyUntil, now) + playedMs`. A request
 * prefetched behind a playing one therefore sees seconds of audio still
 * ahead of it and keeps whole sentences; a request after an idle spell, a
 * Stop or a reader tap sees none and gets the short first piece.
 *
 * It is an estimate, and deliberately errs cheap: a pause makes the queue
 * look drained too early (an unneeded cut — a comma seam), and a Stop after
 * rendering finished makes it look full (no cut — today's TTFA). A stream
 * that ends by cancellation or failure clears it, since what it was
 * feeding has been cut off.
 */
class PlaybackHorizon(private val nowNs: () -> Long = System::nanoTime) {

    private var busyUntilNs = 0L
    private var busy = false

    /** Milliseconds of emitted audio still ahead of the listener (≥ 0). */
    @Synchronized
    fun remainingMs(): Long {
        if (!busy) return 0
        return ((busyUntilNs - nowNs()) / 1_000_000).coerceAtLeast(0)
    }

    /** [playedMs] of audio (on the played clock) was just sent downstream. */
    @Synchronized
    fun onEmitted(playedMs: Long) {
        val now = nowNs()
        val start = if (busy && busyUntilNs > now) busyUntilNs else now
        busyUntilNs = start + playedMs * 1_000_000
        busy = true
    }

    /** The stream feeding the queue was cancelled or failed. */
    @Synchronized
    fun clear() {
        busy = false
    }
}
