package app.marmalade.tts.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ArticleExtractor
import app.marmalade.tts.reader.ArticleFetcher
import app.marmalade.tts.reader.ExtractionResult
import app.marmalade.tts.reader.FetchResult
import app.marmalade.tts.reader.ReaderPlaybackController
import app.marmalade.tts.reader.ReaderPlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
//   ReaderViewModel
//     │
//     ├── nav args (SavedStateHandle): url, text
//     │     url  → the link SharedUrlDetector found in the share
//     │     text → the ORIGINAL shared text, kept only so the failure UI can
//     │            fall back to the plain "speak what you shared" behaviour
//     │
//     ├── init: ArticleFetcher.fetch(url) → ArticleExtractor.extract(bytes)
//     │           └── on success: hand the blocks to ReaderPlaybackController
//     │               and, if it's a new article, start reading
//     │
//     ├── state: ReaderUiState.Loading / Failed(reason) / Ready(blocks)
//     │
//     ├── playback / currentBlockIndex: projections of the controller's state
//     │
//     └── display: the reading surface's background / font / size, from
//         SettingsRepository — app display settings, so these DO persist
//
//
//   The article lives here and nowhere else — no disk cache, no database row
//   (reader-mode design point 8: nothing about a fetched page is persisted).
//   Process death therefore re-fetches, which is correct: the alternative is
//   writing someone's article to storage.
//
//   Playback is NOT owned here. ReaderPlaybackController is an app-scoped
//   singleton so leaving the screen keeps the article being read (design
//   point 7) and coming back re-binds to the block it has reached. onCleared
//   deliberately does nothing.
// -----------------------------------------------------------------------------

/** Why the reader has nothing to show. One message per case in the failure UI. */
enum class ReaderFailure {
    /** DNS/TCP/TLS/timeout, a malformed URL, or a redirect chain we gave up on. */
    Network,

    /** The server answered with a non-2xx status — 404, or a 403 bot-wall. */
    Http,

    /** Body exceeded the fetcher's size cap. */
    TooLarge,

    /** `Content-Type` says the link is an image/video/PDF, not a page. */
    NotHtml,

    /** The page was fetched but Readability found no article in it. */
    ExtractionFailed,
}

/** What the reader screen renders. */
sealed interface ReaderUiState {

    /** Fetch and extraction in flight. */
    data object Loading : ReaderUiState

    /** Nothing to read; the screen offers browser + read-as-is escapes. */
    data class Failed(val reason: ReaderFailure) : ReaderUiState

    /** An article, ready to render. [blocks] is in document order and non-empty. */
    data class Ready(
        val title: String?,
        val byline: String?,
        val blocks: List<ArticleBlock>,
        /** Characters that will actually be spoken — the extraction-quality signal. */
        val totalTextChars: Int,
    ) : ReaderUiState
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val fetcher: ArticleFetcher,
    private val extractor: ArticleExtractor,
    private val playbackController: ReaderPlaybackController,
    private val settings: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The shared link. Non-null in practice — the route can't be built without it. */
    val url: String = savedStateHandle[ARG_URL] ?: ""

    /** The original share payload, for the failure UI's "read text as-is" action. */
    val sharedText: String = savedStateHandle[ARG_TEXT] ?: ""

    /** Host of [url], shown while loading so the user sees who we're contacting. */
    val pageHost: String = hostOf(url)

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /**
     * The controller's transport state, filtered to this article: another
     * article's playback (the user shared a second link, say) must not drive
     * this screen's controls.
     */
    val playback: StateFlow<ReaderPlaybackState> = playbackController.state
        .map { if (it.articleKey == url) it else ReaderPlaybackState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPlaybackState())

