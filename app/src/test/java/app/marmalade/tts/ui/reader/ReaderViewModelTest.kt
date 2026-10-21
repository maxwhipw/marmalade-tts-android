package app.marmalade.tts.ui.reader

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ArticleExtractor
import app.marmalade.tts.reader.ArticleFetcher
import app.marmalade.tts.reader.ExtractionResult
import app.marmalade.tts.reader.FetchResult
import app.marmalade.tts.reader.ReaderArticle
import app.marmalade.tts.reader.ReaderPlaybackController
import app.marmalade.tts.reader.ReaderPlaybackStatus
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.reader.ReaderVoicePicker
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.playback.NarratorConfig
import app.marmalade.tts.playback.NarratorHarness
import app.marmalade.tts.playback.ResolveTarget
import app.marmalade.tts.playback.Track
import app.marmalade.tts.service.ArticleLoadHold
import app.marmalade.tts.service.PlaybackHost
import app.marmalade.tts.perf.DeviceProbe
import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.ui.screen.FakeAliasDao
import app.marmalade.tts.ui.screen.FakeDeviceProbe
import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.util.MainDispatcherRule
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
    fun `page host is exposed for the loading state`() = runTest {
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
            voicePicker = FakeVoicePicker(VoiceChoice.Primary),
            parseDispatcher = Dispatchers.Main,
            savedStateHandle = SavedStateHandle(),
            loadHold = ArticleLoadHold({ true }, backgroundScope),
            voicePaths = voicePaths,
        )

        assertEquals(
            ReaderUiState.Failed(ReaderFailure.Network),
            vm.state.first(),
        )
        assertNull("fetcher must not be called without a URL", fetcher.seenUrl)
    }

    // -- Playback binding -----------------------------------------------------
    //
    // Against the real Narrator (NarratorHarness: fake engine, virtual output,
    // virtual time on this test's scheduler). The controller's sequencing is
    // ReaderPlaybackControllerTest's job; these check the ViewModel's wiring.

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
        h.settle()

        assertEquals(0, vm.currentBlockIndex.first())
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
        h.runUntil { h.firstHeard(articleId(), 0) != null }
        assertEquals("A heading", h.kokoro.renders.first().text)
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
        h.run(3_000)

        assertTrue("expected Ready, got $state", state is ReaderUiState.Ready)
        assertEquals(ReaderPlaybackStatus.Idle, vm.playback.first().status)
        assertTrue("nothing may be spoken", h.kokoro.renders.isEmpty())
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
        h.kokoro.installed = false
        vm.state.first()
        h.run(3_000)

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
        h.kokoro.installed = false
        vm.state.first()
        h.run(3_000)

        assertEquals(ReaderPlaybackError.Failed, vm.playbackError.first())
    }

    @Test
    fun `a synthesis failure surfaces and clears on play`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        assertNull(vm.playbackError.first())
        h.kokoro.failWhen = { RuntimeException("boom") }
        h.run(3_000)
        assertEquals(ReaderPlaybackError.Failed, vm.playbackError.first())

        h.kokoro.failWhen = { null }
        vm.onPlayPause()
        h.settle()
        assertNull(vm.playbackError.first())
    }

    /**
     * The article loaded after the user switched apps and no host was up
     * (Android refuses a foreground service from the background): a specific
     * message, not "Synthesis failed", and Play works once the app is in front.
     */
    @Test
    fun `a host refused in the background says so, and Play works once in front`() = runTest {
        hostAllowed = false
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        h.run(3_000)

        assertEquals(ReaderPlaybackError.StartRefused, vm.playbackError.first())
        assertEquals(ReaderPlaybackStatus.Idle, vm.playback.first().status)

        hostAllowed = true
        vm.onPlayPause()
        h.runUntil { h.firstHeard(articleId(), 0) != null }
        assertNull(vm.playbackError.first())
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
    }

    /** The share's "Loading article…" hold ends with the load, whatever the outcome. */
    @Test
    fun `the share's load hold ends when the article fails to load`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        hold.begin(url)
        val vm = newViewModel(fetch = FetchResult.TooLarge, loadHold = hold)

        assertEquals(ReaderUiState.Failed(ReaderFailure.TooLarge), vm.state.first())
        assertNull(hold.loading.value)
    }

    @Test
    fun `the share's load hold ends once the loaded article is playing`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        hold.begin(url)
        val vm = newViewModel(extraction = threeBlocks(), loadHold = hold)
        vm.state.first()
        h.settle()

        assertNull(hold.loading.value)
        h.runUntil { h.firstHeard(articleId(), 0) != null }
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
    }

    /** #94: the user swiped "Loading article…" away — the article opens but is not read. */
    @Test
    fun `an article whose load was cancelled opens without reading`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        hold.begin(url)
        hold.cancel(url)
        val vm = newViewModel(extraction = threeBlocks(), loadHold = hold)

        val state = vm.state.first()
        h.run(3_000)

        assertTrue("expected Ready, got $state", state is ReaderUiState.Ready)
        assertEquals(ReaderPlaybackStatus.Idle, vm.playback.first().status)
        assertTrue("nothing may be spoken", h.kokoro.renders.isEmpty())
    }

    /** Re-sharing the link of the reader on top reaches no new reader: its hold ends at once. */
    @Test
    fun `a re-share of the article on top ends its hold at once`() = runTest {
        val hold = ArticleLoadHold({ true }, backgroundScope)
        newViewModel(extraction = threeBlocks(), loadHold = hold).state.first()
        h.settle()

        hold.begin(url)
        h.settle()
        assertNull(hold.loading.value)
    }

    /** Audio focus refused (a call in progress) reads as a plain failure. */
    @Test
    fun `audio focus refused surfaces as a failure`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        h.focus.grant = false
        vm.state.first()
        h.run(3_000)

        assertEquals(ReaderPlaybackError.Failed, vm.playbackError.first())
    }

    @Test
    fun `tapping a block moves playback to it`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        vm.onBlockTapped(2)
        h.runUntil { h.firstHeard(articleId(), 2) != null }

        assertEquals(2, vm.currentBlockIndex.first())
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
    }

    @Test
    fun `pausing keeps the highlight but stops the transport`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        h.settle()

        vm.onPlayPause()
        h.settle()

        assertEquals(ReaderPlaybackStatus.Paused, vm.playback.first().status)
        assertEquals(0, vm.currentBlockIndex.first())
    }

    /**
     * The device repro: backing out while the first block was still being
     * synthesised left it to start speaking ~16 s after the user had left.
     */
    @Test
    fun `leaving the reader by back before first audio keeps it silent`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        h.settle()
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
        assertTrue("nothing heard yet", h.heardStarts().isEmpty())

        vm.onBackFromReader()
        h.run(20_000)

        assertEquals(0.0, h.audibleMs(Track.Main), 0.0)
        // Paused, not stopped: the highlight stays so returning resumes in place.
        assertEquals(ReaderPlaybackStatus.Paused, vm.playback.first().status)
        assertEquals(0, vm.currentBlockIndex.first())
    }

    @Test
    fun `forward and backward walk the article`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        h.runUntil { h.firstHeard(articleId(), 0) != null }

        vm.onNextBlock()
        h.settle()
        assertEquals(1, vm.currentBlockIndex.first())

        // Block 1 only just started, so backward is "previous", not "restart".
        vm.onPreviousBlock()
        h.settle()
        assertEquals(0, vm.currentBlockIndex.first())
    }

    /**
     * The session speed is playback state, not a setting: the ViewModel hands
     * it to the controller, which moves the audio in flight to it live.
     * Nothing here touches SettingsRepository — that is what would make it
     * outlive the article, which is exactly what Max asked it not to do.
     */
    @Test
    fun `the session speed reaches playback`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()
        h.settle()

        vm.onSpeedChange(1.5f)
        h.settle()

        assertEquals(1.5f, vm.playback.first().speed, 0f)
        assertEquals(1.5f, h.article.speed, 0f)
    }

    /**
     * The reader's speed overrides the alias's, so it has to START at the
     * alias's — otherwise opening an article would silently undo the speed
     * the user tuned that voice to (Max, 2026-09-27).
     */
    @Test
    fun `a new article starts at the primary alias's speed`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-fast")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-fast", speed = 2.0f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first()
        h.settle()

        assertEquals(2.0f, vm.playback.first().speed, 0f)
        assertEquals(2.0f, h.article.speed, 0f)
    }

    /** The alias slider stores float noise; the chip must match a clean value. */
    @Test
    fun `the starting speed drops the alias slider's float noise`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-noisy")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-noisy", speed = 1.1000001f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first()
        h.settle()

        assertEquals(1.1f, vm.playback.first().speed, 0f)
    }

    @Test
    fun `with no primary alias a new article starts at 1x`() = runTest {
        val vm = newViewModel(extraction = threeBlocks())
        vm.state.first()

        assertEquals(1.0f, vm.playback.first().speed, 0f)
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
        h.settle()
        controller.next()
        h.runUntil { h.firstHeard(articleId(), 1) != null }
        val t = h.now()

        val vm = newViewModel(controller = controller)
        vm.state.first()
        h.settle()

        assertEquals(1, vm.currentBlockIndex.first())
        assertEquals(ReaderPlaybackStatus.Playing, vm.playback.first().status)
        assertTrue("nothing restarted", h.kokoro.renders.none { it.startMs > t && it.text == "The first paragraph." })
        assertEquals(1, h.resolutions)
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

    // -- The article's voice (language-aware) ------------------------------------

    private val zhAlias = VoiceChoice.Alias(
        aliasId = "id-zh",
        voiceId = "kokoro-direct-v1_0:zf_xiaoni",
        engine = "kokoro-direct-v1_0",
        speed = 1.2000001f,
    )
    private val zhInstalled = VoiceChoice.Installed(
        voiceId = "kokoro-direct-v1_0:zf_xiaobei",
        engine = "kokoro-direct-v1_0",
    )

    /** The voice the Narrator was asked to resolve for the article, each time it did. */
    private fun articleVoices() = h.resolveTargets.filterIsInstance<ResolveTarget.Article>().map { it.choice }

    /** Another alias picked for the language starts at ITS speed, not the primary's. */
    @Test
    fun `an article read in another alias starts at that alias's speed`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-fast")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-fast", speed = 2.0f)))
        val vm = newViewModel(
            extraction = threeBlocks(),
            settings = settings,
            aliasDao = aliasDao,
            voicePicker = FakeVoicePicker(zhAlias),
        )
        vm.state.first()
        h.runUntil { h.firstHeard(articleId(), 0) != null }

        assertEquals(1.2f, vm.playback.first().speed, 0f)
        assertEquals(1.2f, h.article.speed, 0f)
        assertEquals(listOf<VoiceChoice>(zhAlias), articleVoices())
    }

    /** An installed voice has no alias, so no speed of its own: 1.0. */
    @Test
    fun `an article read in an installed voice starts at 1x`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-fast")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-fast", speed = 2.0f)))
        val vm = newViewModel(
            extraction = threeBlocks(),
            settings = settings,
            aliasDao = aliasDao,
            voicePicker = FakeVoicePicker(zhInstalled),
        )
        vm.state.first()
        h.runUntil { h.firstHeard(articleId(), 0) != null }

        assertEquals(1.0f, vm.playback.first().speed, 0f)
        assertEquals(listOf<VoiceChoice>(zhInstalled), articleVoices())
    }

    /** The live speed change still reaches an article read in the picked voice. */
    @Test
    fun `a speed change applies to an article read in a picked voice`() = runTest {
        val vm = newViewModel(extraction = threeBlocks(), voicePicker = FakeVoicePicker(zhInstalled))
        vm.state.first()
        h.settle()

        vm.onSpeedChange(1.5f)
        h.settle()

        assertEquals(1.5f, h.article.speed, 0f)
    }

    /** A missing engine is named after the engine actually reading, not the primary's. */
    @Test
    fun `a missing engine names the picked voice's engine`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-1x")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-1x", speed = 1.0f)))
        val vm = newViewModel(
            extraction = threeBlocks(),
            settings = settings,
            aliasDao = aliasDao,
            voicePicker = FakeVoicePicker(zhInstalled),
        )
        h.kokoro.installed = false
        vm.state.first()
        h.run(3_000)

        assertEquals(
            ReaderPlaybackError.EngineNotInstalled(
                EngineCatalog.byName("kokoro-direct-v1_0")!!.displayName,
            ),
            vm.playbackError.first(),
        )
    }

    /** Decided once per article: a rebind keeps the voice and never re-picks. */
    @Test
    fun `rebinding to a held article does not pick a voice again`() = runTest {
        val controller = newController()
        controller.open(heldArticle(), voice = zhInstalled)
        controller.play()
        val picker = FakeVoicePicker(VoiceChoice.Primary)

        newViewModel(controller = controller, voicePicker = picker).state.first()
        controller.next()
        h.runUntil { h.firstHeard(articleId(), 1) != null }

        assertEquals(0, picker.calls)
        assertEquals(listOf<VoiceChoice>(zhInstalled), articleVoices())
    }

    @Test
    fun `a new article is picked for exactly once`() = runTest {
        val picker = FakeVoicePicker(zhInstalled)

        newViewModel(extraction = threeBlocks(), voicePicker = picker).state.first()

        assertEquals(1, picker.calls)
    }

    private class FakeVoicePicker(private val voice: VoiceChoice) : ReaderVoicePicker {
        var calls = 0
            private set

        override suspend fun voiceFor(article: ReaderArticle): VoiceChoice {
            calls++
            return voice
        }
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

    private suspend fun TestScope.assertFailure(
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

    // -- Speed-up perf warning (RTF-aware, on the chosen absolute speed) ------

    @Test
    fun `speed warning uses the static fallback with no RTF signal`() = runTest {
        // Primary alias speed 1.5, so the article starts at 1.5, past the
        // 1.35 static threshold. No probe and no measured RTF, so the warning
        // falls back to that static rule.
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-fast")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-fast", speed = 1.5f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first()
        h.settle()

        assertTrue(vm.showSpeedWarning.first())
    }

    @Test
    fun `speed warning stays off below the static threshold with no RTF signal`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-slow")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-slow", speed = 1.2f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first()
        h.settle()

        assertEquals(1.2f, vm.playback.first().speed, 0f)
        assertFalse(vm.showSpeedWarning.first())
    }

    /**
     * The chip replaces the alias's speed rather than scaling it: a 2x alias
     * read at the 1x chip is spoken at 1.0, well under the static threshold.
     * Under the old multiplier this was 2.0 and warned.
     */
    @Test
    fun `speed warning follows the chosen speed, not the alias's`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-fast")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-fast", speed = 2.0f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first()
        h.settle()
        assertTrue(vm.showSpeedWarning.first())

        vm.onSpeedChange(1.0f)
        h.settle()

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
        // Probe measures Kitten at 1.5 RTF → predicted Kitten RTF 1.5 × its
        // cost (0.62) ≈ 0.93. No stored measured RTF, so the warning runs on
        // the prediction: 0.93 × 1.0 > 0.8. Alias speed 1.0 keeps the static
        // rule silent.
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("id-1x")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "id-1x", speed = 1.0f)))
        val vm = newViewModel(
            settings = settings,
            aliasDao = aliasDao,
            deviceProbe = FakeDeviceProbe(DeviceProbe(measuredKittenRtf = 1.5, computeScore = null)),
        )

        assertTrue(vm.showSpeedWarning.first())
    }

    private val voicePaths = VoicePathResolver { null }

    // -- The voice chip and alias picker (#66) -------------------------------------

    @Test
    fun `the voice chip names the primary alias reading the article`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("adam")
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "adam", speed = 1f), alias(id = "jenny", speed = 1f)))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first { it is ReaderUiState.Ready }

        assertEquals(ReadingVoice("adam", "adam"), vm.readingVoice.first { it != null })
        assertEquals(listOf(true, false), vm.aliases.first { it.isNotEmpty() }.map { it.isPrimary })
    }

    @Test
    fun `an installed voice is named by the voice itself`() = runTest {
        val vm = newViewModel(extraction = threeBlocks(), voicePicker = FakeVoicePicker(zhInstalled))
        vm.state.first { it is ReaderUiState.Ready }

        val expected = voicePaths.resolve(zhInstalled.voiceId, zhInstalled.engine).voice
        assertEquals(ReadingVoice(null, expected), vm.readingVoice.first { it != null })
    }

    @Test
    fun `picking an alias reads the rest of the article in it`() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Bella")
        settings.setPrimaryAliasId("adam")
        val jenny = alias(id = "jenny", speed = 1.25f)
        val aliasDao = FakeAliasDao(initial = listOf(alias(id = "adam", speed = 1f), jenny))
        val vm = newViewModel(extraction = threeBlocks(), settings = settings, aliasDao = aliasDao)
        vm.state.first { it is ReaderUiState.Ready }
        h.runUntil { h.firstHeard(articleId(), 0) != null }

        vm.onAliasPicked("jenny")
        h.settle()

        val picked = VoiceChoice.Alias("jenny", jenny.voiceId, jenny.engine, 1.25f)
        val pb = vm.playback.first { it.voicePicked }
        assertEquals(picked, pb.voice)
        assertEquals(1.25f, pb.speed, 0f)
        assertEquals(listOf(VoiceChoice.Primary, picked), articleVoices())
        assertEquals(ReadingVoice("jenny", "jenny"), vm.readingVoice.first { it?.aliasId == "jenny" })
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

    /** The real Narrator on the test's own scheduler; created by the first [newController] of a test. */
    private var harness: NarratorHarness? = null

    /** What the fake playback host answers: false = Android refused it (in the background). */
    private var hostAllowed = true
    private val h: NarratorHarness get() = harness!!

    private fun articleId(): Long = h.narrator.articleId()!!

    /** A real controller over the harness's Narrator, its projection on the test's scheduler. */
    private fun TestScope.newController(): ReaderPlaybackController {
        val h = harness ?: NarratorHarness(this, NarratorConfig(todaysDepthOnly = true)).also { harness = it }
        return ReaderPlaybackController(
            narrator = h.narrator,
            host = PlaybackHost { hostAllowed },
            scope = backgroundScope,
        )
    }

    private fun TestScope.newViewModel(
        fetch: FetchResult = success(),
        extraction: ExtractionResult = ExtractionResult.ExtractionFailed,
        extractor: FakeExtractor = FakeExtractor(extraction),
        sharedText: String = "Marmalade Ships $url",
        settings: FakeSettings = FakeSettings(initialId = "kitten-direct-v0_8:Bella"),
        fetcher: FakeFetcher = FakeFetcher(fetch),
        controller: ReaderPlaybackController = newController(),
        aliasDao: FakeAliasDao = FakeAliasDao(),
        deviceProbe: FakeDeviceProbe = FakeDeviceProbe(),
        voicePicker: ReaderVoicePicker = FakeVoicePicker(VoiceChoice.Primary),
        parseDispatcher: CoroutineDispatcher = Dispatchers.Main,
        savedState: Map<String, Any> = emptyMap(),
        handle: SavedStateHandle = savedStateHandle(sharedText, savedState),
        loadHold: ArticleLoadHold = ArticleLoadHold({ true }, backgroundScope),
    ) = ReaderViewModel(
        fetcher = fetcher,
        extractor = extractor,
        playbackController = controller,
        settings = settings,
        aliasDao = aliasDao,
        deviceProbe = deviceProbe,
        voicePicker = voicePicker,
        parseDispatcher = parseDispatcher,
        savedStateHandle = handle,
        loadHold = loadHold,
        voicePaths = voicePaths,
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
