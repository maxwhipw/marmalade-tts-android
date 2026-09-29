package app.marmalade.tts.reader

import app.marmalade.tts.service.PlaybackTransport
import app.marmalade.tts.service.PreviewCompletions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers ReaderPlaybackController's sequencing against a fake service client
// and a hand-driven clock: the 2-ahead queue, advance-on-completion, seeking
// (tap / forward / backward incl. the ~2 s backward window), pause/resume, and
// the end of the article.
//
// Plain JVM — the controller touches no Android API (its clock and its speech
// client are both injected seams), and PreviewCompletions is a SharedFlow.
// Completions are posted by hand, which is exactly what the service does when
// a block finishes.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPlaybackControllerTest {

    private val blocks = (0 until 6).map { "Block $it." }
    private val completions = PreviewCompletions()
    private val transport = PlaybackTransport()
    private val speech = FakeReaderSpeechClient()
    private var now = 1_000L

    // -- Queue + advance ------------------------------------------------------

    @Test
    fun `playing a new article enqueues the block plus two ahead`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks))
        controller.play()

        assertEquals(listOf("Block 0.", "Block 1.", "Block 2."), speech.spokenTexts)
        assertEquals(0, controller.state.value.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `a completed block advances the highlight and tops the queue back up`() = runTest {
        val controller = playing()

        finish()
        advanceUntilIdle()

        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(
            listOf("Block 0.", "Block 1.", "Block 2.", "Block 3."),
            speech.spokenTexts,
        )
        assertEquals(3, outstanding.size)
    }

    @Test
    fun `an article shorter than the look-ahead enqueues only what it has`() = runTest {
        val controller = newController()
        controller.open(article(KEY, listOf("Only one.")))
        controller.play()

        assertEquals(listOf("Only one."), speech.spokenTexts)

        finish()
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Finished, controller.state.value.status)
    }

    @Test
    fun `the last block completing ends the article`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks))
        controller.play()

        repeat(blocks.size) {
            finish()
            advanceUntilIdle()
        }

        assertEquals(ReaderPlaybackStatus.Finished, controller.state.value.status)
        assertEquals(blocks.lastIndex, controller.state.value.currentIndex)
        assertEquals(blocks, speech.spokenTexts)
    }

    @Test
    fun `a completion for a request we already cancelled is ignored`() = runTest {
        val controller = playing()
        val stale = speech.spoken.first().requestId

        controller.seekTo(4)
        val afterSeek = speech.spokenTexts.size
        completions.post(stale, null)
        advanceUntilIdle()

        assertEquals(4, controller.state.value.currentIndex)
        assertEquals(afterSeek, speech.spokenTexts.size)
    }

    @Test
    fun `a failed block stops playback instead of storming through the article`() = runTest {
        val controller = playing()

        finish(PreviewCompletions.ErrorKind.MODEL_MISSING)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertTrue(outstanding.isEmpty())
    }

    /** The service shows no error for in-app requests, so the state must carry it. */
    @Test
    fun `a failed block records why playback stopped`() = runTest {
        val controller = playing()

        finish(PreviewCompletions.ErrorKind.FAILED)
        advanceUntilIdle()

        assertEquals(PreviewCompletions.ErrorKind.FAILED, controller.state.value.lastError)
    }

    @Test
    fun `playing again clears the error`() = runTest {
        val controller = playing()
        finish(PreviewCompletions.ErrorKind.MODEL_MISSING)
        advanceUntilIdle()
        assertEquals(PreviewCompletions.ErrorKind.MODEL_MISSING, controller.state.value.lastError)

        controller.play()

        assertNull(controller.state.value.lastError)
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
    }

    /** A Stop the user asked for is not a failure worth reporting. */
    @Test
    fun `an outside stop is not recorded as an error`() = runTest {
        val controller = playing()
        completions.post(speech.spoken[1].requestId, null)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertNull(controller.state.value.lastError)
    }

    /**
     * The notification's Stop runs the service's `doStop`, which resolves the
     * queued requests before the playing one. Reading that as "a block
     * finished" would have the reader enqueue the rest of the article again,
     * i.e. Stop wouldn't stop.
     */
    @Test
    fun `an outside stop of the whole queue stops the reader`() = runTest {
        val controller = playing()
        val (head, queuedA, queuedB) = Triple(
            speech.spoken[0].requestId,
            speech.spoken[1].requestId,
            speech.spoken[2].requestId,
        )

        completions.post(queuedA, null)
        completions.post(queuedB, null)
        completions.post(head, null)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertEquals(3, speech.spoken.size)
    }

    /**
     * A new speak (Speak screen, share sheet) replaces a paused
     * read; the service resolves the reader's requests as stopped. The
     * reader must fall back to stopped — not sit on Paused with nothing left
     * in the service to resume.
     */
    @Test
    fun `a paused read replaced by a new speak shows stopped`() = runTest {
        val controller = playing()
        controller.pause()
        val (head, queued) = outstanding.map { it.requestId }.let { it.first() to it.drop(1) }

        // The service's order: queued requests first, the playing one last.
        queued.forEach { completions.post(it, null, stopped = true) }
        completions.post(head, null, stopped = true)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertNull(controller.state.value.lastError)
        assertEquals(3, speech.spoken.size)
    }

    /**
     * On the last block only the playing request is left, so there is no
     * out-of-order completion to go on — the stopped flag is the only thing
     * telling a replaced block from a finished one.
     */
    @Test
    fun `a stopped last block is not read as the end of the article`() = runTest {
        val controller = playing()
        controller.seekTo(blocks.lastIndex)
        controller.pause()

        completions.post(outstanding.single().requestId, null, stopped = true)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertEquals(blocks.lastIndex, controller.state.value.currentIndex)
    }

    /**
     * Only the request that starts a play may replace paused playback in the
     * service; the blocks behind it, and every top-up, are continuations — a
     * top-up landing just after the user paused must not stop their read.
     */
    @Test
    fun `only the first block of a play is a new speak`() = runTest {
        val controller = playing()
        assertEquals(listOf(false, true, true), speech.spoken.map { it.continuation })

        finish()
        advanceUntilIdle()
        assertTrue(speech.spoken.last().continuation)

        controller.seekTo(4)
        assertEquals(
            listOf(false, true),
            speech.spoken.takeLast(2).map { it.continuation },
        )
    }

    @Test
    fun `a service that refuses to start unwinds to idle`() = runTest {
        val controller = newController()
        speech.startAllowed = false
        controller.open(article(KEY, blocks))
        controller.play()

        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertTrue(speech.spoken.isEmpty())
        assertEquals(PreviewCompletions.ErrorKind.FAILED, controller.state.value.lastError)
    }

    // -- Seeking --------------------------------------------------------------

    @Test
    fun `tapping a block cancels everything queued and restarts there`() = runTest {
        val controller = playing()
        val original = speech.spoken.map { it.requestId }

        controller.seekTo(3)

        assertEquals(original, speech.stopped)
        assertEquals(3, controller.state.value.currentIndex)
        assertEquals(listOf("Block 3.", "Block 4.", "Block 5."), outstanding.map { it.text })
    }

    @Test
    fun `tapping outside the article is ignored`() = runTest {
        val controller = playing()

        controller.seekTo(blocks.size)
        controller.seekTo(-1)

        assertEquals(0, controller.state.value.currentIndex)
        assertTrue(speech.stopped.isEmpty())
    }

    @Test
    fun `forward moves to the next block`() = runTest {
        val controller = playing()

        controller.next()

        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(listOf("Block 1.", "Block 2.", "Block 3."), outstanding.map { it.text })
    }

    @Test
    fun `forward past the last block ends the article`() = runTest {
        val controller = playing()
        controller.seekTo(blocks.lastIndex)

        controller.next()

        assertEquals(ReaderPlaybackStatus.Finished, controller.state.value.status)
        assertTrue(outstanding.isEmpty())
    }

    @Test
    fun `backward near the start of a block goes to the previous block`() = runTest {
        val controller = playing()
        controller.seekTo(3)
        now += ReaderPlaybackController.RESTART_WINDOW_MS - 1

        controller.previous()

        assertEquals(2, controller.state.value.currentIndex)
    }

    @Test
    fun `backward later in a block restarts that block`() = runTest {
        val controller = playing()
        controller.seekTo(3)
        now += ReaderPlaybackController.RESTART_WINDOW_MS

        controller.previous()

        assertEquals(3, controller.state.value.currentIndex)
        assertEquals("Block 3.", outstanding.first().text)
    }

    @Test
    fun `backward on the first block restarts it rather than underflowing`() = runTest {
        val controller = playing()

        controller.previous()

        assertEquals(0, controller.state.value.currentIndex)
    }

    @Test
    fun `the backward window measures from the block that is playing, not the article`() =
        runTest {
            val controller = playing()
            now += 30_000

            finish()
            advanceUntilIdle()
            now += 500
            controller.previous()

            // Block 1 became current 500 ms ago, so backward steps to block 0.
            assertEquals(0, controller.state.value.currentIndex)
        }

    // -- Pause / resume -------------------------------------------------------

    @Test
    fun `pause and resume drive the service transport without re-enqueuing`() = runTest {
        val controller = playing()
        val enqueued = speech.spoken.size

        controller.pause()
        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        assertEquals(1, speech.pauses)

        controller.play()
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(1, speech.resumes)
        assertEquals(enqueued, speech.spoken.size)
        assertTrue(speech.stopped.isEmpty())
    }

    @Test
    fun `a pause does not age the current block`() = runTest {
        val controller = playing()
        controller.seekTo(3)
        now += 1_000
        controller.pause()
        now += 60_000
        controller.play()
        now += 500

        controller.previous()

        // 1 500 ms of actual playback: still inside the backward window, so
        // backward steps back a block. Without the pause compensation the
        // block would look 61 500 ms old and this would restart block 3.
        assertEquals(2, controller.state.value.currentIndex)
    }

    @Test
    fun `tapping a block while paused plays from its start`() = runTest {
        val controller = playing()
        val held = speech.spoken.map { it.requestId }
        controller.pause()
        speech.spoken.clear()

        controller.playFrom(4)

        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(4, controller.state.value.currentIndex)
        assertEquals(held, speech.stopped)
        assertEquals(listOf("Block 4.", "Block 5."), speech.spokenTexts)
        assertEquals(false, speech.spoken.first().continuation)
    }

    @Test
    fun `tapping a block after the end plays from it`() = runTest {
        val controller = playing()
        controller.next(); controller.next(); controller.next(); controller.next(); controller.next(); controller.next()
        assertEquals(ReaderPlaybackStatus.Finished, controller.state.value.status)

        controller.playFrom(2)

        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(2, controller.state.value.currentIndex)
    }

    @Test
    fun `seeking while paused stays paused and enqueues nothing until resume`() = runTest {
        val controller = playing()
        controller.pause()
        speech.spoken.clear()

        controller.seekTo(4)

        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        assertEquals(4, controller.state.value.currentIndex)
        assertTrue(speech.spoken.isEmpty())

        controller.play()

        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(listOf("Block 4.", "Block 5."), speech.spokenTexts)
    }

    @Test
    fun `play after the end starts the article again`() = runTest {
        val controller = newController()
        controller.open(article(KEY, listOf("Only one.")))
        controller.play()
        finish()
        advanceUntilIdle()

        controller.play()

        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(0, controller.state.value.currentIndex)
    }

    // -- Session speed --------------------------------------------------------

    /** The ViewModel opens each article at the primary alias's own speed. */
    @Test
    fun `a fresh article speaks at the speed it was opened with`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks), initialSpeed = 1.1f)
        controller.play()

        assertEquals(1.1f, controller.state.value.speed, 0f)
        assertTrue(speech.spoken.all { it.speed == 1.1f })
    }

    @Test
    fun `an article opened without a speed reads at 1x`() = runTest {
        playing()

        assertEquals(1.0f, speech.spoken.first().speed, 0f)
    }

    @Test
    fun `the session speed rides every request the reader sends`() = runTest {
        val controller = playing()

        controller.setSpeed(1.25f)
        finish()
        advanceUntilIdle()

        assertEquals(1.25f, controller.state.value.speed, 0f)
        // The top-up sent after the change carries the new speed itself.
        assertEquals(1.25f, speech.spoken.last().speed, 0f)
    }

    /**
     * The on-device engines' speed is a playback-side time-stretch, so the
     * blocks already handed over just follow the change — no restart, no
     * re-synthesis (Max, 2026-09-28: 2× → 1× used to stop the audio for
     * seconds).
     */
    @Test
    fun `changing speed while playing retimes the queued blocks live`() = runTest {
        val controller = playing()
        finish()
        advanceUntilIdle()
        val queued = outstanding.map { it.requestId }
        val sent = speech.spoken.size

        controller.setSpeed(1.5f)

        assertEquals(listOf(queued to 1.5f), speech.speedChanges)
        assertTrue(speech.stopped.isEmpty())
        assertEquals(sent, speech.spoken.size)
        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(1.5f, controller.state.value.speed, 0f)
    }

    @Test
    fun `a live speed change while paused stays paused and resumes the same blocks`() = runTest {
        val controller = playing()
        controller.pause()
        val queued = outstanding.map { it.requestId }
        val sent = speech.spoken.size

        controller.setSpeed(2.0f)

        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        assertEquals(listOf(queued to 2.0f), speech.speedChanges)
        assertTrue(speech.stopped.isEmpty())
        assertEquals(0, speech.resumes)

        controller.play()

        assertEquals(1, speech.resumes)
        assertEquals(sent, speech.spoken.size)
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
    }

    @Test
    fun `a speed change with nothing in flight asks the service nothing`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks))

        controller.setSpeed(1.5f)

        assertTrue(speech.speedChanges.isEmpty())
    }

    /**
     * A cloud voice renders the speed itself: audio already synthesised can't
     * be re-speeded, so the change re-enqueues from the current block.
     */
    @Test
    fun `changing speed on a fixed-speed voice restarts the current block at the new speed`() = runTest {
        speech.liveSpeed = false
        val controller = playing()
        finish()
        advanceUntilIdle()
        val queued = outstanding.map { it.requestId }

        controller.setSpeed(1.5f)

        assertEquals(queued, speech.stopped)
        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(
            listOf("Block 1.", "Block 2.", "Block 3."),
            outstanding.map { it.text },
        )
        assertTrue(outstanding.all { it.speed == 1.5f })
    }

    @Test
    fun `changing speed while paused on a fixed-speed voice enqueues nothing until resume`() = runTest {
        speech.liveSpeed = false
        val controller = playing()
        controller.seekTo(3)
        controller.pause()
        speech.spoken.clear()

        controller.setSpeed(0.75f)

        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        assertTrue(speech.spoken.isEmpty())

        controller.play()

        assertEquals(3, controller.state.value.currentIndex)
        assertEquals(listOf("Block 3.", "Block 4.", "Block 5."), speech.spokenTexts)
        assertTrue(speech.spoken.all { it.speed == 0.75f })
    }

    @Test
    fun `speed set before playing applies to the first block`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks))

        controller.setSpeed(2.0f)
        controller.play()

        assertTrue(speech.spoken.isNotEmpty())
        assertTrue(speech.spoken.all { it.speed == 2.0f })
    }

    @Test
    fun `setting the speed it already has changes nothing`() = runTest {
        val controller = playing()

        controller.setSpeed(1.0f)

        assertTrue(speech.stopped.isEmpty())
        assertEquals(3, speech.spoken.size)
    }

    @Test
    fun `a new article goes back to the speed it is opened with`() = runTest {
        val controller = playing()
        controller.setSpeed(2.0f)

        controller.open(article("https://example.com/other", listOf("New.")), initialSpeed = 0.9f)
        controller.play()

        assertEquals(0.9f, controller.state.value.speed, 0f)
        assertEquals(0.9f, speech.spoken.last().speed, 0f)
    }

    /** Coming back to the article being read is a rebind, not a new session. */
    @Test
    fun `reopening the same article keeps the session speed`() = runTest {
        val controller = playing()
        controller.setSpeed(1.5f)

        controller.open(article(KEY, blocks), initialSpeed = 1.0f)

        assertEquals(1.5f, controller.state.value.speed, 0f)
    }

    // -- The article's voice ----------------------------------------------------

    private val chinese = ReaderVoice.Installed("kokoro-direct-v1_0:zf_xiaobei", "kokoro-direct-v1_0")

    @Test
    fun `every block of an article is read in the voice it was opened with`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks), voice = chinese)
        controller.play()
        finish()
        advanceUntilIdle()
        controller.seekTo(4)

        // First three, one top-up after the completion, then a fresh run from 4.
        assertEquals(
            listOf("Block 0.", "Block 1.", "Block 2.", "Block 3.", "Block 4.", "Block 5."),
            speech.spokenTexts,
        )
        assertTrue(speech.spoken.all { it.voice == chinese })
    }

    /** A rebind must not re-decide: the voice stays whatever the article started with. */
    @Test
    fun `reopening the same article keeps its voice`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks), voice = chinese)
        controller.play()

        controller.open(article(KEY, blocks), voice = ReaderVoice.Primary)
        controller.seekTo(3)

        assertEquals(chinese, controller.state.value.voice)
        assertEquals(chinese, speech.spoken.last().voice)
    }

    @Test
    fun `a new article takes the voice it is opened with`() = runTest {
        val controller = newController()
        controller.open(article(KEY, blocks), voice = chinese)
        controller.play()

        controller.open(article("https://example.com/other", listOf("New.")))
        controller.play()

        assertEquals(ReaderVoice.Primary, speech.spoken.last().voice)
    }

    // -- Reconciling with the service's own pause -----------------------------

    /**
     * The notification's Pause (or a media key, or an audio-focus duck) flips
     * the service's global `paused` flag with nothing sent back to us. Before
     * the reader collected that flag the audio stopped while the screen's
     * button still said "playing".
     */
    @Test
    fun `a pause we did not ask for moves the reader to paused`() = runTest {
        val controller = playing()

        transport.setPaused(true)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        // Adopted, not echoed: sending ACTION_PAUSE back would be the loop.
        assertEquals(0, speech.pauses)
    }

    @Test
    fun `a resume we did not ask for moves the reader back to playing`() = runTest {
        val controller = playing()
        controller.pause()
        // The service's echo of our own pause, then someone else's resume.
        transport.setPaused(true)
        advanceUntilIdle()
        val enqueued = speech.spoken.size

        transport.setPaused(false)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(0, speech.resumes)
        assertEquals(enqueued, speech.spoken.size)
    }

    @Test
    fun `our own pause and resume come back as no-ops`() = runTest {
        val controller = playing()

        controller.pause()
        // The service's echo of what we just asked for, twice over.
        transport.setPaused(true)
        transport.setPaused(true)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        assertEquals(1, speech.pauses)

        controller.play()
        transport.setPaused(false)
        transport.setPaused(false)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertEquals(1, speech.resumes)
        assertTrue(speech.stopped.isEmpty())
    }

    /**
     * The service clears its `paused` flag every time it starts a request, so
     * an unrelated share-sheet read starting up must not be mistaken for
     * "resume the article" by a reader that has nothing in flight.
     */
    @Test
    fun `a service resume with nothing of ours in flight is ignored`() = runTest {
        val controller = playing()
        controller.pause()
        controller.seekTo(4)
        speech.spoken.clear()
        transport.setPaused(true)
        advanceUntilIdle()

        transport.setPaused(false)
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Paused, controller.state.value.status)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun `a paused article does not age while the service holds the pause`() = runTest {
        val controller = playing()
        controller.seekTo(3)
        now += 1_000
        transport.setPaused(true)
        advanceUntilIdle()
        now += 60_000
        transport.setPaused(false)
        advanceUntilIdle()
        now += 500

        controller.previous()

        // 1 500 ms of real playback — still inside the backward window.
        assertEquals(2, controller.state.value.currentIndex)
    }

    // -- What the notification is told ----------------------------------------

    @Test
    fun `an idle article publishes no reader transport`() = runTest {
        newController().open(article(KEY, blocks))
        advanceUntilIdle()

        assertFalse(transport.reader.value.isReading)
    }

    @Test
    fun `playing publishes the article and both step directions`() = runTest {
        playing()
        advanceUntilIdle()

        val published = transport.reader.value
        assertTrue(published.isReading)
        assertEquals(KEY, published.articleUrl)
        assertTrue(published.canNext)
        assertTrue(published.canPrevious)
    }

    /** Forward from the last block only ends the article — nothing to skip to. */
    @Test
    fun `the last block publishes no forward step`() = runTest {
        val controller = playing()
        controller.seekTo(blocks.lastIndex)
        advanceUntilIdle()

        assertFalse(transport.reader.value.canNext)
        assertTrue(transport.reader.value.canPrevious)
    }

    @Test
    fun `finishing the article withdraws the reader transport`() = runTest {
        val controller = playing()
        controller.seekTo(blocks.lastIndex)
        finish()
        advanceUntilIdle()

        assertEquals(ReaderPlaybackStatus.Finished, controller.state.value.status)
        assertFalse(transport.reader.value.isReading)
    }

    // -- Article retention ----------------------------------------------------

    @Test
    fun `the loaded article is handed back for a rebind`() = runTest {
        val controller = playing()

        val held = controller.article(KEY)

        assertEquals(KEY, held?.url)
        assertEquals(blocks, held?.blocks?.map { it.text })
        assertEquals("An article", held?.title)
    }

    @Test
    fun `a different article is not handed back`() = runTest {
        val controller = playing()

        assertNull(controller.article("https://example.com/other"))
    }

    // -- Article identity -----------------------------------------------------

    @Test
    fun `reopening the same article keeps the playback that is already running`() = runTest {
        val controller = playing()
        finish()
        advanceUntilIdle()

        val reopened = controller.open(article(KEY, blocks))

        assertFalse(reopened)
        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, controller.state.value.status)
        assertTrue(speech.stopped.isEmpty())
    }

    @Test
    fun `opening a different article cancels the old one and starts idle`() = runTest {
        val controller = playing()
        val original = speech.spoken.map { it.requestId }

        val reopened = controller.open(article("https://example.com/other", listOf("New.")))

        assertTrue(reopened)
        assertEquals(original, speech.stopped)
        assertEquals(ReaderPlaybackStatus.Idle, controller.state.value.status)
        assertEquals(0, controller.state.value.currentIndex)
        assertEquals(1, controller.state.value.blockCount)
    }

    // -- Helpers --------------------------------------------------------------

    private companion object {
        const val KEY = "https://example.com/article"
    }

    private fun TestScope.newController() = ReaderPlaybackController(
        speech = speech,
        completions = completions,
        transport = transport,
        clock = { now },
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    /** A controller mid-article: [blocks] loaded, playing from block 0. */
    private fun TestScope.playing() = newController().apply {
        open(article(KEY, blocks))
        play()
    }

    /** An article whose typed blocks are plain paragraphs of [texts]. */
    private fun article(key: String, texts: List<String>) = ReaderArticle(
        url = key,
        title = "An article",
        byline = "By Max",
        blocks = texts.map { ArticleBlock.Paragraph(it) },
        totalTextChars = texts.sumOf { it.length },
    )

    /** Requests handed to the service and neither cancelled nor completed. */
    private val outstanding: List<FakeReaderSpeechClient.Spoken>
        get() = speech.spoken.filter {
            it.requestId !in speech.stopped && it.requestId !in finished
        }

    private val finished = mutableListOf<Long>()

    /** The service finishing (or failing) the block it is currently speaking. */
    private fun finish(error: PreviewCompletions.ErrorKind? = null) {
        val head = outstanding.first()
        finished += head.requestId
        completions.post(head.requestId, error)
    }
}
