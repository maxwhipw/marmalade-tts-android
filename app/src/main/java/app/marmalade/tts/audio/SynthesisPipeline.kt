package app.marmalade.tts.audio

import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.preprocessing.EmojiProsody
import app.marmalade.tts.preprocessing.Preprocessor
import app.marmalade.tts.preprocessing.ProsodyApplier

// -----------------------------------------------------------------------------
// Shared synthesis pipeline
// -----------------------------------------------------------------------------
//
//   Three call sites (in-app Speak, the system TTS service, the foreground
//   synthesis service) used to inline their own preprocess/synth/shape
//   chains, which drifted: emoji prosody fired on the two services but not
//   on Synthesizer.speak(), so the same input could produce different
//   audio depending on whether the user pressed the in-app Speak button
//   or sent the text through their system TTS engine.
//
//   This module centralises the canonical chain. Callers resolve their
//   inputs (preprocessing rules, effect preset, engine routing) and pass
//   the engine synthesis call as a function reference. The pipeline does
//   nothing async on its own — callers stay in charge of dispatchers, so
//   the system-TTS runBlocking wrappers and the Synthesizer.speak suspend
//   path both compose cleanly.
//
//   Canonical chain:
//     1. EmojiProsody.detect(rawText)            ──► ProsodyHint
//     2. Preprocessor.apply(rawText, rules)      ──► normalised text
//     3. EmojiProsody.stripEmojis(normalised)    ──► engine-safe text
//     4. synthesize(text, voiceId, speed)        ──► SynthAudio
//     5. ProsodyApplier.apply(pcm, sr, emotion)  ──► emotion-shaped PCM
//     6. EffectChain.apply(pcm, sr, effect)      ──► effect-shaped PCM
//
//   Returns a [PipelineResult] so callers can distinguish "input was
//   blank / emoji-only" (Empty) from a successful synthesis. Engine
//   failures propagate as exceptions — the pipeline doesn't paper over
//   them because each caller logs / surfaces failures differently.
// -----------------------------------------------------------------------------

/** Output of the canonical pipeline. */
sealed class PipelineResult {
    /**
     * Successful synthesis. [pcm] is the final, fully-shaped PCM16 mono
     * buffer; [sampleRate] is the engine's output rate.
     */
    data class Audio(val pcm: ShortArray, val sampleRate: Int) : PipelineResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Audio) return false
            return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
        }
        override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRate
    }

    /**
     * Input collapsed to nothing speakable (blank text, or text that was
     * emoji-only and got stripped). Callers should close their callback
     * cleanly without invoking the engine.
     */
    object Empty : PipelineResult()
}

/**
 * Run the canonical synthesis pipeline. See the module-level comment for
 * the chain. All inputs except [rawText] are pre-resolved by the caller —
 * the pipeline doesn't read DataStore / Room / Settings, so it's cheap to
 * call and free of side effects.
 *
 * @param rawText user text, possibly containing emojis.
 * @param voiceId the engine-prefixed voice id (e.g. `"kokoro:af_bella"`),
 *   passed straight through to [synthesize].
 * @param speed length-scale style; 1.0 = native pace, > 1 = faster.
 * @param enabledRules pre-resolved set of preprocessing rule names.
 * @param effect pre-resolved effect preset (NONE = dry).
 * @param preprocessor the shared [Preprocessor] (DI singleton).
 * @param synthesize the engine call — `(text, voiceId, speed) -> SynthAudio`.
 *   Suspending so callers stay free to dispatch on their own thread.
 */
suspend fun runSynthesisPipeline(
    rawText: String,
    voiceId: String,
    speed: Float,
    enabledRules: Set<String>,
    effect: EffectPreset,
    preprocessor: Preprocessor,
    synthesize: suspend (text: String, voiceId: String, speed: Float) -> SynthAudio,
): PipelineResult {
    if (rawText.isBlank()) return PipelineResult.Empty

    // Emoji prosody is computed off the *raw* text (the emojis are the
    // signal). The text fed to the engine then goes through:
    //   1. User-configured text preprocessing (currency, numbers,
    //      abbreviations, … per Settings → Text preprocessing).
    //   2. Emotion-emoji stripping — a safety net for the emoji in the
    //      EmojiProsody set, in case the user disabled the preprocessing
    //      `emoji` rule.
    val hint = EmojiProsody.detect(rawText)
    val preprocessed = preprocessor.apply(rawText, enabledRules)
    val text = EmojiProsody.stripEmojis(preprocessed)
    if (text.isBlank()) return PipelineResult.Empty

    val audio: SynthAudio = synthesize(text, voiceId, speed)

    val emotionShaped = ProsodyApplier.apply(audio.pcm, audio.sampleRate, hint.emotion)
    // EffectChain is a no-op for NONE (returns the same array unchanged),
    // so the dry path adds no extra allocation.
    val shaped = EffectChain.apply(emotionShaped, audio.sampleRate, effect)

    return PipelineResult.Audio(pcm = shaped, sampleRate = audio.sampleRate)
}
