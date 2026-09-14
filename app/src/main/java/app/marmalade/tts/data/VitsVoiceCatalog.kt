package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
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

    /**
     * Voice id form the engine parses: `<engine>:<voiceKey>`, where the key is
     * the bare pack id for a single-speaker pack and `<packId>#<sid>` for one
     * speaker of a multi-speaker pack.
     */
    fun voiceId(voiceKey: String): String = "$ENGINE:$voiceKey"

    /** Default voice = the first voice of the engine's default pack. */
    val DEFAULT_VOICE_ID: String =
        voiceId(VoicePackCatalog.voicesForEngine(ENGINE).firstOrNull()?.voiceKey ?: "")

    val voices: List<VoiceMeta> =
        VoicePackCatalog.voicesForEngine(ENGINE).mapIndexed { index, voice ->
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
                sortOrder = index,
            )
        }
}
