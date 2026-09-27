package app.marmalade.tts.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ArticleExtractor
import app.marmalade.tts.reader.ArticleFetcher
import app.marmalade.tts.reader.ExtractionResult
import app.marmalade.tts.reader.FetchResult
import app.marmalade.tts.reader.ReaderArticle
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.perf.DeviceProbe
import app.marmalade.tts.perf.DeviceProbeSource
import app.marmalade.tts.perf.EngineRecommender
import app.marmalade.tts.perf.SpeedPerfWarning
import app.marmalade.tts.reader.ReaderParseDispatcher
import app.marmalade.tts.reader.ReaderPlaybackController
import app.marmalade.tts.reader.ReaderPlaybackState
import app.marmalade.tts.service.PreviewCompletions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
//   ReaderViewModel
//     │
//     ├── nav args (SavedStateHandle): url, text
//     │     url  → the link SharedUrlDetector found in the share
//     │     text → the ORIGINAL shared text, kept only so the failure UI can
//     │            fall back to the plain "speak what you shared" behaviour
//     │
//     ├── init: ArticleFetcher.fetch(url) → ArticleExtractor.extract(bytes)
//     │           │   (the extract runs on @ReaderParseDispatcher, off Main)
//     │           └── on success: hand the blocks to ReaderPlaybackController
//     │               and, if it's a new article, start reading — once per
//     │               ViewModel: a SavedStateHandle flag stops a ViewModel
//     │               restored after process death from autoplaying again
//     │
//     ├── state: ReaderUiState.Loading / Failed(reason) / Ready(blocks)
//     │
//     ├── playback / currentBlockIndex / playbackError: projections of the
//     │     controller's state
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

/**
 * Why reading stopped, for the inline error line. Only failures the user
 * didn't cause — a Stop from the notification is not an error.
 */
sealed interface ReaderPlaybackError {

    /** The voice's engine isn't installed. [engineLabel] names it for the user. */
    data class EngineNotInstalled(val engineLabel: String) : ReaderPlaybackError

    /** Synthesis failed, or the playback service wouldn't start. */
    data object Failed : ReaderPlaybackError
}

