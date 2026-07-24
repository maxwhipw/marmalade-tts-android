package app.marmalade.tts.data.cloud

import android.content.Context
import android.util.Log
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONException

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   providers()  ◄── memory cache
//     │              ◄── filesDir/cloud/providers.json   (remote copy, if fetched)
//     │              ◄── assets/cloud-providers.json     (bundled fallback)
//     │              + per-provider discovery overlay
//     │                 (filesDir/cloud/voices-<id>.json, written by
//     │                  discoverVoices() from GET {baseUrl}/models?type=tts)
//     ▼
//   sync() ──► VoiceMeta rows for every keyed provider's models × voices
//              ──► voiceDao.replaceEngine("cloud-api-v1", rows)
//
//   Update paths (all data, no app release):
//     - provider added/changed  → refreshProviders() pulls the remote JSON
//     - provider catalog drift  → discoverVoices() re-queries /models
// -----------------------------------------------------------------------------

/**
 * Source of truth for cloud TTS providers and the Room rows of the Cloud
 * API engine.
 *
 * Providers are *data*: a bundled `cloud-providers.json` asset, optionally
 * superseded by the same file fetched from the engines repo
 * ([REMOTE_PROVIDERS_URL]) — so supporting a new provider, or Venice
 * reshuffling its catalog, never requires an app update. Voice lists for
 * providers that support it come from live discovery against the
 * provider's `/models?type=tts`; both fetches are cached under
 * `filesDir/cloud/` so the app works offline with the last-known state.
 */
@Singleton
class CloudProviderStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: CloudJsonHttp,
    private val voiceDao: VoiceMetaDao,
    private val settings: SettingsRepository,
) : CloudProviderDirectory {

    private val cloudDir: File
        get() = File(context.filesDir, "cloud").apply { mkdirs() }

    private val providersCacheFile: File get() = File(cloudDir, "providers.json")
    private fun voicesCacheFile(providerId: String): File =
        File(cloudDir, "voices-$providerId.json")

    /** Merged descriptor+discovery view; invalidated by refresh/discovery. */
    @Volatile
    private var cached: List<CloudProvider>? = null

    /**
     * The provider list with each discovering provider's model list
     * replaced by its cached live-discovery result (when one exists).
     * Cheap after the first call; the first call reads two small local
     * files. Never touches the network.
     */
    fun providers(): List<CloudProvider> {
        cached?.let { return it }
        val base = loadBaseProviders()
        val merged = base.map { provider ->
            if (!provider.discoverVoices) return@map provider
            val cache = voicesCacheFile(provider.id)
            if (!cache.exists()) return@map provider
            val discovered = runCatching {
                CloudProviders.parseDiscoveredModels(cache.readText(), provider.modelExclude)
            }.getOrElse { emptyList() }
            if (discovered.isEmpty()) provider else provider.copy(models = discovered)
        }
        cached = merged
        return merged
    }

    override fun providerById(id: String): CloudProvider? =
        providers().firstOrNull { it.id == id }

    /**
     * Fetch the provider list from the engines repo and cache it. Quietly
     * a no-op on any network/parse failure — the bundled (or previously
     * cached) descriptors stay in force. Returns true when a valid new
     * copy was stored.
     */
    suspend fun refreshProviders(): Boolean = withContext(Dispatchers.IO) {
        val body = try {
            http.get(REMOTE_PROVIDERS_URL, apiKey = null)
        } catch (e: IOException) {
            Log.i(TAG, "provider list refresh skipped: ${e.message}")
            return@withContext false
        }
        try {
            CloudProviders.parse(body) // validate before persisting
        } catch (e: JSONException) {
            Log.w(TAG, "remote provider list malformed; keeping current", e)
            return@withContext false
        }
        providersCacheFile.writeText(body)
        cached = null
        sync()
        true
    }

    /**
     * Query [provider]'s live model+voice list and cache it, then re-sync
     * the Room rows. The key is optional — Venice serves the model list
     * unauthenticated — but is sent when configured.
     */
    suspend fun discoverVoices(provider: CloudProvider): Result<List<CloudModel>> =
        withContext(Dispatchers.IO) {
            val key = settings.cloudApiKeyFor(provider.id).first().ifBlank { null }
            val body = try {
                http.get("${provider.baseUrl}/models?type=tts", key)
            } catch (e: IOException) {
                return@withContext Result.failure(e)
            }
            val models = try {
                CloudProviders.parseDiscoveredModels(body, provider.modelExclude)
            } catch (e: JSONException) {
                return@withContext Result.failure(e)
            }
            if (models.isEmpty()) {
                return@withContext Result.failure(
                    IOException("${provider.displayName} listed no usable TTS models"),
                )
            }
            voicesCacheFile(provider.id).writeText(body)
            cached = null
            sync()
            Result.success(models)
        }

    /**
     * Rebuild the Cloud API engine's Room rows: every (model × voice) of
     * every provider whose key is configured. Providers without a key
     * contribute nothing — their voices only appear once the user
     * configures them, which is what gates them out of the pickers and
     * alias editor. Stale rows (removed voices, unkeyed providers, the
     * CATALOG_VERSION-25 static seed) are dropped by the replace.
     */
    suspend fun sync() {
        val keys = settings.cloudApiKeys.first()
        val rows = providers()
            .filter { !keys[it.id].isNullOrBlank() }
            .flatMap { provider ->
                provider.models.flatMap { model ->
                    model.voices.map { CloudApiVoiceCatalog.voiceMeta(provider, model, it) }
                }
            }
            .distinctBy { it.id }
        voiceDao.replaceEngine(CloudApiVoiceCatalog.ENGINE, rows)
    }

    private fun loadBaseProviders(): List<CloudProvider> {
        providersCacheFile.takeIf { it.exists() }?.let { file ->
            runCatching { return CloudProviders.parse(file.readText()) }
                .onFailure { Log.w(TAG, "cached provider list unreadable; using bundled", it) }
        }
        val bundled = context.assets.open(BUNDLED_ASSET).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        // A malformed bundled asset is a build error — let it throw.
        return CloudProviders.parse(bundled)
    }

    companion object {
        private const val TAG = "CloudProviderStore"
        private const val BUNDLED_ASSET = "cloud-providers.json"

        /** Same document as the bundled asset, updatable without a release. */
        const val REMOTE_PROVIDERS_URL =
            "https://raw.githubusercontent.com/maxwhipw/marmalade-tts-android-engines/main/cloud-providers.json"
    }
}

/**
 * The narrow slice of [CloudProviderStore] the synthesis engine needs
 * (provider lookup for base URLs). An interface so the engine's JVM unit
 * tests can fake it without standing up Context + Room + DataStore.
 */
fun interface CloudProviderDirectory {
    fun providerById(id: String): CloudProvider?
}

/**
 * Injectable seam for the store's GET requests (provider list refresh +
 * voice discovery) so tests serve canned JSON. Mirrors
 * [app.marmalade.tts.engine.api.CloudSpeechHttp].
 */
fun interface CloudJsonHttp {
    /** GET [url], optionally with `Authorization: Bearer` [apiKey]. */
    @Throws(IOException::class)
    fun get(url: String, apiKey: String?): String
}

/** Production [CloudJsonHttp] on HttpURLConnection (no extra deps). */
class UrlCloudJsonHttp @Inject constructor() : CloudJsonHttp {
    override fun get(url: String, apiKey: String?): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            if (apiKey != null) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("GET $url: HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
