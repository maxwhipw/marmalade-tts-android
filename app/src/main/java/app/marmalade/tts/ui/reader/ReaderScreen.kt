package app.marmalade.tts.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.reader.ReaderPlaybackState
import app.marmalade.tts.reader.ReaderPlaybackStatus
import app.marmalade.tts.service.SpeakDispatcher

// -----------------------------------------------------------------------------
// Reader screen — a shared link, rendered as native Compose text.
// -----------------------------------------------------------------------------
//   ShareIntentActivity (URL detected) ─► MainActivity ─► Routes.Reader
//                                                            │
//                                             ReaderViewModel.state
//                                                            │
//     Loading ──► spinner + the host we're contacting
//     Ready   ──► LazyColumn: title header, byline, then one row per block
//     Failed  ──► reason message + "open in browser" / "read text as-is"
//
//   Nothing here renders HTML: blocks are typed text, so there is no WebView
//   and no remote resource load. Both failure actions are user-triggered, on
//   purpose — the reader never opens a browser.
//
//   Ready also carries the transport bar ("Aa" | transport | "N of M") and
//   follows the spoken block with a smooth scroll. The scroll defers to the
//   user: a recent drag suppresses it (ReaderAutoScroll).
//
//   The reading surface is painted from ReaderDisplayPrefs, NOT the app theme:
//   someone running the app in light mode still gets to read on black. Loading
//   and failure stay app-themed — there is no article to style there.
// -----------------------------------------------------------------------------

/** Horizontal margin for the reading column — a comfortable measure, not edge-to-edge. */
private val READING_MARGIN = 24.dp

/** Extra line spacing for sustained reading, applied to body-sized blocks. */
private const val BODY_LINE_HEIGHT_SP = 28

/**
 * How the reading surface is painted right now: the resolved preset colors
 * plus the user's font and size. Threaded through the article composables as
 * one value so each of them doesn't grow two parameters.
 */
private data class ReaderSurface(
    val palette: ReaderPalette,
    val prefs: ReaderDisplayPrefs,
)

/** Re-style a theme text style for the reading surface: user font, user size. */
private fun TextStyle.forReader(prefs: ReaderDisplayPrefs): TextStyle = copy(
    fontFamily = prefs.font.fontFamily() ?: fontFamily,
    fontSize = fontSize * prefs.textScale,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentBlockIndex by viewModel.currentBlockIndex.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val prefs by viewModel.display.collectAsStateWithLifecycle()
    val showShortExtractionNotice by
        viewModel.showShortExtractionNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Which preset an untouched preference resolves to. Read off the theme's
    // own background rather than isSystemInDarkTheme() so the app's in-app
    // light/dark override counts too.
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ready = state as? ReaderUiState.Ready
    val surface = ReaderSurface(prefs.resolveBackground(appIsDark).palette(), prefs)
    // Only an actual article gets the preset paint; loading and failure have
    // no page to style and stay on the app theme.
    val articlePalette = if (ready != null) surface.palette else null

    var showDisplaySheet by remember { mutableStateOf(false) }

    Scaffold(
        // Nested-Scaffold inset handoff — AppRoot's outer Scaffold owns the
        // status-bar insets; opt out here so the bar doesn't double-pad.
        contentWindowInsets = WindowInsets(0),
        containerColor = articlePalette?.background
            ?: MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.reader_title)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                // The bar sits directly above the page, so it takes the
                // preset too — an app-themed strip over a black page reads
                // as a rendering bug.
                colors = if (articlePalette == null) {
                    TopAppBarDefaults.centerAlignedTopAppBarColors()
                } else {
                    TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = articlePalette.background,
                        titleContentColor = articlePalette.text,
                        navigationIconContentColor = articlePalette.text,
                    )
                },
            )
        },
        bottomBar = {
            if (ready != null) {
                TransportBar(
                    blockCount = ready.blocks.size,
                    playback = playback,
                    palette = surface.palette,
                    onOpenDisplaySettings = { showDisplaySheet = true },
                    onPlayPause = viewModel::onPlayPause,
                    onPrevious = viewModel::onPreviousBlock,
                    onNext = viewModel::onNextBlock,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                ReaderUiState.Loading -> LoadingBody(host = viewModel.pageHost)
                is ReaderUiState.Failed -> FailedBody(
                    reason = current.reason,
                    onOpenInBrowser = { openUrl(context, viewModel.url) },
                    onReadAsIs = {
                        SpeakDispatcher.dispatch(context, viewModel.sharedText)
                        onBack()
                    },
                )
                is ReaderUiState.Ready -> ArticleBody(
                    article = current,
                    currentBlockIndex = currentBlockIndex,
                    surface = surface,
                    showShortExtractionNotice = showShortExtractionNotice,
                    onOpenInBrowser = { openUrl(context, viewModel.url) },
                    onDismissNotice = viewModel::onDismissShortExtractionNotice,
                    onBlockTapped = viewModel::onBlockTapped,
                )
            }
        }
    }

    if (showDisplaySheet) {
        ReaderTypographySheet(
            prefs = prefs,
            appIsDark = appIsDark,
            onBackgroundChange = viewModel::onBackgroundChange,
            onFontChange = viewModel::onFontChange,
            onFontSizeStep = viewModel::onFontSizeStep,
            onDismiss = { showDisplaySheet = false },
        )
    }
}

