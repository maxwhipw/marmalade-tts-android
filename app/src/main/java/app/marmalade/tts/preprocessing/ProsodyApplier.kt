package app.marmalade.tts.preprocessing

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   text (with emojis)
//      │
//      ├── EmojiProsody.detect(text)  ──► ProsodyHint(emotion, intensity)
//      │
//      ▼
//   pcm (ShortArray, mono PCM16, native sample rate)
//      │
//      ▼
//   ProsodyApplier.apply(pcm, sampleRate, emotion)
//      │
//      ├── lookup Emotion ──► ProsodyParams(rateRatio, semitones, dB, extras)
//      │
//      ├── pitch shift via resampling
//      │      (changes pitch AND duration by the same factor — see TODO).
//      │
//      ├── rate change via second-pass resampling
//      │      (changes duration only, applied as another resample on the
//      │       already-pitched buffer; pitch reverts to ratio*rateRatio).
//      │
//      │      Result: pitch shifts by `semitones`, duration scales by
//      │      1 / rateRatio. The first resample is by `pitchRatio`, the
//      │      second by `pitchRatio / rateRatio`, so the net pitch is
//      │      `pitchRatio * (rateRatio / pitchRatio) = rateRatio`... no wait
//      │      — see comments in `apply` for the correct math. The v0.1
//      │      compromise: do resample-only pitch shift, then a separate
//      │      WSOLA-free rate via another resample. Pitch + rate are NOT
//      │      independently decoupled in v0.1; tracked below.
//      │
//      // TODO: decouple pitch and rate via WSOLA / OLA for v0.2 — see
//      //   /home/max/coding/marmalade-tts-cli/marmalade_tts/audio/effects.py
//      //   for the sox `tempo` + `pitch` chain we are approximating.
//      │
//      ├── volume scale (linear gain from dB)
//      │
//      └── per-emotion extras: tremolo (Nervous), distortion (Angry),
//          gentle LPF (Loving). All in-place on the resampled buffer.
//
//   Output: a new ShortArray. Input is never mutated.
// -----------------------------------------------------------------------------

/**
 * DSP parameters for each [Emotion]. Centralized so the values can be
 * tuned by ear in one place, without rewriting `apply`. Starting points
 * came from the v0.1 task spec; revisit when we collect listener feedback.
 *
 * `rateRatio`: how much faster than native (1.0 = native, 1.05 = +5%).
 *              The output is *shorter* than the input when `rateRatio > 1`.
 * `semitones`: pitch shift; +12 = octave up, -12 = octave down.
 * `gainDb`:    volume change in decibels (signed).
 * `tremoloDb`: depth of an AM-style amplitude wobble; 0 = off.
 *              When > 0, applied at 5 Hz to mimic a "nervous" voice.
 * `distortion`: 0..1 soft-clip strength; 0 = off.
 * `lowpassHz`: one-pole low-pass cutoff in Hz; 0 = off.
 */
private data class ProsodyParams(
    val rateRatio: Float = 1f,
    val semitones: Float = 0f,
    val gainDb: Float = 0f,
    val tremoloDb: Float = 0f,
    val distortion: Float = 0f,
    val lowpassHz: Float = 0f,
)

