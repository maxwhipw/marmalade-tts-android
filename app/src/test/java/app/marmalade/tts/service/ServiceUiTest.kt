package app.marmalade.tts.service

import android.media.AudioManager
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import app.marmalade.tts.playback.ArticleView
import app.marmalade.tts.playback.ErrorNotice
import app.marmalade.tts.playback.FailureKind
import app.marmalade.tts.playback.FocusChange
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.OneShotView
import app.marmalade.tts.playback.Origin
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What MarmaladeSynthService shows for each Narrator state — the table that
 * replaced its ~15 scattered notification/media-session updates. Media buttons
 * (headset, Bluetooth, `cmd media_session dispatch`) are dropped by the
 * platform unless the session's PlaybackState lists the matching action, so
 * these rows decide whether a stop or pause is heard at all.
 */
class ServiceUiTest {

    private val none = PlaybackStateCompat.STATE_NONE
    private val playing = PlaybackStateCompat.STATE_PLAYING

    private fun article(status: Status, index: Int = 1, count: Int = 4) =
        ArticleView("https://example.org/a", count, index, 1f, 1f, status, text = "Paragraph $index. ".repeat(10))

    private fun shot(status: Status) = OneShotView(7, Origin.External, status, queued = 0, text = "A shared text.")

    private fun render(state: NarratorState, previous: Int = none) = ServiceUi.render(state, previous)

    @Test
    fun `nothing on air - no controls, the service may stop`() {
        for (state in listOf(
            NarratorState(),
            NarratorState(article = article(Status.Idle)),
            NarratorState(article = article(Status.Finished)),
            NarratorState(article = article(Status.Failed(FailureKind.FAILED))),
        )) {
            val ui = render(state)
            assertFalse(ui.onAir)
            assertFalse(ui.showStop)
            assertFalse(ui.wakeLock)
            assertNull(ui.articleKey)
        }
        assertEquals(PlaybackStateCompat.STATE_STOPPED, render(NarratorState(), previous = playing).mediaState)
    }

    @Test
    fun `a session starting from idle is buffering, not stateless`() {
        for (previous in listOf(none, PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.STATE_PAUSED)) {
            assertEquals(PlaybackStateCompat.STATE_BUFFERING, render(NarratorState(oneShot = shot(Status.Buffering)), previous).mediaState)
        }
        assertEquals(ServiceUi.Text.Preparing, render(NarratorState(oneShot = shot(Status.Buffering))).text)
    }

    @Test
    fun `a session following one that plays keeps the session playing`() {
        assertEquals(playing, render(NarratorState(oneShot = shot(Status.Buffering)), previous = playing).mediaState)
        assertEquals(playing, render(NarratorState(article = article(Status.Buffering)), previous = playing).mediaState)
    }

    /**
     * #94: between blocks of one read (a skip, a block re-rendered, the
     * article resuming after a share) the session stays PLAYING until the read
     * stops or pauses, so the controls don't flicker to "loading".
     */
    @Test
    fun `buffering inside an ongoing read stays playing`() {
        var previous = none
        val seen = listOf(
            NarratorState(article = article(Status.Buffering)),
            NarratorState(article = article(Status.Playing, index = 0)),
            NarratorState(article = article(Status.Buffering, index = 1)),
            NarratorState(article = article(Status.Buffering, index = 2)),
            NarratorState(article = article(Status.Paused(PausedBy.Interrupt)), oneShot = shot(Status.Buffering)),
            NarratorState(article = article(Status.Paused(PausedBy.Interrupt)), oneShot = shot(Status.Playing)),
            NarratorState(article = article(Status.Buffering, index = 2)),
            NarratorState(article = article(Status.Playing, index = 2)),
        ).map { state -> render(state, previous).mediaState.also { previous = it } }

        assertEquals(listOf(PlaybackStateCompat.STATE_BUFFERING) + List(7) { playing }, seen)
    }

    @Test
    fun `stop and pause are always advertised while on air`() {
        for (status in listOf(Status.Buffering, Status.Playing, Status.Paused(PausedBy.User))) {
            val ui = render(NarratorState(oneShot = shot(status)))
            for (action in listOf(
                PlaybackStateCompat.ACTION_STOP,
                PlaybackStateCompat.ACTION_PAUSE,
                PlaybackStateCompat.ACTION_PLAY_PAUSE,
                PlaybackStateCompat.ACTION_PLAY,
            )) {
                assertTrue(ui.actions and action != 0L)
            }
            assertTrue(ui.showStop)
        }
    }

