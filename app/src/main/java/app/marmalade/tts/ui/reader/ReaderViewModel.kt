package app.marmalade.tts.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ArticleExtractor
import app.marmalade.tts.reader.ArticleFetcher
import app.marmalade.tts.reader.ExtractionResult
import app.marmalade.tts.reader.FetchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
//     │
//     └── state: ReaderUiState.Loading / Failed(reason) / Ready(blocks)
//
//   The article lives here and nowhere else — no disk cache, no database row
//   (reader-mode design point 8: nothing about a fetched page is persisted).
//   Process death therefore re-fetches, which is correct: the alternative is
//   writing someone's article to storage.
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
    ) : ReaderUiState
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val fetcher: ArticleFetcher,
    private val extractor: ArticleExtractor,
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
     * Index of the block currently being spoken, or null when nothing is.
     * Always null until the playback pipeline lands; the screen already binds
     * its highlight to it so that step is ViewModel-only.
     */
    private val _currentBlockIndex = MutableStateFlow<Int?>(null)
    val currentBlockIndex: StateFlow<Int?> = _currentBlockIndex.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Move playback to the tapped block. A no-op until the playback pipeline
     * exists; taps are wired up now so the screen needs no change then.
     */
    fun onBlockTapped(index: Int) = Unit

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
            is ExtractionResult.Success -> ReaderUiState.Ready(
                title = extracted.title,
                byline = extracted.byline,
                blocks = extracted.blocks,
            )
            ExtractionResult.ExtractionFailed ->
                ReaderUiState.Failed(ReaderFailure.ExtractionFailed)
        }

    companion object {
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
