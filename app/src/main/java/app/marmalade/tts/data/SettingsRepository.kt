package app.marmalade.tts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.perf.KittenRtfMeasurement
import app.marmalade.tts.preprocessing.EngineProfiles
import app.marmalade.tts.service.KeepaliveMode
import app.marmalade.tts.ui.theme.ThemePreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   UI / ViewModel
//     │
//     ├── reads:  SettingsRepository.<flag>  ──► Flow<T>
//     │             │                              │
//     │             ▼                              │
//     │           DataStore<Preferences>.data ─ map { it[KEY] ?: FALLBACK }
//     │
//     └── writes: SettingsRepository.set<Flag>(value)
//                   │
//                   ▼
//                 DataStore.edit { it[KEY] = value }
//
//   Per-engine preprocessing rule sets:
//     enabledRules(engine)        ──► Flow<Set<String>>
//        falls back to EngineProfiles.defaultsFor(engine) when unset.
//     setEnabledRules(engine, …)  ──► CSV-joined string stored under
//                                       "preprocessing_rules_<engine>"
// -----------------------------------------------------------------------------

/**
 * Persists user settings backed by the `marmalade_settings` DataStore.
 *
 * Keeping these in DataStore (instead of Room) matches the SPEC's split:
 * Room for content (voices, aliases, history), DataStore for preferences.
 *
 * The fallback voice is [KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID]
 * (`kokoro-direct-v1_0:af_bella`) — the recommended, release-installable
 * default. We don't seed DataStore on first launch; the Flow's map applies
 * the fallback transparently until the user picks something explicit.
 */
