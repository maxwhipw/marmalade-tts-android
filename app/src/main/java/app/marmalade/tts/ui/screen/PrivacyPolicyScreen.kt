package app.marmalade.tts.ui.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R

// -----------------------------------------------------------------------------
// In-app privacy policy
// -----------------------------------------------------------------------------
//   Settings → About → "Privacy policy". Renders the canonical repo-root
//   PRIVACY.md, which the build copies into assets/PRIVACY.md (see the
//   copyPrivacyPolicy task in app/build.gradle.kts) — one source of truth.
//
//   The renderer here handles just the Markdown PRIVACY.md actually uses:
//   # / ## headings, paragraphs, - bullets, > blockquote, a | pipe | table
//   (rendered as "**cell** — cell" rows), and inline **bold**, _italic_,
//   `code`, [text](url) links and <autolinks>. It is deliberately NOT a
//   general Markdown engine — if the policy grows a construct this doesn't
//   cover, extend this file rather than pulling in a dependency.
//
//   Detail screen; the bottom nav bar is hidden by AppRoot while it's open.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val markdown = remember {
        try {
            context.assets.open("PRIVACY.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("PrivacyPolicyScreen", "Failed to read assets/PRIVACY.md", e)
            context.getString(R.string.settings_privacy_load_failed)
        }
    }
    val blocks = remember(markdown) { parseMarkdown(markdown) }

    Scaffold(
        // Nested-Scaffold inset handoff — AppRoot's outer Scaffold owns the
        // status-bar insets; opt out here so the bar doesn't double-pad. See
        // LicensesScreen / SpeakScreen for the full note.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_privacy)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                blocks.forEach { block -> MarkdownBlockView(block) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (block) {
        is MdBlock.Heading -> {
            Spacer(Modifier.height(if (block.level == 1) 4.dp else 18.dp))
            Text(
                text = block.text,
                style = if (block.level == 1) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Bold,
                color = if (block.level == 1) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .semantics { heading() }
                    .padding(bottom = 6.dp),
            )
        }

        is MdBlock.Paragraph -> Text(
            text = renderInline(block.text, linkColor),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is MdBlock.Bullet -> Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text("•  ", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = renderInline(block.text, linkColor),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is MdBlock.Quote -> Text(
            text = renderInline(block.text, linkColor),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(vertical = 6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Tiny Markdown parser — blocks then inline. Scoped to PRIVACY.md's subset.
// -----------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

private fun isBlockStart(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("# ") || t.startsWith("## ") || t.startsWith("- ") ||
        t.startsWith("> ") || t.startsWith("|")
}

private fun parseMarkdown(md: String): List<MdBlock> {
    val lines = md.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.isBlank() -> i++

            t.startsWith("## ") -> {
                blocks += MdBlock.Heading(2, t.removePrefix("## ").trim())
                i++
            }
            t.startsWith("# ") -> {
                blocks += MdBlock.Heading(1, t.removePrefix("# ").trim())
                i++
            }

            t.startsWith("|") -> {
                // Pipe table: emit each data row as a "**cell0** — cell1"
                // paragraph. Skip the |---| separator; the header row renders
                // as a bold label, which reads fine under the section heading.
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val cells = lines[i].split("|").map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val isSeparator = cells.isNotEmpty() &&
                        cells.all { it.isNotEmpty() && it.all { c -> c == '-' || c == ':' } }
                    if (!isSeparator && cells.isNotEmpty()) {
                        val text = if (cells.size >= 2) {
                            "**${cells[0]}** — ${cells.drop(1).joinToString(" ")}"
                        } else {
                            cells[0]
                        }
                        blocks += MdBlock.Paragraph(text)
                    }
                    i++
                }
            }

            t.startsWith("> ") -> {
                val buf = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    buf += lines[i].trimStart().removePrefix(">").trim()
                    i++
                }
                blocks += MdBlock.Quote(buf.joinToString(" ").trim())
            }

            t.startsWith("- ") -> {
                val buf = mutableListOf(t.removePrefix("- ").trim())
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i])) {
                    buf += lines[i].trim()
                    i++
                }
                blocks += MdBlock.Bullet(buf.joinToString(" "))
            }

            else -> {
                val buf = mutableListOf(t)
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i])) {
                    buf += lines[i].trim()
                    i++
                }
                blocks += MdBlock.Paragraph(buf.joinToString(" "))
            }
        }
    }
    return blocks
}

/**
 * Inline Markdown → [AnnotatedString]: **bold**, _italic_, `code`,
 * [text](url) links and <autolinks>. Recursive so bold can wrap a code span
 * (the permissions table's "**`INTERNET`**"). Links use the platform's default
 * URL handler and are styled with [linkColor] + an underline.
 */
private fun renderInline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    appendInline(text, linkColor)
}

private fun AnnotatedString.Builder.appendInline(s: String, linkColor: Color) {
    var i = 0
    while (i < s.length) {
        val rest = s.substring(i)
        when {
            rest.startsWith("**") -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) {
                    append("**"); i += 2
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(s.substring(i + 2, end), linkColor)
                    }
                    i = end + 2
                }
            }

            rest.startsWith("`") -> {
                val end = s.indexOf("`", i + 1)
                if (end < 0) {
                    append("`"); i++
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }

            rest.startsWith("_") -> {
                val end = s.indexOf("_", i + 1)
                if (end < 0) {
                    append("_"); i++
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(s.substring(i + 1, end), linkColor)
                    }
                    i = end + 1
                }
            }

            rest.startsWith("[") -> {
                val close = s.indexOf("]", i)
                val open = if (close >= 0) close + 1 else -1
                val parenClose = if (open >= 0 && open < s.length && s[open] == '(') {
                    s.indexOf(")", open)
                } else {
                    -1
                }
                if (close >= 0 && parenClose >= 0) {
                    val label = s.substring(i + 1, close)
                    val url = s.substring(open + 1, parenClose)
                    withLink(urlLink(url, linkColor)) { appendInline(label, linkColor) }
                    i = parenClose + 1
                } else {
                    append("["); i++
                }
            }

            rest.startsWith("<http") -> {
                val end = s.indexOf(">", i)
                if (end < 0) {
                    append("<"); i++
                } else {
                    val url = s.substring(i + 1, end)
                    withLink(urlLink(url, linkColor)) { append(url) }
                    i = end + 1
                }
            }

            else -> {
                append(s[i]); i++
            }
        }
    }
}

private fun urlLink(url: String, linkColor: Color) = LinkAnnotation.Url(
    url = url,
    styles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    ),
)
