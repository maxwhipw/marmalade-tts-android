package app.marmalade.tts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   UI / ViewModel
//     │
//     ├── reads:  SettingsRepository.defaultVoiceId  ──► Flow<String>
//     │             │                                       │
//     │             ▼                                       │
//     │           DataStore<Preferences>.data ─ map { it[KEY] ?: FALLBACK }
//     │
//     └── writes: SettingsRepository.setDefaultVoiceId(id)
//                   │
//                   ▼
//                 DataStore.edit { it[KEY] = id }
// -----------------------------------------------------------------------------

/**
 * Persists user settings backed by the `marmalade_settings` DataStore.
 *
 * v0.1 holds a single key — the default voice ID. Keeping this in DataStore
 * (instead of Room) matches the SPEC's split: Room for content (voices,
 * aliases, history), DataStore for preferences. It also matches the CLI's
 * pattern of "voice picker → persist last selection → reuse next session."
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

    companion object {
        // Stable key name — part of the v1.0 public surface per SPEC.md's
        // "settings keys all frozen per semver" line. Don't rename.
        private val KEY_DEFAULT_VOICE_ID = stringPreferencesKey("default_voice_id")
    }
}
