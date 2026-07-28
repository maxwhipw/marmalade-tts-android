package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.ui.MarmaladeFilterChip

// -----------------------------------------------------------------------------
// Advanced settings — a leaf detail screen off Settings (nav bar hidden).
//
// Home of the knobs a first-run user shouldn't meet: ONNX thread count,
// the developer-engines toggle, and (debug builds) the benchmark. Moved
// out of the main Settings page 2026-07-27 per Max — the main page keeps
// the everyday choices (appearance, system TTS, keepalive) and one
// "Advanced" row pointing here.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    /** Null outside debug builds — the Benchmark row is hidden then. */
    onNavigateToBenchmark: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val intraOpThreads by viewModel.intraOpThreads.collectAsStateWithLifecycle()
    val showDeveloperEngines by viewModel.showDeveloperEngines.collectAsStateWithLifecycle()

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Advanced") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            PerformanceSection(
                manualThreads = intraOpThreads,
                autoThreads = viewModel.autoIntraOpThreads,
                onThreadsSelected = viewModel::setIntraOpThreads,
            )

            HorizontalDivider()

            DeveloperEnginesSection(
                checked = showDeveloperEngines,
                onCheckedChange = viewModel::setShowDeveloperEngines,
            )

            if (onNavigateToBenchmark != null) {
                HorizontalDivider()
                BenchmarkSection(onClick = onNavigateToBenchmark)
            }
        }
    }
}

/**
 * ONNX-Runtime intra-op thread count. Defaults to "Auto" which calls
 * [app.marmalade.tts.perf.CpuClusterDetector] at engine load to size
 * threads to the perf-core cluster — deliberately NOT all cores. The
 * efficiency cores run each op slower than the big cores; ONNX's
 * intra-op parallelism splits one op across the pool and waits for the
 * slowest shard, so adding little cores makes every op finish at
 * little-core speed (and fights the UI/audio threads for them).
 * Manual override is exposed because the autodetect has gaps on exotic
 * CPU topologies (some Samsung chips split the prime core off the perf
 * cluster; some MediaTek chips have three-tier hierarchies).
 *
 * The setting is read on next engine load — current synthesis isn't
 * affected. The helper line documents this.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerformanceSection(
    manualThreads: Int?,
    autoThreads: Int,
    onThreadsSelected: (Int?) -> Unit,
) {
    SectionHeader("Performance")

    Text(
        text = "ONNX threads",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
    // Auto + a small ladder of common big-cluster sizes covering Tensor
    // G3 (5), Snapdragon 8 Gen2/Pixel 9 (6), Snapdragon 8 Gen3 (8) and
    // a low fallback for thermal-limited devices.
    val options: List<Pair<Int?, String>> = listOf(
        null to "Auto ($autoThreads)",
        1 to "1",
        2 to "2",
        4 to "4",
        5 to "5",
        6 to "6",
        8 to "8",
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            MarmaladeFilterChip(
                selected = value == manualThreads,
                onClick = { onThreadsSelected(value) },
                label = { Text(label) },
            )
        }
    }
    Text(
        text = "Auto matches the fast CPU cores. More threads is not faster: " +
            "the slow efficiency cores drag every step down to their pace. " +
            "Takes effect on your next Speak — the engine reloads " +
            "automatically when you change this.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Opt-in toggle for the legacy sherpa engines (Kokoro v1.0/v1.1, Kitten
 * Nano/Mini). The direct-ORT engines superseded them; they stay installable
 * for A/B comparison but are hidden by default in release builds. Shown in
 * both build types so an interested user can reveal them — the default value
 * differs (on in debug, off in release), wired through
 * [app.marmalade.tts.data.SettingsRepository.showDeveloperEngines].
 */
@Composable
private fun DeveloperEnginesSection(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SectionHeader("Developer")

    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text("Show developer engines") },
        supportingContent = {
            Text(
                text = "Reveal the older sherpa-onnx engines (Kokoro v1.0/v1.1, " +
                    "Kitten Nano/Mini). The direct-ORT engines replaced them; " +
                    "these are kept for comparison.",
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/**
 * Debug-only entry point to the benchmark surface. Only shown when
 * `BuildConfig.DEBUG` (gated by AppRoot passing a non-null lambda).
 * Release builds never render this section.
 */
@Composable
private fun BenchmarkSection(onClick: () -> Unit) {
    SectionHeader("Debug")

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("Benchmark") },
        supportingContent = {
            Text(
                text = "Measure per-engine synth timings. Pocket shows a phase " +
                    "breakdown; sherpa engines get load + total + realtime ratio.",
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

// Same local copy LicensesScreen keeps — SectionHeader is file-private
// per screen rather than shared, so screens stay self-contained.
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 4.dp,
        ),
    )
}
