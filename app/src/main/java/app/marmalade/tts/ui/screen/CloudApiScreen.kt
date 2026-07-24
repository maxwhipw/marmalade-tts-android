package app.marmalade.tts.ui.screen

import androidx.compose.foundation.layout.Arrangement
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
        LazyColumn(
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

    keyDialogFor?.let { provider ->
        ProviderKeyDialog(
            provider = provider,
            keySet = provider.id in keyedIds,
            onSave = { viewModel.setKey(provider, it) },
            onDismiss = { keyDialogFor = null },
        )
    }
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
