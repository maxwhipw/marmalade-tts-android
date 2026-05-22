package app.marmalade.tts.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AppRoot (gated on SettingsRepository.onboarded == false)
//     │
//     ▼
//   OnboardingScreen(onComplete)
//     │
//     ├── reads step / selectedEngineIds / installStates from
//     │   OnboardingViewModel
//     │
//     ├── Welcome step:   mascot + pitch + "Get started" → vm.next()
//     ├── EnginePick step: cards + "Install selected"    → vm.installSelected()
//     └── Installing step: progress per engine
//          │
//          ├── all engines reached terminal state (Installed/Failed)
//          │      → "Continue" → vm.finish() + onComplete()
//          │
//          └── Failed engine row offers retry → vm.retry(name)
// -----------------------------------------------------------------------------

/**
 * First-launch wizard. Three steps:
 *
 *  1. Welcome — explain what the app does.
 *  2. Engine picker — let the user choose which engines to download.
 *  3. Install progress — watch the bytes come down, then "Continue."
 *
 * Routing is owned by [OnboardingViewModel.step] — this composable is a
 * dumb switch over the current step value.
 *
 * On the final "Continue" tap the VM flips `SettingsRepository.onboarded`
 * to true and the supplied [onComplete] callback navigates the host out
 * of the wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val selected by viewModel.selectedEngineIds.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()

    Scaffold { padding ->
        when (step) {
            OnboardingStep.Welcome -> WelcomeStep(
                padding = padding,
                onGetStarted = viewModel::next,
            )
            OnboardingStep.EnginePick -> EnginePickStep(
                padding = padding,
                engines = engines.map { it.descriptor },
                selectedIds = selected,
                onToggle = viewModel::toggle,
                onInstall = viewModel::installSelected,
                onBack = viewModel::back,
                onSkip = {
                    // Skipping installs an empty set — the user gets to the
                    // Speak screen and can install from Settings → Engines.
                    viewModel.installSelected()
                    viewModel.finish()
                    onComplete()
                },
            )
            OnboardingStep.Installing -> InstallingStep(
                padding = padding,
                installStates = installStates,
                allEngines = engines.map { it.descriptor },
                selectedIds = selected,
                onRetry = viewModel::retry,
                onContinue = {
                    viewModel.finish()
                    onComplete()
                },
            )
        }
    }
}

// -- step 1 -----------------------------------------------------------------

@Composable
private fun WelcomeStep(
    padding: PaddingValues,
    onGetStarted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.mascot_happy),
            contentDescription = "Marmalade mascot",
            modifier = Modifier.size(160.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Welcome to Marmalade TTS",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Marmalade reads text aloud, offline, in voices you choose. " +
                "Pick an engine to get started.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Get started")
        }
    }
}

// -- step 2 -----------------------------------------------------------------

@Composable
private fun EnginePickStep(
    padding: PaddingValues,
    engines: List<EngineDescriptor>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Choose engines",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Engines run on-device. You can install more later from Settings → Engines.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(items = engines, key = { it.name }) { engine ->
                EngineCard(
                    engine = engine,
                    isSelected = selectedIds.contains(engine.name),
                    onToggle = { onToggle(engine.name) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Row {
                TextButton(onClick = onSkip) { Text("Skip") }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onInstall,
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text("Install selected")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EngineCard(
    engine: EngineDescriptor,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = engine.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (engine.isRecommended) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "RECOMMENDED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = engine.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Download: ${formatBytes(engine.downloadSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = engine.licenseSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

// -- step 3 -----------------------------------------------------------------

@Composable
private fun InstallingStep(
    padding: PaddingValues,
    installStates: Map<String, InstallState>,
    allEngines: List<EngineDescriptor>,
    selectedIds: Set<String>,
    onRetry: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val rowsToShow = allEngines.filter { it.name in selectedIds }
    val allDone = rowsToShow.all { e ->
        val s = installStates[e.name]
        s is InstallState.Installed || s is InstallState.Failed
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Image(
            painter = painterResource(id = R.drawable.mascot_focused),
            contentDescription = "Marmalade installing",
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (allDone) "Setup complete" else "Installing engines",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        if (rowsToShow.isEmpty()) {
            // User picked zero engines. Show a friendly note.
            Text(
                text = "No engines selected — you can install one any time from Settings → Engines.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)) {
                items(items = rowsToShow, key = { it.name }) { engine ->
                    InstallRow(
                        engine = engine,
                        state = installStates[engine.name] ?: InstallState.NotInstalled,
                        onRetry = { onRetry(engine.name) },
                    )
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onContinue,
            enabled = allDone || rowsToShow.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InstallRow(
    engine: EngineDescriptor,
    state: InstallState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = engine.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = when (state) {
                    is InstallState.Failed -> MaterialTheme.colorScheme.error
                    is InstallState.Installed -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            is InstallState.Downloading -> {
                val fraction = if (state.totalBytes > 0L) {
                    (state.bytesFetched.toFloat() / state.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = downloadDetail(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is InstallState.Extracting -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is InstallState.Failed -> {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Text("Retry")
                    }
                }
            }
            else -> Unit
        }
    }
}

// -- helpers ----------------------------------------------------------------

private fun statusLabel(state: InstallState): String = when (state) {
    InstallState.NotInstalled -> "Pending"
    is InstallState.Downloading -> "Downloading"
    InstallState.Extracting -> "Finishing up"
    InstallState.Installed -> "Installed"
    is InstallState.Failed -> "Failed"
    InstallState.Corrupt -> "Corrupt"
}

private fun downloadDetail(state: InstallState.Downloading): String {
    val fetched = formatBytes(state.bytesFetched)
    val total = if (state.totalBytes > 0L) formatBytes(state.totalBytes) else "?"
    return if (state.currentFile.isNotBlank()) {
        "$fetched / $total — ${state.currentFile}"
    } else {
        "$fetched / $total"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${"%.1f".format(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024.0) return "${"%.1f".format(mb)} MB"
    val gb = mb / 1024.0
    return "${"%.2f".format(gb)} GB"
}
