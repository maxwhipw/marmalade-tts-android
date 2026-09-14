package app.marmalade.tts.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.R
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePack
import app.marmalade.tts.install.VoicePackAction
import app.marmalade.tts.install.VoicePackLanguageGroup
import app.marmalade.tts.install.VoicePackRow
import app.marmalade.tts.install.VoicePackSummary
import app.marmalade.tts.preprocessing.PreprocessingRules
import app.marmalade.tts.ui.components.PackQualityMeter
import app.marmalade.tts.ui.components.languageDisplayName
import app.marmalade.tts.ui.components.packQualityLabelRes
import app.marmalade.tts.ui.onboarding.formatBytes

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
//     ├── reads packGroups     ◄── EngineDetailViewModel.packGroups
//     │   reads packSummary        (pack-based engines only)
//     │
//     ├── descriptor           ◄── EngineCatalog.byName(engineName)  (static)
//     │
//     └── actions
//          ├── toggleRule(name, on)   → viewModel.toggleRule(name, on)
//          ├── resetRules()           → viewModel.resetRules()
//          ├── install pack           → viewModel.installPack(packId)
//          ├── remove pack            → confirm dialog →
//          │                            viewModel.uninstallPack(packId)
//          └── back arrow             → onBack() (pops back stack)
//
//   Body layout (top → bottom):
//     0. Voice packs — pack-based engines only (VITS Marmalade). One section
//        per language, one row per pack: name, voice count, quality grade,
//        download size and an install/remove action. This is the only surface
//        that installs a pack other than the default one the engine card's
//        Install button fetches.
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
    val packGroups by viewModel.packGroups.collectAsStateWithLifecycle()
    val packSummary by viewModel.packSummary.collectAsStateWithLifecycle()

    /** The pack whose removal is waiting on the confirm dialog. */
    var pendingPackUninstall by remember { mutableStateOf<VoicePack?>(null) }

    // Re-probe the packs each time the screen becomes the active destination,
    // so a pack installed here, then removed from Android's own app-storage
    // screen, doesn't keep reading as installed.
    if (descriptor?.isPackBased == true) {
        LaunchedEffect(Unit) { viewModel.refreshPacks() }
    }

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

            // Pack-based engines lead with their packs: on VITS Marmalade the
            // engine itself is just a runtime, and choosing which language to
            // download is the decision the user came here to make.
            if (descriptor.isPackBased) {
                VoicePacksSection(
                    groups = packGroups,
                    summary = packSummary,
                    onInstall = viewModel::installPack,
                    onUninstall = { pendingPackUninstall = it },
                )

                HorizontalDivider()
            }

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

    pendingPackUninstall?.let { pack ->
        PackUninstallConfirmDialog(
            pack = pack,
            onConfirm = {
                viewModel.uninstallPack(pack.id)
                pendingPackUninstall = null
            },
            onDismiss = { pendingPackUninstall = null },
        )
    }
}

/**
 * The per-pack management list: one section per language, one row per pack.
 *
 * Rendered in the screen's existing scrolling [Column] rather than a
 * LazyColumn — nesting a lazy list inside a vertical scroll throws, and the
 * catalog is a handful of packs, not a feed.
 */
