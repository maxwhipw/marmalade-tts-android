package app.marmalade.tts.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.cloud.CloudProviderStore
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   CloudApiScreen
//     │
//     ├── providers    ◄── CloudProviderStore.providers() (descriptors +
//     │                    discovery overlay; refreshed remotely on open)
//     ├── keyedIds     ◄── SettingsRepository.cloudApiKeys (presence only)
//     ├── voiceCounts  ◄── VoiceMetaDao.getByEngine(cloud) grouped by provider
//     ├── busyIds / errors ◄── per-provider discovery state
//     │
//     └── actions
//          ├── setKey(id, value)  → settings + store.sync() (+ discovery)
//          └── refreshVoices(id)  → store.discoverVoices(provider)
// -----------------------------------------------------------------------------

/**
 * ViewModel for [CloudApiScreen] — the Cloud API engine's configure
 * surface. One row per provider descriptor; a provider becomes active
 * (voices in Room, engine "installed") once its key is saved.
 *
 * Opening the screen opportunistically refreshes the provider list from
 * the engines repo, so new providers/models arrive without an app update;
 * failures are silent (bundled/cached descriptors stay in force).
 */
@HiltViewModel
class CloudApiViewModel @Inject constructor(
    private val store: CloudProviderStore,
    private val settings: SettingsRepository,
    voiceDao: VoiceMetaDao,
) : ViewModel() {

    private val _providers = MutableStateFlow<List<CloudProvider>>(emptyList())
    val providers: StateFlow<List<CloudProvider>> = _providers.asStateFlow()

    /** Provider ids that currently have an API key configured. */
    val keyedIds: StateFlow<Set<String>> = settings.cloudApiKeys
        .map { it.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Synced voice count per provider id (from the Room rows). */
    val voiceCounts: StateFlow<Map<String, Int>> = voiceDao
        .getByEngine(CloudApiVoiceCatalog.ENGINE)
        .map { rows ->
            rows.groupingBy { CloudApiVoiceCatalog.parseVoiceId(it.id)?.providerId ?: "?" }
                .eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Provider ids with a voice discovery in flight. */
    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /** Last discovery error per provider id; cleared on retry/success. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _providers.value = store.providers()
            // Best-effort remote refresh; updates the list if it changed.
            if (store.refreshProviders()) {
                _providers.value = store.providers()
            }
        }
    }

    /**
     * Save (or clear, when blank) a provider's key, resync the engine's
     * voices, and — for discovering providers just gaining a key — kick
     * off live discovery so the static fallback list gets superseded.
     */
    fun setKey(provider: CloudProvider, value: String) {
        viewModelScope.launch {
            settings.setCloudApiKey(provider.id, value)
            store.sync()
            if (value.isNotBlank() && provider.discoverVoices) {
                refreshVoices(provider)
            }
        }
    }

    /** Re-query a provider's live model+voice list. */
    fun refreshVoices(provider: CloudProvider) {
        if (provider.id in _busyIds.value) return
        _busyIds.update { it + provider.id }
        _errors.update { it - provider.id }
        viewModelScope.launch {
            val result = store.discoverVoices(provider)
            _busyIds.update { it - provider.id }
            result.fold(
                onSuccess = { _providers.value = store.providers() },
                onFailure = { err ->
                    _errors.update {
                        it + (provider.id to (err.message ?: "Voice discovery failed"))
                    }
                },
            )
        }
    }
}
