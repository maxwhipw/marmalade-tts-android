package app.marmalade.tts.ui.reader

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ArticleExtractor
import app.marmalade.tts.reader.ArticleFetcher
import app.marmalade.tts.reader.ExtractionResult
import app.marmalade.tts.reader.FakeReaderSpeechClient
import app.marmalade.tts.reader.FetchResult
import app.marmalade.tts.reader.ReaderPlaybackController
import app.marmalade.tts.reader.ReaderPlaybackStatus
import app.marmalade.tts.service.PreviewCompletions
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        clock = { 0L },
        scope = CoroutineScope(Dispatchers.Main),
    )

    private fun newViewModel(
        fetch: FetchResult = success(),
        extraction: ExtractionResult = ExtractionResult.ExtractionFailed,
        extractor: FakeExtractor = FakeExtractor(extraction),
        sharedText: String = "Marmalade Ships $url",
    ) = ReaderViewModel(
        fetcher = FakeFetcher(fetch),
        extractor = extractor,
        playbackController = newController(),
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
