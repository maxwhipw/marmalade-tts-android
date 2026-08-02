package app.marmalade.tts.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectBlock

// -----------------------------------------------------------------------------
//   EffectEditorScreen — full-screen chain builder (a detail route; AppRoot
//   hides the bottom nav bar while it's open).
//
//   reads:  EffectEditorViewModel.state  ──► name field + list of block cards
//   writes: onNameChange / addBlock / updateBlock / removeBlock / moveUp/Down
//   Save (top bar) persists + pops back; Preview synthesizes a sample with the
//   current chain.
//
//   Each block is one OutlinedCard with its own sliders. Reordering is up/down
//   arrows (not drag) — accessible, no extra deps, and a chain is rarely more
//   than a handful of blocks so a full DnD affordance would be overkill.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectEditorScreen(
    onBack: () -> Unit,
    viewModel: EffectEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEdit) {
                                R.string.effects_editor_title_edit
                            } else {
                                R.string.effects_editor_title_new
                            },
                        ),
                    )
                },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.effects_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(onSaved = onBack) }) {
                        Text(stringResource(R.string.effects_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.effects_name)) },
                singleLine = true,
                isError = state.nameError,
                supportingText = if (state.nameError) {
                    {
                        Text(
                            stringResource(R.string.effects_name_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.effects_chain),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.effects_chain_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.blocks.isEmpty()) {
                Text(
                    text = stringResource(R.string.effects_chain_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.blocks.forEachIndexed { index, block ->
                    BlockCard(
                        block = block,
                        index = index,
                        count = state.blocks.size,
                        onChange = { viewModel.updateBlock(index, it) },
                        onRemove = { viewModel.removeBlock(index) },
                        onMoveUp = { viewModel.moveUp(index) },
                        onMoveDown = { viewModel.moveDown(index) },
                    )
                }
            }

            AddBlockButton(onAdd = viewModel::addBlock)

            Spacer(Modifier.height(4.dp))

            PreviewRow(
                preview = state.preview,
                onPreview = viewModel::preview,
                onStop = viewModel::stopPreview,
            )
        }
    }
}

@Composable
private fun PreviewRow(
    preview: EffectPreviewState,
    onPreview: () -> Unit,
    onStop: () -> Unit,
) {
    Column {
        if (preview is EffectPreviewState.Playing) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.effects_stop))
            }
        } else {
            Button(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.effects_preview))
            }
        }
        if (preview is EffectPreviewState.Error) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = preview.detail ?: stringResource(preview.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Per-block card: header (title + reorder + delete) over its param sliders. */
@Composable
private fun BlockCard(
    block: EffectBlock,
    index: Int,
    count: Int,
    onChange: (EffectBlock) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(blockTitleRes(block)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.effects_move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.effects_move_down),
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.effects_remove_block),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            BlockParams(block = block, onChange = onChange)
        }
    }
}

/**
 * The param sliders for one block. Ranges mirror the matching sox effect's
 * sensible span; every slider writes back a `block.copy(...)` so the chain
 * stays immutable data the ViewModel owns.
 */
