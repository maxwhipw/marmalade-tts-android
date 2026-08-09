package app.marmalade.tts.ui.screen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.R
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.cloud.CloudProviderStore
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
//     │                    discovery overlay; refreshed remotely on open,
//     │                    only after the disclaimer is accepted)
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
 * Once the cloud disclaimer is accepted, opening the screen
 * opportunistically refreshes the provider list from the engines repo, so
 * new providers/models arrive without an app update; failures are silent
 * (bundled/cached descriptors stay in force).
 */
@HiltViewModel
class CloudApiViewModel @Inject constructor(
    private val store: CloudProviderStore,
    private val settings: SettingsRepository,
    voiceDao: VoiceMetaDao,
    @ApplicationContext private val appContext: Context,
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

    /**
     * Whether the cloud disclaimer has been accepted, or **null** while the
     * first DataStore read is still in flight. The screen gates on this, so
     * a `false` initial value would flash the disclaimer for one frame at
     * every visit after the first — null renders nothing instead.
     */
    val disclaimerAccepted: StateFlow<Boolean?> = settings.cloudDisclaimerAccepted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Records acceptance, unlocking the configure surface. */
    fun acceptDisclaimer() {
        viewModelScope.launch { settings.acceptCloudDisclaimer() }
    }

    /** Provider ids with a voice discovery in flight. */
    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    /** Last discovery error per provider id; cleared on retry/success. */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _providers.value = store.providers()
            // The remote refresh waits for the cloud disclaimer: the app
            // sends no network request before the user opts in. Resumes
            // immediately when a past session already accepted.
            settings.cloudDisclaimerAccepted.first { it }
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
                        it + (provider.id to (err.message ?: appContext.getString(R.string.engines_cloud_discovery_failed)))
                    }
                },
            )
        }
    }
}