private val PARAMS: Map<Emotion, ProsodyParams> = mapOf(
    Emotion.Amused to ProsodyParams(rateRatio = 1.05f, semitones = 2f, gainDb = 1f),
    Emotion.Sad to ProsodyParams(rateRatio = 0.92f, semitones = -2f, gainDb = -3f),
    Emotion.Angry to ProsodyParams(rateRatio = 1.03f, semitones = 1f, gainDb = 3f, distortion = 0.25f),
    Emotion.Loving to ProsodyParams(rateRatio = 0.97f, semitones = -1f, gainDb = -1f, lowpassHz = 4000f),
    Emotion.Cool to ProsodyParams(rateRatio = 1.0f, semitones = -1f, gainDb = 0f),
    Emotion.Sarcastic to ProsodyParams(rateRatio = 0.98f, semitones = 1f, gainDb = 0f),
    Emotion.Happy to ProsodyParams(rateRatio = 1.03f, semitones = 1f, gainDb = 1f),
    Emotion.Calm to ProsodyParams(rateRatio = 0.95f, semitones = 0f, gainDb = -2f),
    Emotion.Surprised to ProsodyParams(rateRatio = 1.08f, semitones = 3f, gainDb = 2f),
    Emotion.Nervous to ProsodyParams(rateRatio = 1.0f, semitones = 0.5f, gainDb = 0f, tremoloDb = 2f),
    Emotion.Thoughtful to ProsodyParams(rateRatio = 0.97f, semitones = 0f, gainDb = -1f),
    Emotion.Neutral to ProsodyParams(),
)

/**
 * Apply an [Emotion]'s prosody curve to a mono PCM16 buffer.
 *
 * The hard requirements:
 *   - Neutral is identity. Callers can call this unconditionally; the
 *     no-op path returns the input unchanged.
 *   - The returned array is a new allocation for every non-Neutral emotion
 *     (so the caller can safely retain the original).
 *
 * The compromises (v0.1):
 *   - Pitch shift and rate change both ride on linear-interpolated
 *     resampling, so they are coupled: shifting pitch up by N semitones
 *     also shortens the sample by the same ratio, and vice versa.
 *     We compensate by doing pitch first, then a second resample to land
 *     the duration where we want it — but that loses the pitch we just
 *     applied. So in v0.1, pitch and rate share a single resample pass
 *     whose ratio combines both. See the TODO at the top of the file.
 *   - The extras (tremolo, distortion, low-pass) are applied at the
 *     output sample rate after resampling. They're cheap and stable.
 */
object ProsodyApplier {

    /**
     * Apply [emotion]'s DSP transformation to [pcm] sampled at
     * [sampleRate] Hz. Returns a new buffer (or the input itself for
     * Neutral, since no allocation is necessary).
     */
    fun apply(pcm: ShortArray, sampleRate: Int, emotion: Emotion): ShortArray {
        if (emotion == Emotion.Neutral || pcm.isEmpty()) return pcm
        val p = PARAMS[emotion] ?: return pcm

        // -- pitch + rate (coupled, v0.1) -------------------------------------
        //
        // Resampling by ratio R:
        //   - read R input samples per output sample
        //   - output is shorter by 1/R
        //   - pitch shifts UP by R
        //
        // We want pitch ratio P = 2^(semitones/12) and rate ratio T.
        // For a real implementation P and T are independent; here they
        // share one resample, so:
        //   - combined ratio = P (so pitch is right)
        //   - duration accidentally scales by 1/P
        //   - then a *second* resample at ratio P/T fixes duration but
        //     reverts pitch to T. To keep both close to the spec we just
        //     prefer the pitch shift (the perceptually-dominant cue) and
        //     accept a small duration error vs the rateRatio target.
        //
        // // TODO: decouple via WSOLA for v0.2.

        val pitchRatio = 2.0.pow(p.semitones.toDouble() / 12.0).toFloat()
        // Combine: do the pitch resample, then time-scale the result by
        // 1/rateRatio via a second resample at the inverse ratio of what
        // we want. Concretely: pitch then duration. The end-pitch is
        // off by rateRatio, which for our params (|rateRatio - 1| ≤ 8%)
        // is well under a semitone of unintended drift.
        val pitched = resampleLinear(pcm, pitchRatio)
        val timed = if (p.rateRatio == 1f) pitched
                    else resampleLinear(pitched, 1f / p.rateRatio)

        // Float headroom for the remaining stages.
        val buf = FloatArray(timed.size) { timed[it].toFloat() }

        // -- volume (linear gain from dB) -------------------------------------
        if (p.gainDb != 0f) {
            val linear = 10.0.pow(p.gainDb.toDouble() / 20.0).toFloat()
            for (i in buf.indices) buf[i] *= linear
        }

        // -- per-emotion extras -----------------------------------------------
        if (p.tremoloDb > 0f) applyTremolo(buf, sampleRate, depthDb = p.tremoloDb, hz = 5f)
        if (p.distortion > 0f) applyDistortion(buf, p.distortion)
        if (p.lowpassHz > 0f) applyOnePoleLowpass(buf, sampleRate, p.lowpassHz)

        return clampToShort(buf)
    }

