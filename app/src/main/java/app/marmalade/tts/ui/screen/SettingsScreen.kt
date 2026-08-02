package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.R
import app.marmalade.tts.service.KeepaliveMode
import app.marmalade.tts.ui.MarmaladeFilterChip
import app.marmalade.tts.ui.MarmaladeIcons
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
    /** Nav callback for the Advanced leaf screen (threads, developer, benchmark). */
    onNavigateToAdvanced: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themePreset by viewModel.themePreset.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
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
                title = { Text(stringResource(R.string.settings_title)) },
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

            KeepaliveSection(
                current = keepaliveMode,
                onSelect = viewModel::setKeepaliveMode,
            )

            HorizontalDivider()

            AdvancedRow(onClick = onNavigateToAdvanced)

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
    SectionHeader(stringResource(R.string.settings_appearance))

    // Light / Dark / System override — independent of preset. Users
    // who want "Marmalade always dark" should be able to set it.
    Text(
        text = stringResource(R.string.settings_mode),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val modes = listOf(
            "system" to stringResource(R.string.settings_mode_system),
            "light" to stringResource(R.string.settings_mode_light),
            "dark" to stringResource(R.string.settings_mode_dark),
        )
        modes.forEach { (key, label) ->
            MarmaladeFilterChip(
                selected = key == currentMode,
                onClick = { onModeSelected(key) },
                label = { Text(label) },
            )
        }
    }

    Text(
        text = stringResource(R.string.settings_color),
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
    SectionHeader(stringResource(R.string.settings_system_integration))
    val context = androidx.compose.ui.platform.LocalContext.current
    ListItem(
        modifier = Modifier.clickable {
            app.marmalade.tts.ui.openSystemTtsSettings(context)
        },
        headlineContent = { Text(stringResource(R.string.settings_set_system_engine)) },
        supportingContent = {
            Text(text = stringResource(R.string.settings_set_system_engine_desc))
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
    SectionHeader(stringResource(R.string.settings_keepalive))

    val options: List<Pair<KeepaliveMode, String>> = listOf(
        KeepaliveMode.Off to stringResource(R.string.settings_keepalive_off),
        KeepaliveMode.Smart to stringResource(R.string.settings_keepalive_smart),
        KeepaliveMode.Persistent to stringResource(R.string.settings_keepalive_always),
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
    val helper = stringResource(
        when (current) {
            KeepaliveMode.Off -> R.string.settings_keepalive_off_desc
            KeepaliveMode.Smart -> R.string.settings_keepalive_smart_desc
            KeepaliveMode.Persistent -> R.string.settings_keepalive_persistent_desc
        },
    )
    Text(
        text = helper,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** One chevron row to the Advanced leaf screen (threads, developer, benchmark). */
@Composable
private fun AdvancedRow(onClick: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_advanced))

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(R.string.settings_advanced_row)) },
        supportingContent = {
            Text(stringResource(R.string.settings_advanced_row_desc))
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
    SectionHeader(stringResource(R.string.settings_about))

    AboutRow(
        label = stringResource(R.string.app_name),
        supporting = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
        leading = {
            Icon(imageVector = Icons.Filled.Info, contentDescription = null)
        },
        showChevron = false,
    )

    AboutRow(
        label = stringResource(R.string.settings_licenses),
        supporting = stringResource(R.string.settings_licenses_desc),
        leading = {
            Icon(imageVector = MarmaladeIcons.LicenseDoc, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onNavigateToLicenses),
    )

    AboutLinkRow(
        label = stringResource(R.string.settings_report_bug),
        supporting = stringResource(R.string.settings_report_bug_desc),
        url = app.marmalade.tts.util.BugReportUrl.build(
            versionName = BuildConfig.VERSION_NAME,
            flavor = BuildConfig.FLAVOR,
            androidVersion = android.os.Build.VERSION.RELEASE,
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        ),
        leading = {
            Icon(imageVector = MarmaladeIcons.Bug, contentDescription = null)
        },
    )

    AboutLinkRow(
        label = stringResource(R.string.settings_more_marmalade),
        supporting = stringResource(R.string.settings_more_marmalade_desc),
        url = "https://github.com/maxwhipw",
        leading = {
            Text(text = "\uD83C\uDF4A", style = MaterialTheme.typography.titleMedium)
        },
    )

    // Flavor-specific entries — see src/{play,fdroid}/.../AboutExtras.kt.
    // F-Droid renders a "Support development" link to GitHub Sponsors;
    // Play renders nothing (per docs/release/PAYWALL-PLAN.md the donate
    // link is omitted from Play for first-listing policy safety).
    app.marmalade.tts.ui.screen.AboutExtras()
}

@Composable
internal fun AboutLinkRow(
    label: String,
    supporting: String,
    url: String,
    leading: (@Composable () -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AboutRow(
        label = label,
        supporting = supporting,
        leading = leading,
        modifier = Modifier.clickable {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    )
}

/**
 * About-section row drawn as a plain centered [Row] rather than M3's
 * [ListItem]: with two supporting lines ListItem becomes "three-line"
 * and pins leadingContent to the top, which reads as misaligned next
 * to rows whose icon floats mid-height. Center-aligning everything is
 * what the section wants.
 */
@Composable
private fun AboutRow(
    label: String,
    supporting: String,
    leading: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.width(40.dp),
                contentAlignment = Alignment.CenterStart,
            ) { leading() }
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .semantics { heading() }
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 4.dp,
            ),
    )
}