@Composable
private fun BlockParams(block: EffectBlock, onChange: (EffectBlock) -> Unit) {
    when (block) {
        is EffectBlock.Reverb -> LabeledSlider(
            label = stringResource(R.string.effects_param_reverberance),
            value = block.reverberance,
            range = 0f..100f,
            valueText = stringResource(R.string.effects_value_plain0, block.reverberance),
            onValueChange = { onChange(block.copy(reverberance = it)) },
        )
        is EffectBlock.Echo -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_delay),
                value = block.delayMs,
                range = 0f..500f,
                valueText = stringResource(R.string.effects_value_ms0, block.delayMs),
                onValueChange = { onChange(block.copy(delayMs = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_decay),
                value = block.decay,
                range = 0f..1f,
                valueText = stringResource(R.string.effects_value_plain2, block.decay),
                onValueChange = { onChange(block.copy(decay = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_gain_in),
                value = block.gainIn,
                range = 0f..1f,
                valueText = stringResource(R.string.effects_value_plain2, block.gainIn),
                onValueChange = { onChange(block.copy(gainIn = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_gain_out),
                value = block.gainOut,
                range = 0f..1f,
                valueText = stringResource(R.string.effects_value_plain2, block.gainOut),
                onValueChange = { onChange(block.copy(gainOut = it)) },
            )
        }
        is EffectBlock.Overdrive -> LabeledSlider(
            label = stringResource(R.string.effects_param_gain),
            value = block.gainDb,
            range = 0f..40f,
            valueText = stringResource(R.string.effects_value_db, block.gainDb),
            onValueChange = { onChange(block.copy(gainDb = it)) },
        )
        is EffectBlock.Pitch -> LabeledSlider(
            label = stringResource(R.string.effects_param_pitch),
            value = block.cents,
            range = -1200f..1200f,
            valueText = stringResource(
                R.string.effects_value_cents,
                signedRounded(block.cents),
            ),
            onValueChange = { onChange(block.copy(cents = it)) },
        )
        is EffectBlock.Tempo -> LabeledSlider(
            label = stringResource(R.string.effects_param_speed),
            value = block.factor,
            range = 0.5f..2f,
            valueText = stringResource(R.string.effects_value_multiplier, block.factor),
            onValueChange = { onChange(block.copy(factor = it)) },
        )
        is EffectBlock.Bandpass -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_low_cut),
                value = block.lowHz,
                range = 50f..4000f,
                valueText = stringResource(R.string.effects_value_hz0, block.lowHz),
                onValueChange = { onChange(block.copy(lowHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_high_cut),
                value = block.highHz,
                range = 1000f..8000f,
                valueText = stringResource(R.string.effects_value_hz0, block.highHz),
                onValueChange = { onChange(block.copy(highHz = it)) },
            )
        }
        is EffectBlock.Vol -> LabeledSlider(
            label = stringResource(R.string.effects_param_volume),
            value = block.factor,
            range = 0f..3f,
            valueText = stringResource(R.string.effects_value_multiplier, block.factor),
            onValueChange = { onChange(block.copy(factor = it)) },
        )
        is EffectBlock.Treble -> LabeledSlider(
            label = stringResource(R.string.effects_param_treble),
            value = block.db,
            range = -20f..20f,
            valueText = stringResource(R.string.effects_value_db_signed, signedRounded(block.db)),
            onValueChange = { onChange(block.copy(db = it)) },
        )
        is EffectBlock.Bass -> LabeledSlider(
            label = stringResource(R.string.effects_param_bass),
            value = block.db,
            range = -20f..20f,
            valueText = stringResource(R.string.effects_value_db_signed, signedRounded(block.db)),
            onValueChange = { onChange(block.copy(db = it)) },
        )
        is EffectBlock.Mid -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_frequency),
                value = block.freqHz,
                range = 100f..8000f,
                valueText = stringResource(R.string.effects_value_hz0, block.freqHz),
                onValueChange = { onChange(block.copy(freqHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_gain),
                value = block.gainDb,
                range = -20f..20f,
                valueText = stringResource(
                    R.string.effects_value_db_signed,
                    signedRounded(block.gainDb),
                ),
                onValueChange = { onChange(block.copy(gainDb = it)) },
            )
        }
        is EffectBlock.Lowpass -> LabeledSlider(
            label = stringResource(R.string.effects_param_cutoff),
            value = block.freqHz,
            range = 500f..12000f,
            valueText = stringResource(R.string.effects_value_hz0, block.freqHz),
            onValueChange = { onChange(block.copy(freqHz = it)) },
        )
        is EffectBlock.Highpass -> LabeledSlider(
            label = stringResource(R.string.effects_param_cutoff),
            value = block.freqHz,
            range = 20f..2000f,
            valueText = stringResource(R.string.effects_value_hz0, block.freqHz),
            onValueChange = { onChange(block.copy(freqHz = it)) },
        )
        is EffectBlock.Tremolo -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_speed),
                value = block.speedHz,
                range = 0.5f..20f,
                valueText = stringResource(R.string.effects_value_hz1, block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_depth),
                value = block.depth,
                range = 0f..1f,
                valueText = stringResource(R.string.effects_value_percent, block.depth * 100),
                onValueChange = { onChange(block.copy(depth = it)) },
            )
        }
        is EffectBlock.Flanger -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_speed),
                value = block.speedHz,
                range = 0.1f..5f,
                valueText = stringResource(R.string.effects_value_hz2, block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_depth),
                value = block.depthMs,
                range = 0f..10f,
                valueText = stringResource(R.string.effects_value_ms1, block.depthMs),
                onValueChange = { onChange(block.copy(depthMs = it)) },
            )
        }
        is EffectBlock.Chorus -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_speed),
                value = block.speedHz,
                range = 0.1f..5f,
                valueText = stringResource(R.string.effects_value_hz2, block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_depth),
                value = block.depthMs,
                range = 0f..10f,
                valueText = stringResource(R.string.effects_value_ms1, block.depthMs),
                onValueChange = { onChange(block.copy(depthMs = it)) },
            )
        }
        is EffectBlock.Phaser -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_speed),
                value = block.speedHz,
                range = 0.1f..5f,
                valueText = stringResource(R.string.effects_value_hz2, block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_resonance),
                value = block.decay,
                range = 0f..0.9f,
                valueText = stringResource(R.string.effects_value_plain2, block.decay),
                onValueChange = { onChange(block.copy(decay = it)) },
            )
        }
        is EffectBlock.Compressor -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_threshold),
                value = block.thresholdDb,
                range = -60f..0f,
                valueText = stringResource(R.string.effects_value_db, block.thresholdDb),
                onValueChange = { onChange(block.copy(thresholdDb = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_ratio),
                value = block.ratio,
                range = 1f..20f,
                valueText = stringResource(R.string.effects_value_ratio, block.ratio),
                onValueChange = { onChange(block.copy(ratio = it)) },
            )
        }
        is EffectBlock.Bitcrush -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_bits),
                value = block.bits,
                range = 1f..16f,
                valueText = stringResource(R.string.effects_value_bits, block.bits),
                onValueChange = { onChange(block.copy(bits = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_downsample),
                value = block.downsample,
                range = 1f..32f,
                valueText = stringResource(
                    R.string.effects_value_times,
                    block.downsample.toInt(),
                ),
                onValueChange = { onChange(block.copy(downsample = it)) },
            )
        }
        is EffectBlock.RingMod -> {
            LabeledSlider(
                label = stringResource(R.string.effects_param_frequency),
                value = block.freqHz,
                range = 10f..2000f,
                valueText = stringResource(R.string.effects_value_hz0, block.freqHz),
                onValueChange = { onChange(block.copy(freqHz = it)) },
            )
            LabeledSlider(
                label = stringResource(R.string.effects_param_mix),
                value = block.mix,
                range = 0f..1f,
                valueText = stringResource(R.string.effects_value_percent, block.mix * 100),
                onValueChange = { onChange(block.copy(mix = it)) },
            )
        }
        is EffectBlock.Monotone -> LabeledSlider(
            label = stringResource(R.string.effects_param_target_pitch),
            value = block.targetHz,
            range = 50f..400f,
            valueText = stringResource(R.string.effects_value_hz0, block.targetHz),
            onValueChange = { onChange(block.copy(targetHz = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            // Without this TalkBack reads the raw 0..1 fraction with no name;
            // the label and valueText above are separate nodes it never links.
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
    }
}

/**
 * "Add block" button → a modal picker of the nine block types, each appended
 * with sox-like defaults. A dialog (not a DropdownMenu) because the nine-item
 * list is taller than the space below the button, which made the dropdown flip
 * to the top of the screen; a modal sidesteps anchor positioning entirely.
 */
@Composable
private fun AddBlockButton(onAdd: (EffectBlock) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.effects_add_block))
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.effects_add_block)) },
            text = {
                // M3 AlertDialog doesn't scroll custom text content on its
                // own; ~20 rows exceed any phone screen, so without this the
                // bottom entries were unreachable. Same fix as AliasScreen's
                // effect picker.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for ((labelRes, factory) in BLOCK_FACTORIES) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAdd(factory())
                                    showPicker = false
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.effects_cancel))
                }
            },
        )
    }
}