/** The primary alias's engine + its own tuned speed, or a neutral default. */
private data class PrimaryAlias(val engine: String, val speed: Float)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val fetcher: ArticleFetcher,
    private val extractor: ArticleExtractor,
    private val playbackController: ReaderPlaybackController,
    private val settings: SettingsRepository,
    private val aliasDao: VoiceAliasDao,
    private val deviceProbe: DeviceProbeSource,
    @ReaderParseDispatcher private val parseDispatcher: CoroutineDispatcher,
    private val savedStateHandle: SavedStateHandle,
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

    /**
     * The primary alias's engine and its own tuned speed — the two inputs the
     * effective-speed perf warning needs. The service multiplies the reader's
     * chip against this speed (see
     * [app.marmalade.tts.service.TtsRouter.resolveAlias] and MarmaladeSynthService's
     * `speed * speedMultiplier`), and it routes to this engine. Falls back to
     * an empty engine + 1.0 speed when no primary alias is set (or it has been
     * deleted), mirroring the service falling through to the engine's default.
     */
    private val primaryAlias: StateFlow<PrimaryAlias> = combine(
        settings.primaryAliasId,
        aliasDao.getAll(),
    ) { primaryId, aliases ->
        aliases.firstOrNull { it.id == primaryId }
            ?.let { PrimaryAlias(engine = it.engine, speed = it.speed) }
            ?: PrimaryAlias(engine = "", speed = 1.0f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PrimaryAlias(engine = "", speed = 1.0f))

    /**
     * This device's synthesis-capability probe, resolved once. Feeds the
     * cold-start RTF prediction the warning uses before measured RTFs accrue;
     * null while the probe is still running or if it carried no signal.
     */
    private val deviceProbeState = MutableStateFlow<DeviceProbe?>(null)

    /**
     * Whether to show the speed-up performance warning for the current chip
     * selection. Effective speed is chip × the primary alias's own speed; the
     * warning fires when that outruns the engine's measured (or, cold,
     * predicted) RTF. See [SpeedPerfWarning].
     */
    val showSpeedWarning: StateFlow<Boolean> = combine(
        playback,
        primaryAlias,
        deviceProbeState,
        settings.engineRtf,
    ) { pb, alias, probe, rtfByEngine ->
        val effectiveSpeed = pb.speedMultiplier * alias.speed
        val measured = rtfByEngine[alias.engine]
        val predicted = probe?.let { EngineRecommender.predictedRtf(alias.engine, it) }
        SpeedPerfWarning.shouldWarn(measured, predicted, effectiveSpeed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Why reading last stopped on its own, or null. The service suppresses its
     * error notification for in-app requests (the caller is expected to show
     * the error), so without this a failed read would just go quiet.
     *
     * A missing engine is named after the primary alias's engine — the one the
     * service routed to. With no primary alias the service fell back to an
     * engine default we can't name, so that case reads as a plain failure.
     */
    val playbackError: StateFlow<ReaderPlaybackError?> =
        combine(playback, primaryAlias) { pb, alias ->
            when (pb.lastError) {
                null -> null
                PreviewCompletions.ErrorKind.MODEL_MISSING ->
                    if (alias.engine.isEmpty()) {
                        ReaderPlaybackError.Failed
                    } else {
                        ReaderPlaybackError.EngineNotInstalled(engineLabelOf(alias.engine))
                    }
                PreviewCompletions.ErrorKind.FAILED -> ReaderPlaybackError.Failed
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        // Probe once, off the load path. The warning falls back to the static
        // rule until this lands, so a slow probe never blocks the sheet.
        viewModelScope.launch { deviceProbeState.value = deviceProbe.probe() }
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

    /**
     * Set how fast this article is read — session-only, and a factor on the
     * alias's own speed rather than an absolute rate. It goes straight to the
     * controller and nowhere near [settings]: unlike the display prefs above,
     * this one is deliberately not persisted (see
     * [ReaderPlaybackState.speedMultiplier]).
     */
    fun onSpeedMultiplierChange(multiplier: Float) =
        playbackController.setSpeedMultiplier(multiplier)

    /** Read from the tapped block — plays even when paused (design point 9's tap-to-seek). */
    fun onBlockTapped(index: Int) = playbackController.playFrom(index)

    fun onPlayPause() = playbackController.togglePlayPause()

    /**
     * Leaving the reader by back navigation (the top-bar arrow or system back)
     * pauses playback AND cancels the reader's queued/in-flight synthesis, so
     * nothing starts speaking after the user has left — including a back tap
     * during the pre-first-audio synthesis wait. Switching to another app does
     * NOT come through here — the FGS/MediaSession keeps that audio alive on
     * purpose. See [ReaderPlaybackController.pauseForNavigation].
     */
    fun onBackFromReader() = playbackController.pauseForNavigation()

    fun onNextBlock() = playbackController.next()

    fun onPreviousBlock() = playbackController.previous()

    private suspend fun load() {
        if (url.isEmpty()) {
            _state.value = ReaderUiState.Failed(ReaderFailure.Network)
            return
        }
        // The controller first. It holds this article whenever playback is
        // still bound to it, so coming back — tapping the notification,
        // re-sharing the same link — rebinds off the copy in memory instead
        // of fetching the page a second time. No autoplay here: whatever the
        // article was doing, it carries on doing.
        val held = playbackController.article(url)
        if (held != null) {
            _state.value = ReaderUiState.Ready(
                title = held.title,
                byline = held.byline,
                blocks = held.blocks,
                totalTextChars = held.totalTextChars,
            )
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

    private suspend fun extractFrom(fetched: FetchResult.Success): ReaderUiState {
        val extracted = withContext(parseDispatcher) {
            extractor.extract(fetched.bytes, fetched.finalUrl, fetched.contentType)
        }
        return when (extracted) {
            is ExtractionResult.Success -> {
                // Sharing a link to a TTS app means "read me this", so a
                // freshly-opened article starts speaking on its own. Coming
                // back to an article that is already loaded does NOT restart
                // it — open() reports that, and playback carries on wherever
                // it had got to.
                //
                // Nor does a ViewModel that already autoplayed once: after
                // process death the controller is empty again, so open()
                // says "new", but the user never re-shared anything — they
                // are returning to a screen, and it must not start talking.
                val article = ReaderArticle(
                    url = url,
                    title = extracted.title,
                    byline = extracted.byline,
                    blocks = extracted.blocks,
                    totalTextChars = extracted.totalTextChars,
                )
                val isNew = playbackController.open(article)
                if (isNew && savedStateHandle.get<Boolean>(KEY_AUTOPLAYED) != true) {
                    savedStateHandle[KEY_AUTOPLAYED] = true
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

        /** Saved-state flag: this ViewModel has already started the article once. */
        internal const val KEY_AUTOPLAYED = "reader_autoplayed"

        /** User-facing name for [engine], as the engine list shows it. */
        private fun engineLabelOf(engine: String): String = when (engine) {
            // Not in EngineCatalog — hosted engine with no installable bundle.
            CloudApiVoiceCatalog.ENGINE -> CloudApiVoiceCatalog.DISPLAY_NAME
            else -> EngineCatalog.byName(engine)?.displayName ?: engine
        }

        /** Host of [url], falling back to the raw string if it won't parse. */
        private fun hostOf(url: String): String = try {
            URL(url).host.orEmpty().ifEmpty { url }
        } catch (e: Exception) {
            url
        }
    }
}
