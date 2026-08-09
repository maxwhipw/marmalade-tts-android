package app.marmalade.tts.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.perf.EngineFit
import app.marmalade.tts.ui.components.EngineSpecColumn
import app.marmalade.tts.ui.components.JarMascot
import app.marmalade.tts.ui.components.JarMascotState

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
    val recommendation by viewModel.recommendation.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold { padding ->
        when (step) {
            OnboardingStep.Welcome -> WelcomeStep(
                padding = padding,
                onGetStarted = viewModel::next,
            )
            OnboardingStep.EnginePick -> EnginePickStep(
                padding = padding,
                cards = engines,
                // Null recommendation = the device probe is still running,
                // so the cards are showing catalog order, not this phone's.
                probePending = recommendation == null,
                selectedIds = selected,
                onToggle = viewModel::toggle,
                onInstall = viewModel::installSelected,
                onBack = viewModel::back,
                onSkip = {
                    // Installs nothing (the selection set pre-selects the
                    // recommended engines, so installSelected() here would
                    // silently download them) and advances to CreateAlias.
                    // The user still has to create an alias to finish
                    // onboarding.
                    viewModel.skipEngineInstall()
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
            OnboardingStep.BackgroundUnrestricted -> BackgroundUnrestrictedStep(
                padding = padding,
                onOpenSettings = {
                    app.marmalade.tts.ui.openBatteryOptimizationRequest(context)
                },
                onContinue = viewModel::advancePastBackground,
            )
            OnboardingStep.NotificationPermission -> NotificationPermissionStep(
                padding = padding,
                onContinue = viewModel::advancePastNotifications,
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
            contentDescription = stringResource(R.string.onboarding_mascot_content_description),
            modifier = Modifier.size(160.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
        }
    }
}

// -- step 2 -----------------------------------------------------------------

@Composable
private fun EnginePickStep(
    padding: PaddingValues,
    cards: List<EngineCardState>,
    probePending: Boolean,
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
            text = stringResource(R.string.onboarding_engines_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_engines_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // While the capability probe runs the list is in catalog order with
        // the catalog's static Recommended flag. Say so rather than letting
        // the cards silently reshuffle under the user's finger.
        if (probePending) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_checking_device),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(items = cards, key = { it.descriptor.name }) { card ->
                EngineCard(
                    engine = card.descriptor,
                    isSelected = selectedIds.contains(card.descriptor.name),
                    fit = card.fit,
                    isBuiltIn = card.isBuiltIn,
                    onToggle = { onToggle(card.descriptor.name) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // Baked Kitten is permanently selected, so the button can be "enabled"
        // with nothing to fetch — in that state it advances the wizard rather
        // than installing, and the label must not promise a download.
        val downloadsSomething = cards.any {
            !it.isBuiltIn && selectedIds.contains(it.descriptor.name)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
            Row {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onInstall,
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text(
                        stringResource(
                            if (downloadsSomething) R.string.onboarding_install_selected
                            else R.string.onboarding_continue,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * A3 "spec columns" engine card. Left: checkbox + name (+ Recommended pill) +
 * one honest line of prose + a demoted download size. Right: the shared
 * [EngineSpecColumn] stacking Speed / Quality / Languages so the three engines
 * compare axis-to-axis down the list. The espeak/phonemizer/license prose is
 * gone from the resting card — the license still lives on the Engines-tab card
 * (behind "Show more") and in Settings → About → Licenses.
 *
 * [isBuiltIn] swaps the download affordances for a plain "ready to use"
 * statement: the baked engine is already in the APK, so a checkbox and a
 * download size would both be lies.
 *
 * [fit] is this device's measured verdict; null means the probe hasn't
 * answered and the card falls back to the catalog's static flag.
 */
@Composable
private fun EngineCard(
    engine: EngineDescriptor,
    isSelected: Boolean,
    fit: EngineFit?,
    isBuiltIn: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isBuiltIn) {
                    Modifier
                } else {
                    Modifier.toggleable(
                        value = isSelected,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() },
                    )
                },
            ),
        // The permanently-ticked built-in card keeps the plain container:
        // the selected tint means "you chose this", and Kitten isn't a
        // choice (Max, 2026-08-08 — the loud terracotta card read as an
        // error state in dark mode).
        colors = if (isSelected && !isBuiltIn) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // The fit pill sits in its own full-width row at the card's top
            // right (Max, 2026-08-08). Its own row rather than a line inside
            // the text column, so a long engine name can never crush it
            // (recommended-badge-lab R1) and it never collides with the spec
            // column's header.
            val showRecommended =
                if (fit == null) engine.isRecommended else fit == EngineFit.RECOMMENDED
            if (showRecommended || fit == EngineFit.MAY_BE_SLOW) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, end = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (showRecommended) RecommendedTag() else MayBeSlowTag()
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .height(IntrinsicSize.Min),
                // Top-aligned like the Engines tab cards — centering left the
                // short built-in column floating mid-card.
                verticalAlignment = Alignment.Top,
            ) {
                if (isBuiltIn) {
                // Not a checkbox: nothing here is a choice. The adjacent
                // "Built in — ready to use" line carries the meaning, so the
                // tick itself is decorative for screen readers.
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
                }
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = engine.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isBuiltIn) {
                        stringResource(R.string.onboarding_built_in_ready)
                    } else {
                        stringResource(
                            R.string.onboarding_download_size,
                            formatBytes(engine.downloadSizeBytes),
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBuiltIn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                }
                Spacer(Modifier.size(12.dp))
                VerticalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Spacer(Modifier.size(12.dp))
                EngineSpecColumn(engine = engine)
            }
        }
    }
}

/**
 * Small "Recommended" pill. Toast-family container (never the precious accent
 * orange) so it stays warm without flooding the card.
 */
@Composable
private fun RecommendedTag() {
    Text(
        text = stringResource(R.string.onboarding_recommended),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Warning counterpart to [RecommendedTag], shown when the device probe
 * predicts this engine will synthesize slower than real time. Error-container
 * tinted rather than error-solid: it's a caveat on an install the user may
 * still legitimately want, not a blocked action.
 */
@Composable
private fun MayBeSlowTag() {
    Text(
        text = stringResource(R.string.onboarding_may_be_slow),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
        // Live mascot: listening pose (lid open, waves drifting in) while
        // downloads are in flight, settling to idle once everything's done.
        JarMascot(
            state = if (allDone) JarMascotState.IDLE else JarMascotState.LISTENING,
            size = 96.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (allDone) {
                stringResource(R.string.onboarding_setup_complete)
            } else {
                stringResource(R.string.onboarding_installing_engines)
            },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        if (rowsToShow.isEmpty()) {
            // User picked zero engines. Show a friendly note.
            Text(
                text = stringResource(R.string.onboarding_no_engines_selected),
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
                        isBuiltIn = engine.name == KittenDirectVoiceCatalog.ENGINE,
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
            Text(stringResource(R.string.onboarding_continue))
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * One engine's progress line.
 *
 * The baked engine ([isBuiltIn]) normally has nothing to download — its
 * files are seeded from APK assets — so showing it a progress bar crawling
 * to 100% would be theatre. It reports "ready to use" instead. The one case
 * where it *does* download for real is a failed asset seed, and then it
 * falls through to the ordinary progress rendering.
 */
@Composable
private fun InstallRow(
    engine: EngineDescriptor,
    state: InstallState,
    isBuiltIn: Boolean,
    onRetry: () -> Unit,
) {
    val builtInReady = isBuiltIn &&
        state !is InstallState.Downloading &&
        state !is InstallState.Extracting &&
        state !is InstallState.Failed
    if (builtInReady) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = engine.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.onboarding_built_in_ready),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }

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
                text = stringResource(statusLabelRes(state)),
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
                val extractFraction = if (state.totalBytes > 0L) {
                    (state.bytesExtracted.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { extractFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is InstallState.Failed -> {
                Text(
                    text = state.reason.ifBlank {
                        stringResource(R.string.onboarding_install_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Text(stringResource(R.string.onboarding_retry))
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
            text = stringResource(R.string.onboarding_alias_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_alias_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = editor.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.onboarding_alias_name_label)) },
            singleLine = true,
            supportingText = {
                Text(
                    text = stringResource(
                        editor.error ?: R.string.onboarding_alias_name_helper,
                    ),
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
            val speedText = stringResource(R.string.onboarding_alias_speed_value, editor.speed)
            Text(
                text = stringResource(R.string.onboarding_alias_speed_label, speedText),
                style = MaterialTheme.typography.bodyMedium,
            )
            val speedDescription = stringResource(
                R.string.onboarding_alias_speed_content_description,
            )
            Slider(
                value = editor.speed,
                onValueChange = onSpeedChange,
                valueRange = VoiceAlias.MIN_SPEED..VoiceAlias.MAX_SPEED,
                steps = 14,
                modifier = Modifier.semantics {
                    contentDescription = speedDescription
                    stateDescription = speedText
                },
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
        ) { Text(stringResource(R.string.onboarding_alias_save)) }
        Spacer(Modifier.height(8.dp))

        // Secondary CTA: create a default alias without thinking about it.
        OutlinedButton(
            onClick = onUseDefaults,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onboarding_alias_use_defaults)) }

        // If we entered this step with an alias already present
        // (sideloaded edge case) the user can just finish — the gated
        // finish() in the VM accepts the call because aliasCreated is true.
        if (aliasCreated) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_alias_finish_setup)) }
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
    val engines = EngineCatalog.visibleTo(showDeveloper = false)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = engines.firstOrNull { it.name == selected }?.displayName
                ?: if (selected.isBlank()) {
                    stringResource(R.string.onboarding_alias_engine_placeholder)
                } else {
                    selected
                },
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text(stringResource(R.string.onboarding_alias_engine_label)) },
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
        ?: if (selected.isBlank()) {
            stringResource(R.string.onboarding_alias_voice_placeholder)
        } else {
            selected
        }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text(stringResource(R.string.onboarding_alias_voice_label)) },
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
                    text = { Text(stringResource(R.string.onboarding_alias_no_voices)) },
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
            value = stringResource(effectDisplayNameRes(selected)),
            onValueChange = { /* read-only */ },
            readOnly = true,
            label = { Text(stringResource(R.string.onboarding_alias_effect_label)) },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(
                            R.string.onboarding_alias_effect_pick_content_description,
                        ),
                    )
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
                    text = { Text(stringResource(effectDisplayNameRes(preset))) },
                    onClick = {
                        onPick(preset)
                        expanded = false
                    },
                )
            }
        }
    }
}

@StringRes
private fun effectDisplayNameRes(preset: EffectPreset): Int = when (preset) {
    EffectPreset.NONE -> R.string.onboarding_effect_none
    EffectPreset.CAVE -> R.string.onboarding_effect_cave
    EffectPreset.TELEPHONE -> R.string.onboarding_effect_telephone
}

// -- helpers ----------------------------------------------------------------

@StringRes
private fun statusLabelRes(state: InstallState): Int = when (state) {
    InstallState.NotInstalled -> R.string.onboarding_status_pending
    is InstallState.Downloading -> R.string.onboarding_status_downloading
    is InstallState.Extracting -> R.string.onboarding_status_finishing_up
    InstallState.Installed -> R.string.onboarding_status_installed
    is InstallState.Failed -> R.string.onboarding_status_failed
    InstallState.Corrupt -> R.string.onboarding_status_corrupt
    is InstallState.Outdated -> R.string.onboarding_status_outdated
}

@Composable
private fun downloadDetail(state: InstallState.Downloading): String {
    val fetched = formatBytes(state.bytesFetched)
    val total = if (state.totalBytes > 0L) {
        formatBytes(state.totalBytes)
    } else {
        stringResource(R.string.onboarding_download_size_unknown)
    }
    return if (state.currentFile.isNotBlank()) {
        stringResource(R.string.onboarding_download_detail_file, fetched, total, state.currentFile)
    } else {
        stringResource(R.string.onboarding_download_detail, fetched, total)
    }
}

/**
 * P-J — onboarding step that asks the user to exempt Marmalade from
 * Android's battery optimisations. Without the exemption Android may
 * pause our foreground synth service mid-utterance when the screen
 * sleeps or the device enters Doze.
 *
 * Live-checks [isIgnoringBatteryOptimizations] on every recomposition
 * + every lifecycle resume so the screen self-updates after the user
 * returns from the system dialog. If already granted, the explanation
 * is replaced with a confirmation and the only affordance becomes
 * "Continue".
 */
@Composable
private fun BackgroundUnrestrictedStep(
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isAllowed by remember {
        mutableStateOf(app.marmalade.tts.ui.isIgnoringBatteryOptimizations(context))
    }
    // Refresh the live state every time the user comes back from the
    // settings dialog (Lifecycle.Event.ON_RESUME on this screen).
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAllowed = app.marmalade.tts.ui.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            text = if (isAllowed) {
                stringResource(R.string.onboarding_background_title_allowed)
            } else {
                stringResource(R.string.onboarding_background_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isAllowed) {
                stringResource(R.string.onboarding_background_body_allowed)
            } else {
                stringResource(R.string.onboarding_background_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (isAllowed) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_continue)) }
        } else {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_background_allow)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_skip_for_now)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_background_footnote),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Notification-permission step (Android 13+). Requests POST_NOTIFICATIONS
 * at runtime so the "Keep engine loaded" foreground-service notification
 * (Smart keep-warm is the default) and the speaking notification can
 * actually appear — without the grant Android silently suppresses them.
 *
 * Auto-skips on Android 12 and below, where the permission is granted at
 * install time. Granting auto-advances; declining is allowed (just hides
 * those notices — the engine still stays warm).
 */
@Composable
private fun NotificationPermissionStep(
    padding: PaddingValues,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current

    // Pre-13 has no runtime notification permission — nothing to ask.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LaunchedEffect(Unit) { onContinue() }
        return
    }

    fun isGranted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        // Smooth path: a grant advances immediately; a denial leaves the
        // user on this step with the Skip affordance.
        if (result) onContinue()
    }

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
            text = if (granted) {
                stringResource(R.string.onboarding_notifications_title_granted)
            } else {
                stringResource(R.string.onboarding_notifications_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (granted) {
                stringResource(R.string.onboarding_notifications_body_granted)
            } else {
                stringResource(R.string.onboarding_notifications_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (granted) {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_continue)) }
        } else {
            Button(
                onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_notifications_allow)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_skip_for_now)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_notifications_footnote),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Final onboarding step: prompts the user to pick Marmalade as their
 * system TTS engine. The app being installed isn't enough — until the
 * OS-level default is set to ours, no external app's TTS request
 * routes through us.
 *
 * Live-checks [isDefaultSystemTts] on every lifecycle resume so the
 * screen self-updates after the user returns from the system TTS page.
 * Once we're the default, the prompt flips to an "All done" confirmation
 * and the only affordance becomes "Finish".
 */
@Composable
private fun SystemDefaultStep(
    padding: PaddingValues,
    onOpenSystemSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isDefault by remember {
        mutableStateOf(app.marmalade.tts.ui.isDefaultSystemTts(context))
    }
    // Refresh the live state every time the user comes back from the
    // system TTS settings page (Lifecycle.Event.ON_RESUME on this screen).
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isDefault = app.marmalade.tts.ui.isDefaultSystemTts(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            text = if (isDefault) {
                stringResource(R.string.onboarding_system_title_done)
            } else {
                stringResource(R.string.onboarding_system_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isDefault) {
                stringResource(R.string.onboarding_system_body_done)
            } else {
                stringResource(R.string.onboarding_system_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (isDefault) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_finish)) }
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_system_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onOpenSystemSettings,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_system_open_settings)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_system_finish_later)) }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_system_footnote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
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
