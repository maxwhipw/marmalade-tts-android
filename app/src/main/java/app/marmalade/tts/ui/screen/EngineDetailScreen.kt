package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.preprocessing.PreprocessingRules
import app.marmalade.tts.ui.onboarding.formatBytes

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EnginesScreen → tap "Engine settings" on a card
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
//          ├── install()              → viewModel.install()
//          └── back arrow             → onBack() (pops back stack)
//
//   Body layout (top → bottom):
//     1. Status card — install state + storage line + (if not installed)
//        an "Install this engine" affordance.
//     2. Text preprocessing — same Switch list that used to live on
//        SettingsScreen, but scoped to this one engine (no per-engine
//        subheadings here).
//     3. About this engine — license summary + description.
// -----------------------------------------------------------------------------

/**
 * Per-engine settings page.
 *
 * Reached from [EnginesScreen] via the "Engine settings" button on each
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
    viewModel: EngineDetailViewModel = hiltViewModel(),
) {
    // Resolve the catalog entry. We accept the route arg even if the engine
    // doesn't exist in the catalog so a stale deep link doesn't crash —
    // the fallback `null` here triggers the not-found body.
    val descriptor: EngineDescriptor? = EngineCatalog.byName(engineName)
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val enabledRules by viewModel.enabledRules.collectAsStateWithLifecycle()
    val isInstalling by viewModel.isInstalling.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(descriptor?.displayName ?: engineName)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
            StatusSection(
                descriptor = descriptor,
                state = installState,
                isInstalling = isInstalling,
                onInstall = { viewModel.install() },
            )

            HorizontalDivider()

            // Preprocessing toggles are valid even when the engine is not
            // installed — the rules are stored per-engine and applied
            // whenever the engine is later loaded. De-emphasise visually
            // so the user understands they're configuring a future state.
            val isInstalled = installState is InstallState.Installed
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

@Composable
private fun StatusSection(
    descriptor: EngineDescriptor,
    state: InstallState,
    isInstalling: Boolean,
    onInstall: () -> Unit,
) {
    DetailSectionHeader("Status")

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.label(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isInstalling || state is InstallState.Downloading || state is InstallState.Extracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatBytes(descriptor.downloadSizeBytes)} download · " +
                    "${formatBytes(descriptor.installedSizeBytes)} installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state is InstallState.Downloading) {
                Spacer(Modifier.height(8.dp))
                val fraction = if (state.totalBytes > 0L) {
                    (state.bytesFetched.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Downloading · ${formatBytes(state.bytesFetched)} / ${formatBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is InstallState.Extracting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Installing · unpacking model files…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is InstallState.Failed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Inline install affordance for the most common detail-page
            // entry-paths: user tapped Engine settings on a NotInstalled
            // card, OR landed via deep link. EnginesScreen still owns the
            // primary install/uninstall surface; this is a convenience.
            val needsInstall = state is InstallState.NotInstalled ||
                state is InstallState.Failed ||
                state is InstallState.Corrupt
            if (needsInstall) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Install this engine to see its settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onInstall,
                    enabled = !isInstalling,
                ) {
                    val label = when (state) {
                        is InstallState.Failed -> "Retry"
                        InstallState.Corrupt -> "Reinstall"
                        else -> "Install"
                    }
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun PreprocessingSection(
    enabled: Set<String>,
    deEmphasise: Boolean,
    onToggle: (rule: String, enabled: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    DetailSectionHeader("Text preprocessing")

    // Hint shown above the rule list when the engine isn't installed yet —
    // explains that the choices persist regardless. We still let the user
    // toggle: their selections are committed immediately and will be in
    // effect the moment the engine ships.
    if (deEmphasise) {
        Text(
            text = "Preferences saved; will take effect once you install this engine.",
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
                        .clickable { onToggle(rule.name, !isOn) }
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
                        onCheckedChange = { onToggle(rule.name, it) },
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
                    Text("Reset to defaults")
                }
            }
        }
    }
}

@Composable
private fun AboutEngineSection(descriptor: EngineDescriptor) {
    DetailSectionHeader("About this engine")

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
            text = "Engine \"$name\" is not in the catalog.",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "It may have been removed in a newer version of the app.",
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
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 4.dp,
        ),
    )
}

/** Human-readable label for the install-state chip / header. */
private fun InstallState.label(): String = when (this) {
    InstallState.NotInstalled -> "Not installed"
    is InstallState.Downloading -> "Downloading"
    InstallState.Extracting -> "Installing"
    InstallState.Installed -> "Installed"
    is InstallState.Failed -> "Failed"
    InstallState.Corrupt -> "Corrupt — needs reinstall"
}