@Composable
private fun VoicePacksSection(
    groups: List<VoicePackLanguageGroup>,
    summary: VoicePackSummary,
    onInstall: (String) -> Unit,
    onUninstall: (VoicePack) -> Unit,
) {
    DetailSectionHeader(stringResource(R.string.engine_packs))

    Text(
        text = stringResource(R.string.engine_packs_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Text(
        text = packSummaryLine(summary),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    for (group in groups) {
        Text(
            text = languageDisplayName(group.languageCode),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .semantics { heading() }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        for (row in group.rows) {
            VoicePackRowView(
                row = row,
                onInstall = { onInstall(row.pack.id) },
                onUninstall = { onUninstall(row.pack) },
            )
        }
    }
}

/**
 * "9 voice packs · 6 languages · 2 of 9 installed" — the same counts the
 * engine card carries, so the two surfaces can't tell different stories about
 * what the user actually has.
 */
@Composable
fun packSummaryLine(summary: VoicePackSummary): String {
    val packs = pluralStringResource(
        R.plurals.engine_packs_count,
        summary.packCount,
        summary.packCount,
    )
    val head = stringResource(R.string.engine_packs_summary, packs, summary.languageCount)
    val installed = if (summary.installedCount == 0) {
        stringResource(R.string.engine_packs_none_installed)
    } else {
        stringResource(
            R.string.engine_packs_installed_of,
            summary.installedCount,
            summary.packCount,
        )
    }
    return "$head · $installed"
}

/**
 * One pack row: name, what you get, the honest quality grade, and the single
 * action its state allows. The grade sits on the row rather than behind a
 * "more info" tap because it is part of the download decision — Norwegian NVCC
 * ships knowing it sounds rough, and the label has to say so before the user
 * spends 70 MB on it.
 */
@Composable
private fun VoicePackRowView(
    row: VoicePackRow,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.pack.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.engine_pack_row_summary,
                        pluralStringResource(
                            R.plurals.engine_pack_voice_count,
                            row.voiceCount,
                            row.voiceCount,
                        ),
                        formatBytes(row.pack.archive.sizeBytes),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PackQualityMeter(quality = row.pack.quality)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(packQualityLabelRes(row.pack.quality)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            PackActionButton(row = row, onInstall = onInstall, onUninstall = onUninstall)
        }

        // Progress mirrors the engine card's strip: determinate while we know
        // the byte count, indeterminate when we don't.
        if (row.isBusy) {
            Spacer(Modifier.height(6.dp))
            val fraction = row.progress
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = packProgressLabel(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }

        row.failureReason?.let { reason ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
    }
}

/** "Downloading · 4.2 MB / 56.9 MB" / "Installing · …", same copy as the card. */
@Composable
private fun packProgressLabel(row: VoicePackRow): String = when (val state = row.state) {
    is InstallState.Downloading -> stringResource(
        R.string.engines_downloading_progress,
        formatBytes(state.bytesFetched),
        formatBytes(if (state.totalBytes > 0L) state.totalBytes else row.pack.archive.sizeBytes),
    )
    is InstallState.Extracting -> stringResource(
        R.string.engines_installing_progress,
        formatBytes(state.bytesExtracted),
        formatBytes(if (state.totalBytes > 0L) state.totalBytes else row.pack.installedSizeBytes),
    )
    else -> ""
}

/**
 * The row's one action. Busy rows get a spinner instead of a button — the same
 * rule the engine card follows, so there is never a tappable Install while a
 * download of the same thing is running.
 */
@Composable
private fun PackActionButton(
    row: VoicePackRow,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val action = row.action
    if (action == null) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            // .size(), not .height() — see ActionRow in EnginesScreen: an
            // unconstrained width draws the spinner ~2x its declared height.
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
        }
        return
    }
    when (action) {
        VoicePackAction.UNINSTALL -> OutlinedButton(onClick = onUninstall) {
            Text(stringResource(R.string.engines_uninstall))
        }
        VoicePackAction.INSTALL -> Button(onClick = onInstall) {
            Text(stringResource(R.string.engines_install))
        }
        VoicePackAction.RETRY -> Button(onClick = onInstall) {
            Text(stringResource(R.string.engines_retry))
        }
        VoicePackAction.REINSTALL -> Button(onClick = onInstall) {
            Text(stringResource(R.string.engines_reinstall))
        }
        VoicePackAction.UPDATE -> Button(onClick = onInstall) {
            Text(stringResource(R.string.engines_update))
        }
    }
}

@Composable
private fun PackUninstallConfirmDialog(
    pack: VoicePack,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.engines_uninstall_confirm_title, pack.displayName))
        },
        text = { Text(stringResource(R.string.engine_pack_uninstall_confirm_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.engines_uninstall)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.engines_cancel)) }
        },
    )
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
            text = stringResource(descriptor.descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(descriptor.licenseSummaryRes),
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