@Composable
private fun LoadingBody(host: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(READING_MARGIN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.reader_loading),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = host,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailedBody(
    reason: ReaderFailure,
    onOpenInBrowser: () -> Unit,
    onReadAsIs: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(READING_MARGIN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.reader_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(reason.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenInBrowser, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reader_open_in_browser))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onReadAsIs, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reader_read_as_is))
        }
    }
}

@Composable
private fun ArticleBody(
    article: ReaderUiState.Ready,
    currentBlockIndex: Int?,
    surface: ReaderSurface,
    showShortExtractionNotice: Boolean,
    onOpenInBrowser: () -> Unit,
    onDismissNotice: () -> Unit,
    onBlockTapped: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    FollowSpokenBlock(listState = listState, currentBlockIndex = currentBlockIndex)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = READING_MARGIN,
            end = READING_MARGIN,
            top = 8.dp,
            bottom = 48.dp,
        ),
    ) {
        // Banner and header share one list item so the article's blocks keep
        // starting at list index 1 — FollowSpokenBlock's offset depends on it,
        // and the banner can appear or vanish mid-playback.
        item {
            Column {
                if (showShortExtractionNotice) {
                    ShortExtractionNotice(
                        surface = surface,
                        onOpenInBrowser = onOpenInBrowser,
                        onDismiss = onDismissNotice,
                    )
                }
                ArticleHeader(
                    title = article.title,
                    byline = article.byline,
                    surface = surface,
                )
            }
        }
        itemsIndexed(article.blocks) { index, block ->
            BlockRow(
                block = block,
                isCurrent = index == currentBlockIndex,
                surface = surface,
                onClick = { onBlockTapped(index) },
            )
        }
    }
}

/**
 * Smooth-scroll the list to the block being spoken, unless the user has just
 * been scrolling (see [ReaderAutoScroll]).
 *
 * The `+ 1` is the header item: the article's own blocks start at list index 1
 * whether or not the header has a title to draw.
 */
@Composable
private fun FollowSpokenBlock(listState: LazyListState, currentBlockIndex: Int?) {
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        // Only drags reach the list's own interaction source — a tap on a
        // block is that block's clickable, so tapping to seek never counts as
        // "the user is reading somewhere else".
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start ||
                interaction is DragInteraction.Stop ||
                interaction is DragInteraction.Cancel
            ) {
                lastUserScrollAt = SystemClock.elapsedRealtime()
            }
        }
    }

    LaunchedEffect(currentBlockIndex) {
        val index = currentBlockIndex ?: return@LaunchedEffect
        val allowed = ReaderAutoScroll.shouldAutoScroll(
            nowMillis = SystemClock.elapsedRealtime(),
            lastUserScrollMillis = lastUserScrollAt,
            userIsScrolling = listState.isScrollInProgress,
        )
        if (allowed) listState.animateScrollToItem(index + 1)
    }
}

/**
 * Advisory strip above the article when very little text came out of the page
 * (see `ReaderViewModel.SHORT_EXTRACTION_CHARS`).
 *
 * Not a gate: whatever was extracted is rendered below it and is already being
 * read. It exists because the alternative — silently reading three paragraphs
 * of a long page — leaves the user with no way to tell that anything is
 * missing. Dismissing it is meant to be cheap, so it dismisses for good.
 */
@Composable
private fun ShortExtractionNotice(
    surface: ReaderSurface,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = surface.palette.highlight,
        contentColor = surface.palette.text,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.reader_short_extraction),
                style = MaterialTheme.typography.bodyMedium.forReader(surface.prefs),
                color = surface.palette.text,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.reader_short_extraction_dismiss),
                        color = surface.palette.muted,
                    )
                }
                TextButton(onClick = onOpenInBrowser) {
                    Text(
                        text = stringResource(R.string.reader_open_in_browser),
                        color = surface.palette.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleHeader(title: String?, byline: String?, surface: ReaderSurface) {
    if (title == null && byline == null) return
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.forReader(surface.prefs),
                fontWeight = FontWeight.Bold,
                color = surface.palette.text,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (byline != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = byline,
                style = MaterialTheme.typography.labelLarge.forReader(surface.prefs),
                color = surface.palette.muted,
            )
        }
    }
}

/**
 * Three zones: "Aa" (display settings) | transport | "N of M".
 *
 * The transport is centred rather than parked on one end — it's the control
 * the thumb goes for, and the two text zones are read, not pressed. Both
 * flanks take equal weight so the centre stays centred whatever the readout
 * grows to.
 *
 * Backward is not a seek — audio is synthesised one block at a time, so there
 * is nothing to scrub. It restarts the current block, or steps back a block if
 * the current one only just started (see [app.marmalade.tts.reader.ReaderPlaybackController.previous]).
 */
