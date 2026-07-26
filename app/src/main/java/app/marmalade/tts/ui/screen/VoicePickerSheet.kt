package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marmalade.tts.data.db.VoiceMeta

// -----------------------------------------------------------------------------
// The drill-down voice picker
// -----------------------------------------------------------------------------
// Replaces a single dropdown that listed every installed voice alphabetically.
// With Venice configured that was 156 entries from nine unrelated models,
// including two different "Alice" (ElevenLabs and Gradium) and two different
// "Alex" (Kokoro and Inworld) with nothing to tell them apart.
//
// Three short lists instead of one long one: ~7 sources → 1-9 models →
// 5-54 voices. The middle level is SKIPPED for on-device engines, which have
// exactly one model (themselves) — rendering a one-item list would be a step
// that teaches the user nothing.
//
// Search cuts across all three levels, because when you know the voice's name
// you shouldn't have to remember which model owns it. Search results carry
// their full path as a subtitle, which is what disambiguates the two Alices.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    state: VoicePickerState,
    tree: List<VoiceSource>,
    selectedVoiceId: String,
    onQueryChange: (String) -> Unit,
    onSelectSource: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onPick: (VoiceMeta) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val source = tree.firstOrNull { it.name == state.source }
    val model = source?.models?.firstOrNull { it.name == state.model }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.source != null || state.searching) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Spacer(Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Choose a voice",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // Breadcrumb. Always says where you are, so the two "Alice"
                // are never ambiguous.
                val crumb = when {
                    state.searching -> "Searching every voice"
                    model != null && source != null && source.models.size > 1 ->
                        "${source.name} › ${model.name}"
                    source != null -> source.name
                    else -> "All sources"
                }
                Text(
                    text = crumb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search all voices") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.heightIn(min = 220.dp, max = 460.dp)) {
            when {
                state.searching -> SearchResults(tree, state.query, selectedVoiceId, onPick)
                tree.isEmpty() -> Empty("No voices installed yet")
                model != null -> VoiceList(model.voices, selectedVoiceId, onPick)
                source != null -> ModelList(source, onSelectModel)
                else -> SourceList(tree, onSelectSource)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun SourceList(tree: List<VoiceSource>, onSelect: (String) -> Unit) {
    LazyColumn {
        items(items = tree, key = { it.name }) { source ->
            DrillRow(
                title = source.name,
                subtitle = buildString {
                    append(if (source.isCloud) "Cloud" else "On device")
                    append(" · ${source.voiceCount} voice")
                    if (source.voiceCount != 1) append("s")
                    // Only worth naming the model count when there's a
                    // choice to make at the next level.
                    if (source.models.size > 1) append(" in ${source.models.size} models")
                },
                onClick = { onSelect(source.name) },
            )
        }
    }
}

@Composable
private fun ModelList(source: VoiceSource, onSelect: (String) -> Unit) {
    LazyColumn {
        items(items = source.models, key = { it.name }) { model ->
            DrillRow(
                title = model.name,
                subtitle = "${model.voices.size} voices",
                onClick = { onSelect(model.name) },
            )
        }
    }
}

@Composable
private fun VoiceList(
    voices: List<VoiceMeta>,
    selectedVoiceId: String,
    onPick: (VoiceMeta) -> Unit,
) {
    if (voices.isEmpty()) {
        Empty("This model has no installed voices")
        return
    }
    LazyColumn {
        items(items = voices, key = { it.id }) { voice ->
            VoiceRow(voice, voice.id == selectedVoiceId, subtitle = null, onPick = onPick)
        }
    }
}

/**
 * Flat cross-level results. Each row carries its full path so a name that
 * exists under several models stays distinguishable.
 */
@Composable
private fun SearchResults(
    tree: List<VoiceSource>,
    query: String,
    selectedVoiceId: String,
    onPick: (VoiceMeta) -> Unit,
) {
    val hits = remember(tree, query) {
        val q = query.trim().lowercase()
        tree.flatMap { source ->
            source.models.flatMap { model ->
                model.voices
                    .filter {
                        it.displayName.lowercase().contains(q) ||
                            model.name.lowercase().contains(q) ||
                            source.name.lowercase().contains(q)
                    }
                    .map { it to "${source.name} › ${model.name}" }
            }
        }
    }
    if (hits.isEmpty()) {
        Empty("No voices match \"$query\"")
        return
    }
    LazyColumn {
        items(items = hits, key = { it.first.id }) { (voice, path) ->
            VoiceRow(voice, voice.id == selectedVoiceId, subtitle = path, onPick = onPick)
        }
    }
}

@Composable
private fun DrillRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceMeta,
    selected: Boolean,
    subtitle: String?,
    onPick: (VoiceMeta) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(voice) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = voice.displayName, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
