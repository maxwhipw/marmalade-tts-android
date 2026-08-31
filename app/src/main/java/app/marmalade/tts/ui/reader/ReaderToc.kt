package app.marmalade.tts.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.reader.ArticleBlock

// -----------------------------------------------------------------------------
// Table of contents — the article's own headings, as a way to jump.
//
//   blocks[] ──► tocEntriesOf() ──► TocEntry(blockIndex, text, depth)
//                     │
//                     └── empty unless the page really has a structure
//                         (MIN_HEADINGS), because a "contents" button that
//                         opens a list of one is worse than no button.
//
//   Tapping an entry is tapping that block in the article: same seek, same
//   auto-scroll, so the ToC adds a way in and no second behaviour to maintain.
//
// The two functions are plain Kotlin so the visibility rule and the
// current-section arithmetic are unit-testable off-UI.
// -----------------------------------------------------------------------------

/**
 * One heading, ready to draw. [blockIndex] indexes the article's blocks (what
 * playback speaks); [depth] is 0 for the shallowest heading in this article,
 * so a page whose sections are all `h2` doesn't render uniformly indented.
 */
data class TocEntry(
    val blockIndex: Int,
    val text: String,
    val depth: Int,
)

/**
 * Headings of [blocks], or empty when the article has no structure worth
 * navigating — fewer than [MIN_TOC_HEADINGS] of them.
 */
fun tocEntriesOf(blocks: List<ArticleBlock>): List<TocEntry> {
    val headings = blocks.withIndex().filter { it.value is ArticleBlock.Heading }
    if (headings.size < MIN_TOC_HEADINGS) return emptyList()
    val minLevel = headings.minOf { (it.value as ArticleBlock.Heading).level }
    return headings.map { (index, block) ->
        val heading = block as ArticleBlock.Heading
        TocEntry(
            blockIndex = index,
            text = heading.text,
            depth = heading.level - minLevel,
        )
    }
}

/**
 * The entry the reader is inside: the last heading at or before
 * [currentBlockIndex]. Null before the first heading, or when nothing is being
 * spoken — the article's preamble belongs to no section.
 */
fun currentTocEntry(entries: List<TocEntry>, currentBlockIndex: Int?): TocEntry? {
    val index = currentBlockIndex ?: return null
    return entries.lastOrNull { it.blockIndex <= index }
}

/** Below this many headings a page isn't structured, it just has a subhead. */
const val MIN_TOC_HEADINGS = 2

/** Indent per heading level. Two levels deep is already the practical maximum. */
private val TOC_INDENT = 16.dp

/** Keeps a long article's contents from taking over the whole screen. */
private val TOC_MAX_HEIGHT = 420.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    entries: List<TocEntry>,
    currentBlockIndex: Int?,
    onEntryTapped: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = currentTocEntry(entries, currentBlockIndex)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_toc_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
            LazyColumn(modifier = Modifier.heightIn(max = TOC_MAX_HEIGHT)) {
                items(entries, key = { it.blockIndex }) { entry ->
                    val isCurrent = entry.blockIndex == current?.blockIndex
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEntryTapped(entry.blockIndex) }
                            .padding(
                                start = 24.dp + TOC_INDENT * entry.depth,
                                end = 24.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                    )
                }
            }
        }
    }
}
