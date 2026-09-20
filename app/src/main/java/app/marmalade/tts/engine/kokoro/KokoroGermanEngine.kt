package app.marmalade.tts.engine.kokoro

import android.content.Context
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------------
// KokoroGermanEngine — native German Kokoro (`kokoro-de-v1_0`)
// -----------------------------------------------------------------------------
//
// Thorsten-Voice/Kokoro (Apache-2.0): a German fine-tune of Kokoro-82M v1.0,
// exported to the SAME ONNX graph contract as `kokoro-direct-v1_0` (tokens
// int64 [1,N] / style float32 [1,256] / speed float32 [1] → audio + pred_dur,
// 24 kHz) and quantized QDQ int8. The fine-tune touched the whole acoustic
// stack, so it can never share weights with the multilingual engine — it is a
// separate downloadable engine with its own directory and `voices.bin`.
//
// Everything about running the model — ORT session setup (CPU-EP-only because
// the bundle's model-format.txt starts with "qdq"), the streaming pre-roll
// gate, style-row mmap, tail trim — is inherited unchanged from
// [KokoroDirectEngine]. This subclass only redirects the catalog-shaped seams:
//
//   * one speaker → [speakerIdFor] is always 0;
//   * German-only → [espeakVoiceFor] is always "de", so the base
//     [KokoroDirectEngine.espeakPhonemes] routes every span through
//     [app.marmalade.tts.phonemizer.GermanG2P] (normalizer + override lexicon +
//     tied espeak-de + postprocess + the ʏ→y vocab repair). The bundle ships
//     no lexicon-zh.txt or openjtalk_dic, so the base's zh/ja pipelines never
//     load and never fire (lang is fixed to "de").
//
// Speed is a Tempo time-stretch on the output like the base engine
// (supportsNativeSpeed = false, inherited) — the model's speed tensor is fed
// 1.0.
// -----------------------------------------------------------------------------

@Singleton
class KokoroGermanEngine @Inject constructor(
    @ApplicationContext ctx: Context,
    settings: SettingsRepository,
    sharedEspeak: SharedEspeakData,
) : KokoroDirectEngine(ctx, settings, sharedEspeak) {

    override val engineName: String = KokoroGermanVoiceCatalog.ENGINE
    override val sampleRate: Int = KokoroGermanVoiceCatalog.SAMPLE_RATE

    /** Single speaker: voices.bin holds exactly one style table (row 0). */
    override fun speakerIdFor(voiceKey: String): Int = 0

    /** Always German — this engine has no other language, and no override. */
    override fun espeakVoiceFor(voiceKey: String): String = "de"

    override val warmupVoiceKey: String = KokoroGermanVoiceCatalog.THORSTEN_VOICE_KEY
}
