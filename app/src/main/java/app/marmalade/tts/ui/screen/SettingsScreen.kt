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
//     │              SettingsViewModel.themeMode         (StateFlow<String>)
//     │              SettingsViewModel.keepEngineLoaded  (StateFlow<Boolean>)
//     │              SettingsViewModel.aliasCount        (StateFlow<Int>)
//     │              SettingsViewModel.appMappingCount   (StateFlow<Int>)
//     │
//     └── writes ──► SettingsViewModel.setThemePreset(ThemePreset)
//                    SettingsViewModel.setThemeMode(String)
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
//   Tap on "Per-app voices" row
//     │
//     ▼
//   onNavigateToAppMappings()  ──► AppRoot routes to AppMappingsScreen
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
 *  2. Engine behavior — toggle to keep the engine resident.
 *  3. Voice aliases  — chevron row routing to the alias editor.
 *  4. About          — version string from [BuildConfig].
 *
 * Text preprocessing toggles used to live here. They're per-engine settings
 * and now live on [EngineDetailScreen] — see Engines tab → tap a card →
 * "Engine settings".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAliases: () -> Unit,
    onNavigateToAppMappings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themePreset by viewModel.themePreset.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val keepLoaded by viewModel.keepEngineLoaded.collectAsStateWithLifecycle()
    val aliasCount by viewModel.aliasCount.collectAsStateWithLifecycle()
    val mappingCount by viewModel.appMappingCount.collectAsStateWithLifecycle()

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
                currentPreset = themePreset,
                onPresetSelected = viewModel::setThemePreset,
                currentMode = themeMode,
                onModeSelected = viewModel::setThemeMode,
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

            AppMappingsSection(
                mappingCount = mappingCount,
                onClick = onNavigateToAppMappings,
            )

            HorizontalDivider()

            SystemDefaultSection()

            HorizontalDivider()

            AboutSection()
        }
    }
}

// -- Sections -----------------------------------------------------------------

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
            FilterChip(
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
    // FlowRow would be the right primitive here but it's still in the
    // experimental compose-foundation-layout artifact for our BOM. A
    // simple wrapping Row with arrangement.spacedBy + horizontal padding
    // covers the same use case for 5 short chips on a phone-width screen.
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreset.entries.forEach { preset ->
            FilterChip(
                selected = preset == currentPreset,
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
private fun AppMappingsSection(
    mappingCount: Int,
    onClick: () -> Unit,
) {
    SectionHeader("Per-app voices")

    val subtitle = when (mappingCount) {
        0 -> "No per-app routes configured"
        1 -> "1 app configured"
        else -> "$mappingCount apps configured"
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("Per-app voices") },
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
