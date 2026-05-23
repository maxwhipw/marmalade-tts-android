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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog
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
//     ├── Installing step: progress per engine
//     │    │
//     │    ├── all engines reached terminal state (Installed/Failed)
//     │    │      → "Continue" → vm.next() (advances to CreateAlias)
//     │    │
//     │    └── Failed engine row offers retry → vm.retry(name)
//     │
//     └── CreateAlias step: inline alias editor
//          │
//          ├── "Save and continue" → vm.saveAliasAndContinue() then onComplete()
//          ├── "Use defaults"      → vm.useDefaultsAndContinue() then onComplete()
//          │
//          └── Skipped automatically (vm.finish() + onComplete()) when an
//              alias already exists on entry — sideloaded-data edge case.
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
    val aliasCreated by viewModel.aliasCreated.collectAsStateWithLifecycle()
    val aliasEditor by viewModel.aliasEditorState.collectAsStateWithLifecycle()
    val installedVoices by viewModel.installedVoices.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                    // Skipping installs an empty set — installs nothing
                    // and advances to the CreateAlias step. The user still
                    // has to create an alias to finish onboarding.
                    viewModel.installSelected()
                    viewModel.next()
                },
            )
            OnboardingStep.Installing -> InstallingStep(
                padding = padding,
                installStates = installStates,
                allEngines = engines.map { it.descriptor },
                selectedIds = selected,
                onRetry = viewModel::retry,
                onContinue = {
                    // Advance to the CreateAlias step. The wizard can no
                    // longer be exited from here — the user must save an
                    // alias (or accept "Use defaults") to finish.
                    viewModel.next()
                },
            )
            OnboardingStep.CreateAlias -> CreateAliasStep(
                padding = padding,
                editor = aliasEditor,
                voices = installedVoices,
                aliasCreated = aliasCreated,
                onSeedDefaults = viewModel::seedAliasDefaults,
                onNameChange = viewModel::onAliasNameChange,
                onEngineChange = viewModel::onAliasEngineChange,
                onVoiceChange = viewModel::onAliasVoiceChange,
                onSpeedChange = viewModel::onAliasSpeedChange,
                onEffectChange = viewModel::onAliasEffectChange,
                // saveAliasAndContinue + useDefaultsAndContinue now both
                // advance to SystemDefault rather than completing — the
                // user still needs to enable us as their system TTS
                // engine before the system-TTS path actually routes here.
                onSave = { viewModel.saveAliasAndContinue() },
                onUseDefaults = { viewModel.useDefaultsAndContinue() },
                onFinish = {
                    // "Finish setup" branch when an alias already exists
                    // (sideloaded data). Skip directly to SystemDefault
                    // so the user still gets the system-TTS-pick prompt.
                    if (viewModel.advanceToSystemDefault()) {
                        // step has been moved; the screen recomposes
                    }
                },
            )
            OnboardingStep.SystemDefault -> SystemDefaultStep(
                padding = padding,
                onOpenSystemSettings = {
                    app.marmalade.tts.ui.openSystemTtsSettings(context)
                },
                onFinish = {
                    if (viewModel.finish()) onComplete()
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

// -- step 4 -----------------------------------------------------------------

/**
 * Create-primary-alias step.
 *
 * UX choice: full inline editor + a "Use defaults" affordance side-by-side.
 * The editor surface is a stack of name / engine / voice / speed / effect
 * controls — matching the AliasScreen editor but inline (no dialog) so the
 * onboarding context stays linear. "Use defaults" is a secondary
 * OutlinedButton that bypasses the form and creates a baseline alias.
 *
 * The "Save and continue" button is disabled until the editor's `name`
 * and `voiceId` look syntactically valid. A separate "Finish" affordance
 * appears when an alias already exists on entry (sideloaded edge case) —
 * it just calls [onFinish], which routes through the gated
 * `OnboardingViewModel.finish`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateAliasStep(
    padding: PaddingValues,
    editor: OnboardingViewModel.AliasFields,
    voices: List<VoiceMeta>,
    aliasCreated: Boolean,
    onSeedDefaults: () -> Unit,
    onNameChange: (String) -> Unit,
    onEngineChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEffectChange: (EffectPreset) -> Unit,
    onSave: () -> Unit,
    onUseDefaults: () -> Unit,
    onFinish: () -> Unit,
) {
    // Seed the editor once on first composition so the user sees sane
    // defaults (engine pre-picked, voice pre-picked) rather than an empty
    // form. Keyed on `Unit` so it never re-runs and clobbers the user's edits.
    LaunchedEffect(Unit) { onSeedDefaults() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Create your primary alias",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "An alias bundles your favorite voice, speed, and effect under a single " +
                "name like \"narrator\". Marmalade uses the primary alias whenever an app " +
                "asks it to speak without specifying a voice. Create at least one alias to " +
                "finish setup — you can add more later from Settings → Voice aliases.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = editor.name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            singleLine = true,
            supportingText = {
                Text(
                    text = editor.error
                        ?: "Lower-case letters, digits, dash, underscore.",
                    color = if (editor.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = editor.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OnboardingEngineDropdown(
            selected = editor.engine,
            onPick = onEngineChange,
        )
        Spacer(Modifier.height(12.dp))

        OnboardingVoiceDropdown(
            selected = editor.voiceId,
            voices = voices,
            onPick = onVoiceChange,
        )
        Spacer(Modifier.height(12.dp))

        Column {
            Text(
                text = "Speed: %.2f×".format(editor.speed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = editor.speed,
                onValueChange = onSpeedChange,
                valueRange = VoiceAlias.MIN_SPEED..VoiceAlias.MAX_SPEED,
                steps = 14,
            )
        }
        Spacer(Modifier.height(12.dp))

        OnboardingEffectDropdown(
            selected = editor.effect,
            onPick = onEffectChange,
        )

        Spacer(Modifier.height(24.dp))

        // Primary CTA: save the editor's values and finish onboarding.
        // Enabled even when the user hasn't filled the name field yet —
        // the VM validates on save and surfaces the failure inline.
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and continue") }
        Spacer(Modifier.height(8.dp))

        // Secondary CTA: create a default alias without thinking about it.
        OutlinedButton(
            onClick = onUseDefaults,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use defaults") }

        // If we entered this step with an alias already present
        // (sideloaded edge case) the user can just finish — the gated
        // finish() in the VM accepts the call because aliasCreated is true.
        if (aliasCreated) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish setup") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingEngineDropdown(
    selected: String,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val engines = EngineCatalog.all
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = engines.firstOrNull { it.name == selected }?.displayName
                ?: if (selected.isBlank()) "Select an engine" else selected,
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text("Engine") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (engine in engines) {
                DropdownMenuItem(
                    text = { Text(engine.displayName) },
                    onClick = {
                        onPick(engine.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingVoiceDropdown(
    selected: String,
    voices: List<VoiceMeta>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Prefer installed voices; fall back to the full list (model not yet
    // loaded, or engine install hasn't refreshed isInstalled). Matches
    // AliasScreen.VoiceDropdown's behaviour.
    val installed = voices.filter { it.isInstalled }
    val choices = installed.ifEmpty { voices }
    val selectedLabel = choices.firstOrNull { it.id == selected }?.displayName
        ?: if (selected.isBlank()) "Select a voice" else selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (choices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No voices available — install an engine first.") },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                for (voice in choices) {
                    DropdownMenuItem(
                        text = { Text(voice.displayName) },
                        onClick = {
                            onPick(voice.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingEffectDropdown(
    selected: EffectPreset,
    onPick: (EffectPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = effectDisplayName(selected),
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text("Effect") },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick effect")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (preset in EffectPreset.entries) {
                DropdownMenuItem(
                    text = { Text(effectDisplayName(preset)) },
                    onClick = {
                        onPick(preset)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun effectDisplayName(preset: EffectPreset): String = when (preset) {
    EffectPreset.NONE -> "None"
    EffectPreset.CAVE -> "Cave"
    EffectPreset.ROBOT -> "Robot"
    EffectPreset.TELEPHONE -> "Telephone"
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

/**
 * Final onboarding step: prompts the user to pick Marmalade as their
 * system TTS engine. The app being installed isn't enough — until the
 * OS-level default is set to ours, no external app's TTS request
 * routes through us.
 */
@Composable
private fun SystemDefaultStep(
    padding: PaddingValues,
    onOpenSystemSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.mascot_happy),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "One more step",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "To let other apps speak through Marmalade, you have to pick it " +
                "as your system text-to-speech engine. Android won't route TTS to " +
                "us until you do.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Tap the button below — most phones land directly on the right " +
                "page. If yours doesn't, look for \"Text-to-speech\" under " +
                "System → Languages, Accessibility → Audio, or Languages & input.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenSystemSettings,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open system TTS settings") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Finish — I'll do this later") }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "You can re-open this from Settings → Set as system TTS engine.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
