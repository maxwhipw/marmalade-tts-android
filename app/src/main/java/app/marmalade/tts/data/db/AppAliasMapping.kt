package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AppMappingsScreen / AppMappingsViewModel
//     │
//     ├── reads:  AppAliasMappingDao.getAll() ──► Flow<List<AppAliasMapping>>
//     │             ▲
//     │             │
//     │           Room (table `app_alias_mapping`)
//     │
//     └── writes: AppAliasMappingDao.upsert(mapping) / delete(packageName)
//                   ▲
//                   │
//                 AppMappingsViewModel.save(...) — after picking app + alias.
//
//   TtsRouter.resolveAlias(callerPackage)
//     │
//     ▼
//   AppAliasMappingDao.findByPackage(packageName) ──► AppAliasMapping?
//     │
//     └─► VoiceAliasDao.findByName(mapping.aliasName) ──► VoiceAlias?
//
//   Note: aliasName is NOT a foreign key. If the referenced alias is
//   deleted, the mapping intentionally lingers and the router silently
//   falls through to the primary alias on the next synth — see
//   TtsRouter for the precedence rules.
// -----------------------------------------------------------------------------

/**
 * Persisted mapping from a calling app's package name → user-saved voice alias.
 *
 * When an external app invokes the system TTS service without specifying
 * a voice, [MarmaladeTtsService] reads `SynthesisRequest.callerUid`,
 * resolves to a package name via `PackageManager.getNameForUid`, and looks
 * up this table to decide which alias to use. Falls through to the primary
 * alias (see [app.marmalade.tts.data.SettingsRepository.primaryAliasName])
 * when no mapping matches, or when the referenced alias has been deleted.
 *
 * The primary key is the [packageName] — one mapping per app. Editing a
 * mapping is just an `upsert` with the same PK.
 *
 * @property packageName  Android package name, e.g. `"com.spotify.music"`.
 *                        The PK; matches the value returned by
 *                        `PackageManager.getNameForUid(uid)`.
 * @property aliasName    References [VoiceAlias.name]. **Not a foreign key
 *                        on purpose** — cascade-delete behavior is wrong
 *                        here. If the alias is deleted, we want the router
 *                        to fall through to the primary, not silently drop
 *                        the user's per-app preference.
 * @property displayName  Cached human-readable app label from
 *                        `PackageManager.getApplicationLabel(...)` at
 *                        mapping-creation time. Nullable because the
 *                        lookup may legitimately return null (uninstalled
 *                        app, or shared UID); the mappings screen
 *                        refreshes this when it opens.
 * @property createdAt    Epoch ms — used only for stable list ordering
 *                        in the UI.
 */
@Entity(tableName = "app_alias_mapping")
data class AppAliasMapping(
    @PrimaryKey val packageName: String,
    val aliasName: String,
    val displayName: String?,
    val createdAt: Long,
)
