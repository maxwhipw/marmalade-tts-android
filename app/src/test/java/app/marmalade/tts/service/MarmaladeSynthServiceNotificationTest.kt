package app.marmalade.tts.service

import android.app.Notification
import android.support.v4.media.session.PlaybackStateCompat
import app.marmalade.tts.playback.ArticleView
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.OneShotView
import app.marmalade.tts.playback.Origin
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #94: Android 14+ lets the user swipe away a foreground service's
 * notification despite setOngoing. Every notification the service posts must
 * stop the read when dismissed, or the audio plays on with no controls.
 * (Swiping away the shade's media player is not a dismissal: SystemUI only
 * hides it and tells the app nothing.)
 *
 * The service is attached but never created (onCreate needs Hilt), so there is
 * no media session: the plain notification, as on a detached re-post.
 */
@RunWith(RobolectricTestRunner::class)
class MarmaladeSynthServiceNotificationTest {

    private val service = Robolectric.buildService(MarmaladeSynthService::class.java).get()

    private fun article(status: Status) =
        ArticleView("https://example.org/a", 4, 1, 1f, 1f, status, text = "Paragraph one.")

    private fun shot(status: Status) = OneShotView(7, Origin.External, status, queued = 0, text = "A shared text.")

    private fun assertDismissStops(notification: Notification, what: String) {
        val delete = notification.deleteIntent
        assertNotNull("$what: no delete intent", delete)
        assertEquals(what, MarmaladeSynthService.ACTION_STOP, shadowOf(delete).savedIntent.action)
    }

    @Test
    fun `dismissing any playback notification stops the read`() {
        val states = mapOf(
            "before the first state" to null,
            "article buffering" to NarratorState(article = article(Status.Buffering)),
            "article playing" to NarratorState(article = article(Status.Playing)),
            "article paused" to NarratorState(article = article(Status.Paused(PausedBy.User))),
            "article paused by a call" to NarratorState(article = article(Status.Paused(PausedBy.Focus))),
            "article paused by another app" to NarratorState(article = article(Status.Paused(PausedBy.FocusLoss))),
            "share buffering" to NarratorState(oneShot = shot(Status.Buffering)),
            "share playing" to NarratorState(oneShot = shot(Status.Playing)),
        )
        for ((what, state) in states) {
            val ui = state?.let { ServiceUi.render(it, PlaybackStateCompat.STATE_NONE) }
            assertDismissStops(service.buildNotification(ui), what)
        }
    }

    @Test
    fun `dismissing the loading notification stops the read`() {
        assertDismissStops(service.loadingNotification("https://example.org/a"), "loading")
    }
}
