package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.service.KeepaliveMode
import app.marmalade.tts.ui.MarmaladeFilterChip
import app.marmalade.tts.ui.theme.ThemePreset

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   SettingsScreen (composable)
//     │
//     ├── reads  ◄── SettingsViewModel.themePreset       (StateFlow<ThemePreset>)
//     │              SettingsViewModel.themeMode         (StateFlow<String>)
//     │
//     └── writes ──► SettingsViewModel.setThemePreset(ThemePreset)
//                    SettingsViewModel.setThemeMode(String)
//                                 │
//                                 ▼
//                          SettingsRepository.setX(...)
//                                 │
//                                 ▼
//                          DataStore<Preferences> edit
//
//   The "Keep engine loaded in memory" Switch was removed in v0.1.16 because
//   neither KittenEngine nor KokoroEngine ever read SettingsRepository.
//   keepEngineLoaded — the toggle was a no-op surfaced as a real control.
//   The storage and SettingsRepository accessor stay; the UI will return
//   when the engines actually honour the flag (v0.2).
//
//   Voice aliases used to be a Settings row that routed to AliasScreen.
//   In v0.1.18 Aliases was promoted to its own bottom-nav tab, so the
//   Settings entry was removed (duplication is worse than redundancy
//   for a top-level concept).
//
//   Per-app voices used to be a chevron row here, routing to a dedicated
//   AppMappingsScreen. It moved onto the Aliases tab — a route belongs to the
//   alias it points at, so it's edited there (see AliasScreen's routing strip).
//
//   Text preprocessing lives in EngineDetailScreen, reachable from the
//   Engines tab (Engines → tap card → "Engine settings"). It used to be a
//   global section on this screen and was moved in v0.1.11 — the rules
//   were already per-engine in DataStore.
//
//   This screen sits inside the bottom-nav graph as a sibling of Speak /
//   Voices / Engines — no back arrow (it's a tab destination, not a detail).
// -----------------------------------------------------------------------------

/**
 * Single-page Settings surface — a tab destination on the bottom nav.
 *
 * Sections (separated by HorizontalDivider):
 *  1. Appearance     — mode (system/light/dark) + color preset chips.
 *  2. System default — opens Android's TTS engine picker.
 *  3. About          — version string from [BuildConfig].
 *
 * Text preprocessing toggles used to live here. They're per-engine settings
 * and now live on [EngineDetailScreen] — see Engines tab → tap a card →
 * "Engine settings". Voice aliases used to live here as a chevron row too;
 * in v0.1.18 it became its own bottom-nav tab.
 *
 * The "Engine behavior" section (a single Switch to keep the engine resident
 * between utterances) was removed in v0.1.16: the engines never honoured the
 * flag, so leaving the control wired up was misleading. We'll bring it back
 * when KittenEngine / KokoroEngine actually read the setting (tracked
 * against [SettingsRepository.keepEngineLoaded] for v0.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** Nav callback for Settings → About → Open-source licenses. */
    onNavigateToLicenses: () -> Unit,
    /**
     * Nav callback for the debug-only Benchmark entry. Null in release
     * builds — the row is hidden when this is null. AppRoot passes the
     * actual nav lambda only when `BuildConfig.DEBUG`.
     */
    onNavigateToBenchmark: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themePreset by viewModel.themePreset.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val intraOpThreads by viewModel.intraOpThreads.collectAsStateWithLifecycle()
    val showDeveloperEngines by viewModel.showDeveloperEngines.collectAsStateWithLifecycle()
    val keepaliveMode by viewModel.keepaliveMode.collectAsStateWithLifecycle()

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            // Deliberately no scrollBehavior. A pinned behaviour still swaps in
            // M3's elevated `scrolledContainerColor` the moment content passes
            // under the bar, and no other screen does that — Settings was the
            // only one whose title background changed colour as you scrolled.
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
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
            AppearanceSection(
                currentPreset = themePreset,
                onPresetSelected = viewModel::setThemePreset,
                currentMode = themeMode,
                onModeSelected = viewModel::setThemeMode,
            )

            HorizontalDivider()

            SystemDefaultSection()

            HorizontalDivider()

            PerformanceSection(
                manualThreads = intraOpThreads,
                autoThreads = viewModel.autoIntraOpThreads,
                onThreadsSelected = viewModel::setIntraOpThreads,
            )

            HorizontalDivider()

            KeepaliveSection(
                current = keepaliveMode,
                onSelect = viewModel::setKeepaliveMode,
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

            HorizontalDivider()

            AboutSection(onNavigateToLicenses = onNavigateToLicenses)
        }
    }
}

// -- Sections -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(
    currentPreset: ThemePreset,
    onPresetSelected: (ThemePreset) -> Unit,
    currentMode: String,
    onModeSelected: (String) -> Unit,
) {
    SectionHeader("Appearance")

    // Light / Dark / System override — independent of preset. Users
    // who want "Marmalade always dark" should be able to set it.
    Text(
        text = "Mode",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
            MarmaladeFilterChip(
                selected = key == currentMode,
                onClick = { onModeSelected(key) },
                label = { Text(label) },
            )
        }
    }

    Text(
        text = "Color",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    // FlowRow so the five preset chips wrap on narrow screens / large
    // font scale instead of pushing the last chip off the edge — same
    // primitive PerformanceSection and KeepaliveSection already use.
    FlowRow(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreset.entries.forEach { preset ->
            MarmaladeFilterChip(
                selected = preset == currentPreset,
                onClick = { onPresetSelected(preset) },
                label = { Text(preset.displayName) },
            )
        }
    }
}

