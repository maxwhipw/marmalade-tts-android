package app.marmalade.tts.engine

/**
 * Synthesised audio returned by [TtsEngine.synthesize].
 *
 * `pcm` is 16-bit signed PCM, mono, in little-endian native order.
 * Engines hand us `FloatArray` in -1..1; we clamp and convert to
 * `ShortArray` here so the system TTS callback path (which expects PCM16)
 * has no further work to do.
 *
 * [resumePoint]: playback may restart at this piece's first frame — it
 * starts a sentence or clause (or at worst a word). True for every piece an
 * engine renders whole; a streaming engine that sends one sentence as several
 * slices (KittenDirect, a cloud WAV stream) sets it on the first slice only,
 * so the Narrator never resumes mid-word after an interruption.
 */
data class SynthAudio(
    val pcm: ShortArray,
    val sampleRate: Int,
    val resumePoint: Boolean = true,
    /**
     * Render time (ms) spent on this audio only because of how it was sent:
     * chunk mode's shorter slices cost extra generator calls (#54). The
     * app's measured RTF leaves it out, so it doesn't depend on the mode.
     */
    val extraRenderMs: Long = 0,
    /**
     * Samples of [pcm] that are an alias's pause between sentences (#96):
     * silence the app added, which the model never rendered. The measured
     * RTF leaves them out, or a long pause would make the engine look fast.
     */
    val addedSilence: Int = 0,
) {

    /** The audio the model rendered, in ms: [pcm] without [addedSilence]. */
    val renderedMs: Double get() = (pcm.size - addedSilence) * 1000.0 / sampleRate

    // Generated equality so test fixtures can compare audio buffers directly
    // (audio only: [resumePoint], [extraRenderMs] and [addedSilence] are hints, not part of the sound).
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
            "Open the Engines tab to install it.",
    )
