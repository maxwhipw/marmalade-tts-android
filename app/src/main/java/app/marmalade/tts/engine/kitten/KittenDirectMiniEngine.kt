package app.marmalade.tts.engine.kitten

import android.content.Context
import app.marmalade.tts.data.KittenDirectMiniVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------------
// KittenDirectMiniEngine — the 80M KittenML model on the KittenDirect path.
// -----------------------------------------------------------------------------
//
// Identical inference pipeline to [KittenDirectEngine] (nano, 15M): the Mini
// ONNX has the exact same signature (input_ids/style/speed → waveform/duration)
// and the same per-voice `[400, 256]` style tables. Only three things differ,
// all captured by the overrides below:
//
//   1. Engine name / bundle dir → `kitten-direct-mini-v0_8`.
//   2. Voice catalog → [KittenDirectMiniVoiceCatalog] (same 8 names + order;
//      distinct ENGINE string for routing).
//   3. Speed prior → 1.0 for every voice. Unlike nano (which runs ~25% fast
//      and needs 0.84), Mini's sherpa `speaker_speed_priors` metadata reports
//      all-1.0 — it's correctly paced at speed=1.0, so applying nano's 0.84
//      would make it ~16% too slow.
//
// Everything else (espeak phonemization, chunking, BERT 512-position cap,
// trim, voice-row indexing, the Pocket-style ORT perf stack) is inherited
// unchanged from [KittenDirectEngine].
// -----------------------------------------------------------------------------

@Singleton
class KittenDirectMiniEngine @Inject constructor(
    @ApplicationContext ctx: Context,
    settings: SettingsRepository,
) : KittenDirectEngine(ctx, settings) {

    override val engineName: String = KittenDirectMiniVoiceCatalog.ENGINE

    override val voiceMetas: List<VoiceMeta> = KittenDirectMiniVoiceCatalog.voices

    /** Mini is correctly paced at speed=1.0 — no prior compensation. */
    override fun speedPriorFor(voiceName: String): Float = 1.0f
}
