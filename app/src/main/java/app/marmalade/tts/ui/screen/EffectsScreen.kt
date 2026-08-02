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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectBlock

// -----------------------------------------------------------------------------
//   EffectsScreen
//     │
//     ├── reads: EffectsViewModel.effects ──► LazyColumn of effect cards
//     │
//     └── actions
//          ├── FAB → onCreate()              — open the editor blank (E-F)
//          ├── card Play            → viewModel.preview(card) / stopPreview()
//          ├── card Edit (custom)   → onEdit(id)        — edit in place
//          ├── card Edit (built-in) → onDuplicate(id)   — fork to a custom copy
//          └── card Delete (custom) → confirm → viewModel.delete(id)
//
//   Hosted by AppRoot as a bottom-nav tab; the back arrow returns to Speak.
//   The user's own effects list above the built-ins (EffectDao.getAll orders
//   them). Built-ins are read-only (re-seeded on catalog bumps), so their "Edit"
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
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<EffectCard?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.effects_title)) },
                windowInsets = WindowInsets(0),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreate,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.effects_create),
                )
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
                    text = stringResource(R.string.effects_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(items = effects, key = { it.id }) { card ->
                EffectCardItem(
                    card = card,
                    isPlaying = preview.playingId == card.id,
                    error = previewErrorFor(preview, card.id),
                    onPlay = { viewModel.preview(card) },
                    onStop = viewModel::stopPreview,
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
            title = { Text(stringResource(R.string.effects_delete_title, card.name)) },
            text = { Text(stringResource(R.string.effects_delete_message)) },
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
                ) { Text(stringResource(R.string.effects_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.effects_cancel))
                }
            },
        )
    }
}

/**
 * The failure text for [cardId], or null when the last failure belongs to a
 * different card. The engine's own message wins when it has one — it names the
 * actual fault, where the resource is only the generic headline.
 */
@Composable
private fun previewErrorFor(preview: EffectsPreviewState, cardId: String): String? {
    if (preview.errorId != cardId) return null
    val fallback = preview.errorRes?.let { stringResource(it) }
    return preview.errorDetail ?: fallback
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectCardItem(
    card: EffectCard,
    isPlaying: Boolean,
    error: String?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
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
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.effects_builtin)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // One chip per block in the chain — the visual separation reads the
            // signal flow (left → right) better than a run-on string.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (card.chain.isEmpty()) {
                    BlockChip(stringResource(R.string.effects_chip_none))
                } else {
                    for (block in card.chain) {
                        BlockChip(blockChipLabel(block))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Play = hear the chain on the default voice (all cards, leftmost);
            // pencil = edit-in-place (custom only); copy = duplicate (all cards);
            // trash = delete (custom only). Built-ins are read-only (re-seeded on
            // catalog bumps), so they get no pencil — duplicate to fork an
            // editable copy.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = if (isPlaying) onStop else onPlay) {
                    Icon(
                        imageVector = if (isPlaying) StopIcon else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) {
                                R.string.effects_stop_card
                            } else {
                                R.string.effects_preview_card
                            },
                            card.name,
                        ),
                    )
                }
                if (!card.isBuiltin) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(
                                R.string.effects_edit_card,
                                card.name,
                            ),
                        )
                    }
                }
                IconButton(onClick = onDuplicate) {
                    Icon(
                        ContentCopyIcon,
                        contentDescription = stringResource(
                            R.string.effects_duplicate_card,
                            card.name,
                        ),
                    )
                }
                if (!card.isBuiltin) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(
                                R.string.effects_delete_card,
                                card.name,
                            ),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
 * Material "stop" glyph — a filled square. Hand-built for the same reason as
 * [ContentCopyIcon]: core has no stop icon and the extended set is multi-MB.
 */
private val StopIcon: ImageVector = ImageVector.Builder(
    name = "Stop",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 6f)
        horizontalLineToRelative(12f)
        verticalLineToRelative(12f)
        horizontalLineTo(6f)
        close()
    }
}.build()

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

/** "+12" / "-12" — the sign is what makes a cut readable as a cut. */
internal fun signed(value: Int): String = "%+d".format(value)

/** Same, for a slider read-out that rounds rather than truncates. */
internal fun signedRounded(value: Float): String = "%+.0f".format(value)

/**
 * One block summarised for its chip: the block's name plus the parameter that
 * defines how it sounds. Deliberately shorter than the editor's full read-out —
 * a chain of five blocks has to fit on a card.
 */
@Composable
private fun blockChipLabel(block: EffectBlock): String = when (block) {
    is EffectBlock.Reverb -> stringResource(R.string.effects_chip_reverb, block.reverberance.toInt())
    is EffectBlock.Echo -> stringResource(R.string.effects_chip_echo)
    is EffectBlock.Overdrive -> stringResource(R.string.effects_chip_overdrive, block.gainDb.toInt())
    is EffectBlock.Pitch -> stringResource(R.string.effects_chip_pitch, signed(block.cents.toInt()))
    is EffectBlock.Tempo -> stringResource(R.string.effects_chip_tempo, block.factor)
    is EffectBlock.Bandpass -> stringResource(
        R.string.effects_chip_bandpass,
        block.lowHz.toInt(),
        block.highHz.toInt(),
    )
    is EffectBlock.Vol -> stringResource(R.string.effects_chip_vol, block.factor)
    is EffectBlock.Treble -> stringResource(R.string.effects_chip_treble, signed(block.db.toInt()))
    is EffectBlock.Bass -> stringResource(R.string.effects_chip_bass, signed(block.db.toInt()))
    is EffectBlock.Mid -> stringResource(
        R.string.effects_chip_mid,
        block.freqHz.toInt(),
        signed(block.gainDb.toInt()),
    )
    is EffectBlock.Lowpass -> stringResource(R.string.effects_chip_lowpass, block.freqHz.toInt())
    is EffectBlock.Highpass -> stringResource(R.string.effects_chip_highpass, block.freqHz.toInt())
    is EffectBlock.Tremolo -> stringResource(R.string.effects_chip_tremolo, block.speedHz)
    is EffectBlock.Flanger -> stringResource(R.string.effects_chip_flanger, block.speedHz)
    is EffectBlock.Chorus -> stringResource(R.string.effects_chip_chorus, block.speedHz)
    is EffectBlock.Phaser -> stringResource(R.string.effects_chip_phaser, block.speedHz)
    is EffectBlock.Compressor -> stringResource(
        R.string.effects_chip_compressor,
        block.thresholdDb.toInt(),
        block.ratio,
    )
    is EffectBlock.Bitcrush -> stringResource(
        R.string.effects_chip_bitcrush,
        block.bits.toInt(),
        block.downsample.toInt(),
    )
    is EffectBlock.RingMod -> stringResource(R.string.effects_chip_ringmod, block.freqHz.toInt())
    is EffectBlock.Monotone -> stringResource(R.string.effects_chip_monotone, block.targetHz.toInt())
}

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