/** Friendly name → default block instance, in a sensible add-menu order. */
private val BLOCK_FACTORIES: List<Pair<Int, () -> EffectBlock>> = listOf(
    R.string.effects_block_reverb to { EffectBlock.Reverb(reverberance = 50f) },
    R.string.effects_block_echo to {
        EffectBlock.Echo(gainIn = 0.6f, gainOut = 0.6f, delayMs = 120f, decay = 0.3f)
    },
    R.string.effects_block_pitch to { EffectBlock.Pitch(cents = 0f) },
    R.string.effects_block_tempo to { EffectBlock.Tempo(factor = 1f) },
    R.string.effects_block_overdrive to { EffectBlock.Overdrive(gainDb = 10f) },
    R.string.effects_block_bandpass to { EffectBlock.Bandpass(lowHz = 300f, highHz = 3400f) },
    R.string.effects_block_lowpass to { EffectBlock.Lowpass(freqHz = 3000f) },
    R.string.effects_block_highpass to { EffectBlock.Highpass(freqHz = 300f) },
    R.string.effects_block_vol to { EffectBlock.Vol(factor = 1f) },
    R.string.effects_block_bass to { EffectBlock.Bass(db = 0f) },
    R.string.effects_block_mid to { EffectBlock.Mid(freqHz = 1000f, gainDb = 0f) },
    R.string.effects_block_treble to { EffectBlock.Treble(db = 0f) },
    R.string.effects_block_tremolo to { EffectBlock.Tremolo(speedHz = 5f, depth = 0.5f) },
    R.string.effects_block_flanger to { EffectBlock.Flanger(speedHz = 0.5f, depthMs = 2f) },
    R.string.effects_block_chorus to { EffectBlock.Chorus(speedHz = 0.25f, depthMs = 2f) },
    R.string.effects_block_phaser to { EffectBlock.Phaser(speedHz = 0.5f, decay = 0.4f) },
    R.string.effects_block_compressor to {
        EffectBlock.Compressor(thresholdDb = -20f, ratio = 4f)
    },
    R.string.effects_block_bitcrush to { EffectBlock.Bitcrush(bits = 8f, downsample = 4f) },
    R.string.effects_block_ringmod to { EffectBlock.RingMod(freqHz = 60f, mix = 0.6f) },
    R.string.effects_block_monotone to { EffectBlock.Monotone(targetHz = 160f) },
)

