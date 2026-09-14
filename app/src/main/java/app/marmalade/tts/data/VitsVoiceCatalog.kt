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
 * with two packs installed and one not, the missing one's row would still be
 * listed. That is the same granularity every engine has today (per-engine, not
 * per-file); per-pack filtering in the picker is tracked for the pack-UI slice.
 * Slice A ships exactly one pack, where the two are equivalent.
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
                // the catalog's copy for the UI + the system-TTS negotiation,
                // and every pack shipped so far renders at 16 kHz.
                sampleRate = SAMPLE_RATE,
                // Upstream single-speaker corpora don't all publish a gender,
                // and we don't infer one.
                gender = null,
                isInstalled = false,
                sortOrder = index,
            )
        }

    /** Sample rate of the `x_low`/`low` tier checkpoints the catalog ships. */
    const val SAMPLE_RATE = 16_000
}
