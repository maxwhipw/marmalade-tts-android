package app.marmalade.tts.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// -----------------------------------------------------------------------------
//   EffectsScreen
//     │
//     ├── reads: EffectsViewModel.effects ──► LazyColumn of effect cards
//     │
//     └── actions
//          ├── FAB → onCreate()              — open the editor blank (E-F)
//          ├── card Edit (custom)   → onEdit(id)        — edit in place
//          ├── card Edit (built-in) → onDuplicate(id)   — fork to a custom copy
//          └── card Delete (custom) → confirm → viewModel.delete(id)
//
//   Hosted by AppRoot as a bottom-nav tab; the back arrow returns to Speak.
//   Built-ins are read-only (re-seeded on catalog bumps), so their "Edit"
//   forks the preset into a new custom effect rather than mutating it.
//   Effects are assigned to voices via an alias (Aliases tab → Effect picker).
// -----------------------------------------------------------------------------

/**
 * The effects catalog — the seeded CLI presets (Cave, Robot, Telephone,
 * Chipmunk, Deep, Whisper, Stadium, Megaphone, Slow deep, Fast high) plus any
 * user-created effects, shown as cards. Mirrors the Engines screen idiom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    viewModel: EffectsViewModel = hiltViewModel(),
) {
    val effects by viewModel.effects.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<EffectCard?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Effects") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "Create effect")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Apply an effect to a voice by attaching it to an alias " +
                        "(Aliases tab → Effect). Tap + to build your own, or Edit a " +
                        "built-in to fork your own copy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items = effects, key = { it.id }) { card ->
                EffectCardItem(
                    card = card,
                    onEdit = { onEdit(card.id) },
                    onDuplicate = { onDuplicate(card.id) },
                    onDelete = { pendingDelete = card },
                )
            }
        }
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${card.name}\"?") },
            text = {
                Text(
                    "This removes the effect. Any alias still using it falls back to " +
                        "no effect.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(card.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectCardItem(
    card: EffectCard,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (card.isBuiltin) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Built-in") })
                }
            }
            Spacer(Modifier.height(8.dp))
            // One chip per block in the chain — the visual separation reads the
            // signal flow (left → right) better than a run-on string.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (label in card.blocks) {
                    BlockChip(label)
                }
            }
            Spacer(Modifier.height(8.dp))
            // Pencil = edit-in-place (custom only); copy = duplicate (all cards);
            // trash = delete (custom only). Built-ins are read-only (re-seeded on
            // catalog bumps), so they get no pencil — duplicate to fork an
            // editable copy.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!card.isBuiltin) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit ${card.name}")
                    }
                }
                IconButton(onClick = onDuplicate) {
                    Icon(ContentCopyIcon, contentDescription = "Duplicate ${card.name}")
                }
                if (!card.isBuiltin) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete ${card.name}",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

// Outlined chip to match the Engines screen idiom (border, no fill), but tinted
// with the theme accent (`primary`) so the blocks keep an orange identity and
// read as the card's primary content — distinct from the muted, gray "Built-in"
// AssistChip tag. `primary` is theme-aware, so the chips track the user's chosen
// accent (orange by default, blue under Midnight, etc.).
/**
 * Material "content copy" glyph, hand-built so we don't pull in the multi-MB
 * `material-icons-extended` dependency for a single icon (core has no copy
 * icon). Standard 24dp path; the fill is overridden by Icon's tint.
 */
private val ContentCopyIcon: ImageVector = ImageVector.Builder(
    name = "ContentCopy",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f)
        horizontalLineTo(4f)
        curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
        verticalLineToRelative(14f)
        horizontalLineToRelative(2f)
        verticalLineTo(3f)
        horizontalLineToRelative(12f)
        verticalLineTo(1f)
        close()
        moveTo(19f, 5f)
        horizontalLineTo(8f)
        curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
        verticalLineToRelative(14f)
        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
        horizontalLineToRelative(11f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(7f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        close()
        moveToRelative(0f, 16f)
        horizontalLineTo(8f)
        verticalLineTo(7f)
        horizontalLineToRelative(11f)
        verticalLineToRelative(14f)
        close()
    }
}.build()

@Composable
private fun BlockChip(label: String) {
    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
