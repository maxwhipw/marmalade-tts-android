package app.marmalade.tts.audio

import app.marmalade.tts.data.db.EffectDao

/**
 * Resolves a stored effect reference — an [app.marmalade.tts.data.db.Effect]
 * row id such as `"builtin:cave"` or a custom effect's id — to the playable
 * [EffectBlock] chain that the DSP ([EffectChain.applyChain]) consumes.
 *
 * Null, unknown, or un-decodable ids resolve to the empty chain (dry — no
 * effect), so a stale `alias.effectId` left over from an uninstalled custom
 * effect never blocks playback.
 *
 * Why an interface rather than a bare function: the synth-path callers
 * ([app.marmalade.tts.ui.screen.SpeakViewModel] and the two TTS services)
 * depend on this seam instead of on Room + `org.json` directly. That lets
 * their plain-JVM unit tests substitute a fake without the Robolectric-only
 * `org.json` round-trip — see `EffectBlockJsonTest` for why decode is a no-op
 * stub on the JVM.
 */
interface EffectResolver {
    suspend fun blocksFor(effectId: String?): List<EffectBlock>
}

/**
 * Production [EffectResolver]: looks the row up in Room and decodes its
 * `blocksJson`. A decode failure (corrupt row) degrades to the dry chain
 * rather than crashing synthesis — the persisted JSON is a system boundary,
 * and one bad row shouldn't take down the system-TTS service.
 */
class DefaultEffectResolver(private val effectDao: EffectDao) : EffectResolver {
    override suspend fun blocksFor(effectId: String?): List<EffectBlock> {
        if (effectId == null) return emptyList()
        val row = effectDao.findById(effectId) ?: return emptyList()
        return try {
            EffectBlockJson.decode(row.blocksJson)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
