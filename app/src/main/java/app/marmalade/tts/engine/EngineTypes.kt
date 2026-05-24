package app.marmalade.tts.engine

/**
 * Synthesised audio returned by [SherpaEngine.synthesize].
 *
 * `pcm` is 16-bit signed PCM, mono, in little-endian native order.
 * Sherpa-ONNX hands us `FloatArray` in -1..1; we clamp and convert to
 * `ShortArray` here so the system TTS callback path (which expects PCM16)
 * has no further work to do.
 */
data class SynthAudio(val pcm: ShortArray, val sampleRate: Int) {

    // Generated equality so test fixtures can compare audio buffers directly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthAudio) return false
        return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int =
        31 * pcm.contentHashCode() + sampleRate
}

/**
 * Signalled when the user hasn't installed (downloaded) the engine yet.
 * The UI catches this and routes to the Engines screen / onboarding flow.
 *
 * Extends [UnsupportedOperationException] so existing call sites that catch
 * the broader type continue to work; new code should match against this
 * specific subtype to distinguish "not installed" from other failures.
 */
class EngineNotInstalledException(engineName: String) :
    UnsupportedOperationException(
        "Engine '$engineName' is not installed. " +
            "Open Settings → Engines to install it.",
    )