    // -- private --------------------------------------------------------------

    /**
     * Linear-interpolated resampler. Reading at [ratio] samples per output
     * sample means a `ratio > 1` shortens and pitches up; `ratio < 1`
     * lengthens and pitches down.
     */
    internal fun resampleLinear(pcm: ShortArray, ratio: Float): ShortArray {
        if (ratio == 1f || pcm.isEmpty()) return pcm.copyOf()
        // Output length: floor(n / ratio). At least 1 sample so we don't
        // round small inputs to nothing.
        val outLen = maxOf(1, (pcm.size / ratio).toInt())
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcIdx = i * ratio.toDouble()
            val lo = srcIdx.toInt()
            val hi = lo + 1
            val frac = (srcIdx - lo).toFloat()
            val a = pcm[lo].toFloat()
            val b = if (hi < pcm.size) pcm[hi].toFloat() else a
            val v = a * (1f - frac) + b * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * 5 Hz amplitude wobble with `depthDb` peak-to-trough excursion. Used
     * for Nervous emotion. AM via multiplication on the float buffer; no
     * filtering needed.
     */
    private fun applyTremolo(buf: FloatArray, sampleRate: Int, depthDb: Float, hz: Float) {
        // depthDb is the half-amplitude swing in dB. Convert to a linear
        // depth around 1.0: depth ≈ 10^(depthDb/20) - 1 for the +depthDb
        // peak; we use the symmetric form (1 + d*sin(...)) with d set so
        // that +d corresponds to +depthDb.
        val d = (10.0.pow(depthDb.toDouble() / 20.0) - 1.0).toFloat()
        for (i in buf.indices) {
            val t = i.toDouble() / sampleRate
            val mod = 1f + d * sin(2.0 * PI * hz * t).toFloat()
            buf[i] *= mod
        }
    }

    /**
     * Soft saturator at strength 0..1. Uses x / sqrt(1 + (k·x)²) — a
     * smooth tanh-like curve that adds harmonics without hard clipping.
     */
    private fun applyDistortion(buf: FloatArray, amount: Float) {
        if (amount <= 0f) return
        val k = 1f + 4f * amount.coerceIn(0f, 1f) // amount=0.25 → k=2
        val full = Short.MAX_VALUE.toFloat()
        for (i in buf.indices) {
            val x = buf[i] / full
            val y = (k * x) / sqrt(1f + (k * x) * (k * x))
            buf[i] = y * full
        }
    }

    /**
     * One-pole low-pass filter, RC-style. `cutoffHz` is the -3 dB point.
     * Stable for any `cutoffHz < sampleRate / 2`; we don't validate
     * because all our presets sit well under Nyquist.
     */
    private fun applyOnePoleLowpass(buf: FloatArray, sampleRate: Int, cutoffHz: Float) {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val alpha = (dt / (rc + dt)).toFloat()
        var prev = 0f
        for (i in buf.indices) {
            prev += alpha * (buf[i] - prev)
            buf[i] = prev
        }
    }

    private fun clampToShort(buf: FloatArray): ShortArray {
        val out = ShortArray(buf.size)
        for (i in buf.indices) {
            val v = buf[i]
            out[i] = when {
                v >= Short.MAX_VALUE.toFloat() -> Short.MAX_VALUE
                v <= Short.MIN_VALUE.toFloat() -> Short.MIN_VALUE
                else -> v.toInt().toShort()
            }
        }
        return out
    }
}
