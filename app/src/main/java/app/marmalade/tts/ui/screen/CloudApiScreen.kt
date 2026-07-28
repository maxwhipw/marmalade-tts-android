package app.marmalade.tts.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.data.cloud.CloudProvider

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Engines tab → Cloud voices card → Configure
//     │
//     ▼
//   CloudApiScreen(onBack)
//     │
//     ├── disclaimerAccepted == false → CloudDisclaimerGate, and nothing
//     │     else renders. "I agree" is the only way past it; there is no
//     │     key field behind it to reach by any other route, so acceptance
//     │     always precedes the first byte leaving the device.
//     │
//     ├── one ProviderCard per CloudApiViewModel.providers entry:
//     │     key status + synced voice count + model count
//     │     "Set key" / "Change key" → key dialog → vm.setKey(...)
//     │     "Refresh voices" (discovering providers, keyed) → vm.refreshVoices
//     │
//     └── provider list itself refreshes from the engines repo on open
//         (CloudApiViewModel.init) — adding a provider is a JSON change.
// -----------------------------------------------------------------------------

/**
 * Configure surface for the Cloud API engine: per-provider API keys and
 * voice discovery. Reached from the Cloud voices card on the Engines tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudApiScreen(
    onBack: () -> Unit,
    viewModel: CloudApiViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val keyedIds by viewModel.keyedIds.collectAsStateWithLifecycle()
    val voiceCounts by viewModel.voiceCounts.collectAsStateWithLifecycle()
    val busyIds by viewModel.busyIds.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val disclaimerAccepted by viewModel.disclaimerAccepted.collectAsStateWithLifecycle()

    var keyDialogFor by remember { mutableStateOf<CloudProvider?>(null) }

    Scaffold(
        // Nested-Scaffold inset handoff — see SpeakScreen for the full note.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Cloud voices") },
                windowInsets = WindowInsets(0),
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
        when (disclaimerAccepted) {
            // Still reading DataStore — render nothing rather than guess.
            null -> Unit

            false -> CloudDisclaimerGate(
                onAgree = viewModel::acceptDisclaimer,
                onDecline = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            true -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "intro") {
                    Text(
                        text = "Voices synthesized on a provider's servers — your " +
                            "text is sent over the network per request. Add a key " +
                            "for any provider to use its voices; each provider " +
                            "bills through its own account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(items = providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        keyed = provider.id in keyedIds,
                        voiceCount = voiceCounts[provider.id] ?: 0,
                        busy = provider.id in busyIds,
                        error = errors[provider.id],
                        onSetKey = { keyDialogFor = provider },
                        onRefreshVoices = { viewModel.refreshVoices(provider) },
                    )
                }
            }
        }
    }

    keyDialogFor?.let { provider ->
        ProviderKeyDialog(
            provider = provider,
            keySet = provider.id in keyedIds,
            onSave = { viewModel.setKey(provider, it) },
            onDismiss = { keyDialogFor = null },
        )
    }
}

/**
 * One-time consent gate in front of the cloud configure surface.
 *
 * Shown until `SettingsRepository.cloudDisclaimerAccepted` is true, then
 * never again — the same wording lives in PRIVACY.md, which is where a
 * user goes to re-read it. Agreement is a deliberate button press rather
 * than an inferred one: entering a key is arguably consent by conduct,
 * but "arguably" is not what you want load-bearing when the consequence
 * is a stranger's text reaching a third party.
 *
 * Declining backs out of the screen instead of leaving a dead end. There
 * is no "don't show again" — the choice is agree or leave.
 */
@Composable
private fun CloudDisclaimerGate(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Before you use cloud voices",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))

                DisclaimerPoint(
                    title = "Your text leaves this device",
                    body = "Marmalade's built-in voices run entirely on your " +
                        "phone and send nothing anywhere. Cloud voices are the " +
                        "exception: the text you send them to read aloud is " +
                        "transmitted in full to the provider you choose, along " +
                        "with the voice, model and speed requested, and your " +
                        "API key.",
                )
                DisclaimerPoint(
                    title = "Their rules apply, not ours",
                    body = "What that provider logs, how long it keeps your " +
                        "text, and whether it trains on it are governed by " +
                        "their privacy policy and terms — which you accept " +
                        "directly with them when you create an account.",
                )
                DisclaimerPoint(
                    title = "We are not affiliated with them",
                    body = "Marmalade is independent. We are not endorsed by, " +
                        "sponsored by, or partnered with any provider, we " +
                        "receive nothing from them, and we cannot see or act " +
                        "on your usage — including deletion requests, which " +
                        "have to go to the provider directly.",
                )
                DisclaimerPoint(
                    title = "Other apps are affected too",
                    body = "Marmalade can be your system-wide voice. If you " +
                        "set a cloud voice as your primary, or route an app to " +
                        "one, then text from those apps — ebooks, messages, " +
                        "anything read aloud to you — is also sent to that " +
                        "provider. On-device voices are unaffected.",
                )
                DisclaimerPoint(
                    title = "You pay them, not us",
                    body = "Each provider bills your own account for what you " +
                        "use. Marmalade itself is free and takes no cut.",
                )

                Text(
                    text = "This is also in the privacy policy, linked from " +
                        "Settings → About, where you can read it again at any " +
                        "time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Scroll affordance: on tall-text/small-screen combinations the
            // gate overflows with no visual hint that more points follow.
            // A fade into the background at the clipped edge reads as
            // "continues below"; it disappears once the end is reached.
            if (scrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I understand and agree") }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Not now") }
        }
    }
}

@Composable
private fun DisclaimerPoint(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ProviderCard(
    provider: CloudProvider,
    keyed: Boolean,
    voiceCount: Int,
    busy: Boolean,
    error: String?,
    onSetKey: () -> Unit,
    onRefreshVoices: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(
                        // .size() — see EngineDetailScreen for why not .height().
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            val modelSummary = provider.models.joinToString { it.displayName }
            Text(
                text = when {
                    keyed && voiceCount > 0 ->
                        "$voiceCount voices · $modelSummary"
                    keyed -> "Key configured — syncing voices…"
                    else -> "API key: ${provider.keyHint}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (keyed && provider.discoverVoices) {
                    OutlinedButton(onClick = onRefreshVoices, enabled = !busy) {
                        Text("Refresh voices")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onSetKey) {
                    Text(if (keyed) "Change key" else "Set key")
                }
            }
        }
    }
}

/**
 * Per-provider key dialog. The stored key is never displayed back; the
 * field always starts empty ("Remove key" is the explicit way out).
 */
@Composable
private fun ProviderKeyDialog(
    provider: CloudProvider,
    keySet: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.displayName} API key") },
        text = {
            Column {
                Text(
                    "Get a key at ${provider.keyHint}. It is stored only on " +
                        "this device and sent only to ${provider.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(if (keySet) "New key (replaces current)" else "API key") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    onSave(draft)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (keySet) {
                    TextButton(
                        onClick = {
                            onSave("")
                            onDismiss()
                        },
                    ) { Text("Remove key") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
