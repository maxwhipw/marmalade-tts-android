package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AliasScreen routing strip / AppRoutingSheet — AppRoutingViewModel
//     │
//     ├── reads:  AppAliasMappingDao.getAll() ──► Flow<List<AppAliasMapping>>
//     │             ▲
//     │             │
//     │           Room (table `app_alias_mapping`)
//     │
//     └── writes: AppAliasMappingDao.upsert(mapping) / delete(packageName)
//                   ▲
//                   │
//                 AppRoutingViewModel.saveRouting() — diffs one alias's tick
//                 set against its saved rows: upsert additions, delete removals.
//
//   TtsRouter.resolveAlias(callerPackage)
//     │
//     ▼
//   AppAliasMappingDao.findByPackage(packageName) ──► AppAliasMapping?
//     │
//     └─► VoiceAliasDao.findById(mapping.aliasId) ──► VoiceAlias?
//
//   Note: aliasId is NOT a foreign key. Deleting an alias releases its
//   apps (AliasViewModel.delete), and rows an older version left behind
//   are purged when the routing UI loads (AppAliasMappingDao.deleteOrphans).
//   Until then the router treats such a row as no mapping and falls
//   through to the primary — see TtsRouter for the precedence rules.
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
 * @property aliasId      References [VoiceAlias.id] — the immutable one,
 *                        so renaming an alias no longer orphans every
 *                        mapping that points at it (the bug this column
 *                        was renamed to fix, db v10). **Still not a
 *                        foreign key on purpose**: the alias upsert is a
 *                        REPLACE (delete + insert), so a cascade would
 *                        drop the app's routing on every alias edit.
 *                        Deleting an alias releases its apps explicitly
 *                        instead (GitHub #32).
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
    val aliasId: String,
    val displayName: String?,
    val createdAt: Long,
)
