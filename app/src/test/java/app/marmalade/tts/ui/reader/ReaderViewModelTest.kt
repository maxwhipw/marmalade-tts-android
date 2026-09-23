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
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.service.PlaybackTransport
import app.marmalade.tts.service.PreviewCompletions
import app.marmalade.tts.perf.DeviceProbe
import app.marmalade.tts.ui.screen.FakeAliasDao
import app.marmalade.tts.ui.screen.FakeDeviceProbe
import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.util.MainDispatcherRule
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
// runTest without advanceUntilIdle() — and the parse dispatcher is Main too,
// for the same reason.
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
    fun `extractor receives the http content type for its charset`() = runTest {
        val extractor = FakeExtractor(ExtractionResult.ExtractionFailed)
        newViewModel(extractor = extractor).state.first()

        assertEquals("text/html; charset=utf-8", extractor.seenContentType)
    }

    /** jsoup + Readability is CPU work; it must not run on the main thread. */
    @Test
    fun `extraction runs on the parse dispatcher`() = runTest {
        val parse = RecordingDispatcher()
        val extractor = FakeExtractor(ExtractionResult.ExtractionFailed, parse)
        newViewModel(extractor = extractor, parseDispatcher = parse).state.first()

        assertEquals(true, extractor.ranOnParseDispatcher)
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
            aliasDao = FakeAliasDao(),
            deviceProbe = FakeDeviceProbe(),
            parseDispatcher = Dispatchers.Main,
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

    /**
     * After process death the controller is empty, so the article looks new
     * again — but the restored ViewModel already started it once. Returning
     * to a screen must not start it talking.
     */
    @Test
    fun `a view model restored after it autoplayed does not autoplay again`() = runTest {
        val vm = newViewModel(
            extraction = threeBlocks(),
            savedState = mapOf(ReaderViewModel.KEY_AUTOPLAYED to true),
        )

        val state = vm.state.first()

        assertTrue("expected Ready, got $state", state is ReaderUiState.Ready)
        assertEquals(ReaderPlaybackStatus.Idle, vm.playback.first().status)
        assertTrue("nothing may be spoken", speech.spoken.isEmpty())
    }

    @Test
    fun `autoplay is remembered in saved state`() = runTest {
        val handle = savedStateHandle()
        newViewModel(extraction = threeBlocks(), handle = handle).state.first()

        assertEquals(true, handle.get<Boolean>(ReaderViewModel.KEY_AUTOPLAYED))
    }

    // -- Playback errors ------------------------------------------------------

    @Test
    fun `a missing engine names the primary alias's engine`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-1x")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-1x", speed = 1.0f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first()

        completions.post(speech.spoken.first().requestId, PreviewCompletions.ErrorKind.MODEL_MISSING)

        assertEquals(
            ReaderPlaybackError.EngineNotInstalled(
                EngineCatalog.byName("kitten-direct-v0_8")!!.displayName,
            ),
            vm.playbackError.first(),
        )
    }

    @Test
    fun `a missing engine with no primary alias reads as a plain failure`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        completions.post(speech.spoken.first().requestId, PreviewCompletions.ErrorKind.MODEL_MISSING)

        assertEquals(ReaderPlaybackError.Failed, vm.playbackError.first())
    }

    @Test
    fun `a synthesis failure surfaces and clears on play`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        assertNull(vm.playbackError.first())

        completions.post(speech.spoken.first().requestId, PreviewCompletions.ErrorKind.FAILED)
        assertEquals(ReaderPlaybackError.Failed, vm.playbackError.first())

        vm.onPlayPause()
        assertNull(vm.playbackError.first())
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
    fun `leaving the reader by back cancels queued synthesis before first audio`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
        // Freshly loaded: the lookahead has queued all three blocks and no
        // completion has fired, i.e. nothing has produced audio yet — the
        // before-first-audio window from the device repro.
        val queued = speech.spoken.map { it.requestId }
        assertEquals(3, queued.size)
        assertTrue("nothing cancelled before back", speech.stopped.isEmpty())

        vm.onBackFromReader()

        // Every queued request is cancelled, so none starts speaking once the
        // user has left the screen (the reported defect: pause alone left them
        // to fire ~16 s later).
        assertEquals(queued.toSet(), speech.stopped.toSet())
        // Paused, not stopped: the highlight stays so returning resumes in place.
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

    // -- Speed-up perf warning (RTF-aware, chip × primary alias speed) --------

    @Test
    fun `speed warning uses the static fallback with no RTF signal`() = runTest {
        // Primary alias speed 1.5, chip default 1.0 → effective 1.5, past the
        // 1.35 static threshold. No probe and no measured RTF, so the warning
        // falls back to that static rule.
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-fast")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-fast", speed = 1.5f)))
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)

        assertTrue(vm.showSpeedWarning.first())
    }

    @Test
    fun `speed warning stays off below the static threshold with no RTF signal`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-slow")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-slow", speed = 1.2f)))
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)

        assertFalse(vm.showSpeedWarning.first())
    }

    @Test
    fun `measured RTF fires the warning below the static threshold`() = runTest {
        // Alias speed 1.0 — the static rule would never fire here. A measured
        // Kitten RTF of 0.9 pushes effective RTF (0.9 × 1.0) past 0.8, so the
        // warning fires on measurement, proving measured beats the fallback.
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-1x")
        settings.setEngineRtfForTest("kitten-direct-v0_8", 0.9)
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-1x", speed = 1.0f)))
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)

        assertTrue(vm.showSpeedWarning.first())
    }

    @Test
    fun `predicted RTF fires the warning when no measured value exists`() = runTest {
        // Probe measures Kitten at 0.9 RTF → predicted Kitten RTF 0.9. No
        // stored measured RTF, so the warning runs on the prediction:
        // 0.9 × 1.0 > 0.8. Alias speed 1.0 keeps the static rule silent.
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-1x")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-1x", speed = 1.0f)))
        val vm = newViewModel(
            settings = settings,
            aliasDao = aliasDao,
            deviceProbe = FakeDeviceProbe(DeviceProbe(measuredKittenRtf = 0.9, computeScore = null)),
        )

        assertTrue(vm.showSpeedWarning.first())
    }

    private fun alias(id: String, speed: Float) = VoiceAlias(
        id = id,
        name = id,
        engine = "kitten-direct-v0_8",
        voiceId = "kitten-direct-v0_8:Bella",
        speed = speed,
        effectPreset = "NONE",
        createdAt = 0L,
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
    private val completions = PreviewCompletions()

    /**
     * A real controller over the fake service client. Its completion collector
     * runs on the test's Main dispatcher (MainDispatcherRule), which keeps the
     * ViewModel tests single-threaded; no completions are posted here — the
     * sequencing itself is ReaderPlaybackControllerTest's job.
     */
    private fun newController() = ReaderPlaybackController(
        speech = speech,
        completions = completions,
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
        aliasDao: FakeAliasDao = FakeAliasDao(),
        deviceProbe: FakeDeviceProbe = FakeDeviceProbe(),
        parseDispatcher: CoroutineDispatcher = Dispatchers.Main,
        savedState: Map<String, Any> = emptyMap(),
        handle: SavedStateHandle = savedStateHandle(sharedText, savedState),
    ) = ReaderViewModel(
        fetcher = fetcher,
        extractor = extractor,
        playbackController = controller,
        settings = settings,
        aliasDao = aliasDao,
        deviceProbe = deviceProbe,
        parseDispatcher = parseDispatcher,
        savedStateHandle = handle,
    )

    /** The nav args, plus whatever a restored ViewModel would find saved. */
    private fun savedStateHandle(
        sharedText: String = "Marmalade Ships $url",
        saved: Map<String, Any> = emptyMap(),
    ) = SavedStateHandle(
        mapOf(
            ReaderViewModel.ARG_URL to url,
            ReaderViewModel.ARG_TEXT to sharedText,
        ) + saved,
    )

    private class FakeFetcher(private val result: FetchResult) : ArticleFetcher() {
        var seenUrl: String? = null

        override suspend fun fetch(url: String): FetchResult {
            seenUrl = url
            return result
        }
    }

    private class FakeExtractor(
        private val result: ExtractionResult,
        private val parseDispatcher: RecordingDispatcher? = null,
    ) : ArticleExtractor() {
        var seenBaseUrl: String? = null
        var seenContentType: String? = null
        var ranOnParseDispatcher: Boolean? = null

        override fun extract(
            bytes: ByteArray,
            finalUrl: String,
            contentType: String?,
        ): ExtractionResult {
            seenBaseUrl = finalUrl
            seenContentType = contentType
            ranOnParseDispatcher = parseDispatcher?.running
            return result
        }
    }

    /** Runs work inline, but knows when it is running something. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var running = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            running = true
            try {
                block.run()
            } finally {
                running = false
            }
        }
    }
}
