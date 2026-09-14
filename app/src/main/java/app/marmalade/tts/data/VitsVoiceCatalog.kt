package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.VoicePackCatalog

/**
 * Voices for the pack-based VITS Marmalade engine
 * ([app.marmalade.tts.engine.vits.VitsDirectEngine]).
 *
 * One voice per [app.marmalade.tts.install.VoicePack]: a pack *is* a voice
 * (single-speaker checkpoint), so the pack catalog is the voice list and there
 * is nothing to hardcode here. Room rows are seeded from this like every other
 * engine's static catalog; the voice picker then hides the rows whose engine
 * isn't installed, so an uninstalled pack's language never shows up as
 * pickable.
 *
 * Caveat this inherits from the seeding design: the row exists as soon as the
 * *engine* verifies as installed, which is true once ANY pack is present — so
 * every catalog pack's voice is listed even when only one pack is on disk, and
 * picking an absent one fails with `EngineNotInstalledException`. That is the
 * same granularity every engine has today (per-engine, not per-file), and it
 * is why the engine stays developer-only: per-pack filtering in the picker is
 * a prerequisite for promotion and is tracked in STUBS.md.
 */
object VitsVoiceCatalog {

    const val ENGINE = VoicePackCatalog.VITS_MARMALADE_ENGINE

    /** Voice id form the engine parses: `<engine>:<packId>`. */
    fun voiceId(packId: String): String = "$ENGINE:$packId"

    /** Default voice = the engine's default pack. */
    val DEFAULT_VOICE_ID: String =
        voiceId(VoicePackCatalog.defaultPackFor(ENGINE)?.id ?: "")

    val voices: List<VoiceMeta> =
        VoicePackCatalog.forEngine(ENGINE).mapIndexed { index, pack ->
            VoiceMeta(
                id = voiceId(pack.id),
                engine = ENGINE,
                displayName = pack.displayName,
                languageCode = pack.languageCode,
                // The pack config is the authority at synthesis time (and the
                // returned SynthAudio always carries the real rate); this is
                // the catalog's copy for the UI + the system-TTS negotiation.
                // Per-pack because the tiers differ: 16 kHz for x_low/low,
                // 22.05 kHz for medium/high.
                sampleRate = pack.sampleRate,
                // Only where the corpus documents it; never inferred from the
                // speaker's name or from the audio.
                gender = pack.gender,
                isInstalled = false,
                sortOrder = index,
            )
        }
}
