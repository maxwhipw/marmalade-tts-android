package app.marmalade.tts.service

import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import app.marmalade.tts.service.MarmaladeSynthService.Companion.playPauseKeyPauses
import app.marmalade.tts.service.MarmaladeSynthService.Companion.sessionActions
import app.marmalade.tts.service.MarmaladeSynthService.Companion.stateForRequestStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What MarmaladeSynthService's media session advertises, and when. Media
 * buttons (headset, Bluetooth, `cmd media_session dispatch`) are dropped by
 * the platform unless the session's PlaybackState lists the matching action,
 * so these decide whether a stop or pause is heard at all.
 */
class MediaSessionStateTest {

    @Test
    fun `a request starting from idle is buffering, not stateless`() {
        for (idle in listOf(
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.STATE_STOPPED,
            PlaybackStateCompat.STATE_PAUSED,
        )) {
            assertEquals(PlaybackStateCompat.STATE_BUFFERING, stateForRequestStart(idle))
        }
    }

    @Test
    fun `a request following one that plays keeps the session playing`() {
        assertEquals(
            PlaybackStateCompat.STATE_PLAYING,
            stateForRequestStart(PlaybackStateCompat.STATE_PLAYING),
        )
    }

    @Test
    fun `stop and pause are always advertised`() {
        val actions = sessionActions(ReaderTransportState())
        for (action in listOf(
            PlaybackStateCompat.ACTION_STOP,
            PlaybackStateCompat.ACTION_PAUSE,
            PlaybackStateCompat.ACTION_PLAY_PAUSE,
            PlaybackStateCompat.ACTION_PLAY,
        )) {
            assertTrue(actions and action != 0L)
        }
        assertEquals(0L, actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        assertEquals(0L, actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
    }

    @Test
    fun `the reader adds skips only where it can step`() {
        val reading = ReaderTransportState(
            articleUrl = "https://example.org/a",
            active = true,
            canNext = true,
            canPrevious = false,
        )
        val actions = sessionActions(reading)
        assertTrue(actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
        assertEquals(0L, actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
    }

    @Test
    fun `a play-pause toggle pauses while buffering`() {
        assertTrue(
            playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, PlaybackStateCompat.STATE_BUFFERING),
        )
        assertTrue(
            playPauseKeyPauses(KeyEvent.KEYCODE_HEADSETHOOK, PlaybackStateCompat.STATE_BUFFERING),
        )
    }

    @Test
    fun `other keys and states keep the default media-button handling`() {
        assertFalse(
            playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, PlaybackStateCompat.STATE_PLAYING),
        )
        assertFalse(
            playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, PlaybackStateCompat.STATE_PAUSED),
        )
        assertFalse(
            playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_STOP, PlaybackStateCompat.STATE_BUFFERING),
        )
        assertFalse(
            playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PAUSE, PlaybackStateCompat.STATE_BUFFERING),
        )
    }
}
