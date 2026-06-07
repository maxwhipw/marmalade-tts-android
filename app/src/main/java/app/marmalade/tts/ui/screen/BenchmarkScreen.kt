package app.marmalade.tts.ui.screen

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.engine.EnginePhaseTimings
import app.marmalade.tts.perf.SystemStats
import app.marmalade.tts.perf.SystemStatsSnapshot
import app.marmalade.tts.ui.MarmaladeFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   BenchmarkScreen
//     │
//     ├── reads  ◄── BenchmarkViewModel.state (BenchmarkState)
//     │              .text                — current input string
//     │              .selectedEngines     — engineNames opted in for this run
//     │              .results             — per-engine outcomes
//     │              .running / .currentlyRunning
//     │
//     └── writes ──► setText / toggleEngine / runBenchmark()
//                                 │
//                                 ▼
//                          For each selected engine:
//                            engine.synthesizeWithTimings(text, defaultVoiceId, 1.0f)
//                              ──► EnginePhaseTimings appended to results
//
//   No audio playback. The purpose is timing, and AudioTrack init +
//   buffer-drain latency would confound the numbers.
// -----------------------------------------------------------------------------

/** Debug-only benchmark surface — reachable from Settings → Benchmark (debug builds). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    onBack: () -> Unit,
    viewModel: BenchmarkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Refresh installed-engine selection every time the screen opens —
    // the user may have just installed/uninstalled via the Engines tab.
    LaunchedEffect(Unit) { viewModel.refreshInstalled() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Benchmark") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InputSection(
                text = state.text,
                onTextChange = viewModel::setText,
            )

            EngineSelectorSection(
                profiles = viewModel.engineProfiles,
                selectedEngines = state.selectedEngines,
                onToggle = viewModel::toggleEngine,
            )

            SystemStatsBar()

            Button(
                onClick = viewModel::runBenchmark,
                enabled = !state.running && state.text.isNotBlank() &&
                    state.selectedEngines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Running ${state.currentlyRunning ?: "..."}")
                } else {
                    Text("Run benchmark")
                }
            }

            if (state.results.isNotEmpty()) {
                ResultsSection(results = state.results)
            }
        }
    }
}

/**
 * Live device-load overlay. Polls [SystemStats] every ~1.5 s while the
 * benchmark screen is open. The headline figures are free RAM + zram-in-use:
 * a memory-bandwidth-bound AR loop tanks once the phone starts swapping
 * weights into zram, so a "slow" benchmark is usually a loaded phone, not
 * the engine. CPU busy% shows contention from other apps; thermal is mostly
 * a sanity check (the Tensor G3 rarely throttles at these temps).
 */
@Composable
private fun SystemStatsBar() {
    val context = LocalContext.current
    var snap by remember { mutableStateOf<SystemStatsSnapshot?>(null) }
    LaunchedEffect(Unit) {
        var prev: SystemStats.CpuCounters? = null
        while (true) {
            val (s, counters) = withContext(Dispatchers.IO) { SystemStats.sample(context, prev) }
            prev = counters
            snap = s
            delay(1500)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Device load (live)", style = MaterialTheme.typography.labelLarge)
            val s = snap
            if (s == null) {
                Text("sampling…", style = MaterialTheme.typography.bodySmall)
            } else {
                // Memory is the figure that usually explains a slow run; flag it
                // in the error colour when free RAM is low / zram is heavily used.
                val pressured = s.ramAvailMb in 1..800 || s.zramUsedMb > 500
                Text(
                    text = "RAM free ${s.ramAvailMb} / ${s.ramTotalMb} MB" +
                        "   ·   zram ${s.zramUsedMb} / ${s.zramTotalMb} MB used",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pressured) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text("CPU ${s.cpuBusyPct}% busy", style = MaterialTheme.typography.bodySmall)
                if (s.cores.isNotEmpty()) {
                    Text(
                        text = s.cores.joinToString(" ") { "c${it.core}:${it.busyPct}".padEnd(6) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                val temps = if (s.clusterTemps.isNotEmpty()) {
                    "  ·  " + s.clusterTemps.joinToString(" ") {
                        "${it.first}=${"%.0f".format(it.second)}°"
                    }
                } else {
                    ""
                }
                val headroom = s.thermalHeadroom?.let { " (headroom ${"%.2f".format(it)})" } ?: ""
                Text(
                    text = "thermal ${s.thermalStatus}$headroom$temps",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InputSection(
    text: String,
    onTextChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Input",
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Text to synthesize") },
            minLines = 2,
            maxLines = 5,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetChip(label = "Short", onClick = { onTextChange(DEFAULT_TEXT_SHORT) })
            PresetChip(label = "Medium", onClick = { onTextChange(DEFAULT_TEXT_MEDIUM) })
            PresetChip(label = "Long", onClick = { onTextChange(DEFAULT_TEXT_LONG) })
        }
    }
}

@Composable
private fun PresetChip(label: String, onClick: () -> Unit) {
    MarmaladeFilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun EngineSelectorSection(
    profiles: List<EngineProfile>,
    selectedEngines: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Engines",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = "Uninstalled engines are skipped automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (p in profiles) {
                val installed = p.engine.isInstalled()
                MarmaladeFilterChip(
                    selected = p.engineName in selectedEngines && installed,
                    onClick = { if (installed) onToggle(p.engineName) },
                    enabled = installed,
                    label = {
                        val suffix = if (installed) "" else " (not installed)"
                        Text("${p.displayName}$suffix")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ResultsSection(results: List<BenchmarkResult>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Results",
            style = MaterialTheme.typography.labelLarge,
        )
        for (r in results) {
            ResultCard(result = r)
        }
    }
}

@Composable
private fun ResultCard(result: BenchmarkResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.engineDisplayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(8.dp))
                Box(modifier = Modifier.weight(1f))
                if (result.error == null) {
                    RealtimeRatioBadge(result.realtimeRatio)
                }
            }

            if (result.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "FAILED: ${result.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            // For streaming runs, show TTFA prominently — it's the
            // headline metric that streaming is meant to improve.
            if (result.timeToFirstAudioMs != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "First audio: ${result.timeToFirstAudioMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                    if (result.chunkCount != null && result.chunkCount > 0) {
                        Text(
                            text = "(${result.chunkCount} chunk${if (result.chunkCount == 1) "" else "s"})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TimingsTable(result.timings, audioSeconds = result.audioSeconds)
        }
    }
}

@Composable
private fun RealtimeRatioBadge(ratio: Double) {
    val color = when {
        ratio >= 1.5 -> Color(0xFF2E7D32) // green — comfortably faster than realtime
        ratio >= 1.0 -> Color(0xFFEF6C00) // amber — at-realtime
        else -> Color(0xFFC62828) // red — slower than realtime
    }
    Text(
        text = "%.2fx realtime".format(ratio),
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun TimingsTable(timings: EnginePhaseTimings, audioSeconds: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (timings.loadMs > 0) {
            TimingRow("model load", "${timings.loadMs} ms")
        }
        // Header row showing the wall-clock total + audio duration.
        TimingRow(
            label = "total",
            value = "${timings.totalMs} ms",
            detail = "→ %.2f s audio".format(audioSeconds),
            bold = true,
        )
        if (timings.phases.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            for (p in timings.phases) {
                TimingRow(label = p.name, value = "${p.ms} ms", detail = p.detail)
            }
        }
    }
}

@Composable
private fun TimingRow(
    label: String,
    value: String,
    detail: String? = null,
    bold: Boolean = false,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = if (bold) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (bold) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
            )
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
        }
    }
}
