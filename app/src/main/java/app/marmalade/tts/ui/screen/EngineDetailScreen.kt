package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.preprocessing.PreprocessingRules

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EnginesScreen → tap "Configure" on a card
//     │
//     ▼
//   navController.navigate("engine/<name>") → AppRoot's composable("engine/{name}")
//     │
//     ▼
//   EngineDetailScreen(engineName, onBack)
//     │
//     ├── EngineDetailViewModel (hilt) reads engineName from SavedStateHandle
//     │
//     ├── reads installState   ◄── EngineDetailViewModel.installState
//     ├── reads enabledRules   ◄── EngineDetailViewModel.enabledRules
//     │
//     ├── descriptor           ◄── EngineCatalog.byName(engineName)  (static)
//     │
//     └── actions
//          ├── toggleRule(name, on)   → viewModel.toggleRule(name, on)
//          ├── resetRules()           → viewModel.resetRules()
//          └── back arrow             → onBack() (pops back stack)
//
//   Body layout (top → bottom):
//     1. Browse voices — opens the engine-scoped voice picker.
//     2. Text preprocessing — same Switch list that used to live on
//        SettingsScreen, but scoped to this one engine (no per-engine
//        subheadings here).
//     3. About this engine — license summary + description.
// -----------------------------------------------------------------------------

/**
 * Per-engine settings page.
 *
 * Reached from [EnginesScreen] via the "Configure" button on each
 * card. Shows install status (live, mirrored from
 * [app.marmalade.tts.install.EngineInstaller]) and the per-engine text
 * preprocessing rule toggles — preferences round-trip via
 * [app.marmalade.tts.data.SettingsRepository] regardless of install state,
 * so the user can pre-configure an engine before installing it (the
 * section is visually de-emphasised in that case).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineDetailScreen(
    engineName: String,
    onBack: () -> Unit,
    /** Opens the voice picker scoped to this engine's voices. */
    onShowVoices: () -> Unit,
    viewModel: EngineDetailViewModel = hiltViewModel(),
) {
    // Resolve the catalog entry. We accept the route arg even if the engine
    // doesn't exist in the catalog so a stale deep link doesn't crash —
    // the fallback `null` here triggers the not-found body.
    val descriptor: EngineDescriptor? = EngineCatalog.byName(engineName)
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val enabledRules by viewModel.enabledRules.collectAsStateWithLifecycle()

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(descriptor?.displayName ?: engineName)
                },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.engines_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (descriptor == null) {
            UnknownEngineBody(name = engineName, padding = padding)
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // No status card here: this screen is only reachable from an
            // installed (or update-available) engine's Configure button, so a
            // "Installed · 61.2 MB download" panel just repeated the card the
            // user tapped to get here.
            val isInstalled = installState is InstallState.Installed

            VoicesSection(
                enabled = isInstalled,
                onShowVoices = onShowVoices,
            )

            HorizontalDivider()

            // Preprocessing toggles are valid even when the engine is not
            // installed — the rules are stored per-engine and applied
            // whenever the engine is later loaded. De-emphasise visually
            // so the user understands they're configuring a future state.
            PreprocessingSection(
                enabled = enabledRules,
                deEmphasise = !isInstalled,
                onToggle = viewModel::toggleRule,
                onReset = viewModel::resetRules,
            )

            HorizontalDivider()

            AboutEngineSection(descriptor)

            // Tail spacer so the last section doesn't crash into the system
            // gesture bar on devices with edge-to-edge handling.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Entry into the voice picker scoped to this engine. De-emphasised (and
 * inert) until the engine is installed — the picker filters to installed
 * engines, so opening it early would just show an empty list.
 */
@Composable
private fun VoicesSection(
    enabled: Boolean,
    onShowVoices: () -> Unit,
) {
    DetailSectionHeader(stringResource(R.string.engines_voices))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onShowVoices)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.engines_browse_voices),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = stringResource(
                    if (enabled) {
                        R.string.engines_browse_voices_enabled
                    } else {
                        R.string.engines_browse_voices_disabled
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreprocessingSection(
    enabled: Set<String>,
    deEmphasise: Boolean,
    onToggle: (rule: String, enabled: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    DetailSectionHeader(stringResource(R.string.engines_preprocessing))

    // Hint shown above the rule list when the engine isn't installed yet —
    // explains that the choices persist regardless. We still let the user
    // toggle: their selections are committed immediately and will be in
    // effect the moment the engine ships.
    if (deEmphasise) {
        Text(
            text = stringResource(R.string.engines_preprocessing_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    // Wrap the rule rows in a CompositionLocalProvider that nudges the
    // default content tint when de-emphasised — that's the same idiom the
    // Material 3 OutlinedTextField uses for its disabled-state cues.
    val contentAlpha = if (deEmphasise) 0.6f else 1.0f
    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = contentAlpha)) {
        Column {
            for (rule in PreprocessingRules.ALL) {
                val isOn = rule.name in enabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isOn,
                            role = Role.Switch,
                            onValueChange = { onToggle(rule.name, it) },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = rule.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isOn,
                        onCheckedChange = null,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.engines_reset_defaults))
                }
            }
        }
    }
}

@Composable
private fun AboutEngineSection(descriptor: EngineDescriptor) {
    DetailSectionHeader(stringResource(R.string.engines_about))

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = descriptor.description,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = descriptor.licenseSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnknownEngineBody(name: String, padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.engines_unknown_title, name),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.engines_unknown_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailSectionHeader(label: String) {
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

