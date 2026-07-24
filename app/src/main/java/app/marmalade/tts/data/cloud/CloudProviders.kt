package app.marmalade.tts.data.cloud

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   cloud-providers.json (bundled asset, overridable by a cached remote copy)
//     │
//     ▼
//   CloudProviders.parse(json) ──► List<CloudProvider>
//     │                              │
//     │                              ├── discoverVoices = true →
//     │                              │     CloudProviderStore queries the
//     │                              │     provider's /models?type=tts and
//     │                              │     parses it via parseDiscoveredModels
//     │                              │     (static `models` = offline fallback)
//     │                              │
//     │                              └── discoverVoices = false →
//     │                                    static `models` list is authoritative
//     ▼
//   CloudProviderStore.sync() ──► VoiceMeta rows for the cloud engine
// -----------------------------------------------------------------------------

/**
 * One OpenAI-compatible hosted TTS provider, described as *data* — adding
 * or updating a provider is a JSON edit (remotely fetchable), never an
 * app release. All descriptors share the same wire protocol
 * (`POST {baseUrl}/audio/speech`, Bearer-key auth, `response_format:
 * "wav"`); what varies per provider is only this metadata.
 *
 * @property id            Stable key — appears inside cloud voice ids and
 *                         per-provider DataStore key names. Never rename.
 * @property displayName   User-facing provider name.
 * @property baseUrl       API root, no trailing slash (e.g.
 *                         `https://api.venice.ai/api/v1`).
 * @property keyHint       Where the user gets an API key, shown in the
 *                         configure dialog.
 * @property discoverVoices True when the provider serves a public
 *                         `GET {baseUrl}/models?type=tts` whose entries
 *                         carry `model_spec.voices` (Venice does). The
 *                         live list then supersedes [models].
 * @property modelExclude  Substring blocklist applied to model ids, for
 *                         models that break the wire contract — e.g.
 *                         Venice's `tts-qwen3-*` ignores `response_format`
 *                         and returns MP3, which the engine can't play.
 * @property models        Static model+voice list: the whole catalog for
 *                         non-discovering providers, and the offline
 *                         fallback for discovering ones.
 */
data class CloudProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val keyHint: String,
    val discoverVoices: Boolean,
    val modelExclude: List<String>,
    val models: List<CloudModel>,
)

/** One TTS model a provider serves, with its voice list. */
data class CloudModel(
    val id: String,
    val displayName: String,
    val voices: List<String>,
)

object CloudProviders {

    /**
     * Parse a `cloud-providers.json` document. Throws [JSONException] on a
     * malformed document — callers decide whether that means "fall back to
     * the bundled asset" (remote copy) or is fatal (bundled asset broken =
     * programmer error).
     */
    fun parse(json: String): List<CloudProvider> {
        val root = JSONObject(json)
        val providers = root.getJSONArray("providers")
        return (0 until providers.length()).map { i ->
            val p = providers.getJSONObject(i)
            CloudProvider(
                id = p.getString("id"),
                displayName = p.getString("displayName"),
                baseUrl = p.getString("baseUrl").trimEnd('/'),
                keyHint = p.optString("keyHint"),
                discoverVoices = p.optBoolean("discoverVoices", false),
                modelExclude = p.optJSONArray("modelExclude").toStringList(),
                models = parseModels(p.optJSONArray("models")),
            )
        }
    }

    /**
     * Parse a provider's live `GET /models?type=tts` response (the shape
     * Venice serves, mirrored from the CLI's `list_voices`): a `data`
     * array of `{id, model_spec: {name, voices: [...]}}`. Entries without
     * voices, or whose id matches [CloudProvider.modelExclude], are
     * dropped.
     */
    fun parseDiscoveredModels(json: String, exclude: List<String>): List<CloudModel> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.getJSONObject(i)
            val id = m.optString("id")
            if (id.isBlank() || exclude.any { id.contains(it) }) return@mapNotNull null
            val spec = m.optJSONObject("model_spec")
            val voices = spec?.optJSONArray("voices").toStringList()
            if (voices.isEmpty()) return@mapNotNull null
            CloudModel(
                id = id,
                displayName = spec?.optString("name").orEmpty().ifBlank { id },
                voices = voices,
            )
        }
    }

    private fun parseModels(arr: JSONArray?): List<CloudModel> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            CloudModel(
                id = m.getString("id"),
                displayName = m.optString("displayName").ifBlank { m.getString("id") },
                voices = m.optJSONArray("voices").toStringList(),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
