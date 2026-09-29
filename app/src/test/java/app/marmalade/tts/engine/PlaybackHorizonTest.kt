package app.marmalade.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHorizonTest {

    private var nowMs = 0L
    private val horizon = PlaybackHorizon { nowMs * 1_000_000 }

    @Test
    fun nothingEmittedMeansNothingAhead() {
        assertEquals(0, horizon.remainingMs())
    }

    @Test
    fun emittedAudioDrainsInRealTime() {
        horizon.onEmitted(3000)
        assertEquals(3000, horizon.remainingMs())
        nowMs = 1000
        assertEquals(2000, horizon.remainingMs())
        nowMs = 5000
        assertEquals(0, horizon.remainingMs())
    }

    @Test
    fun chunksQueueBehindEachOther() {
        horizon.onEmitted(3000)
        nowMs = 1000
        horizon.onEmitted(2000) // plays after the first: busy until 5 s
        assertEquals(4000, horizon.remainingMs())
    }

    @Test
    fun aChunkAfterAStallStartsFromNow() {
        horizon.onEmitted(1000)
        nowMs = 4000
        horizon.onEmitted(2000)
        assertEquals(2000, horizon.remainingMs())
    }

    @Test
    fun clearDropsWhatWasQueued() {
        horizon.onEmitted(3000)
        horizon.clear()
        assertEquals(0, horizon.remainingMs())
        horizon.onEmitted(500)
        assertEquals(500, horizon.remainingMs())
    }
}
