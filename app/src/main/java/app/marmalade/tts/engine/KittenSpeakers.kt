package app.marmalade.tts.engine

/**
 * Shared speaker-id → display-name mapping for Kitten v0.8 voices.
 *
 * Kitten Nano and Kitten Mini both bundle the same 8-voice `voices.bin`
 * (same model architecture family, same voice identities), only the
 * acoustic model file differs in parameter count + quantisation. The
 * speaker indices are therefore identical across the two engines —
 * factored out here to avoid duplication.
 *
 * Friendly names match upstream KittenML's `voice_aliases` map:
 *   Bella → expr-voice-2-f   Jasper → expr-voice-2-m
 *   Luna  → expr-voice-3-f   Bruno  → expr-voice-3-m
 *   Rosie → expr-voice-4-f   Hugo   → expr-voice-4-m
 *   Kiki  → expr-voice-5-f   Leo    → expr-voice-5-m
 *
 * Sherpa-ONNX's `voices.bin` orders speakers MALE-FIRST in each pair
 * (see `scripts/kitten-tts/v0_8/generate_voices_bin.py` in the
 * sherpa-onnx repo):
 *   0: expr-voice-2-m   1: expr-voice-2-f
 *   2: expr-voice-3-m   3: expr-voice-3-f
 *   4: expr-voice-4-m   5: expr-voice-4-f
 *   6: expr-voice-5-m   7: expr-voice-5-f
 */
internal object KittenSpeakers {
    val SPEAKER_ID_BY_NAME: Map<String, Int> = mapOf(
        "Jasper" to 0, // expr-voice-2-m
        "Bella" to 1,  // expr-voice-2-f
        "Bruno" to 2,  // expr-voice-3-m
        "Luna" to 3,   // expr-voice-3-f
        "Hugo" to 4,   // expr-voice-4-m
        "Rosie" to 5,  // expr-voice-4-f
        "Leo" to 6,    // expr-voice-5-m
        "Kiki" to 7,   // expr-voice-5-f
    )
}