@StringRes
private fun blockTitleRes(block: EffectBlock): Int = when (block) {
    is EffectBlock.Reverb -> R.string.effects_block_reverb
    is EffectBlock.Echo -> R.string.effects_block_echo
    is EffectBlock.Overdrive -> R.string.effects_block_overdrive
    is EffectBlock.Pitch -> R.string.effects_block_pitch
    is EffectBlock.Tempo -> R.string.effects_block_tempo
    is EffectBlock.Bandpass -> R.string.effects_block_bandpass
    is EffectBlock.Vol -> R.string.effects_block_vol
    is EffectBlock.Treble -> R.string.effects_block_treble
    is EffectBlock.Bass -> R.string.effects_block_bass
    is EffectBlock.Mid -> R.string.effects_block_mid
    is EffectBlock.Lowpass -> R.string.effects_block_lowpass
    is EffectBlock.Highpass -> R.string.effects_block_highpass
    is EffectBlock.Tremolo -> R.string.effects_block_tremolo
    is EffectBlock.Flanger -> R.string.effects_block_flanger
    is EffectBlock.Chorus -> R.string.effects_block_chorus
    is EffectBlock.Phaser -> R.string.effects_block_phaser
    is EffectBlock.Compressor -> R.string.effects_block_compressor
    is EffectBlock.Bitcrush -> R.string.effects_block_bitcrush
    is EffectBlock.RingMod -> R.string.effects_block_ringmod
    is EffectBlock.Monotone -> R.string.effects_block_monotone
}