@Composable
private fun SystemDefaultSection() {
    SectionHeader("System integration")
    val context = androidx.compose.ui.platform.LocalContext.current
    ListItem(
        modifier = Modifier.clickable {
            app.marmalade.tts.ui.openSystemTtsSettings(context)
        },
        headlineContent = { Text("Set as system TTS engine") },
        supportingContent = {
            Text(
                text = "Opens Android's text-to-speech settings so you can pick Marmalade " +
                    "as the default engine. Required for external apps to route TTS through us.",
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

/**
 * ONNX-Runtime intra-op thread count. Defaults to "Auto" which calls
 * [app.marmalade.tts.perf.CpuClusterDetector] at engine load to size
 * threads to the perf-core cluster. Manual override is exposed because
 * the autodetect has gaps on exotic CPU topologies (some Samsung chips
 * split the prime core off the perf cluster; some MediaTek chips have
 * three-tier hierarchies).
 *
 * The setting is read on next engine load — current synthesis isn't
 * affected. The "Restart engine to apply" line documents this.
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
        text = "Takes effect on your next Speak — the engine reloads " +
            "automatically when you change this.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * P-K — engine keepalive mode selector. Three options:
 *
 *  - Off:        no foreground service; engine reloads from cold each
 *                time the OS reclaims the process.
 *  - Smart:      foreground service runs 10 min after each synth.
 *                Default. Catches the "user is reading repeatedly"
 *                pattern at near-zero long-term cost.
 *  - Persistent: foreground service runs forever. Surfaces the RAM
 *                cost in the helper text so the user understands the
 *                trade. For power users who care about zero-cold-start
 *                system TTS.
 *
 * The RAM numbers in the persistent helper text are per-engine post-load
 * estimates on Pixel 8a (Tensor G3, 6GB) — see
 * `docs/keepalive-ram.md` for the methodology. They're rough; the real
 * cost depends on which voices are loaded and any state buffers
 * allocated by P-V.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepaliveSection(
    current: KeepaliveMode,
    onSelect: (KeepaliveMode) -> Unit,
) {
    SectionHeader("Keep engine loaded")

    val options: List<Pair<KeepaliveMode, String>> = listOf(
        KeepaliveMode.Off to "Off",
        KeepaliveMode.Smart to "Smart (10 min)",
        KeepaliveMode.Persistent to "Always",
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (mode, label) ->
            MarmaladeFilterChip(
                selected = mode == current,
                onClick = { onSelect(mode) },
                label = { Text(label) },
            )
        }
    }
    val helper = when (current) {
        KeepaliveMode.Off ->
            "The engine reloads from cold each time Android reclaims the " +
                "app process (usually within a few minutes of leaving). " +
                "Cheapest on RAM; slowest first-speak."
        KeepaliveMode.Smart ->
            "After each speak, Marmalade stays warm for 10 minutes so the " +
                "next speak is instant. A small notification appears " +
                "during that window. No long-term cost."
        KeepaliveMode.Persistent ->
            "Marmalade stays loaded all the time and shows a permanent " +
                "notification. Trade-off is RAM use: Pocket ≈ 500 MB, " +
                "Kokoro ≈ 150 MB, Kitten Nano ≈ 80 MB. Only the " +
                "engines you actually use stay loaded."
    }
    Text(
        text = helper,
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
 * [SettingsRepository.showDeveloperEngines].
 */
@Composable
private fun DeveloperEnginesSection(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SectionHeader("Advanced")

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
 * `BuildConfig.DEBUG` (gated by AppRoot passing a non-null lambda to
 * [SettingsScreen]). Release builds never render this section.
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

@Composable
private fun AboutSection(onNavigateToLicenses: () -> Unit) {
    SectionHeader("About")

    ListItem(
        headlineContent = { Text("Marmalade TTS") },
        supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )

    ListItem(
        modifier = Modifier.clickable(onClick = onNavigateToLicenses),
        headlineContent = { Text("Open-source licenses") },
        supportingContent = {
            Text("Third-party components, the app's MIT license, and the licenses of downloadable engines.")
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )

    AboutLinkRow(
        label = "Report a bug",
        supporting = "Something broken or mispronounced? Opens a GitHub issue with the details filled in.",
        url = app.marmalade.tts.util.BugReportUrl.build(
            versionName = BuildConfig.VERSION_NAME,
            flavor = BuildConfig.FLAVOR,
            androidVersion = android.os.Build.VERSION.RELEASE,
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        ),
    )

    AboutLinkRow(
        label = "More Marmalade",
        supporting = "Other Marmalade apps on GitHub.",
        url = "https://github.com/maxwhipw",
    )

    // Flavor-specific entries — see src/{play,fdroid}/.../AboutExtras.kt.
    // F-Droid renders a "Support development" link to GitHub Sponsors;
    // Play renders nothing (per docs/release/PAYWALL-PLAN.md the donate
    // link is omitted from Play for first-listing policy safety).
    app.marmalade.tts.ui.screen.AboutExtras()
}

@Composable
internal fun AboutLinkRow(label: String, supporting: String, url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ListItem(
        modifier = Modifier.clickable {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        headlineContent = { Text(label) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

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
