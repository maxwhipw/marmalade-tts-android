package app.marmalade.tts.ui.screen

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                title = { Text(if (state.isEdit) "Edit effect" else "New effect") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(onSaved = onBack) }) { Text("Save") }
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
                label = { Text("Name") },
                singleLine = true,
                isError = state.nameError,
                supportingText = if (state.nameError) {
                    { Text("Give the effect a name.", color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Chain",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Blocks run top → bottom: each one processes the audio the " +
                    "block above it produced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.blocks.isEmpty()) {
                Text(
                    text = "No blocks yet — add one below for a dry (unprocessed) effect, " +
                        "or stack several to shape the sound.",
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
                Text("Stop")
            }
        } else {
            Button(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Preview")
            }
        }
        if (preview is EffectPreviewState.Error) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = preview.message,
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
                    text = blockTitle(block),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove block",
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
            label = "Reverberance",
            value = block.reverberance,
            range = 0f..100f,
            valueText = "%.0f".format(block.reverberance),
            onValueChange = { onChange(block.copy(reverberance = it)) },
        )
        is EffectBlock.Echo -> {
            LabeledSlider(
                label = "Delay",
                value = block.delayMs,
                range = 0f..500f,
                valueText = "%.0f ms".format(block.delayMs),
                onValueChange = { onChange(block.copy(delayMs = it)) },
            )
            LabeledSlider(
                label = "Decay",
                value = block.decay,
                range = 0f..1f,
                valueText = "%.2f".format(block.decay),
                onValueChange = { onChange(block.copy(decay = it)) },
            )
            LabeledSlider(
                label = "Input gain",
                value = block.gainIn,
                range = 0f..1f,
                valueText = "%.2f".format(block.gainIn),
                onValueChange = { onChange(block.copy(gainIn = it)) },
            )
            LabeledSlider(
                label = "Output gain",
                value = block.gainOut,
                range = 0f..1f,
                valueText = "%.2f".format(block.gainOut),
                onValueChange = { onChange(block.copy(gainOut = it)) },
            )
        }
        is EffectBlock.Overdrive -> LabeledSlider(
            label = "Gain",
            value = block.gainDb,
            range = 0f..40f,
            valueText = "%.0f dB".format(block.gainDb),
            onValueChange = { onChange(block.copy(gainDb = it)) },
        )
        is EffectBlock.Pitch -> LabeledSlider(
            label = "Pitch",
            value = block.cents,
            range = -1200f..1200f,
            valueText = "${if (block.cents >= 0) "+" else ""}${"%.0f".format(block.cents)} cents",
            onValueChange = { onChange(block.copy(cents = it)) },
        )
        is EffectBlock.Tempo -> LabeledSlider(
            label = "Speed",
            value = block.factor,
            range = 0.5f..2f,
            valueText = "%.2f×".format(block.factor),
            onValueChange = { onChange(block.copy(factor = it)) },
        )
        is EffectBlock.Bandpass -> {
            LabeledSlider(
                label = "Low cut",
                value = block.lowHz,
                range = 50f..4000f,
                valueText = "%.0f Hz".format(block.lowHz),
                onValueChange = { onChange(block.copy(lowHz = it)) },
            )
            LabeledSlider(
                label = "High cut",
                value = block.highHz,
                range = 1000f..8000f,
                valueText = "%.0f Hz".format(block.highHz),
                onValueChange = { onChange(block.copy(highHz = it)) },
            )
        }
        is EffectBlock.Vol -> LabeledSlider(
            label = "Volume",
            value = block.factor,
            range = 0f..3f,
            valueText = "%.2f×".format(block.factor),
            onValueChange = { onChange(block.copy(factor = it)) },
        )
        is EffectBlock.Treble -> LabeledSlider(
            label = "Treble",
            value = block.db,
            range = -20f..20f,
            valueText = "${if (block.db >= 0) "+" else ""}${"%.0f".format(block.db)} dB",
            onValueChange = { onChange(block.copy(db = it)) },
        )
        is EffectBlock.Bass -> LabeledSlider(
            label = "Bass",
            value = block.db,
            range = -20f..20f,
            valueText = "${if (block.db >= 0) "+" else ""}${"%.0f".format(block.db)} dB",
            onValueChange = { onChange(block.copy(db = it)) },
        )
        is EffectBlock.Mid -> {
            LabeledSlider(
                label = "Frequency",
                value = block.freqHz,
                range = 100f..8000f,
                valueText = "%.0f Hz".format(block.freqHz),
                onValueChange = { onChange(block.copy(freqHz = it)) },
            )
            LabeledSlider(
                label = "Gain",
                value = block.gainDb,
                range = -20f..20f,
                valueText = "${if (block.gainDb >= 0) "+" else ""}${"%.0f".format(block.gainDb)} dB",
                onValueChange = { onChange(block.copy(gainDb = it)) },
            )
        }
        is EffectBlock.Lowpass -> LabeledSlider(
            label = "Cutoff",
            value = block.freqHz,
            range = 500f..12000f,
            valueText = "%.0f Hz".format(block.freqHz),
            onValueChange = { onChange(block.copy(freqHz = it)) },
        )
        is EffectBlock.Highpass -> LabeledSlider(
            label = "Cutoff",
            value = block.freqHz,
            range = 20f..2000f,
            valueText = "%.0f Hz".format(block.freqHz),
            onValueChange = { onChange(block.copy(freqHz = it)) },
        )
        is EffectBlock.Tremolo -> {
            LabeledSlider(
                label = "Speed",
                value = block.speedHz,
                range = 0.5f..20f,
                valueText = "%.1f Hz".format(block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = "Depth",
                value = block.depth,
                range = 0f..1f,
                valueText = "%.0f%%".format(block.depth * 100),
                onValueChange = { onChange(block.copy(depth = it)) },
            )
        }
        is EffectBlock.Flanger -> {
            LabeledSlider(
                label = "Speed",
                value = block.speedHz,
                range = 0.1f..5f,
                valueText = "%.2f Hz".format(block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = "Depth",
                value = block.depthMs,
                range = 0f..10f,
                valueText = "%.1f ms".format(block.depthMs),
                onValueChange = { onChange(block.copy(depthMs = it)) },
            )
        }
        is EffectBlock.Chorus -> {
            LabeledSlider(
                label = "Speed",
                value = block.speedHz,
                range = 0.1f..5f,
                valueText = "%.2f Hz".format(block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = "Depth",
                value = block.depthMs,
                range = 0f..10f,
                valueText = "%.1f ms".format(block.depthMs),
                onValueChange = { onChange(block.copy(depthMs = it)) },
            )
        }
        is EffectBlock.Phaser -> {
            LabeledSlider(
                label = "Speed",
                value = block.speedHz,
                range = 0.1f..5f,
                valueText = "%.2f Hz".format(block.speedHz),
                onValueChange = { onChange(block.copy(speedHz = it)) },
            )
            LabeledSlider(
                label = "Resonance",
                value = block.decay,
                range = 0f..0.9f,
                valueText = "%.2f".format(block.decay),
                onValueChange = { onChange(block.copy(decay = it)) },
            )
        }
        is EffectBlock.Compressor -> {
            LabeledSlider(
                label = "Threshold",
                value = block.thresholdDb,
                range = -60f..0f,
                valueText = "%.0f dB".format(block.thresholdDb),
                onValueChange = { onChange(block.copy(thresholdDb = it)) },
            )
            LabeledSlider(
                label = "Ratio",
                value = block.ratio,
                range = 1f..20f,
                valueText = "%.1f:1".format(block.ratio),
                onValueChange = { onChange(block.copy(ratio = it)) },
            )
        }
        is EffectBlock.Bitcrush -> {
            LabeledSlider(
                label = "Bits",
                value = block.bits,
                range = 1f..16f,
                valueText = "%.0f-bit".format(block.bits),
                onValueChange = { onChange(block.copy(bits = it)) },
            )
            LabeledSlider(
                label = "Downsample",
                value = block.downsample,
                range = 1f..32f,
                valueText = "${block.downsample.toInt()}×",
                onValueChange = { onChange(block.copy(downsample = it)) },
            )
        }
        is EffectBlock.RingMod -> {
            LabeledSlider(
                label = "Frequency",
                value = block.freqHz,
                range = 10f..2000f,
                valueText = "%.0f Hz".format(block.freqHz),
                onValueChange = { onChange(block.copy(freqHz = it)) },
            )
            LabeledSlider(
                label = "Mix",
                value = block.mix,
                range = 0f..1f,
                valueText = "%.0f%%".format(block.mix * 100),
                onValueChange = { onChange(block.copy(mix = it)) },
            )
        }
        is EffectBlock.Monotone -> LabeledSlider(
            label = "Target pitch",
            value = block.targetHz,
            range = 50f..400f,
            valueText = "%.0f Hz".format(block.targetHz),
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
        Text("Add block")
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add block") },
            text = {
                // M3 AlertDialog doesn't scroll custom text content on its
                // own; ~20 rows exceed any phone screen, so without this the
                // bottom entries were unreachable. Same fix as AliasScreen's
                // effect picker.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for ((label, factory) in BLOCK_FACTORIES) {
                        Text(
                            text = label,
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
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        )
    }
}

/** Friendly name → default block instance, in a sensible add-menu order. */
private val BLOCK_FACTORIES: List<Pair<String, () -> EffectBlock>> = listOf(
    "Reverb" to { EffectBlock.Reverb(reverberance = 50f) },
    "Echo" to { EffectBlock.Echo(gainIn = 0.6f, gainOut = 0.6f, delayMs = 120f, decay = 0.3f) },
    "Pitch" to { EffectBlock.Pitch(cents = 0f) },
    "Speed (tempo)" to { EffectBlock.Tempo(factor = 1f) },
    "Overdrive" to { EffectBlock.Overdrive(gainDb = 10f) },
    "Band-pass" to { EffectBlock.Bandpass(lowHz = 300f, highHz = 3400f) },
    "Low-pass" to { EffectBlock.Lowpass(freqHz = 3000f) },
    "High-pass" to { EffectBlock.Highpass(freqHz = 300f) },
    "Volume" to { EffectBlock.Vol(factor = 1f) },
    "Bass" to { EffectBlock.Bass(db = 0f) },
    "Mid" to { EffectBlock.Mid(freqHz = 1000f, gainDb = 0f) },
    "Treble" to { EffectBlock.Treble(db = 0f) },
    "Tremolo" to { EffectBlock.Tremolo(speedHz = 5f, depth = 0.5f) },
    "Flanger" to { EffectBlock.Flanger(speedHz = 0.5f, depthMs = 2f) },
    "Chorus" to { EffectBlock.Chorus(speedHz = 0.25f, depthMs = 2f) },
    "Phaser" to { EffectBlock.Phaser(speedHz = 0.5f, decay = 0.4f) },
    "Compressor" to { EffectBlock.Compressor(thresholdDb = -20f, ratio = 4f) },
    "Bitcrush" to { EffectBlock.Bitcrush(bits = 8f, downsample = 4f) },
    "Ring mod" to { EffectBlock.RingMod(freqHz = 60f, mix = 0.6f) },
    "Monotone" to { EffectBlock.Monotone(targetHz = 160f) },
)

private fun blockTitle(block: EffectBlock): String = when (block) {
    is EffectBlock.Reverb -> "Reverb"
    is EffectBlock.Echo -> "Echo"
    is EffectBlock.Overdrive -> "Overdrive"
    is EffectBlock.Pitch -> "Pitch"
    is EffectBlock.Tempo -> "Speed (tempo)"
    is EffectBlock.Bandpass -> "Band-pass"
    is EffectBlock.Vol -> "Volume"
    is EffectBlock.Treble -> "Treble"
    is EffectBlock.Bass -> "Bass"
    is EffectBlock.Mid -> "Mid"
    is EffectBlock.Lowpass -> "Low-pass"
    is EffectBlock.Highpass -> "High-pass"
    is EffectBlock.Tremolo -> "Tremolo"
    is EffectBlock.Flanger -> "Flanger"
    is EffectBlock.Chorus -> "Chorus"
    is EffectBlock.Phaser -> "Phaser"
    is EffectBlock.Compressor -> "Compressor"
    is EffectBlock.Bitcrush -> "Bitcrush"
    is EffectBlock.RingMod -> "Ring mod"
    is EffectBlock.Monotone -> "Monotone"
}
