package app.marmalade.tts.playback

import app.marmalade.tts.playback.NarratorHarness.Companion.VITS_ENGINE
import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.NarratorHarness.Companion.voice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 scenarios about sessions meeting each other and the outside world:
 * one-shots vs the article (9, 20), focus (8), failures (16), the host
 * (21) and the paused-notification time-out (§6 Q7).
 */
class NarratorSessionsTest {

    private fun article(n: Int = 3, sentences: Int = 2) = List(n) { paragraph(sentences, fill = 'a' + it) }

    private fun share(fill: Char = 'q', origin: Origin = Origin.External, paragraphs: Int = 1) = OneShotRequest(
        text = List(paragraphs) { paragraph(1, fill = fill + it) }.joinToString("\n\n"),
        voice = OneShotVoice.LanguageAware,
        origin = origin,
    )

    private fun TestScope.harness(config: NarratorConfig = NarratorConfig()) = NarratorHarness(this, config)

    private fun NarratorHarness.done() {
        checkInvariants()
        narrator.close()
    }

    // -- 9: the P14 family -------------------------------------------------------------------

    @Test
    fun `9a - a share with nothing playing just plays`() = runTest {
        val h = harness()
        val handle = h.narrator.speakOneShot(share())
        h.runUntil { handle.outcome.isCompleted }
        assertEquals(Outcome.Played, handle.outcome.getCompleted())
        assertTrue(h.audibleMs(Track.Main) > 3_000)
        assertNull(h.state.oneShot)
        h.done()
    }

    @Test
    fun `9b - a share while the article plays interrupts it on its own track, then the article resumes from the interrupted chunk`() = runTest {
        val h = harness()
        h.open(article(3))
        val id = h.narrator.articleId()!!
        h.runUntil { h.heardStarts().any { it.second.sessionId == id && it.second.chunk == 1 } }
        h.run(1_000) // mid chunk 1 of segment 0
        val handle = h.narrator.speakOneShot(share())
        h.settle()
        assertEquals(Status.Paused(PausedBy.Interrupt), h.article.status)
        val t = h.now()
        h.runUntil { handle.outcome.isCompleted }
        val done = h.now()
        assertEquals(Outcome.Played, handle.outcome.getCompleted())
        assertTrue("share on its own track", h.audibleMs(Track.Interrupt) > 3_000)
        assertEquals("article silent meanwhile", 0.0, h.audibleMs(Track.Main, from = t + 10, to = done), 0.0)
        h.runUntil { h.firstHeard(id, 0, after = done) != null }
        val resumed = h.heardStarts().first { it.first >= done && it.second.sessionId == id }.second
        assertEquals("from the start of the interrupted chunk", 0 to 1, resumed.segment to resumed.chunk)
        assertTrue(h.firstHeard(id, 0, after = done)!! - done <= 20)
        assertEquals(Status.Playing, h.article.status)
        h.done()
    }