@Composable
private fun TransportBar(
    blockCount: Int,
    playback: ReaderPlaybackState,
    palette: ReaderPalette,
    onOpenDisplaySettings: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val isPlaying = playback.status == ReaderPlaybackStatus.Playing
    // The highlight tint, not the page color: it separates the bar from the
    // article without introducing a fourth color per preset.
    Surface(color = palette.highlight, contentColor = palette.text) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayLabel = stringResource(R.string.reader_display_open)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                TextButton(
                    onClick = onOpenDisplaySettings,
                    modifier = Modifier.semantics { contentDescription = displayLabel },
                ) {
                    Text(
                        text = stringResource(R.string.reader_display_sample),
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.text,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        painter = painterResource(R.drawable.ic_reader_skip_previous),
                        contentDescription = stringResource(R.string.reader_previous_block),
                        tint = palette.text,
                    )
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = palette.text,
                        contentColor = palette.background,
                    ),
                ) {
                    if (isPlaying) {
                        Icon(
                            painter = painterResource(R.drawable.ic_reader_pause),
                            contentDescription = stringResource(R.string.reader_pause),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.reader_play),
                        )
                    }
                }
                IconButton(onClick = onNext) {
                    Icon(
                        painter = painterResource(R.drawable.ic_reader_skip_next),
                        contentDescription = stringResource(R.string.reader_next_block),
                        tint = palette.text,
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.reader_progress,
                    playback.currentIndex + 1,
                    blockCount,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = palette.muted,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One article block. The highlight background is the whole point of the row
 * wrapper: playback marks its current block by index, and the row it lands on
 * has to read as "this is what you're hearing" without moving the text.
 */
@Composable
private fun BlockRow(
    block: ArticleBlock,
    isCurrent: Boolean,
    surface: ReaderSurface,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    // Cross-fade rather than snap: the highlight moves on every block
    // boundary, and a hard swap of a full-width background reads as a flicker.
    val background by animateColorAsState(
        targetValue = if (isCurrent) surface.palette.highlight else Color.Transparent,
        label = "blockHighlight",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        when (block) {
            is ArticleBlock.Heading -> HeadingText(block, surface)
            is ArticleBlock.Paragraph -> BodyText(block.text, surface)
            is ArticleBlock.ListItem -> ListItemText(block.text, surface)
            is ArticleBlock.Quote -> QuoteText(block.text, surface)
        }
    }
}

/**
 * h1–h6 map onto decreasing type. h1 inside the body is rare (the headline is
 * already the screen's header) but real, so it gets its own step rather than
 * colliding with h2.
 */
@Composable
private fun HeadingText(heading: ArticleBlock.Heading, surface: ReaderSurface) {
    val style = when (heading.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = heading.text,
        style = style.forReader(surface.prefs),
        fontWeight = FontWeight.SemiBold,
        color = surface.palette.text,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

@Composable
private fun BodyText(
    text: String,
    surface: ReaderSurface,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.forReader(surface.prefs),
        lineHeight = BODY_LINE_HEIGHT_SP.sp * surface.prefs.textScale,
        color = surface.palette.text,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun ListItemText(text: String, surface: ReaderSurface) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyLarge.forReader(surface.prefs),
            lineHeight = BODY_LINE_HEIGHT_SP.sp * surface.prefs.textScale,
            color = surface.palette.text,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.width(12.dp))
        BodyText(text, surface)
    }
}

/** Inset with a leading accent bar — the conventional "this is quoted" cue. */
@Composable
private fun QuoteText(text: String, surface: ReaderSurface) {
    // IntrinsicSize.Min lets the bar match the quote's own height without a
    // hardcoded guess at how many lines the quote runs to.
    Row(modifier = Modifier.height(IntrinsicSize.Min).padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(surface.palette.muted, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.forReader(surface.prefs),
            fontStyle = FontStyle.Italic,
            lineHeight = BODY_LINE_HEIGHT_SP.sp * surface.prefs.textScale,
            color = surface.palette.muted,
        )
    }
}

/** Message for each failure category. */
private fun ReaderFailure.messageRes(): Int = when (this) {
    ReaderFailure.Network -> R.string.reader_failed_network
    ReaderFailure.Http -> R.string.reader_failed_http
    ReaderFailure.TooLarge -> R.string.reader_failed_too_large
    ReaderFailure.NotHtml -> R.string.reader_failed_not_html
    ReaderFailure.ExtractionFailed -> R.string.reader_failed_extraction
}

/** Open [url] in the user's browser; no-op (logged) if nothing can handle it. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (e: ActivityNotFoundException) {
        Log.w("ReaderScreen", "No browser to open the shared link", e)
    }
}
