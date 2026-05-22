package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.ui.theme.ThemePreset

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   SettingsScreen (composable)
//     │
//     ├── reads  ◄── SettingsViewModel.themePreset       (StateFlow<ThemePreset>)
//     │              SettingsViewModel.keepEngineLoaded  (StateFlow<Boolean>)
//     │              SettingsViewModel.aliasCount        (StateFlow<Int>)
//     │
//     └── writes ──► SettingsViewModel.setThemePreset(ThemePreset)
//                    SettingsViewModel.setKeepEngineLoaded(Boolean)
//                                 │
//                                 ▼
//                          SettingsRepository.setX(...)
//                                 │
//                                 ▼
//                          DataStore<Preferences> edit
//
//   Tap on "Voice aliases / personas" row
//     │
//     ▼
//   onNavigateToAliases()  ──► AppRoot routes to AliasScreen
//
//   This screen sits inside the bottom-nav graph as a sibling of Speak /
//   Voices / Engines — no back arrow (it's a tab destination, not a detail).
// -----------------------------------------------------------------------------

/**
 * Single-page Settings surface — a tab destination on the bottom nav.
 *
 * Sections (separated by HorizontalDivider):
 *  1. Appearance   — 5 chips to pick a [ThemePreset].
 *  2. Engine       — toggle to keep the engine resident.
 *  3. Aliases      — chevron row routing to the existing alias editor.
 *  4. About        — version string from [BuildConfig].
 *
 * The Light/Dark/System override is NOT exposed in v0.1 — system dark mode
 * follows the OS. A follow-up agent can wire a dropdown using
 * [app.marmalade.tts.ui.theme.resolveThemeIsDark] without re-shaping this file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAliases: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themePreset by viewModel.themePreset.collectAsStateWithLifecycle()
    val keepLoaded by viewModel.keepEngineLoaded.collectAsStateWithLifecycle()
    val aliasCount by viewModel.aliasCount.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                scrollBehavior = scrollBehavior,
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
                current = themePreset,
                onPresetSelected = viewModel::setThemePreset,
            )

            HorizontalDivider()

            EngineBehaviorSection(
                keepLoaded = keepLoaded,
                onKeepLoadedChange = viewModel::setKeepEngineLoaded,
            )

            HorizontalDivider()

            AliasesSection(
                aliasCount = aliasCount,
                onClick = onNavigateToAliases,
            )

            HorizontalDivider()

            AboutSection()
        }
    }
}

// -- Sections -----------------------------------------------------------------

@Composable
private fun AppearanceSection(
    current: ThemePreset,
    onPresetSelected: (ThemePreset) -> Unit,
) {
    SectionHeader("Appearance")

    // FlowRow would be the right primitive here but it's still in the
    // experimental compose-foundation-layout artifact for our BOM. A
    // simple wrapping Row with arrangement.spacedBy + horizontal padding
    // covers the same use case for 5 short chips on a phone-width screen.
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreset.entries.forEach { preset ->
            FilterChip(
                selected = preset == current,
                onClick = { onPresetSelected(preset) },
                label = { Text(preset.displayName) },
            )
        }
    }
}

@Composable
private fun EngineBehaviorSection(
    keepLoaded: Boolean,
    onKeepLoadedChange: (Boolean) -> Unit,
) {
    SectionHeader("Engine behavior")

    // TODO(v0.2): wire `keepLoaded = false` to release KittenEngine between
    // utterances (currently the storage exists but the engine ignores it —
    // tracked in STUBS.md).
    ListItem(
        modifier = Modifier.clickable { onKeepLoadedChange(!keepLoaded) },
        headlineContent = { Text("Keep engine loaded in memory") },
        supportingContent = {
            Text(
                "Faster speak; uses ~40 MB of RAM when idle. " +
                    "Turn off to release the model between utterances.",
            )
        },
        trailingContent = {
            Switch(
                checked = keepLoaded,
                onCheckedChange = onKeepLoadedChange,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun AliasesSection(
    aliasCount: Int,
    onClick: () -> Unit,
) {
    SectionHeader("Voice aliases")

    val subtitle = when (aliasCount) {
        0 -> "No aliases saved yet"
        1 -> "1 alias saved"
        else -> "$aliasCount aliases saved"
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("Voice aliases / personas") },
        supportingContent = { Text(subtitle) },
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
private fun AboutSection() {
    SectionHeader("About")

    ListItem(
        headlineContent = { Text("marmalade-tts") },
        supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
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
