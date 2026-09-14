package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.PackVoice
import app.marmalade.tts.install.VoicePack
import app.marmalade.tts.install.VoicePackCatalog

/**
 * Voices for the pack-based VITS Marmalade engine
 * ([app.marmalade.tts.engine.vits.VitsDirectEngine]).
 *
 * One row per [app.marmalade.tts.install.PackVoice]: a single-speaker pack is
 * one voice, and a multi-speaker pack is one voice per speaker. The pack
 * catalog is therefore the voice list and there is nothing to hardcode here.
 * Room rows are seeded from this like every other engine's static catalog; the
 * voice picker then hides the rows whose engine isn't installed, so an
 * uninstalled engine's languages never show up as pickable.
 *
 * Rows are seeded for every catalog pack whether or not its tarball is on
 * disk — like every other engine's static catalog. What makes the rows honest
 * is the picker's filter: [app.marmalade.tts.data.isVoiceAvailable] requires
 * the voice's own pack to verify, not just its engine (which verifies as soon
 * as ANY pack is present). Without that, all 25 voices appeared the moment one
 * pack was installed and picking an absent one failed at synth with
 * `EngineNotInstalledException`.
 */
object VitsVoiceCatalog {

    const val ENGINE = VoicePackCatalog.VITS_MARMALADE_ENGINE

    /**
     * Voice id form the engine parses: `<engine>:<voiceKey>`, where the key is
     * the bare pack id for a single-speaker pack and `<packId>#<sid>` for one
     * speaker of a multi-speaker pack.
     */
    fun voiceId(voiceKey: String): String = "$ENGINE:$voiceKey"

    /**
     * The pack id inside a VITS voice id, or null if [voiceId] isn't one.
     *
     * `vits-marmalade-v1:kk-issai-high#1` → `kk-issai-high`. Parsed rather than
     * looked up so the picker's filter keeps working for a voice id stored by an
     * older build whose pack the catalog no longer lists (the row is then simply
     * unavailable, which is the honest answer).
     */
    /**
     * The catalog voice behind a VITS voice id, or null when [voiceId] isn't a
     * VITS id or names a pack/speaker this build doesn't ship. Lets a UI row
     * reach pack facts — the quality grade above all — from a Room row.
     */
    fun packVoiceOf(voiceId: String): PackVoice? {
        val key = voiceId.substringAfter("$ENGINE:", missingDelimiterValue = "")
        if (key.isEmpty()) return null
        return VoicePackCatalog.voiceByKey(ENGINE, key)
    }

    fun packIdOf(voiceId: String): String? {
        val key = voiceId.substringAfter("$ENGINE:", missingDelimiterValue = "")
        if (key.isEmpty()) return null
        return key.substringBefore(VoicePack.SPEAKER_SEPARATOR).takeIf { it.isNotEmpty() }
    }

    /** Default voice = the first voice of the engine's default pack. */
    val DEFAULT_VOICE_ID: String =
        voiceId(VoicePackCatalog.voicesForEngine(ENGINE).firstOrNull()?.voiceKey ?: "")

    /**
     * Rank of each voice key in a **language-grouped** ordering: languages in
     * the order they first appear in the pack catalog, packs and speakers in
     * catalog order within a language.
     *
     * This is what [VoiceMeta.sortOrder] carries, and it is why the picker's
     * Ukrainian voices sit together even though the two Ukrainian packs are at
     * opposite ends of the catalog list (the x_low one shipped first, the medium
     * one last). The row ORDER of [voices] itself stays catalog order — it is
     * the seeded-row identity the catalog test pins — so only the sort key moves.
     */
    private val languageGroupedRank: Map<String, Int> =
        VoicePackCatalog.voicesForEngine(ENGINE)
            .withIndex()
            .sortedBy { (index, voice) ->
                // Stable two-level key packed into one comparable: the language's
                // first-appearance position dominates, catalog position breaks
                // ties inside a language.
                val languageRank = VoicePackCatalog.voicesForEngine(ENGINE)
                    .indexOfFirst { it.languageCode == voice.languageCode }
                languageRank * 1_000 + index
            }
            .mapIndexed { rank, (_, voice) -> voice.voiceKey to rank }
            .toMap()

    val voices: List<VoiceMeta> =
        VoicePackCatalog.voicesForEngine(ENGINE).map { voice ->
            VoiceMeta(
                id = voiceId(voice.voiceKey),
                engine = ENGINE,
                displayName = voice.displayName,
                languageCode = voice.languageCode,
                // The pack config is the authority at synthesis time (and the
                // returned SynthAudio always carries the real rate); this is
                // the catalog's copy for the UI + the system-TTS negotiation.
                // Per-pack because the tiers differ: 16 kHz for x_low/low,
                // 22.05 kHz for medium/high.
                sampleRate = voice.sampleRate,
                // Only where the corpus documents it; never inferred from the
                // speaker's name or from the audio.
                gender = voice.gender,
                isInstalled = false,
                // Language-grouped, not catalog position — see
                // [languageGroupedRank].
                sortOrder = languageGroupedRank.getValue(voice.voiceKey),
            )
        }
}
