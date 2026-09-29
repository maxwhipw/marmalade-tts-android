package app.marmalade.tts.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's session speed, shared live with [MarmaladeSynthService] so a
 * speed change reaches requests that are already queued or playing without
 * restarting them.
 *
 * Every on-device engine renders at 1.0 and the user's speed is a Tempo
 * time-stretch (see [applySpeedFallback]). The service runs that stretch on
 * the playback side, right before the AudioTrack, and reads [current] for it
 * every ~100 ms slice — so changing the value here is audible after the
 * AudioTrack's own ~250 ms buffer, with no re-synthesis.
 *
 * A request can't follow a change once the speed is baked into its audio:
 * an engine that renders speed itself (cloud), or the batched emoji-prosody
 * path, which applies the whole effect chain in the producer. The service
 * marks those [fixed] when it resolves them, and [change] then reports false
 * so the reader falls back to re-enqueueing from the current block.
 *
 * One value, not one per request: the reader is the only sender of session
 * speeds and all its requests share one. It sets the value on every speak
 * ([set]), so a new article's starting speed replaces the last article's.
 *
 * A plain in-process @Singleton rather than a service intent: the reader needs
 * the "could it be applied live?" answer synchronously, and a value here also
 * reaches speak intents still in flight to the service.
 */
@Singleton
class LiveSessionSpeed @Inject constructor() {

    private val lock = Any()

    @Volatile private var speed: Float? = null

    /** Request ids resolved with their speed baked in. Guarded by [lock]. */
    private val fixed = HashSet<Long>()

    /** Reader: the speed its requests (sent now or earlier) play at. */
    fun set(speed: Float) {
        synchronized(lock) { this.speed = speed }
    }

    /**
     * Reader: move the session to [speed] for [requestIds], its requests
     * already handed to the service. True when every one of them follows the
     * change live; false when at least one has its speed baked in, and the
     * caller must re-enqueue them to be heard at [speed].
     */
    fun change(requestIds: Collection<Long>, speed: Float): Boolean = synchronized(lock) {
        this.speed = speed
        requestIds.none { it in fixed }
    }

    /**
     * Service: the session speed to synthesise [requestId] at, as it resolves
     * it. [liveCapable] false marks it fixed — a later [change] can't reach it.
     * Atomic with [change], so a change can't slip between this read and the
     * mark and be lost. [fallback] (the request's own extra) only applies if
     * the reader never set a value in this process.
     */
    fun resolve(requestId: Long, fallback: Float, liveCapable: Boolean): Float =
        synchronized(lock) {
            if (!liveCapable) fixed += requestId
            speed ?: fallback
        }

    /** Service playback: the speed to stretch to right now. */
    fun current(fallback: Float): Float = speed ?: fallback

    /** Service: [requestId] is finished with; forget its fixed mark. */
    fun release(requestId: Long) {
        synchronized(lock) { fixed -= requestId }
    }
}
