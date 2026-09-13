package app.marmalade.tts.audio

import kotlin.math.abs

// -----------------------------------------------------------------------------
// TailTrim — amplitude-aware trailing-silence trim for raw model renders.
// -----------------------------------------------------------------------------
//
// Data flow:
//   ONNX session.run → FloatArray waveform (24 kHz mono, full scale ±1.0)
//     → TailTrim.trimTail(raw)            ← this file
//       → floatToPcm16 → SilenceCompressor (safety net) → AudioTrack / WAV
//
// Why this exists:
//   Both direct-ORT engines used to chop a blind 5000 samples (208 ms) off
//   the end of EVERY chunk to remove the decoder's ring-out. Measured
//   2026-09-12 on desktop fp32 Kokoro, the raw tail silence is only
//   179–431 ms and it SHRINKS with speed: at 2.0x the blind chop sits on
//   the cliff, and at 2.5–3.0x it eats 27–28 ms of real speech — a clipped
//   final fricative on every sentence. GitHub issue #8.
//
// The recipe is a faithful port of the CLI kokoro daemon
// (~/coding/marmalade-tts-cli/daemon/kokoro-daemon.py, `_last_loud_end` +
// `TAIL_KEEP`): walk back from the end across everything below the silence
// level, then keep TAIL_KEEP frames of that silence as the natural
// inter-sentence gap. No sample at or above the threshold is ever removed,
// so a quiet fricative decay survives — that is the whole point of the
// amplitude test.
// -----------------------------------------------------------------------------

object TailTrim {

    /**
     * `|sample| < SILENCE_LEVEL` counts as silence. 0.006 full scale is the
     * CLI daemon's `_SIL_LEVEL`; it is also what [SilenceCompressor] already
     * uses (200 / 32767 ≈ 0.0061 on the int16 side), so both stages agree on
     * what "quiet" means.
     */
    const val SILENCE_LEVEL: Float = 0.006f

    /** Samples per model frame at 24 kHz — the CLI daemon's `FRAME`. */
    const val FRAME_SAMPLES: Int = 600

    /** Frames of tail silence kept, the CLI's `TAIL_KEEP` (~75 ms). */
    const val TAIL_KEEP_FRAMES: Int = 3

    /** Tail silence kept after the last speech sample (~75 ms at 24 kHz). */
    const val TAIL_KEEP_SAMPLES: Int = TAIL_KEEP_FRAMES * FRAME_SAMPLES

    /**
     * [wav] with its trailing near-silence shortened to [keepSamples].
     *
     * Returns the input unchanged when there is nothing to do: the tail
     * silence is already shorter than [keepSamples], or the chunk is
     * entirely below [silenceLevel]. The all-quiet case is deliberately a
     * pass-through — we know nothing about such a chunk, and deleting it
     * would be the pathological version of the bug this replaces. (The CLI
     * daemon reaches the same outcome via its `start >= end` guard.)
     *
     * Never removes a sample at or above [silenceLevel], and never removes
     * more than the detected trailing silence run.
     */
    fun trimTail(
        wav: FloatArray,
        silenceLevel: Float = SILENCE_LEVEL,
        keepSamples: Int = TAIL_KEEP_SAMPLES,
    ): FloatArray {
        var lastLoudEnd = wav.size
        while (lastLoudEnd > 0 && abs(wav[lastLoudEnd - 1]) < silenceLevel) lastLoudEnd--
        if (lastLoudEnd == 0) return wav          // all quiet — leave it alone
        val end = lastLoudEnd + keepSamples
        if (end >= wav.size) return wav           // tail already short enough
        return wav.copyOf(end)
    }
}
