package app.marmalade.tts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.marmalade.tts.preprocessing.EngineProfiles
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
 * The fallback voice — `kitten:Bella` — is the documented default in
 * [KittenVoiceCatalog.DEFAULT_VOICE_ID]. We don't seed DataStore on first
 * launch; the Flow's map applies the fallback transparently until the
 * user picks something explicit.
 */
@Singleton
open class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * Emits the persisted default voice ID, falling back to
     * [KittenVoiceCatalog.DEFAULT_VOICE_ID] when nothing is stored.
     *
     * Cold Flow — every collector triggers a fresh read; downstream
     * `stateIn(...)` in ViewModels caches the latest value.
     *
     * `open` so JVM unit tests can subclass with an in-memory backing
     * store instead of standing up a real DataStore. (Hilt is fine with
     * the open modifier — final isn't required for `@Inject` providers.)
     */
    open val defaultVoiceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_VOICE_ID] ?: KittenVoiceCatalog.DEFAULT_VOICE_ID
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
     * can install later from Settings → Engines).
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
     * Name of the alias currently designated as **primary** — the
     * default-fallback voice/effect/speed bundle used when no per-app
     * rule matches, or when an external caller asks marmalade to speak
     * without specifying a voice.
     *
     * Emits `null` when no primary has been set yet (fresh install,
     * or the previously-primary alias was deleted). The alias name is
     * the PK on the `voice_alias` table; callers must treat a stale
     * pointer (alias deleted out from under us) as null and re-derive.
     *
     * Stored as a *user preference*, not as a column on `VoiceAlias`,
     * so the alias schema stays unchanged and deleting an alias is a
     * simple `dao.delete(name)` plus a defensive clear here.
     */
    open val primaryAliasName: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_ALIAS]
    }

    /**
     * Set (or clear) the primary alias pointer.
     *
     * Passing `null` removes the key from the DataStore rather than
     * storing an empty string — keeps the "no primary set" state
     * indistinguishable from a fresh install on read.
     */
    open suspend fun setPrimaryAliasName(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(KEY_PRIMARY_ALIAS)
            } else {
                prefs[KEY_PRIMARY_ALIAS] = value
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

    companion object {
        // Stable key names — part of the v1.0 public surface per SPEC.md's
        // "settings keys all frozen per semver" line. Don't rename.
        private val KEY_DEFAULT_VOICE_ID = stringPreferencesKey("default_voice_id")

        // Onboarding completion flag — part of the same stability contract.
        // Removing this would cause every existing install to re-run
        // onboarding after an update; renaming would do the same.
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")

        // Theme preset name (stored as ThemePreset.name, not ordinal — see
        // [themePreset] kdoc).
        private val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")

        // Dark-mode override: "system" / "light" / "dark". Decoupled from
        // theme preset so the user can pick "Marmalade + always dark" etc.
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // Keep-engine-loaded toggle; default true preserves pre-toggle behavior.
        private val KEY_KEEP_LOADED = booleanPreferencesKey("keep_engine_loaded")

        // Primary alias pointer (nullable). Null is encoded as
        // "key absent from DataStore" — see [setPrimaryAliasName].
        private val KEY_PRIMARY_ALIAS = stringPreferencesKey("primary_alias_name")

        // Last-seeded catalog version. v0.1.19 introduces this so that
        // expanding KokoroVoiceCatalog (11 → 53 voices for multi-lang)
        // automatically re-seeds existing installs whose DB still has the
        // pre-expansion rows.
        private val KEY_CATALOG_VERSION = intPreferencesKey("catalog_version")
    }
}