    @Test
    fun `speaking quotes the start of what is read, paused shows resume`() {
        val ui = render(NarratorState(article = article(Status.Playing)))
        assertEquals(ServiceUi.Text.Speaking, ui.text)
        assertEquals(40, ui.snippet.length)
        assertTrue(ui.snippet.startsWith("Paragraph 1."))
        assertFalse(ui.paused)

        val paused = render(NarratorState(article = article(Status.Paused(PausedBy.User))))
        assertEquals(ServiceUi.Text.Paused, paused.text)
        assertTrue(paused.paused)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, paused.mediaState)
    }

    @Test
    fun `shared text has no skips and no tap target`() {
        val ui = render(NarratorState(oneShot = shot(Status.Playing)))
        assertEquals(0L, ui.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        assertEquals(0L, ui.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        assertFalse(ui.showNext)
        assertFalse(ui.showPrevious)
        assertNull(ui.articleKey)
        assertEquals("A shared text.", ui.snippet)
    }

    /** A share reading over the article: the share is on air, not the article's steps. */
    @Test
    fun `a share over the article shows the share`() {
        val ui = render(NarratorState(article = article(Status.Paused(PausedBy.Interrupt)), oneShot = shot(Status.Playing)))
        assertEquals(playing, ui.mediaState)
        assertFalse(ui.showNext)
        assertNull(ui.articleKey)
        assertTrue(ui.wakeLock)
    }

    @Test
    fun `the article adds skips only where it can step`() {
        val mid = render(NarratorState(article = article(Status.Playing, index = 1)))
        assertTrue(mid.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(mid.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertEquals("https://example.org/a", mid.articleKey)

        val last = render(NarratorState(article = article(Status.Playing, index = 3)))
        assertEquals(0L, last.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        assertTrue(last.showPrevious)
    }

    /** §6 Q6: another app took the sound for good — paused in place, swipe-away-able. */
    @Test
    fun `a permanent focus loss makes the notification dismissable, a user pause does not`() {
        assertFalse(render(NarratorState(article = article(Status.Paused(PausedBy.FocusLoss)))).ongoing)
        assertTrue(render(NarratorState(article = article(Status.Paused(PausedBy.FocusLoss)))).onAir)
        assertTrue(render(NarratorState(article = article(Status.Paused(PausedBy.User)))).ongoing)
        assertTrue(render(NarratorState(article = article(Status.Playing))).ongoing)
    }

    @Test
    fun `the wake lock is held while buffering or playing, never while paused`() {
        assertTrue(ServiceUi.wantsWakeLock(NarratorState(article = article(Status.Buffering))))
        assertTrue(ServiceUi.wantsWakeLock(NarratorState(oneShot = shot(Status.Playing))))
        assertFalse(ServiceUi.wantsWakeLock(NarratorState(article = article(Status.Paused(PausedBy.User)))))
        assertFalse(ServiceUi.wantsWakeLock(NarratorState(oneShot = shot(Status.Paused(PausedBy.Focus)))))
        assertFalse(ServiceUi.wantsWakeLock(NarratorState()))
    }

    @Test
    fun `an error notice is posted once per one-shot`() {
        val state = NarratorState(notice = ErrorNotice(9, FailureKind.MODEL_MISSING, "kokoro-direct-v1_0"))
        assertEquals(9L, ServiceUi.errorToPost(state, lastPostedId = null)?.oneShotId)
        assertNull(ServiceUi.errorToPost(state, lastPostedId = 9))
        assertNull(ServiceUi.errorToPost(NarratorState(), lastPostedId = null))
    }

    @Test
    fun `a play-pause toggle pauses while buffering`() {
        val buffering = PlaybackStateCompat.STATE_BUFFERING
        assertTrue(ServiceUi.playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, buffering))
        assertTrue(ServiceUi.playPauseKeyPauses(KeyEvent.KEYCODE_HEADSETHOOK, buffering))
    }

    @Test
    fun `other keys and states keep the default media-button handling`() {
        assertFalse(ServiceUi.playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing))
        assertFalse(ServiceUi.playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, PlaybackStateCompat.STATE_PAUSED))
        assertFalse(ServiceUi.playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_STOP, PlaybackStateCompat.STATE_BUFFERING))
        assertFalse(ServiceUi.playPauseKeyPauses(KeyEvent.KEYCODE_MEDIA_PAUSE, PlaybackStateCompat.STATE_BUFFERING))
    }

    @Test
    fun `audio focus changes reach the Narrator by kind`() {
        assertEquals(FocusChange.LossTransient, AudioFocusPort.focusChangeOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(
            FocusChange.LossTransientCanDuck,
            AudioFocusPort.focusChangeOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        assertEquals(FocusChange.Loss, AudioFocusPort.focusChangeOf(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(FocusChange.Gain, AudioFocusPort.focusChangeOf(AudioManager.AUDIOFOCUS_GAIN))
        assertNull(AudioFocusPort.focusChangeOf(AudioManager.AUDIOFOCUS_NONE))
    }
}
