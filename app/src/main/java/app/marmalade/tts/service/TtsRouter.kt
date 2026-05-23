package app.marmalade.tts.service

import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   MarmaladeTtsService.onSynthesizeText (or MarmaladeSynthService.runOne)
//     │
//     │  callerPackage = packageManager.getNameForUid(request.callerUid)
//     │                  (or null for shared-UID system apps / non-IPC paths)
//     ▼
//   TtsRouter.resolveAlias(callerPackage)
//     │
//     ├── if callerPackage != null:
//     │     AppAliasMappingDao.findByPackage(callerPackage) ──► mapping?
//     │       │
//     │       └── VoiceAliasDao.findByName(mapping.aliasName) ──► alias?
//     │             │
//     │             └── if alias != null → return alias  (per-app match)
//     │
//     ├── SettingsRepository.primaryAliasName.first() ──► primaryName?
//     │     │
//     │     └── VoiceAliasDao.findByName(primaryName) ──► alias?
//     │           │
//     │           └── return alias OR null  (primary fallback)
//     │
//     └── if no primary set or primary alias missing → null
//         (caller falls back to engine default voice + 1.0× speed + NONE effect)
// -----------------------------------------------------------------------------

/**
 * Resolves the [VoiceAlias] (voice + speed + effect bundle) that should be
 * used for a TTS request, given the calling app's package name.
 *
 * Order of precedence (most-specific wins):
 *   1. **Per-app mapping** — when `callerPackage` is known and a row exists
 *      in `app_alias_mapping`, and the referenced alias still exists.
 *   2. **Primary alias** — when [SettingsRepository.primaryAliasName] points
 *      at an existing alias.
 *   3. **Null** — caller should fall through to the engine's default voice
 *      with default speed (1.0×) and NONE effect.
 *
 * Note: the caller-specified voice (from `SynthesisRequest.voiceName`)
 * is honored higher up the call stack and never reaches this router —
 * per-app routing is a *fallback* for the no-voice case, not an override.
 *
 * Defensive: if the mapping points at a deleted alias, [resolveAlias] does
 * NOT return null — it falls through to the primary. The mapping itself is
 * not deleted (the user's intent is preserved; the alias might be
 * re-created with the same name later).
 *
 * `@Singleton` so the same instance is shared between [MarmaladeTtsService]
 * (system TTS path) and [MarmaladeSynthService] (foreground playback path).
 * The DAOs and repository are themselves singletons, so this is essentially
 * a stateless function holder — the singleton lifecycle is for Hilt's
 * benefit, not for any cached state.
 */
@Singleton
class TtsRouter @Inject constructor(
    private val mappingDao: AppAliasMappingDao,
    private val aliasDao: VoiceAliasDao,
    private val settings: SettingsRepository,
) {

    /**
     * Resolve the alias to use for a synthesis request.
     *
     * @param callerPackage  Android package name of the calling app, or
     *                       null when the caller cannot be identified
     *                       (shared UID, in-process path like the share
     *                       sheet, etc.).
     * @return the resolved [VoiceAlias], or null when the caller should
     *         fall back to the engine's default voice (no primary set
     *         or primary alias has been deleted).
     */
    suspend fun resolveAlias(callerPackage: String?): VoiceAlias? {
        // 1. Per-app match wins if both the mapping exists AND the
        // referenced alias still exists. Defensive on the alias lookup:
        // an alias deleted out from under the mapping must NOT short-
        // circuit to null — the user's primary is the next-best guess.
        if (callerPackage != null) {
            val mapping = mappingDao.findByPackage(callerPackage)
            if (mapping != null) {
                val perApp = aliasDao.findByName(mapping.aliasName)
                if (perApp != null) return perApp
            }
        }

        // 2. Primary fallback.
        val primaryName = settings.primaryAliasName.first() ?: return null
        return aliasDao.findByName(primaryName)
    }
}
