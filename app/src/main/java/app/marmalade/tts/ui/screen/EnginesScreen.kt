package app.marmalade.tts.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.ui.onboarding.formatBytes

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   SpeakScreen menu → "Engines" → EnginesScreen(onBack)
//     │
//     ├── reads EngineCatalog.all + per-engine InstallState from
//     │   EnginesViewModel
//     │
//     ├── per row:
//     │     NotInstalled → "Install" button → confirm dialog → vm.install(name)
//     │     Downloading  → progress bar + bytes-so-far label
//     │     Installed    → "Uninstall" button → confirm dialog → vm.uninstall(name)
//     │     Failed       → "Retry" button + error text
//     │
//     └── back arrow → onBack()
// -----------------------------------------------------------------------------

/**
 * Settings → Engines screen.
 *
 * Lists every engine in [EngineCatalog] with a per-row affordance
 * appropriate to its current state. Confirmation dialogs gate destructive
 * actions (install eats network + storage; uninstall deletes files).
 *
 * Why not a generic "settings" container yet: v0.1 has exactly one
 * settings surface (engines). Putting it on a generic SettingsScreen
 * would be an empty wrapper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginesScreen(
    onBack: () -> Unit,
    viewModel: EnginesViewModel = hiltViewModel(),
) {
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val states by viewModel.installStates.collectAsStateWithLifecycle()

    var pendingInstall by remember { mutableStateOf<EngineDescriptor?>(null) }
    var pendingUninstall by remember { mutableStateOf<EngineDescriptor?>(null) }

    // Verify install state once when the screen is composed — covers the
    // case where the user installed engines in onboarding and is now
    // returning to this screen to add another.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Engines") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(items = engines, key = { it.name }) { engine ->
                EngineRow(
                    engine = engine,
                    state = states[engine.name] ?: InstallState.NotInstalled,
                    onInstallRequested = { pendingInstall = engine },
                    onUninstallRequested = { pendingUninstall = engine },
                    onRetry = { viewModel.install(engine.name) },
                )
                HorizontalDivider()
            }
        }
    }

    pendingInstall?.let { engine ->
        InstallConfirmDialog(
            engine = engine,
            onConfirm = {
                viewModel.install(engine.name)
                pendingInstall = null
            },
            onDismiss = { pendingInstall = null },
        )
    }

    pendingUninstall?.let { engine ->
        UninstallConfirmDialog(
            engine = engine,
            onConfirm = {
                viewModel.uninstall(engine.name)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null },
        )
    }
}

@Composable
private fun EngineRow(
    engine: EngineDescriptor,
    state: InstallState,
    onInstallRequested: () -> Unit,
    onUninstallRequested: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = engine.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatBytes(engine.downloadSizeBytes)} · ${engine.licenseSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            ActionAffordance(
                state = state,
                onInstall = onInstallRequested,
                onUninstall = onUninstallRequested,
                onRetry = onRetry,
            )
        }
        // Inline progress strip during install — keeps the row from
        // jumping in height as state transitions. Determinate during
        // download (byte progress is known), indeterminate during
        // extraction (tar.bz2 unpack doesn't expose progress).
        if (state is InstallState.Downloading) {
            Spacer(Modifier.height(8.dp))
            val fraction = if (state.totalBytes > 0L) {
                (state.bytesFetched.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f
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
    }
}

@Composable
private fun ActionAffordance(
    state: InstallState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        InstallState.NotInstalled -> Button(onClick = onInstall) { Text("Install") }
        // A small spinner sits in the action slot during the install — pairs
        // with the wider LinearProgressIndicator strip below the row so the
        // user always has *something* visibly moving while we work.
        is InstallState.Downloading,
        InstallState.Extracting -> CircularProgressIndicator(
            modifier = Modifier.width(24.dp),
            strokeWidth = 2.5.dp,
        )
        InstallState.Installed -> OutlinedButton(onClick = onUninstall) { Text("Uninstall") }
        is InstallState.Failed -> Button(onClick = onRetry) { Text("Retry") }
        InstallState.Corrupt -> Button(onClick = onRetry) { Text("Reinstall") }
    }
}

@Composable
private fun InstallConfirmDialog(
    engine: EngineDescriptor,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install ${engine.displayName}?") },
        text = {
            Column {
                Text(engine.description)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Download: ${formatBytes(engine.downloadSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = engine.licenseSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun UninstallConfirmDialog(
    engine: EngineDescriptor,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uninstall ${engine.displayName}?") },
        text = {
            Text(
                "This will delete the engine's files from your device. " +
                    "You can reinstall any time.",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Uninstall") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

