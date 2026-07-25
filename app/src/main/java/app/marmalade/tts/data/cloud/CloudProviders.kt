package app.marmalade.tts.data.cloud

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   cloud-providers.json (bundled asset; a cached remote copy wins only if
//   its `version` is >= the bundled one — see CloudProviderStore)
//     │
//     ▼
//   CloudProviders.parseDocument(json) ──► CloudProvidersDocument
//     │                                      │
//     │                                      ├── discoverVoices = true →
//     │                                      │     CloudProviderStore queries
//     │                                      │     /models?type=tts and MERGES
//     │                                      │     it into the static models
//     │                                      │     via mergeDiscovered()
//     │                                      │
//     │                                      └── discoverVoices = false →
//     │                                            static `models` used as-is
//     ▼
//   CloudProviderStore.sync() ──► VoiceMeta rows for the cloud engine
//
// The static `models` list is an ALLOWLIST, not a fallback. Discovery only
// refreshes the voice arrays of models already listed; a model the provider
// starts serving that isn't in the descriptor is ignored.
//
// This is deliberate and was learned the hard way. The old shape was a
// substring blocklist (`modelExclude`) over a discovery result that
// *replaced* the static list wholesale. It failed OPEN: Venice grew from 3
// to 11 TTS models, and each new one landed straight in users' voice
// pickers untested. Five of them return MP3 regardless of
// `response_format`, and two more return 48 kHz — all of which the engine
// rejects at synthesis time, i.e. after the user has already picked the
// voice and pressed Speak. Failing closed means a model Venice adds is
// invisible until someone verifies it and adds a descriptor entry, which
// is the correct direction to be wrong in.
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
 *                         carry `model_spec.voices` (Venice does). Discovery
 *                         refreshes the voice arrays of [models]; it cannot
 *                         introduce a model that isn't already listed.
 * @property models        The allowlist: every model this app will speak
 *                         through, with its verified capabilities.
 */
data class CloudProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val keyHint: String,
    val discoverVoices: Boolean,
    val models: List<CloudModel>,
)

/**
 * One TTS model a provider serves, with its voice list and the capabilities
 * that were *measured* against it.
 *
 * @property sampleRate The rate this model actually returns, in Hz. Not a
 *   preference — the wire format is whatever the model emits, and the
 *   system-TTS path has to commit to a rate in `callback.start()` before a
 *   single byte arrives (see MarmaladeTtsService), so it must be declared
 *   ahead of time. [CloudApiEngine] re-checks the real WAV header against
 *   this value and fails loudly on a mismatch, so a wrong number here
 *   surfaces as a clear error rather than audio at the wrong pitch.
 *
 *   Measured for Venice 2026-07-24: Kokoro and xAI 24 kHz, Gradium and
 *   Inworld 48 kHz. Do not guess this field — synthesize one clip and read
 *   the header.
 */
data class CloudModel(
    val id: String,
    val displayName: String,
    val voices: List<String>,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    companion object {
        /** Kokoro's rate; the historical assumption for every cloud model. */
        const val DEFAULT_SAMPLE_RATE = 24_000
    }
}

/**
 * A parsed `cloud-providers.json`, including its schema [version].
 *
 * The version exists so a newer bundled asset can beat a stale cached
 * remote copy — without it, a device that ever fetched the remote
 * descriptor would pin that copy forever and never see a corrected
 * descriptor shipped in an app update.
 */
data class CloudProvidersDocument(
    val version: Int,
    val providers: List<CloudProvider>,
)

object CloudProviders {

    /**
     * Parse a `cloud-providers.json` document. Throws [JSONException] on a
     * malformed document — callers decide whether that means "fall back to
     * the bundled asset" (remote copy) or is fatal (bundled asset broken =
     * programmer error).
     */
    fun parseDocument(json: String): CloudProvidersDocument {
        val root = JSONObject(json)
        val providers = root.getJSONArray("providers")
        return CloudProvidersDocument(
            // A document without a version predates the field; treat it as
            // the oldest possible so any versioned copy supersedes it.
            version = root.optInt("version", 0),
            providers = (0 until providers.length()).map { i ->
                val p = providers.getJSONObject(i)
                CloudProvider(
                    id = p.getString("id"),
                    displayName = p.getString("displayName"),
                    baseUrl = p.getString("baseUrl").trimEnd('/'),
                    keyHint = p.optString("keyHint"),
                    discoverVoices = p.optBoolean("discoverVoices", false),
                    models = parseModels(p.optJSONArray("models")),
                )
            },
        )
    }

    /** Providers only — for callers that don't care about the version. */
    fun parse(json: String): List<CloudProvider> = parseDocument(json).providers

    /**
     * Parse a provider's live `GET /models?type=tts` response (the shape
     * Venice serves, mirrored from the CLI's `list_voices`): a `data`
     * array of `{id, model_spec: {name, voices: [...]}}`. Entries without
     * voices are dropped.
     *
     * This returns everything the provider advertises. Filtering to the
     * allowlist is [mergeDiscovered]'s job — keeping the two apart means
     * the raw response can be cached verbatim and re-filtered when the
     * descriptor changes, without re-hitting the network.
     */
    fun parseDiscoveredModels(json: String): List<CloudModel> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.getJSONObject(i)
            val id = m.optString("id")
            if (id.isBlank()) return@mapNotNull null
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

    /**
     * Join a live-discovery result onto the descriptor's allowlist.
     *
     * Voices come from [discovered] (the provider is authoritative about
     * which voices exist); everything else — crucially [CloudModel.sampleRate]
     * — comes from [allowed], because the provider's `/models` response
     * carries no capability data. A discovered model with no descriptor
     * entry is dropped; an allowed model absent from discovery keeps its
     * static voice list so an unreachable network degrades to the bundled
     * catalog rather than an empty picker.
     */
    fun mergeDiscovered(
        allowed: List<CloudModel>,
        discovered: List<CloudModel>,
    ): List<CloudModel> {
        val byId = discovered.associateBy { it.id }
        return allowed.map { model ->
            val live = byId[model.id] ?: return@map model
            model.copy(
                displayName = live.displayName.ifBlank { model.displayName },
                voices = live.voices,
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
                sampleRate = m.optInt("sampleRate", CloudModel.DEFAULT_SAMPLE_RATE),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
