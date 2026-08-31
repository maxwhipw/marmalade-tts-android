package app.marmalade.tts.ui.reader

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ArticleExtractor
import app.marmalade.tts.reader.ArticleFetcher
import app.marmalade.tts.reader.ExtractionResult
import app.marmalade.tts.reader.FakeReaderSpeechClient
import app.marmalade.tts.reader.FetchResult
import app.marmalade.tts.reader.ReaderArticle
import app.marmalade.tts.reader.ReaderPlaybackController
import app.marmalade.tts.reader.ReaderPlaybackStatus
import app.marmalade.tts.service.PlaybackTransport
import app.marmalade.tts.service.PreviewCompletions
import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers ReaderViewModel: the fetch → extract pipeline and the mapping from
// every FetchResult / ExtractionResult variant onto a ReaderUiState.
//
// Plain JVM — the ViewModel touches no Android APIs. ArticleFetcher and
// ArticleExtractor are `open`, so the fakes below subclass them and answer
// with a canned result rather than doing I/O. MainDispatcherRule swaps Main
// for an UnconfinedTestDispatcher so the init-block load resolves inside
// runTest without advanceUntilIdle().
// -----------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val url = "https://example.com/articles/marmalade"

    // -- Success path ---------------------------------------------------------

    @Test
    fun `successful fetch and extraction maps to Ready with blocks in order`() = runTest {
        val blocks = listOf(
            ArticleBlock.Heading(level = 2, text = "A heading"),
            ArticleBlock.Paragraph("The first paragraph."),
            ArticleBlock.ListItem("A bullet."),
            ArticleBlock.Quote("Someone said this."),
        )
        val vm = newViewModel(
            extraction = ExtractionResult.Success(
                title = "Marmalade Ships",
                byline = "By Max",
                blocks = blocks,
                totalTextChars = blocks.sumOf { it.text.length },
            ),
        )

        val state = vm.state.first()
        assertTrue("expected Ready, got $state", state is ReaderUiState.Ready)
        state as ReaderUiState.Ready
        assertEquals("Marmalade Ships", state.title)
        assertEquals("By Max", state.byline)
        assertEquals(blocks, state.blocks)
    }

    @Test
    fun `extractor receives the post-redirect URL as its base`() = runTest {
        val extractor = FakeExtractor(ExtractionResult.ExtractionFailed)
        newViewModel(
            fetch = success(finalUrl = "https://example.com/canonical"),
            extractor = extractor,
        ).state.first()

        assertEquals("https://example.com/canonical", extractor.seenBaseUrl)
    }

    @Test
    fun `page host is exposed for the loading state`() {
        assertEquals("example.com", newViewModel().pageHost)
    }

    // -- Failure mapping ------------------------------------------------------

    @Test
    fun `network error maps to Network failure`() = runTest {
        assertFailure(ReaderFailure.Network, fetch = FetchResult.NetworkError("no dns"))
    }

    @Test
    fun `http error maps to Http failure`() = runTest {
        assertFailure(ReaderFailure.Http, fetch = FetchResult.HttpError(403))
    }

    @Test
    fun `oversized body maps to TooLarge failure`() = runTest {
        assertFailure(ReaderFailure.TooLarge, fetch = FetchResult.TooLarge)
    }

    @Test
    fun `non-html content type maps to NotHtml failure`() = runTest {
        assertFailure(ReaderFailure.NotHtml, fetch = FetchResult.NotHtml("application/pdf"))
    }

    /** A chain we can't follow is the same dead end as an unreachable host. */
    @Test
    fun `redirect loop maps to Network failure`() = runTest {
        assertFailure(ReaderFailure.Network, fetch = FetchResult.TooManyRedirects)
    }

    @Test
    fun `failed extraction maps to ExtractionFailed`() = runTest {
        assertFailure(
            ReaderFailure.ExtractionFailed,
            extraction = ExtractionResult.ExtractionFailed,
        )
    }

    @Test
    fun `missing url argument fails instead of fetching`() = runTest {
        val fetcher = FakeFetcher(success())
        val vm = ReaderViewModel(
            fetcher = fetcher,
            extractor = FakeExtractor(ExtractionResult.ExtractionFailed),
            playbackController = newController(),
            settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella"),
            savedStateHandle = SavedStateHandle(),
        )

        assertEquals(
            ReaderUiState.Failed(ReaderFailure.Network),
            vm.state.first(),
        )
        assertNull("fetcher must not be called without a URL", fetcher.seenUrl)
    }

    // -- Playback binding -----------------------------------------------------

    @Test
    fun `nothing is highlighted when the article never loaded`() = runTest {
        val vm = newViewModel(fetch = FetchResult.TooLarge)

        assertNull(vm.currentBlockIndex.first())
        assertEquals(ReaderPlaybackStatus.Idle, vm.playback.first().status)
    }

    @Test
    fun `a freshly loaded article starts reading itself`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())

        vm.state.first()

        assertEquals(0, vm.currentBlockIndex.first())
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
        // The block plus two ahead — the article's whole three, here.
        assertEquals(
            listOf("A heading", "The first paragraph.", "The second paragraph."),
            speech.spokenTexts,
        )
    }

    @Test
    fun `tapping a block moves playback to it`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        vm.onBlockTapped(2)

        assertEquals(2, vm.currentBlockIndex.first())
        assertEquals("The second paragraph.", speech.spoken.last().text)
    }

    @Test
    fun `pausing keeps the highlight but stops the transport`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        vm.onPlayPause()

        assertEquals(ReaderPlaybackStatus.Paused, vm.playback.first().status)
        assertEquals(0, vm.currentBlockIndex.first())
    }

    @Test
    fun `forward and backward walk the article`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        vm.onNextBlock()
        assertEquals(1, vm.currentBlockIndex.first())

        // Block 1 only just started, so backward is "previous", not "restart".
        vm.onPreviousBlock()
        assertEquals(0, vm.currentBlockIndex.first())
    }

    /**
     * The session speed is playback state, not a setting: the ViewModel hands
     * it to the controller, and the re-enqueued blocks carry it. Nothing here
     * touches SettingsRepository — that is what would make it outlive the
     * article, which is exactly what Max asked it not to do.
     */
    @Test
    fun `the session speed reaches playback`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        vm.onSpeedMultiplierChange(1.5f)

        assertEquals(1.5f, vm.playback.first().speedMultiplier, 0f)
        assertEquals(1.5f, speech.spoken.last().speedMultiplier, 0f)
    }

    // -- Rebinding to an article already being read ---------------------------

    /**
     * Tapping the playback notification (and re-sharing a link mid-read) comes
     * back through the same route as the original share. The article is still
     * in the controller, so the screen must rebuild from that copy — fetching
     * the page again would be a second, unasked-for request to the site.
     */
    @Test
    fun `an article the controller still holds rebinds without fetching`() = runTest {
        val controller = newController()
        controller.open(heldArticle())
        val fetcher = FakeFetcher(success())

        val vm = newViewModel(fetcher = fetcher, controller = controller)

        val state = vm.state.first()
        assertTrue("expected Ready, got $state", state is ReaderUiState.Ready)
        state as ReaderUiState.Ready
        assertEquals("Marmalade Ships", state.title)
        assertEquals("By Max", state.byline)
        assertEquals(heldArticle().blocks, state.blocks)
        assertNull("a held article must not be fetched again", fetcher.seenUrl)
    }

    /** Rebinding is not a fresh open, so it must not restart the article. */
    @Test
    fun `rebinding leaves playback exactly where it was`() = runTest {
        val controller = newController()
        controller.open(heldArticle())
        controller.play()
        controller.next()
        val spokenBefore = speech.spoken.size

        val vm = newViewModel(controller = controller)
        vm.state.first()

        assertEquals(1, vm.currentBlockIndex.first())
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
        assertEquals(spokenBefore, speech.spoken.size)
    }

    /** A different article in the controller is no reason to skip the fetch. */
    @Test
    fun `an unrelated article in the controller does not block the fetch`() = runTest {
        val controller = newController()
        controller.open(heldArticle().copy(url = "https://example.com/other"))
        val fetcher = FakeFetcher(success())

        newViewModel(
            extraction = threeBlocks(),
            fetcher = fetcher,
            controller = controller,
        ).state.first()

        assertEquals(url, fetcher.seenUrl)
    }

    private fun heldArticle() = threeBlocks().let {
        ReaderArticle(
            url = url,
            title = it.title,
            byline = it.byline,
            blocks = it.blocks,
            totalTextChars = it.totalTextChars,
        )
    }

    // -- Short-extraction notice ----------------------------------------------

    @Test
    fun `a thin extraction raises the short-extraction notice`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())

        vm.state.first()

        assertTrue(vm.showShortExtractionNotice.first())
    }

    @Test
    fun `a full-length article raises no notice`() = runTest {
        val vm = newViewModel(
            extraction = threeBlocks().copy(
                totalTextChars = ReaderViewModel.SHORT_EXTRACTION_CHARS,
            ),
        )

        vm.state.first()

        assertFalse(vm.showShortExtractionNotice.first())
    }

    @Test
    fun `dismissing the notice keeps it gone`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        vm.onDismissShortExtractionNotice()

        assertFalse(vm.showShortExtractionNotice.first())
    }

    /** No article, nothing to warn about — the failure card already covers it. */
    @Test
    fun `a failed load raises no notice`() = runTest {
        val vm = newViewModel(fetch = FetchResult.HttpError(403))

        vm.state.first()

        assertFalse(vm.showShortExtractionNotice.first())
    }

    // -- Helpers --------------------------------------------------------------

    private suspend fun assertFailure(
        expected: ReaderFailure,
        fetch: FetchResult = success(),
        extraction: ExtractionResult = ExtractionResult.ExtractionFailed,
    ) {
        val state = newViewModel(fetch = fetch, extraction = extraction).state.first()
        assertEquals(ReaderUiState.Failed(expected), state)
    }

    private fun success(finalUrl: String = url) = FetchResult.Success(
        bytes = "<html><body><p>Body</p></body></html>".toByteArray(),
        finalUrl = finalUrl,
        statusCode = 200,
        contentType = "text/html; charset=utf-8",
    )

    // -- display settings ----------------------------------------------------

    @Test
    fun `display prefs default to unset background, sans, and the theme body size`() =
        runTest {
            val vm = newViewModel(extraction = threeBlocks())

            val prefs = vm.display.first()
            assertNull("background stays unset until the user picks one", prefs.background)
            assertEquals(ReaderFont.Sans, prefs.font)
            assertEquals(ReaderDisplayPrefs.DEFAULT_FONT_SIZE_SP, prefs.fontSizeSp)
        }

    @Test
    fun `background and font choices round-trip through the settings store`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())

        vm.onBackgroundChange(ReaderBackground.Paper)
        vm.onFontChange(ReaderFont.Serif)

        assertEquals(ReaderBackground.Paper, vm.display.first().background)
        assertEquals(ReaderFont.Serif, vm.display.first().font)
    }

    @Test
    fun `text size steps clamp at both ends of the range`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())

        // Far past the top: the stepper stops at MAX rather than running away.
        repeat(20) { vm.onFontSizeStep(ReaderDisplayPrefs.FONT_SIZE_STEP_SP) }
        assertEquals(ReaderDisplayPrefs.MAX_FONT_SIZE_SP, vm.display.first().fontSizeSp)

        repeat(20) { vm.onFontSizeStep(-ReaderDisplayPrefs.FONT_SIZE_STEP_SP) }
        assertEquals(ReaderDisplayPrefs.MIN_FONT_SIZE_SP, vm.display.first().fontSizeSp)
    }

    private fun threeBlocks() = ExtractionResult.Success(
        title = "Marmalade Ships",
        byline = "By Max",
        blocks = listOf(
            ArticleBlock.Heading(level = 2, text = "A heading"),
            ArticleBlock.Paragraph("The first paragraph."),
            ArticleBlock.Paragraph("The second paragraph."),
        ),
        totalTextChars = 60,
    )

    private val speech = FakeReaderSpeechClient()

    /**
     * A real controller over the fake service client. Its completion collector
     * runs on the test's Main dispatcher (MainDispatcherRule), which keeps the
     * ViewModel tests single-threaded; no completions are posted here — the
     * sequencing itself is ReaderPlaybackControllerTest's job.
     */
    private fun newController() = ReaderPlaybackController(
        speech = speech,
        completions = PreviewCompletions(),
        transport = PlaybackTransport(),
        clock = { 0L },
        scope = CoroutineScope(Dispatchers.Main),
    )

    private fun newViewModel(
        fetch: FetchResult = success(),
        extraction: ExtractionResult = ExtractionResult.ExtractionFailed,
        extractor: FakeExtractor = FakeExtractor(extraction),
        sharedText: String = "Marmalade Ships $url",
        settings: FakeSettings = FakeSettings(initialId = "kitten-direct-v0_8:Bella"),
        fetcher: FakeFetcher = FakeFetcher(fetch),
        controller: ReaderPlaybackController = newController(),
    ) = ReaderViewModel(
        fetcher = fetcher,
        extractor = extractor,
        playbackController = controller,
        settings = settings,
        savedStateHandle = SavedStateHandle(
            mapOf(
                ReaderViewModel.ARG_URL to url,
                ReaderViewModel.ARG_TEXT to sharedText,
            ),
        ),
    )

    private class FakeFetcher(private val result: FetchResult) : ArticleFetcher() {
        var seenUrl: String? = null

        override suspend fun fetch(url: String): FetchResult {
            seenUrl = url
            return result
        }
    }

    private class FakeExtractor(private val result: ExtractionResult) : ArticleExtractor() {
        var seenBaseUrl: String? = null

        override fun extract(bytes: ByteArray, finalUrl: String): ExtractionResult {
            seenBaseUrl = finalUrl
            return result
        }
    }
}
