package app.marmalade.tts.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.perf.EngineFit
import app.marmalade.tts.ui.components.EngineSpecColumn
import app.marmalade.tts.ui.components.JarMascot
import app.marmalade.tts.ui.components.JarMascotState
import app.marmalade.tts.ui.components.MarmaladeCard
import app.marmalade.tts.ui.components.WorkingDots
import app.marmalade.tts.ui.screen.installFailureText
import app.marmalade.tts.ui.theme.MarmaladeMotion
import app.marmalade.tts.ui.theme.MarmaladeToast
import app.marmalade.tts.ui.theme.depth
import app.marmalade.tts.ui.theme.riseIn

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
//     ├── EnginePick step: working dots until vm.probeDone, then
//     │    cards + "Install selected"                    → vm.installSelected()
//     ├── Installing step: progress per engine
//     │    │
//     │    ├── all engines reached terminal state (Installed/Failed)
//     │    │      → "Continue" → vm.next() (advances to ChooseVoice)
//     │    │
//     │    └── Failed engine row offers retry → vm.retry(name), plus
//     │        "Remove download" → vm.removeDownload(name) while a partial
//     │        archive is kept for resume
//     │
//     ├── ChooseVoice step (VoiceStep.kt, #87): the installed voices with a
//     │    speaker each, one picked → "Use Alba" → vm.useVoice() (saves the
//     │    first alias, named after the voice) → battery step; "Finish setup"
//     │    → vm.skipVoiceStep() when an alias already exists
//     │
//     └── Battery → Notifications → System default → vm.finish() + onComplete()
// -----------------------------------------------------------------------------

/**
 * First-launch wizard, one [OnboardingStep] at a time:
 *
 *  1. Welcome — explain what the app does.
 *  2. Engine picker — let the user choose which engines to download.
 *  3. Install progress — watch the bytes come down, then "Continue."
 *  4. Pick a voice — hear the installed voices, keep one as the first alias.
 *  5. Background, notifications — the battery exemption and (Android 13+)
 *     the notification permission.
 *  6. System default — pick Marmalade as the system TTS engine.
 *
 * Routing is owned by [OnboardingViewModel.step] — this composable is a
 * dumb (animated) switch over the current step value.
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
    val voiceGroups by viewModel.voiceGroups.collectAsStateWithLifecycle()
    val aliasOnEntry by viewModel.aliasOnEntry.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val probeDone by viewModel.probeDone.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // System Back steps back through setup like the on-screen Back (#148);
    // it used to close the app from any step. Welcome has nowhere to go back
    // to, so Back leaves the app there as before.
    BackHandler(enabled = step != OnboardingStep.Welcome, onBack = viewModel::back)

    // Each step arrives with the lab's rise (8 dp up and a fade) while the
    // last one fades out (#83). Each content is keyed by its own step.
    val stepEnter = riseIn()
    Scaffold { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { (stepEnter togetherWith MarmaladeMotion.fadeExit).using(null) },
            label = "onboardingStep",
        ) { shown ->
            // The outgoing step stays on screen while it fades. Its buttons call
            // relative moves (vm.next()), so a quick second tap on "Get started"
            // would skip the engine picker: it takes no input while it leaves.
            val leaving = transition.targetState == EnterExitState.PostExit
            Box(if (leaving) Modifier.fillMaxSize().blockTouches() else Modifier.fillMaxSize()) {
                when (shown) {
                    OnboardingStep.Welcome -> WelcomeStep(
                        padding = padding,
                        onGetStarted = viewModel::next,
                    )
                    OnboardingStep.EnginePick -> EnginePickStep(
                        padding = padding,
                        cards = engines,
                        probePending = !probeDone,
                        selectedIds = selected,
                        onToggle = viewModel::toggle,
                        onInstall = viewModel::installSelected,
                        onBack = viewModel::back,
                        onSkip = {
                            // Installs nothing (the selection set pre-selects the
                            // recommended engines, so installSelected() here would
                            // silently download them) and advances to ChooseVoice.
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
                        onRemoveDownload = viewModel::removeDownload,
                        onContinue = {
                            // Advance to the voice step. The wizard can no longer
                            // be exited from here: finishing needs an alias.
                            viewModel.next()
                        },
                    )
                    OnboardingStep.ChooseVoice -> ChooseVoiceStep(
                        padding = padding,
                        groups = voiceGroups,
                        selected = selectedVoice,
                        previewState = previewState,
                        aliasOnEntry = aliasOnEntry,
                        onPrepare = viewModel::prepareVoiceStep,
                        onPick = viewModel::pickVoice,
                        onTogglePreview = viewModel::togglePreview,
                        onStopPreview = viewModel::stopPreview,
                        onUse = viewModel::useVoice,
                        onFinish = { viewModel.skipVoiceStep() },
                        onBack = viewModel::back,
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
    }
}

/** Swallows every touch before the content under it sees one. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
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
            // Decorative everywhere — the mascot is never a TalkBack stop
            // (Max, 2026-08-09). The welcome copy below carries the meaning.
            contentDescription = null,
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
        Spacer(Modifier.height(16.dp))

        // While the phone check runs, no cards: they would re-sort and
        // re-tick under the user's finger when it answers (issue #76, Max
        // 2026-10-13). The VM bounds the wait, so this can't spin forever.
        if (probePending) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Working dots, not a spinning ring (#83). They keep the
                // indicator's "in progress" semantics; the text says what.
                WorkingDots()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.onboarding_checking_device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val listState = rememberLazyListState()
            // The list opens at the top, Recommended card first. If a late
            // verdict re-sorts it while visible, LazyColumn would keep the
            // old first card in view and leave the new one above it, so
            // follow the order back to the top.
            val order = cards.map { it.descriptor.name }
            LaunchedEffect(order) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(items = cards, key = { it.descriptor.name }) { card ->
                    // A late verdict moves the Recommended card to the top
                    // (#63); animateItem slides it there instead of the
                    // list snapping.
                    Column(Modifier.animateItem()) {
                        EngineCard(
                            engine = card.descriptor,
                            isSelected = selectedIds.contains(card.descriptor.name),
                            fit = card.fit,
                            showsRecommended = card.showsRecommended,
                            isBuiltIn = card.isBuiltIn,
                            onToggle = { onToggle(card.descriptor.name) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
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
                // Off while checking: the verdict still has to tick the
                // recommended engine, and Install now would fetch only part
                // of what the list will show. Skip stays on: it fetches
                // nothing, so there is no selection to get wrong.
                Button(
                    onClick = onInstall,
                    enabled = !probePending && selectedIds.isNotEmpty(),
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
 * answered. [showsRecommended] ([EngineCardState.showsRecommended]) then
 * falls back to the catalog's static flag.
 */