@Singleton
open class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Emits the persisted default voice ID, falling back to
     * [KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID] when nothing is stored.
     *
     * Cold Flow — every collector triggers a fresh read; downstream
     * `stateIn(...)` in ViewModels caches the latest value.
     *
     * `open` so JVM unit tests can subclass with an in-memory backing
     * store instead of standing up a real DataStore. (Hilt is fine with
     * the open modifier — final isn't required for `@Inject` providers.)
     */
    open val defaultVoiceId: Flow<String> = dataStore.data.map { prefs ->
        // Default to Kitten (Bella): it's baked into the APK, so it's the one
        // engine guaranteed present on a fresh install — even fully offline,
        // before any download. A Kokoro default would fail the first synth on
        // a fresh offline install because Kokoro isn't installed yet. (The old
        // comment here predates Kitten Direct + baking: the current Kitten is a
        // real direct-ORT engine, not the retired sherpa Kitten Mini.)
        prefs[KEY_DEFAULT_VOICE_ID] ?: KittenDirectVoiceCatalog.DEFAULT_VOICE_ID
    }

    /**
     * Persists [id] as the new default voice. Suspending because DataStore's
     * edit is async; callers should run from a coroutine scope.
     */
    open suspend fun setDefaultVoiceId(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_VOICE_ID] = id
        }
    }

    /**
     * True once the user has completed (or dismissed) the onboarding flow.
     *
     * Defaults to `false` for fresh installs — `AppRoot` reads this to
     * decide whether to route to the onboarding wizard or straight to the
     * Speak screen. Flipped to `true` exactly once, on the last step of
     * onboarding (even if the user chose to install zero engines — they
     * can install later from the Engines tab).
     */
    open val onboarded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDED] ?: false
    }

    /**
     * Marks onboarding as complete. Should be called from the final
     * onboarding step's "Continue" button handler. Idempotent — calling
     * it on an already-onboarded user is a successful no-op.
     */
    open suspend fun setOnboarded(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDED] = value
        }
    }

    /**
     * Whether the baked default engine (Kitten) has been seeded from the APK
     * assets. Seeding runs exactly once (first run) — see
     * `MarmaladeTtsApplication`. Gating on this rather than "is Kitten
     * installed" means a user who deliberately uninstalls Kitten to reclaim
     * space isn't re-seeded on the next launch.
     */
    open val bakedDefaultSeeded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BAKED_DEFAULT_SEEDED] ?: false
    }

    /** Records that the baked default engine seed has run. Idempotent. */
    open suspend fun setBakedDefaultSeeded(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BAKED_DEFAULT_SEEDED] = value
        }
    }

    /**
     * [app.marmalade.tts.data.db.VoiceAlias.id] of the alias designated
     * **primary** — the fallback voice/effect/speed bundle used when no
     * per-app rule matches, or when an external caller asks marmalade to
     * speak without naming a voice.
     *
     * Emits `null` when no primary is set (fresh install, or the primary
     * was deleted). Callers must treat a stale pointer as null and
     * re-derive; the id is deliberately not a foreign key.
     *
     * Held an alias *name* before db v10, which meant a rename silently
     * detached the primary unless every write site remembered to retarget
     * it. Ids do not need remembering.
     *
     * Stored as a user preference rather than a column on `VoiceAlias` so
     * the alias table has no "is primary" flag to keep consistent.
     */
    open val primaryAliasId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_ALIAS_ID]
    }

    /**
     * Set (or clear) the primary alias pointer.
     *
     * Passing `null` removes the key from the DataStore rather than
     * storing an empty string — keeps the "no primary set" state
     * indistinguishable from a fresh install on read.
     */
    open suspend fun setPrimaryAliasId(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(KEY_PRIMARY_ALIAS_ID)
            } else {
                prefs[KEY_PRIMARY_ALIAS_ID] = value
            }
        }
    }

    /**
     * Emits the persisted theme preset name (one of [ThemePreset.name]),
     * falling back to [ThemePreset.MARMALADE]'s name when nothing is stored.
     *
     * Stored as a string (not an int ordinal) so reordering the enum in a
     * future release doesn't silently re-skin existing installs.
     *
     * Default changed from SYSTEM → MARMALADE in v0.1.10: the orange palette
     * is the brand identity, and System (Material You) felt arbitrary as
     * the default for new installs.
     */
    open val themePreset: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_PRESET] ?: ThemePreset.MARMALADE.name
    }

    /** Persists [value] (a [ThemePreset.name]) as the new theme preset. */
    open suspend fun setThemePreset(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_PRESET] = value
        }
    }

    /**
     * Emits the user's dark-mode override: `"system"` / `"light"` / `"dark"`.
     * Defaults to `"system"` (follow the OS).
     *
     * Decoupled from [themePreset] — preset is the color *family* (Marmalade,
     * Midnight, etc.) and themeMode is the *brightness* (light/dark/auto).
     * Resolved at the theme-application site via
     * [app.marmalade.tts.ui.theme.resolveThemeIsDark].
     */
    open val themeMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
    }

    /** Persists the dark-mode override. Caller is responsible for normalising the input. */
    open suspend fun setThemeMode(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = value
        }
    }

    /**
     * True when the synth engine should stay loaded between utterances
     * (faster speak-onset, costs ~40 MB resident memory). Default is
     * `true` — matches the v0.1.x behavior pre-toggle.
     *
     * TODO(v0.2): the `false` branch (release engine between utterances)
     * is not yet wired into [KittenEngine] / [KokoroEngine]. The Settings
     * UI toggle was *hidden in v0.1.16* because the engines ignored this
     * flag — surfacing a dead control as a real Switch was misleading.
     * The storage stays in place (cheap, doesn't hurt, keeps the key
     * stable across the rename) so we can re-add the UI without a
     * migration when the engines start honouring it. See STUBS.md.
     */
    open val keepEngineLoaded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_KEEP_LOADED] ?: true
    }

    /** Persists the keep-engine-loaded toggle. */
    open suspend fun setKeepEngineLoaded(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_KEEP_LOADED] = value
        }
    }

    /**
     * P-K — engine keepalive mode. Drives [MarmaladeKeepaliveService]:
     *
     *   - [KeepaliveMode.Off]:        no service
     *   - [KeepaliveMode.Smart]:      service starts after each synth,
     *                                 stops after 10 minutes of idle
     *   - [KeepaliveMode.Persistent]: service runs forever (opt-in)
     *
     * Defaults to [KeepaliveMode.Smart] — sub-realtime synth on Pocket
     * (per P-V) makes the 1-second cold load the dominant felt-latency
     * for second-and-later synths, so smoothing it over a 10-minute
     * window is a near-pure win at zero long-term cost.
     */
    open val keepaliveMode: Flow<KeepaliveMode> = dataStore.data.map { prefs ->
        prefs[KEY_KEEPALIVE_MODE]?.let { name ->
            KeepaliveMode.entries.firstOrNull { it.name == name }
        } ?: KeepaliveMode.Smart
    }

    /** Persist the keepalive mode. */
    open suspend fun setKeepaliveMode(mode: KeepaliveMode) {
        dataStore.edit { prefs ->
            prefs[KEY_KEEPALIVE_MODE] = mode.name
        }
    }

    /**
     * The catalog version that the on-device DB was last seeded against.
     *
     * `MarmaladeTtsApplication.onCreate` compares this against the current
     * [app.marmalade.tts.MarmaladeTtsApplication.CATALOG_VERSION] constant
     * and re-runs `voiceDao.upsertAll(...)` for every catalog whose
     * version increased. That keeps users on the same DB-row set as the
     * shipped app, without ever destructively wiping the table — Room's
     * REPLACE-on-conflict upsert just refreshes the metadata.
     *
     * Stored as an integer rather than a hash so the upgrade path is
     * monotonic and trivially comparable; bump by 1 in
     * `MarmaladeTtsApplication` every time a catalog's voice rows change
     * (add/remove a voice, change a language code, etc.).
     *
     * Defaults to 0 — fresh installs go through the same code path as an
     * upgrade and pick up the latest catalog on first run.
     */
    open val catalogVersion: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_CATALOG_VERSION] ?: 0
    }

    /** Persist [version] as the latest catalog version seeded into Room. */
    open suspend fun setCatalogVersion(version: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_CATALOG_VERSION] = version
        }
    }

    /**
     * The set of enabled text-preprocessing rule names for [engineName].
     *
     * Stored as a comma-separated string under
     * `preprocessing_rules_<engineName>` to avoid pulling in
     * kotlinx-serialization just for this — rule names are stable
     * identifiers from `PreprocessingRules.ALL` (no commas, no special
     * chars to escape).
     *
     * Falls back to [EngineProfiles.defaultsFor] when nothing is
     * persisted yet. That gives a fresh install the CLI's per-engine
     * defaults without us having to seed DataStore on first launch
     * (the seed would race with first-read collectors in the UI).
     *
     * The empty-string case is treated as "user disabled everything"
     * (returns the empty set), distinct from "nothing stored yet"
     * (returns the defaults).
     */
    open fun enabledRules(engineName: String): Flow<Set<String>> {
        val key = preprocessingKeyFor(engineName)
        return dataStore.data.map { prefs ->
            val stored = prefs[key]
            when {
                stored == null -> EngineProfiles.defaultsFor(engineName)
                stored.isEmpty() -> emptySet()
                else -> stored.split(",").filter { it.isNotBlank() }.toSet()
            }
        }
    }

    /**
     * Persist [rules] as the enabled set for [engineName]. Stored
     * verbatim as a comma-joined string; the empty set is stored as
     * `""` (not removed) so the "user disabled everything" state
     * round-trips correctly.
     */
    open suspend fun setEnabledRules(engineName: String, rules: Set<String>) {
        val key = preprocessingKeyFor(engineName)
        dataStore.edit { prefs ->
            prefs[key] = rules.joinToString(",")
        }
    }

    private fun preprocessingKeyFor(engineName: String) =
        stringPreferencesKey("preprocessing_rules_$engineName")

    /**
     * Per-device ONNX-Runtime intra-op thread count. `null` means "auto" —
     * the engine calls [app.marmalade.tts.perf.CpuClusterDetector] at init
     * time to pick a value matching the CPU's perf-cluster size. Manual
     * overrides are stored as a positive Int; values ≤ 0 or unset map to
     * auto.
     *
     * Exposed in Settings because the auto heuristic can be wrong on
     * exotic CPU topologies (some Samsungs split a "prime" core off the
     * usual big cluster; some MediaTek chips have three-tier hierarchies).
     * Users with reliable benchmarks can pin their preferred value.
     */
    open val intraOpThreads: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[KEY_INTRA_OP_THREADS]?.takeIf { it > 0 }
    }

    /** Persist a manual thread count, or pass `null` to revert to auto. */
    open suspend fun setIntraOpThreads(count: Int?) {
        dataStore.edit { prefs ->
            if (count == null || count <= 0) {
                prefs.remove(KEY_INTRA_OP_THREADS)
            } else {
                prefs[KEY_INTRA_OP_THREADS] = count
            }
        }
    }

    /**
     * Whether developer-only engines (currently the Pocket clean-reference
     * diagnostic build) are surfaced in the engine lists. They stay in the
     * catalog for A/B comparison but are hidden from normal users by default.
     *
     * Defaults to [BuildConfig.DEBUG]: shown in debug builds (so we keep the
     * A/B affordance), hidden in release builds where they're an explicit
     * opt-in. The user can flip it either way; routing never consults this
     * flag, so an alias already pointing at a hidden engine keeps
     * synthesizing even while the engine is hidden.
     */
    open val showDeveloperEngines: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_DEVELOPER_ENGINES] ?: BuildConfig.DEBUG
    }

    /**
     * Per-provider API keys for the Cloud API engine, keyed by
     * [app.marmalade.tts.data.cloud.CloudProvider.id]. Empty map = engine
     * unconfigured, which the app treats as "not installed"
     * ([app.marmalade.tts.engine.api.CloudApiEngine.isInstalled]).
     *
     * The single-provider era stored one key under `cloud_api_key`; it is
     * read here as Venice's key until a per-provider write replaces it.
     *
     * Plain DataStore is acceptable here: the file is app-private and
     * `android:allowBackup="false"` keeps it out of device backups.
     * Never log these values.
     */
    open val cloudApiKeys: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val keys = mutableMapOf<String, String>()
        for ((key, value) in prefs.asMap()) {
            val providerId = key.name.removePrefix(CLOUD_API_KEY_PREFIX)
            if (providerId != key.name && value is String && value.isNotBlank()) {
                keys[providerId] = value
            }
        }
        val legacy = prefs[KEY_CLOUD_API_KEY]
        if (!legacy.isNullOrBlank() && LEGACY_CLOUD_PROVIDER !in keys) {
            keys[LEGACY_CLOUD_PROVIDER] = legacy
        }
        keys
    }

    /** One provider's Cloud API key; blank when unconfigured. */
    open fun cloudApiKeyFor(providerId: String): Flow<String> =
        cloudApiKeys.map { it[providerId] ?: "" }

    /** True when at least one cloud provider has a key. */
    open val anyCloudApiKeySet: Flow<Boolean> = cloudApiKeys.map { it.isNotEmpty() }

    /**
     * True once the user has read and accepted the cloud disclaimer — that
     * choosing a cloud voice sends the text to be spoken to a third party.
     *
     * Gates the Cloud voices configure screen: no key can be entered until
     * this is true, so acceptance always precedes the first byte leaving the
     * device. Deliberately **not** cleared when the last key is removed —
     * this records "was told," not "is using," and re-prompting someone who
     * already read it trains people to click through disclaimers.
     */
    open val cloudDisclaimerAccepted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_DISCLAIMER_ACCEPTED] ?: false
    }

    /** Records acceptance of the cloud disclaimer. One-way by design. */
    open suspend fun acceptCloudDisclaimer() {
        dataStore.edit { prefs ->
            prefs[KEY_CLOUD_DISCLAIMER_ACCEPTED] = true
        }
    }

    /** Persist [providerId]'s Cloud API key; blank removes it. */
    open suspend fun setCloudApiKey(providerId: String, value: String) {
        dataStore.edit { prefs ->
            val trimmed = value.trim()
            val key = stringPreferencesKey("$CLOUD_API_KEY_PREFIX$providerId")
            if (trimmed.isEmpty()) prefs.remove(key) else prefs[key] = trimmed
            // Any per-provider write for Venice supersedes the
            // single-provider era's key — drop it so remove actually removes.
            if (providerId == LEGACY_CLOUD_PROVIDER) prefs.remove(KEY_CLOUD_API_KEY)
        }
    }

    /**
     * Measured time-to-first-audio samples, keyed by
     * [app.marmalade.tts.data.latencyKeyFor]. Values are the raw
     * milliseconds of the last few runs, newest last.
     *
     * Stored as one comma-separated string per key rather than a structured
     * blob: Preferences has no list type, the window is ~10 small integers,
     * and a malformed entry should degrade to "no data" rather than take the
     * settings file down — hence the lenient parse.
     */
    open val latencySamples: Flow<Map<String, List<Int>>> = dataStore.data.map { prefs ->
        val out = mutableMapOf<String, List<Int>>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name.removePrefix(LATENCY_PREFIX)
            if (name == key.name || value !is String) continue
            val samples = value.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (samples.isNotEmpty()) out[name] = samples
        }
        out
    }

    /** Append one sample for [key], keeping at most [keep] most-recent. */
    open suspend fun recordLatencySample(key: String, millis: Int, keep: Int) {
        dataStore.edit { prefs ->
            val prefKey = stringPreferencesKey("$LATENCY_PREFIX$key")
            val existing = prefs[prefKey]
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                .orEmpty()
            prefs[prefKey] = (existing + millis).takeLast(keep).joinToString(",")
        }
    }

    /**
     * Take one of [key]'s [perWeek] sampling slots for [week], returning
     * false when the week's budget is already spent.
     *
     * Claim-and-check in a single `edit` so two concurrent utterances can't
     * both read the same count and both spend the last slot; DataStore
     * serialises the transform.
     */
    open suspend fun claimLatencyQuota(key: String, week: Long, perWeek: Int): Boolean {
        var claimed = false
        dataStore.edit { prefs ->
            val prefKey = stringPreferencesKey("$LATENCY_QUOTA_PREFIX$key")
            val parts = prefs[prefKey]?.split(':')
            val storedWeek = parts?.getOrNull(0)?.toLongOrNull()
            val used = if (storedWeek == week) parts?.getOrNull(1)?.toIntOrNull() ?: 0 else 0
            if (used >= perWeek) return@edit
            claimed = true
            prefs[prefKey] = "$week:${used + 1}"
        }
        return claimed
    }

    /**
     * The one-off Kitten benchmark result, or null when it hasn't run on
     * this install yet. Written exactly once by
     * [app.marmalade.tts.perf.DeviceCapability]; the timestamp rides along
     * so a future app version can decide a stored measurement is too old to
     * trust (e.g. after an engine bundle rev changes the workload).
     *
     * Both halves are read as one atomic snapshot — a value without a
     * timestamp is treated as absent rather than half-trusted.
     */
    open val kittenRtfMeasurement: Flow<KittenRtfMeasurement?> = dataStore.data.map { prefs ->
        val rtf = prefs[KEY_KITTEN_RTF] ?: return@map null
        val at = prefs[KEY_KITTEN_RTF_AT] ?: return@map null
        KittenRtfMeasurement(rtf = rtf, measuredAtMillis = at)
    }

    /** Records the device benchmark. Overwrites any previous measurement. */
    open suspend fun setKittenRtfMeasurement(rtf: Double, measuredAtMillis: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_KITTEN_RTF] = rtf
            prefs[KEY_KITTEN_RTF_AT] = measuredAtMillis
        }
    }

    /** Persist the show-developer-engines toggle. */
    open suspend fun setShowDeveloperEngines(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_DEVELOPER_ENGINES] = value
        }
    }

    companion object {
        // Stable key names — part of the v1.0 public surface per SPEC.md's
        // "settings keys all frozen per semver" line. Don't rename.
        private val KEY_DEFAULT_VOICE_ID = stringPreferencesKey("default_voice_id")

        // Onboarding completion flag — part of the same stability contract.
        // Removing this would cause every existing install to re-run
        // onboarding after an update; renaming would do the same.
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_BAKED_DEFAULT_SEEDED = booleanPreferencesKey("baked_default_seeded")

        // Theme preset name (stored as ThemePreset.name, not ordinal — see
        // [themePreset] kdoc).
        private val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")

        // Dark-mode override: "system" / "light" / "dark". Decoupled from
        // theme preset so the user can pick "Marmalade + always dark" etc.
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // Keep-engine-loaded toggle; default true preserves pre-toggle behavior.
        private val KEY_KEEP_LOADED = booleanPreferencesKey("keep_engine_loaded")

        // Primary alias pointer (nullable). Null is encoded as
        // "key absent from DataStore" — see [setPrimaryAliasId].
        // Points at VoiceAlias.id since db v10. Deliberately a NEW key
        // rather than a reused one: the old "primary_alias_name" holds a
        // display name, and a stale name silently resolving to nothing is
        // exactly the failure v10 set out to remove. DataStore survives the
        // destructive DB step, so the old key may still be on disk; nothing
        // reads it, and a pointer at a wiped alias is already handled as
        // "no primary" by every caller.
        private val KEY_PRIMARY_ALIAS_ID = stringPreferencesKey("primary_alias_id")

        // Last-seeded catalog version. v0.1.19 introduces this so that
        // expanding a voice catalog (e.g. Kokoro 11 → 53 voices for
        // multi-lang) automatically re-seeds existing installs whose DB
        // still has the pre-expansion rows.
        private val KEY_CATALOG_VERSION = intPreferencesKey("catalog_version")

        // Manual ONNX-Runtime intra-op thread count. Absent / ≤0 ⇒ auto-
        // detect per CPU cluster. Cross-device: hardcoded 6 fits Tensor
        // G3 well but spills onto efficiency cores on Snapdragon Gen 2/3
        // and Exynos 2400, regressing 5-20% per ORT bench.
        private val KEY_INTRA_OP_THREADS = intPreferencesKey("intra_op_threads")

        // Show the legacy sherpa engines in the engine lists. Absent ⇒
        // BuildConfig.DEBUG (shown in debug, hidden in release). v0.3.0-alpha.10.Z.
        private val KEY_SHOW_DEVELOPER_ENGINES = booleanPreferencesKey("show_developer_engines")

        // Cloud API engine keys, one pref per provider:
        // "cloud_api_key_<providerId>". No provider keyed => unconfigured.
        private const val CLOUD_API_KEY_PREFIX = "cloud_api_key_"

        // Cloud disclaimer acceptance. Absent ⇒ never shown, so existing
        // installs that already keyed a provider see it once on next visit.
        private val KEY_CLOUD_DISCLAIMER_ACCEPTED =
            booleanPreferencesKey("cloud_disclaimer_accepted")

        // Measured latency window, one key per model. Purely derived data —
        // safe to drop, and it repopulates itself from use.
        private const val LATENCY_PREFIX = "voice_latency_"

        // "<weekIndex>:<samplesTaken>" per model. A stale week is simply
        // overwritten, so nothing needs sweeping. Deliberately NOT prefixed
        // with LATENCY_PREFIX — [latencySamples] scans by prefix, and a
        // quota key nested under it would be picked up as a sample list.
        private const val LATENCY_QUOTA_PREFIX = "latency_quota_"

        // Single-provider era key (v0.3.0-alpha.11 dev builds); read as
        // Venice's key until a per-provider write supersedes it.
        private val KEY_CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        private const val LEGACY_CLOUD_PROVIDER = "venice"

        // Device benchmark (Feature P). Derived data — safe to drop; the
        // benchmark just re-runs. Deliberately NOT semver-frozen for that
        // reason.
        private val KEY_KITTEN_RTF = doublePreferencesKey("device_kitten_rtf")
        private val KEY_KITTEN_RTF_AT = longPreferencesKey("device_kitten_rtf_at")

        // P-K — keepalive mode, stored as KeepaliveMode.name string.
        // Absent ⇒ Smart (default). Stable key; semver-protected.
        private val KEY_KEEPALIVE_MODE = stringPreferencesKey("keepalive_mode")
    }
}
