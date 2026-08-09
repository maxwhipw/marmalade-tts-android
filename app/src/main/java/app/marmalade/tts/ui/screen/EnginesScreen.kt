package app.marmalade.tts.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.ui.components.EngineSpecColumn
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
    viewModel: EnginesViewModel = hiltViewModel(),
) {
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val states by viewModel.installStates.collectAsStateWithLifecycle()

    var pendingInstall by remember { mutableStateOf<EngineDescriptor?>(null) }
    var pendingUninstall by remember { mutableStateOf<EngineDescriptor?>(null) }

    // On-device / Cloud split. On-device (the default) lists the installable
    // engines; Cloud shows the cloud-voices configure surface inline
    // ([CloudApiContent]) — its one-time consent gate on first entry, then the
    // per-provider key cards. The line is a real boundary, not just tidiness:
    // on-device engines send nothing off the phone, cloud sends your text to a
    // provider per request. Saveable so the choice survives rotation / process
    // death.
    var showCloud by rememberSaveable { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.engines_title)) },
                windowInsets = WindowInsets(0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            EngineSourceToggle(
                showCloud = showCloud,
                onSelect = { showCloud = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (showCloud) {
                // The Cloud tab IS the cloud-voices configure surface: the
                // consent gate on first entry (declining flips back to
                // On-device), then the per-provider key cards. No summary
                // card / "Configure" hop.
                CloudApiContent(
                    onDeclined = { showCloud = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
                }
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

/**
 * The On-device / Cloud switch above the engine list — a two-segment pill.
 * The selected segment fills with primaryContainer (the Toast peach), NOT the
 * accent orange, which stays reserved for the Install / Configure CTAs so the
 * screen never floods with accent. The two segments are a selectable group so
 * TalkBack announces them as tabs.
 */
@Composable
private fun EngineSourceToggle(
    showCloud: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SourceSegment(
            label = stringResource(R.string.engines_tab_local),
            selected = !showCloud,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
        )
        SourceSegment(
            label = stringResource(R.string.engines_tab_cloud),
            selected = showCloud,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SourceSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
    // Resting card leads with the A3 spec column (Speed / Quality / Languages)
    // + one line of prose + the download/installed sizes. "Show more" reveals
    // the full description and the license — the GPL disclosure has to stay
    // reachable for install consent, but it's too verbose to sit in the resting
    // card (it's also on the install-confirm dialog and Settings → About →
    // Licenses). It opens in a dialog rather than expanding inline: expanding
    // reflowed the whole list and pushed the neighbouring cards around.
    var showDetails by remember(engine.name) { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // Top, not centered: the spec column is taller than the text
                // column, and centering let the engine name drift below its
                // SPEED label on the taller cards.
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Header row: display name + an optional developer tag.
                    // The install/uninstall/update buttons below communicate
                    // download state, so no status chip here. Only the legacy
                    // sherpa engines carry a tag; production engines leave the
                    // slot empty.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = engine.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (engine.developerOnly) {
                            DeveloperBadge()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(engine.taglineRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.engines_size_summary,
                            formatBytes(engine.downloadSizeBytes),
                            formatBytes(engine.installedSizeBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Show more lives INSIDE the text column, directly under the
                    // size line. The row's height is set by the taller spec
                    // column, so a sibling below the row would be pushed down by
                    // all of that leftover height — a gap that got worse once
                    // the columns were top-aligned. Sitting in the column keeps
                    // it tight to the text it expands.
                    Text(
                        text = stringResource(R.string.engines_show_more),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clickable { showDetails = true },
                    )
                }
                Spacer(Modifier.width(12.dp))
                EngineSpecColumn(engine = engine)
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
                    text = stringResource(
                        R.string.engines_downloading_progress,
                        formatBytes(state.bytesFetched),
                        formatBytes(state.totalBytes),
                    ),
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
                    text = stringResource(
                        R.string.engines_installing_progress,
                        formatBytes(state.bytesExtracted),
                        formatBytes(state.totalBytes),
                    ),
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

    if (showDetails) {
        EngineDetailsDialog(engine = engine, onDismiss = { showDetails = false })
    }
}

/**
 * "Show more" contents — the engine's full description and its license
 * summary. A dialog rather than an inline expansion so opening it doesn't
 * reflow the list underneath. Scrollable: the license summary runs long on
 * the GPL bundles.
 */
@Composable
private fun EngineDetailsDialog(
    engine: EngineDescriptor,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(engine.displayName) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(engine.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(engine.licenseSummaryRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.engine_languages_dialog_close))
            }
        },
    )
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
        label = { Text(stringResource(R.string.engines_developer_badge)) },
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
        // Two-button states anchor the secondary action left and the primary
        // right, so the destructive Uninstall isn't a thumb-width from Engine
        // settings. Single-button states keep the CTA in its usual right corner.
        horizontalArrangement = when (state) {
            InstallState.Installed, is InstallState.Outdated -> Arrangement.SpaceBetween
            else -> Arrangement.End
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            InstallState.NotInstalled -> {
                Button(onClick = onInstall) { Text(stringResource(R.string.engines_install)) }
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
                OutlinedButton(onClick = onUninstall) { Text(stringResource(R.string.engines_uninstall)) }
                Button(onClick = onEngineSettings) { Text(stringResource(R.string.engines_configure)) }
            }
            is InstallState.Failed -> {
                Button(onClick = onRetry) { Text(stringResource(R.string.engines_retry)) }
            }
            InstallState.Corrupt -> {
                Button(onClick = onRetry) { Text(stringResource(R.string.engines_reinstall)) }
            }
            is InstallState.Outdated -> {
                // The current install still works; the user can keep using
                // it while the new bundle downloads. We surface Update as
                // the primary action and leave Engine settings reachable
                // so they don't lose access to alias management.
                OutlinedButton(onClick = onEngineSettings) { Text(stringResource(R.string.engines_configure)) }
                Button(onClick = onInstall) { Text(stringResource(R.string.engines_update)) }
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
        title = { Text(stringResource(R.string.engines_install_confirm_title, engine.displayName)) },
        text = {
            Column {
                Text(stringResource(engine.descriptionRes))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.engines_install_download_size, formatBytes(engine.downloadSizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(engine.licenseSummaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.engines_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.engines_cancel)) }
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
        title = { Text(stringResource(R.string.engines_uninstall_confirm_title, engine.displayName)) },
        text = {
            Text(stringResource(R.string.engines_uninstall_confirm_body))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.engines_uninstall)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.engines_cancel)) }
        },
    )
}
