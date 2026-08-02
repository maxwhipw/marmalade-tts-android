package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.ui.onboarding.formatBytes

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Bottom-nav "Engines" tab → EnginesScreen(onEngineSettings)
//     │
//     ├── reads EngineCatalog.all + per-engine InstallState from
//     │   EnginesViewModel.
//     │
//     ├── per card:
//     │     NotInstalled → "Install" button → confirm dialog → vm.install(name)
//     │     Downloading  → CircularProgressIndicator + linear progress strip
//     │     Extracting   → CircularProgressIndicator + indeterminate strip
//     │     Installed    → "Uninstall" (outlined) + "Engine settings" (filled)
//     │                    confirm dialog before uninstall;
//     │                    Engine settings → onEngineSettings(engine) →
//     │                                       AppRoot navigates to
//     │                                       engine/<name>
//     │     Failed       → "Retry" + reason text below
//     │     Corrupt      → "Reinstall"
//     │
//
//   Cards are OutlinedCard for a quieter look that pairs with the orange
//   primary used by the install / engine-settings buttons. ElevatedCard
//   would compete visually with the buttons; outlined keeps the buttons as
//   the focal point of each card.
// -----------------------------------------------------------------------------

/**
 * Engines tab.
 *
 * One card per engine in [app.marmalade.tts.install.EngineCatalog]. Each
 * card exposes its own install/uninstall lifecycle plus a route into
 * [EngineDetailScreen] for per-engine settings (preprocessing rules etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginesScreen(
    onEngineSettings: (EngineDescriptor) -> Unit,
    /** Opens the Cloud API engine's configure surface (provider keys). */
    onConfigureCloud: () -> Unit,
    /** Opens the voice picker scoped to the Cloud API engine's voices. */
    onShowCloudVoices: () -> Unit,
    viewModel: EnginesViewModel = hiltViewModel(),
) {
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val states by viewModel.installStates.collectAsStateWithLifecycle()
    val cloudKeySet by viewModel.cloudApiKeySet.collectAsStateWithLifecycle()

    var pendingInstall by remember { mutableStateOf<EngineDescriptor?>(null) }
    var pendingUninstall by remember { mutableStateOf<EngineDescriptor?>(null) }

    // Verify install state once when the screen is composed — covers the
    // case where the user installed engines in onboarding and is now
    // returning to this screen to add another.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        // AppRoot's outer Scaffold owns status-bar insets; opt this inner
        // Scaffold + its TopAppBar out so the bar doesn't double-pad itself.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                // Top-level tab — no back arrow; the bottom bar is the way out.
                title = { Text("Engines") },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = engines, key = { it.name }) { engine ->
                EngineCard(
                    engine = engine,
                    state = states[engine.name] ?: InstallState.NotInstalled,
                    onInstallRequested = { pendingInstall = engine },
                    onUninstallRequested = { pendingUninstall = engine },
                    onRetry = { viewModel.install(engine.name) },
                    onEngineSettings = { onEngineSettings(engine) },
                )
            }

            // The Cloud API engine has no bundle to install — a configured
            // API key is what "installs" it — so it gets its own card with
            // a Configure action where the local engines have Install.
            item(key = "cloud-api") {
                CloudApiCard(
                    keySet = cloudKeySet,
                    onConfigure = onConfigureCloud,
                    onShowVoices = onShowCloudVoices,
                )
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
private fun EngineCard(
    engine: EngineDescriptor,
    state: InstallState,
    onInstallRequested: () -> Unit,
    onUninstallRequested: () -> Unit,
    onRetry: () -> Unit,
    onEngineSettings: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: display name + a status chip on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = engine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // The "Developer" tag occupies the slot the install-state chip
                // used to — the install/uninstall/update buttons below already
                // communicate download state, so the status chip was redundant.
                // Only the legacy sherpa engines carry a tag; production engines
                // leave the slot empty.
                if (engine.developerOnly) {
                    DeveloperBadge()
                }
            }

            Spacer(Modifier.height(8.dp))

            // Collapsed resting state stays light: a 2-line description plus
            // the download sizes (which inform the install decision). "Show
            // more" reveals the full description and the license details —
            // the license legalese is too verbose to sit in the resting card.
            var expanded by remember(engine.name) { mutableStateOf(false) }
            Text(
                text = engine.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            )
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { expanded = !expanded },
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "${formatBytes(engine.downloadSizeBytes)} download · " +
                    "${formatBytes(engine.installedSizeBytes)} installed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // License details live behind "Show more" — present for the
            // install-consent UX (esp. GPL disclosure) without cluttering
            // the resting card.
            if (expanded) {
                Text(
                    text = engine.licenseSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // In-progress strip + label. Kept identical to the v0.1.10
            // implementation — determinate while we know the byte count,
            // indeterminate during the tar.bz2 unpack.
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
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state is InstallState.Extracting) {
                Spacer(Modifier.height(8.dp))
                val fraction = if (state.totalBytes > 0L) {
                    (state.bytesExtracted.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Installing · ${formatBytes(state.bytesExtracted)} / ${formatBytes(state.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state is InstallState.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Spacer(Modifier.height(12.dp))

            // Action row. Right-aligned by default; Installed state shows
            // two buttons with an Uninstall (outlined, destructive-ish)
            // on the left and Engine settings (filled, primary action)
            // on the right.
            ActionRow(
                state = state,
                onInstall = onInstallRequested,
                onUninstall = onUninstallRequested,
                onRetry = onRetry,
                onEngineSettings = onEngineSettings,
            )
        }
    }
}

/**
 * Compact "Developer" tag shown on legacy sherpa engine cards. Tertiary
 * color so it's visually distinct from the primary-colored install state
 * chip — a quiet "this isn't a normal engine" marker.
 */
@Composable
private fun DeveloperBadge() {
    AssistChip(
        onClick = { /* informational only */ },
        enabled = false,
        label = { Text("Developer") },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.tertiary,
        ),
    )
}

@Composable
private fun ActionRow(
    state: InstallState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onRetry: () -> Unit,
    onEngineSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            InstallState.NotInstalled -> {
                Button(onClick = onInstall) { Text("Install") }
            }
            is InstallState.Downloading, is InstallState.Extracting -> {
                // No buttons during install — the action area is
                // intentionally just a small spinner. Progress + label
                // are shown above in the card body.
                Box(
                    modifier = Modifier.height(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        // .size() — see EngineDetailScreen for the same
                        // fix. .height() alone leaves the spinner width
                        // unconstrained and it draws ~2x larger than the
                        // declared height. Bug Max reported in v0.1.19.
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }
            InstallState.Installed -> {
                OutlinedButton(onClick = onUninstall) { Text("Uninstall") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onEngineSettings) { Text("Engine settings") }
            }
            is InstallState.Failed -> {
                Button(onClick = onRetry) { Text("Retry") }
            }
            InstallState.Corrupt -> {
                Button(onClick = onRetry) { Text("Reinstall") }
            }
            is InstallState.Outdated -> {
                // The current install still works; the user can keep using
                // it while the new bundle downloads. We surface Update as
                // the primary action and leave Engine settings reachable
                // so they don't lose access to alias management.
                OutlinedButton(onClick = onEngineSettings) { Text("Engine settings") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onInstall) { Text("Update") }
            }
        }
    }
}

/**
 * Card for the Cloud API engine — visually a sibling of the local
 * [EngineCard]s, but its lifecycle is "configure a key", not
 * "download a bundle". Voices synthesize on the provider's servers
 * (Venice.ai today), so the card is explicit that text leaves the
 * device and that nothing is downloaded.
 */
@Composable
private fun CloudApiCard(
    keySet: Boolean,
    onConfigure: () -> Unit,
    onShowVoices: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Cloud voices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            var expanded by remember { mutableStateOf(false) }
            Text(
                text = "Hosted voices over the network — nothing to download, " +
                    "but each request sends your text to the provider you " +
                    "configure (Venice, OpenAI, …) and needs that provider's " +
                    "API key. Fast to start speaking; the provider's full " +
                    "voice lineup at your fingertips.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            )
            Text(
                text = if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { expanded = !expanded },
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (keySet) {
                    "Configured — cloud voices are available."
                } else {
                    "Not configured — add a provider API key to enable."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (keySet) {
                    OutlinedButton(onClick = onConfigure) { Text("Configure") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onShowVoices) { Text("Voices") }
                } else {
                    Button(onClick = onConfigure) { Text("Configure") }
                }
            }
        }
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
