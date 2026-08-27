package app.marmalade.tts.ui.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.reader.ArticleBlock
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
//   purpose — the reader never opens a browser or speaks by itself.
//
//   Playback controls (play/pause, next/previous paragraph) are deliberately
//   absent until the playback pipeline exists; the per-block highlight and
//   tap handler below are already wired to the ViewModel so that step adds
//   no UI plumbing.
// -----------------------------------------------------------------------------

/** Horizontal margin for the reading column — a comfortable measure, not edge-to-edge. */
private val READING_MARGIN = 24.dp

/** Extra line spacing for sustained reading, applied to body-sized blocks. */
private const val BODY_LINE_HEIGHT_SP = 28

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentBlockIndex by viewModel.currentBlockIndex.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        // Nested-Scaffold inset handoff — AppRoot's outer Scaffold owns the
        // status-bar insets; opt out here so the bar doesn't double-pad.
        contentWindowInsets = WindowInsets(0),
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
            )
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
                    onBlockTapped = viewModel::onBlockTapped,
                )
            }
        }
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
    onBlockTapped: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = READING_MARGIN,
            end = READING_MARGIN,
            top = 8.dp,
            bottom = 48.dp,
        ),
    ) {
        item {
            ArticleHeader(title = article.title, byline = article.byline)
        }
        itemsIndexed(article.blocks) { index, block ->
            BlockRow(
                block = block,
                isCurrent = index == currentBlockIndex,
                onClick = { onBlockTapped(index) },
            )
        }
    }
}

@Composable
private fun ArticleHeader(title: String?, byline: String?) {
    if (title == null && byline == null) return
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (byline != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = byline,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun BlockRow(block: ArticleBlock, isCurrent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val background = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        when (block) {
            is ArticleBlock.Heading -> HeadingText(block)
            is ArticleBlock.Paragraph -> BodyText(block.text)
            is ArticleBlock.ListItem -> ListItemText(block.text)
            is ArticleBlock.Quote -> QuoteText(block.text)
        }
    }
}

/**
 * h1–h6 map onto decreasing type. h1 inside the body is rare (the headline is
 * already the screen's header) but real, so it gets its own step rather than
 * colliding with h2.
 */
@Composable
private fun HeadingText(heading: ArticleBlock.Heading) {
    val style = when (heading.level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = heading.text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

@Composable
private fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = BODY_LINE_HEIGHT_SP.sp,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun ListItemText(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = BODY_LINE_HEIGHT_SP.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Spacer(Modifier.width(12.dp))
        BodyText(text)
    }
}

/** Inset with a leading accent bar — the conventional "this is quoted" cue. */
@Composable
private fun QuoteText(text: String) {
    // IntrinsicSize.Min lets the bar match the quote's own height without a
    // hardcoded guess at how many lines the quote runs to.
    Row(modifier = Modifier.height(IntrinsicSize.Min).padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            lineHeight = BODY_LINE_HEIGHT_SP.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