    /** Index of the block currently being spoken, or null when nothing is. */
    val currentBlockIndex: StateFlow<Int?> = playback
        .map { if (it.isActive) it.currentIndex else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Reading-surface display settings. Persisted (they are app settings, not
     * article content), so they survive the screen and the process.
     */
    val display: StateFlow<ReaderDisplayPrefs> = combine(
        settings.readerBackground,
        settings.readerFont,
        settings.readerFontSizeSp,
    ) { background, font, sizeSp ->
        ReaderDisplayPrefs(
            background = readerBackgroundOf(background),
            font = readerFontOf(font),
            fontSizeSp = clampReaderFontSize(
                sizeSp ?: ReaderDisplayPrefs.DEFAULT_FONT_SIZE_SP,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderDisplayPrefs())

    private val shortExtractionNoticeDismissed = MutableStateFlow(false)

    /**
     * Whether to warn that the page probably didn't extract fully.
     *
     * Advisory only — the article still renders and still plays. Some pages
     * (MDN, Substack) hand Readability a fraction of their text, and paywall
     * chrome is deliberately not pattern-matched, so this banner is the single
     * mitigation for "what you're hearing isn't the whole page": it offers the
     * browser and gets out of the way.
     *
     * Dismissal is in-memory and per-article, which the ViewModel's own
     * lifetime already gives us — a new share means a new ViewModel.
     */
    val showShortExtractionNotice: StateFlow<Boolean> =
        combine(state, shortExtractionNoticeDismissed) { current, dismissed ->
            !dismissed &&
                current is ReaderUiState.Ready &&
                current.totalTextChars < SHORT_EXTRACTION_CHARS
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { load() }
    }

    fun onDismissShortExtractionNotice() {
        shortExtractionNoticeDismissed.value = true
    }

    fun onBackgroundChange(background: ReaderBackground) {
        viewModelScope.launch { settings.setReaderBackground(background.name) }
    }

    fun onFontChange(font: ReaderFont) {
        viewModelScope.launch { settings.setReaderFont(font.name) }
    }

    /** Step the body size by [deltaSp], clamped to the supported range. */
    fun onFontSizeStep(deltaSp: Int) {
        val next = clampReaderFontSize(display.value.fontSizeSp + deltaSp)
        viewModelScope.launch { settings.setReaderFontSizeSp(next) }
    }

    /** Move playback to the tapped block (design point 9's tap-to-seek). */
    fun onBlockTapped(index: Int) = playbackController.seekTo(index)

    fun onPlayPause() = playbackController.togglePlayPause()

    fun onNextBlock() = playbackController.next()

    fun onPreviousBlock() = playbackController.previous()

    private suspend fun load() {
        if (url.isEmpty()) {
            _state.value = ReaderUiState.Failed(ReaderFailure.Network)
            return
        }
        _state.value = when (val fetched = fetcher.fetch(url)) {
            is FetchResult.Success -> extractFrom(fetched)
            is FetchResult.HttpError -> ReaderUiState.Failed(ReaderFailure.Http)
            is FetchResult.NotHtml -> ReaderUiState.Failed(ReaderFailure.NotHtml)
            FetchResult.TooLarge -> ReaderUiState.Failed(ReaderFailure.TooLarge)
            // A chain we can't follow is, from the reader's point of view, the
            // same dead end as an unreachable host: retry or open a browser.
            FetchResult.TooManyRedirects -> ReaderUiState.Failed(ReaderFailure.Network)
            is FetchResult.NetworkError -> ReaderUiState.Failed(ReaderFailure.Network)
        }
    }

    private fun extractFrom(fetched: FetchResult.Success): ReaderUiState =
        when (val extracted = extractor.extract(fetched.bytes, fetched.finalUrl)) {
            is ExtractionResult.Success -> {
                // Sharing a link to a TTS app means "read me this", so a
                // freshly-opened article starts speaking on its own. Coming
                // back to an article that is already loaded does NOT restart
                // it — open() reports that, and playback carries on wherever
                // it had got to.
                if (playbackController.open(url, extracted.blocks.map { it.text })) {
                    playbackController.play()
                }
                ReaderUiState.Ready(
                    title = extracted.title,
                    byline = extracted.byline,
                    blocks = extracted.blocks,
                    totalTextChars = extracted.totalTextChars,
                )
            }
            ExtractionResult.ExtractionFailed ->
                ReaderUiState.Failed(ReaderFailure.ExtractionFailed)
        }

    companion object {
        /**
         * Below this many extracted characters, the reader warns the page may
         * not have come across whole.
         *
         * Calibrated on Spike A's 15-URL corpus, where the worst genuine
         * under-extraction was MDN at 2022 chars. 1000 deliberately sits below
         * even that: a short-but-complete page — a link post, a release note —
         * must never be told it failed, and the cost of the conservative
         * setting is only that a borderline miss like MDN goes unflagged.
         */
        const val SHORT_EXTRACTION_CHARS = 1000

        /** Nav argument names — see `Routes.reader`. */
        const val ARG_URL = "url"
        const val ARG_TEXT = "text"

        /** Host of [url], falling back to the raw string if it won't parse. */
        private fun hostOf(url: String): String = try {
            URL(url).host.orEmpty().ifEmpty { url }
        } catch (e: Exception) {
            url
        }
    }
}