    @Test
    fun `9c - a share while the article is paused by the user replaces it, the article keeps its place`() = runTest {
        val h = harness()
        h.open(article(3))
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 1) != null }
        h.narrator.pause()
        h.settle()
        val handle = h.narrator.speakOneShot(share())
        h.runUntil { handle.outcome.isCompleted }
        assertEquals(Status.Idle, h.article.status)
        assertEquals(1, h.article.index)
        assertTrue(h.audibleMs(Track.Main) > 0)
        val t = h.now()
        h.narrator.play()
        h.runUntil { h.firstHeard(id, 1, after = t) != null }
        h.done()
    }

    @Test
    fun `9d - a share during a transient focus loss waits for the regain, then reads over the article`() = runTest {
        val h = harness()
        h.open(article(3))
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.narrator.onFocusChange(FocusChange.LossTransient)
        h.settle()
        val handle = h.narrator.speakOneShot(share())
        h.run(3_000)
        assertFalse(handle.outcome.isCompleted)
        assertEquals(Status.Paused(PausedBy.Focus), h.article.status)
        assertEquals(0.0, h.audibleMs(Track.Interrupt), 0.0)
        h.narrator.onFocusChange(FocusChange.Gain)
        h.settle()
        assertEquals(Status.Paused(PausedBy.Interrupt), h.article.status)
        h.runUntil { handle.outcome.isCompleted }
        h.runUntil { h.article.status == Status.Playing }
        assertEquals(1, h.focus.requests)
        h.done()
    }

    // -- 20: queued one-shots ------------------------------------------------------------------

    @Test
    fun `20 - three queued one-shots play in order, gapless where the rate matches, rebuilt where not, one focus hold`() = runTest {
        val h = harness()
        h.oneShotVoices = { req -> if (req.text.startsWith("v")) voice(engine = VITS_ENGINE) else voice() }
        val a = h.narrator.speakOneShot(share('a'))
        val b = h.narrator.speakOneShot(share('b'))
        val c = h.narrator.speakOneShot(share('v'))
        h.runUntil { c.outcome.isCompleted }
        val ends = listOf(a, b, c).map { it.outcome.getCompleted() }
        assertEquals(listOf(Outcome.Played, Outcome.Played, Outcome.Played), ends)
        val ended = h.output.events.filter { it.second is OutputEvent.SessionEnded }.map { (it.second as OutputEvent.SessionEnded).sessionId }
        assertEquals(listOf(a.id, b.id, c.id), ended)
        val bStart = h.heardStarts().first { it.second.sessionId == b.id }.first
        val cStart = h.heardStarts().first { it.second.sessionId == c.id }.first
        assertEquals("a → b gapless", 0.0, h.gapsMs(Track.Main, to = bStart + 100), 0.0)
        assertEquals("track rebuilt once, at 22.05 kHz", listOf(22_050), h.output.rebuilds.map { it.second })
        assertTrue(h.output.rebuilds.single().first in bStart..cStart)
        assertEquals(1, h.focus.requests)
        h.done()
    }

    @Test
    fun `cancelling one queued one-shot leaves the others`() = runTest {
        val h = harness()
        val a = h.narrator.speakOneShot(share('a'))
        val b = h.narrator.speakOneShot(share('b'))
        val c = h.narrator.speakOneShot(share('c'))
        h.runUntil { h.heardStarts().any { it.second.sessionId == a.id } }
        h.narrator.cancelOneShot(b.id)
        h.runUntil { c.outcome.isCompleted }
        assertEquals(Outcome.Played, a.outcome.getCompleted())
        assertEquals(Outcome.Stopped, b.outcome.getCompleted())
        assertEquals(Outcome.Played, c.outcome.getCompleted())
        assertTrue(h.heardStarts().none { it.second.sessionId == b.id })
        h.done()
    }

    // -- 8: focus ------------------------------------------------------------------------------

    @Test
    fun `8a - a transient loss pauses, the regain resumes, one request for the hold`() = runTest {
        val h = harness()
        h.open(article(2))
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.narrator.onFocusChange(FocusChange.LossTransientCanDuck)
        h.settle()
        assertEquals(Status.Paused(PausedBy.Focus), h.article.status)
        val t = h.now()
        h.run(2_000)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 10), 0.0)
        h.narrator.onFocusChange(FocusChange.Gain)
        h.runUntil { h.article.status == Status.Playing }
        h.runUntil { h.audibleMs(Track.Main, from = h.now() - 20) > 0 }
        assertEquals(1, h.focus.requests)
        assertTrue(h.state.focusHeld)
        h.done()
    }

    @Test
    fun `8b - a user pause survives a transient loss and regain`() = runTest {
        val h = harness()
        h.open(article(2))
        h.runUntil { h.article.status == Status.Playing }
        h.narrator.pause()
        h.narrator.onFocusChange(FocusChange.LossTransient)
        h.narrator.onFocusChange(FocusChange.Gain)
        h.run(1_000)
        assertEquals(Status.Paused(PausedBy.User), h.article.status)
        h.done()
    }

    @Test
    fun `8c - a permanent loss pauses and keeps the place, play re-requests focus`() = runTest {
        val h = harness()
        h.open(article(3))
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 1) != null }
        h.narrator.onFocusChange(FocusChange.Loss)
        h.settle()
        assertEquals(Status.Paused(PausedBy.FocusLoss), h.article.status)
        assertFalse(h.state.focusHeld)
        assertEquals(1, h.focus.abandons)
        assertEquals(1, h.article.index)
        h.run(10_000)
        h.narrator.play()
        h.runUntil { h.article.status == Status.Playing }
        assertEquals(2, h.focus.requests)
        assertEquals(1, h.article.index)
        h.done()
    }

    @Test
    fun `8d - focus denied at start fails the article and the one-shot`() = runTest {
        val h = harness()
        h.focus.grant = false
        h.open(article(2))
        assertEquals(Status.Failed(FailureKind.FOCUS_DENIED), h.article.status)
        val handle = h.narrator.speakOneShot(share())
        h.settle()
        assertEquals(Outcome.Failed(FailureKind.FOCUS_DENIED, "audio focus denied"), handle.outcome.getCompleted())
        h.run(5_000)
        assertEquals(0, h.kokoro.renders.size)
        h.done()
    }

    @Test
    fun `a permanent loss stops one-shots`() = runTest {
        val h = harness()
        val handle = h.narrator.speakOneShot(share(paragraphs = 3))
        h.runUntil { h.state.oneShot?.status == Status.Playing }
        h.narrator.onFocusChange(FocusChange.Loss)
        h.settle()
        assertEquals(Outcome.Stopped, handle.outcome.getCompleted())
        h.done()
    }

    // -- 16: failures --------------------------------------------------------------------------

    @Test
    fun `16a - a render failure at block 3 fails once, nothing after it renders, play clears it`() = runTest {
        val h = harness()
        val segs = article(5)
        var broken = true
        h.kokoro.failWhen = { if (broken && it == segs[2]) RuntimeException("boom") else null }
        val statuses = ArrayList<Status>()
        h.open(segs)
        h.runUntil(what = "failed") {
            h.article.status.let { if (statuses.lastOrNull() != it) statuses += it; it is Status.Failed }
        }
        h.run(10_000)
        assertEquals(1, statuses.count { it is Status.Failed })
        assertTrue(h.kokoro.rendersOf(segs[3]).isEmpty())
        assertTrue(h.kokoro.rendersOf(segs[4]).isEmpty())
        assertNull("the reader shows article errors; no notice", h.state.notice)
        broken = false
        h.narrator.play()
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        h.done()
    }

    @Test
    fun `16b - an external one-shot failure posts a notice, an in-app one does not`() = runTest {
        val h = harness()
        h.kokoro.installed = false
        val inApp = h.narrator.speakOneShot(share(origin = Origin.InApp))
        h.runUntil { inApp.outcome.isCompleted }
        assertEquals(FailureKind.MODEL_MISSING, (inApp.outcome.getCompleted() as Outcome.Failed).kind)
        assertNull(h.state.notice)
        val external = h.narrator.speakOneShot(share(origin = Origin.External))
        h.runUntil { external.outcome.isCompleted }
        assertEquals(ErrorNotice(external.id, FailureKind.MODEL_MISSING, NarratorHarness.ENGINE), h.state.notice)
        h.done()
    }

    // -- 21: the host --------------------------------------------------------------------------

    @Test
    fun `21a - a late host - renders at once, audio only once foreground`() = runTest {
        val h = harness()
        h.foreground = false
        h.open(article(2))
        h.run(3_000)
        assertTrue("rendering started", h.kokoro.renders.isNotEmpty())
        assertEquals(0.0, h.audibleMs(Track.Main), 0.0)
        h.foreground = true
        h.narrator.onHostChanged(true)
        val t = h.now()
        h.runUntil { h.article.status == Status.Playing }
        assertTrue(h.now() - t <= 20)
        h.done()
    }

    @Test
    fun `21b - a host that never becomes foreground fails the session`() = runTest {
        val h = harness()
        h.foreground = false
        h.open(article(2))
        val handle = h.narrator.speakOneShot(share())
        h.run(7_000)
        assertEquals(Status.Failed(FailureKind.FAILED), h.article.status)
        h.done()
        assertTrue(handle.outcome.isCompleted)
    }

    @Test
    fun `21c - a host demoted mid-play stops everything`() = runTest {
        val h = harness()
        h.open(article(2))
        h.runUntil { h.article.status == Status.Playing }
        h.foreground = false
        h.narrator.onHostChanged(false)
        h.settle()
        val t = h.now()
        h.run(3_000)
        assertEquals(Status.Idle, h.article.status)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 10), 0.0)
        h.done()
    }

    // -- §6 Q7: the paused notification clears after 30 min ---------------------------------------

    @Test
    fun `Q7 - a session paused for 30 minutes is stopped, reopening continues at its place`() = runTest {
        val h = harness()
        h.open(article(4))
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 1) != null }
        h.narrator.pause()
        h.run(29 * 60_000L)
        assertEquals(Status.Paused(PausedBy.User), h.article.status)
        h.run(60_000L + 10)
        assertEquals(Status.Idle, h.article.status)
        assertFalse(h.state.focusHeld)
        assertTrue(h.residency.held.isEmpty())
        h.checkInvariants()
        val t = h.now()
        h.narrator.play()
        h.runUntil { h.firstHeard(id, 1, after = t) != null }
        h.done()
    }
}