@Composable
private fun EngineCard(
    engine: EngineDescriptor,
    isSelected: Boolean,
    fit: EngineFit?,
    showsRecommended: Boolean,
    isBuiltIn: Boolean,
    onToggle: () -> Unit,
) {
    // Every card is the shared MarmaladeCard: card fill, hairline edge, soft
    // shadow (#83). The checkbox carries the state; a ticked card only warms
    // a little, animated. In dark mode that is the faint shared wash on
    // stone, never a filled slab (it used to be a brown secondaryContainer
    // card); in light mode a faint toast wash (full toast was "too orange",
    // Max in the #83 lab, reversing 2026-10-13's "Stronger tint"; he picked
    // the faint wash over a toast edge or the checkbox alone).
    //
    // The permanently-ticked built-in card keeps the plain fill: the wash
    // means "you chose this", and Kitten isn't a choice (Max, 2026-08-08 —
    // the loud terracotta card read as an error state in dark mode).
    val tinted = isSelected && !isBuiltIn
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // The toggle sits on the card's outer modifier, outside the card's
    // clip, so its ripple is drawn on the content inside instead, where it
    // follows the rounded corners.
    val interaction = remember { MutableInteractionSource() }
    MarmaladeCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isBuiltIn) {
                    // toggleable() merges each selectable card into ONE
                    // TalkBack object; the built-in card must merge the same
                    // way or TalkBack walks its every text fragment
                    // separately (Max's 2026-08-09 TalkBack pass).
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier.toggleable(
                        value = isSelected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() },
                    )
                },
            ),
        selected = tinted,
        selectedFill = if (isDark) null else MarmaladeToast.copy(alpha = LIGHT_TICK_WASH).compositeOver(MaterialTheme.depth.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .indication(interaction, ripple())
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
                // The fit pill sits under the title (Max's pick after seeing
                // the top-right corner variant, 2026-08-08), on its own line
                // so a long engine name can never crush it
                // (recommended-badge-lab R1).
                if (showsRecommended) {
                    Spacer(Modifier.height(4.dp))
                    RecommendedTag()
                } else if (fit == EngineFit.MAY_BE_SLOW) {
                    Spacer(Modifier.height(4.dp))
                    MayBeSlowTag()
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(engine.taglineRes),
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
                VerticalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.depth.line,
                )
                Spacer(Modifier.size(12.dp))
                EngineSpecColumn(engine = engine)
        }
    }
}

/** How strongly a ticked engine card, or a picked voice row, warms in light mode (#83 M). */
internal const val LIGHT_TICK_WASH = 0.35f

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
    onRemoveDownload: (String) -> Unit,
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
                        onRemoveDownload = { onRemoveDownload(engine.name) },
                    )
                    HorizontalDivider(color = MaterialTheme.depth.line)
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
    onRemoveDownload: () -> Unit,
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
                val target = if (state.totalBytes > 0L) {
                    (state.bytesFetched.toFloat() / state.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                // Glides between progress reports instead of jumping (#83).
                val fraction by animateFloatAsState(
                    targetValue = target,
                    animationSpec = MarmaladeMotion.slow(),
                    label = "downloadProgress",
                )
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
                val extractTarget = if (state.totalBytes > 0L) {
                    (state.bytesExtracted.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val extractFraction by animateFloatAsState(
                    targetValue = extractTarget,
                    animationSpec = MarmaladeMotion.slow(),
                    label = "extractProgress",
                )
                LinearProgressIndicator(
                    progress = { extractFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is InstallState.Failed -> {
                Text(
                    text = installFailureText(state).ifBlank {
                        stringResource(R.string.onboarding_install_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                // Same pairing as the Engines tab: a kept partial archive can
                // be deleted rather than left holding space the user can't
                // otherwise reclaim.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.partialDownloadBytes > 0L) {
                        TextButton(onClick = onRemoveDownload) {
                            Text(stringResource(R.string.engines_remove_download))
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.onboarding_retry))
                    }
                }
            }
            else -> Unit
        }
    }
}

// -- step 4: ChooseVoiceStep, in VoiceStep.kt -------------------------------

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
