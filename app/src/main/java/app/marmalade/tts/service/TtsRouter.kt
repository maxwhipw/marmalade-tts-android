package app.marmalade.tts.service

import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.pro.ProEntitlement
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
 * Two entry points reflect the two precedence rules in the call stack:
 *
 * - [resolvePerApp] — strict per-app lookup. Caller checks this BEFORE
 *   honoring the caller-specified voice from `SynthesisRequest.voiceName`,
 *   because the user's explicit "this app gets this voice" mapping should
 *   override the TTS client's default voice (which most clients auto-fill
 *   from system Settings; otherwise per-app mappings would never fire).
 *
 * - [resolveAlias] — per-app then primary, used as the fallback when
 *   neither per-app NOR caller-voice produced a result.
 *
 * Returns null when nothing maps the call; the caller falls through to
 * the engine's default voice with default speed (1.0×) and NONE effect.
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
    private val proEntitlement: ProEntitlement,
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
        resolvePerApp(callerPackage)?.let { return it }

        // 2. Primary fallback.
        val primaryName = settings.primaryAliasName.first() ?: return null
        return aliasDao.findByName(primaryName)
    }

    /**
     * Resolve only the per-app mapping for [callerPackage]. Returns null
     * when there is no mapping, the mapping points at a deleted alias, or
     * the caller package is unknown. No primary fallback — the caller
     * decides whether to honor the request voice next or fall through to
     * [resolveAlias] for the primary path. Defensive on the alias
     * lookup: a mapping pointing at a deleted alias returns null (not
     * the primary) so this method's contract stays "did the user pick
     * something for this exact app?".
     */
    suspend fun resolvePerApp(callerPackage: String?): VoiceAlias? {
        if (callerPackage == null) return null
        // Per-app routing is a Pro feature. Free users (Play flavor
        // without `marmalade_pro`, including refunded users) fall
        // through to the primary alias — same behaviour as if the
        // mapping didn't exist. The rows stay in the DB (so a future
        // re-purchase restores routing seamlessly) but are inert at
        // synth time. F-Droid flavor's [ProEntitlement.isPro] is
        // always true so this is a free check there.
        if (!proEntitlement.isPro.value) return null
        val mapping = mappingDao.findByPackage(callerPackage) ?: return null
        return aliasDao.findByName(mapping.aliasName)
    }
    /**
     * The voice an alias falls back to when its own voice can't be reached,
     * or null when it has none (or the referenced alias has since been
     * deleted — [VoiceAlias.fallbackAliasName] is deliberately not a foreign
     * key, so a dangling reference is expected rather than exceptional).
     */
    suspend fun fallbackVoiceIdFor(alias: VoiceAlias): String? =
        alias.fallbackAliasName?.let { aliasDao.findByName(it) }?.voiceId

}
