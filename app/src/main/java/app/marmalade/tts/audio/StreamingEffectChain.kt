package app.marmalade.tts.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// -----------------------------------------------------------------------------
// The single DSP engine. Each EffectBlock maps to a stateful, streamable
// processor that mirrors the corresponding sox effect (see EffectChain.kt). The
// engine runs the chain chunk-by-chunk and carries each block's state across
// chunk seams, so effects run on the time-to-first-audio streaming path without
// resetting reverb/echo tails or filter memory at every chunk boundary.
//
//   producer (engine) ──chunk──► process(chunk) ──► consumer
//                                     │  (state carries across chunks)
//                               ...last chunk...
//                                     ▼
//                               flush()  ──► reverb / echo tail
//
// Continuity is pinned by StreamingEffectChainTest (chunked == whole) for every
// deterministic block. NOT reproduced: a whole-buffer peak-normalize (it needs
// the future); the output hard-clips on the rare overflow instead.
// -----------------------------------------------------------------------------

/**
 * Stateful, chunk-by-chunk effect chain. Construct once per utterance, feed
 * engine chunks through [process], then call [flush] after the last chunk to
 * drain any reverb/echo tail.
 */
class StreamingEffectChain(blocks: List<EffectBlock>, sampleRate: Int) {

    private val processors: List<BlockProcessor> = blocks.map { processorFor(it, sampleRate) }

    /** True when there is no DSP to apply — callers can skip allocation. */
    val isEmpty: Boolean get() = processors.isEmpty()

    /** Shape one PCM chunk. State carries to the next call. */
    fun process(pcm: ShortArray): ShortArray {
        if (processors.isEmpty()) return pcm
        var buf = FloatArray(pcm.size) { pcm[it].toFloat() }
        for (p in processors) buf = p.process(buf)
        return toPcm(buf)
    }

    /**
     * Emit trailing samples (reverb/echo ring-out) after the last input chunk.
     * Each processor's tail is threaded through the downstream processors so a
     * `[Reverb, Echo]` chain echoes the reverb tail too.
     */
    fun flush(): ShortArray {
        if (processors.isEmpty()) return ShortArray(0)
        var signal = FloatArray(0)
        for (p in processors) {
            val processed = if (signal.isEmpty()) FloatArray(0) else p.process(signal)
            val tail = p.flush()
            signal = if (tail.isEmpty()) processed else processed + tail
        }
        return toPcm(signal)
    }

    // Hard-clip float → PCM16. No global normalize (unavailable while streaming).
    private fun toPcm(buf: FloatArray): ShortArray = ShortArray(buf.size) { i ->
        val v = buf[i]
        when {
            v >= Short.MAX_VALUE.toFloat() -> Short.MAX_VALUE
            v <= Short.MIN_VALUE.toFloat() -> Short.MIN_VALUE
            else -> v.toInt().toShort()
        }
    }
}

private fun processorFor(block: EffectBlock, sampleRate: Int): BlockProcessor = when (block) {
    is EffectBlock.Reverb -> ReverbProcessor(block.reverberance, sampleRate)
    is EffectBlock.Echo -> EchoProcessor(block, sampleRate)
    is EffectBlock.Overdrive -> OverdriveProcessor(block.gainDb)
    is EffectBlock.Pitch -> PitchProcessor(block.cents, sampleRate)
    is EffectBlock.Tempo -> TempoProcessor(block.factor)
    is EffectBlock.Bandpass -> BandpassProcessor(block.lowHz, block.highHz, sampleRate)
    is EffectBlock.Vol -> VolProcessor(block.factor)
    is EffectBlock.Treble -> ShelfProcessor.highShelf(block.db, sampleRate)
    is EffectBlock.Bass -> ShelfProcessor.lowShelf(block.db, sampleRate)
    is EffectBlock.Mid -> BiquadProcessor(Biquad.peaking(block.freqHz, block.gainDb, 1.0f, sampleRate))
    is EffectBlock.Lowpass -> BiquadProcessor(Biquad.lowPass(block.freqHz, sampleRate))
    is EffectBlock.Highpass -> BiquadProcessor(Biquad.highPass(block.freqHz, sampleRate))
    is EffectBlock.Tremolo -> TremoloProcessor(block.speedHz, block.depth, sampleRate)
    // Flanger: short base delay (~0.5 ms) swept by depthMs, no feedback.
    is EffectBlock.Flanger -> ModDelayProcessor(
        baseDelayMs = 0.5f, depthMs = block.depthMs, speedHz = block.speedHz,
        feedback = 0f, dry = 0.7f, wet = 0.7f, sr = sampleRate,
    )
    // Chorus: longer base delay (~25 ms) so the wet copy reads as a second
    // voice rather than a comb notch.
    is EffectBlock.Chorus -> ModDelayProcessor(
        baseDelayMs = 25f, depthMs = block.depthMs, speedHz = block.speedHz,
        feedback = 0f, dry = 0.7f, wet = 0.7f, sr = sampleRate,
    )
    // Phaser: short swept comb with feedback (decay) → resonant moving notches.
    is EffectBlock.Phaser -> ModDelayProcessor(
        baseDelayMs = 0.5f, depthMs = 3f, speedHz = block.speedHz,
        feedback = block.decay.coerceIn(0f, 0.9f), dry = 0.7f, wet = 0.7f, sr = sampleRate,
    )
    is EffectBlock.Compressor -> CompressorProcessor(block.thresholdDb, block.ratio, sampleRate)
    is EffectBlock.Bitcrush -> BitcrushProcessor(block.bits, block.downsample)
    is EffectBlock.RingMod -> RingModProcessor(block.freqHz, block.mix, sampleRate)
    is EffectBlock.Monotone -> MonotoneProcessor(block.targetHz, sampleRate)
}

/** A single block as a stateful, streamable unit. */
private interface BlockProcessor {
    fun process(input: FloatArray): FloatArray

    /** Trailing samples after input ends (reverb/echo tail). Empty by default. */
    fun flush(): FloatArray = FloatArray(0)
}

// ── Biquad (RBJ cookbook) — shared by Bandpass + shelf EQ ─────────────────────

private class Biquad(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun step(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        private const val Q = 0.70710678f // Butterworth

        fun lowPass(cutoffHz: Float, sr: Int): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sr
            val cosw = cos(w0); val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1 + alpha
            return Biquad(
                (((1 - cosw) / 2) / a0).toFloat(),
                ((1 - cosw) / a0).toFloat(),
                (((1 - cosw) / 2) / a0).toFloat(),
                ((-2 * cosw) / a0).toFloat(),
                ((1 - alpha) / a0).toFloat(),
            )
        }

        fun highPass(cutoffHz: Float, sr: Int): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sr
            val cosw = cos(w0); val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1 + alpha
            return Biquad(
                (((1 + cosw) / 2) / a0).toFloat(),
                (-(1 + cosw) / a0).toFloat(),
                (((1 + cosw) / 2) / a0).toFloat(),
                ((-2 * cosw) / a0).toFloat(),
                ((1 - alpha) / a0).toFloat(),
            )
        }

        // RBJ peaking EQ: a bell boost/cut of [dbGain] centered at [f0], width [q].
        fun peaking(f0: Float, dbGain: Float, q: Float, sr: Int): Biquad {
            val a = 10.0.pow(dbGain / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cosw = cos(w0); val alpha = sin(w0) / (2.0 * q)
            val a0 = 1 + alpha / a
            return Biquad(
                ((1 + alpha * a) / a0).toFloat(),
                ((-2 * cosw) / a0).toFloat(),
                ((1 - alpha * a) / a0).toFloat(),
                ((-2 * cosw) / a0).toFloat(),
                ((1 - alpha / a) / a0).toFloat(),
            )
        }
    }
}

// Generic single-biquad block — Mid (peaking), Lowpass, Highpass.
private class BiquadProcessor(private val biquad: Biquad) : BlockProcessor {
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i -> biquad.step(input[i]) }
}

// One-pole-pair band: high-pass at lowHz cascaded with low-pass at highHz.
private class BandpassProcessor(lowHz: Float, highHz: Float, sr: Int) : BlockProcessor {
    private val hp = Biquad.highPass(lowHz, sr)
    private val lp = Biquad.lowPass(highHz, sr)
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        lp.step(hp.step(input[i]))
    }
}

// RBJ shelving EQ. f0 fixed at sox's defaults (bass 100 Hz, treble 3000 Hz).
private class ShelfProcessor private constructor(private val biquad: Biquad) : BlockProcessor {
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i -> biquad.step(input[i]) }

    companion object {
        private const val S = 1.0 // shelf slope

        fun highShelf(db: Float, sr: Int): ShelfProcessor = ShelfProcessor(shelf(db, 3000f, sr, high = true))
        fun lowShelf(db: Float, sr: Int): ShelfProcessor = ShelfProcessor(shelf(db, 100f, sr, high = false))

        private fun shelf(db: Float, f0: Float, sr: Int, high: Boolean): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cosw = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt((a + 1 / a) * (1 / S - 1) + 2)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            return if (high) {
                val a0 = (a + 1) - (a - 1) * cosw + twoSqrtAAlpha
                Biquad(
                    (a * ((a + 1) + (a - 1) * cosw + twoSqrtAAlpha) / a0).toFloat(),
                    (-2 * a * ((a - 1) + (a + 1) * cosw) / a0).toFloat(),
                    (a * ((a + 1) + (a - 1) * cosw - twoSqrtAAlpha) / a0).toFloat(),
                    (2 * ((a - 1) - (a + 1) * cosw) / a0).toFloat(),
                    (((a + 1) - (a - 1) * cosw - twoSqrtAAlpha) / a0).toFloat(),
                )
            } else {
                val a0 = (a + 1) + (a - 1) * cosw + twoSqrtAAlpha
                Biquad(
                    (a * ((a + 1) - (a - 1) * cosw + twoSqrtAAlpha) / a0).toFloat(),
                    (2 * a * ((a - 1) - (a + 1) * cosw) / a0).toFloat(),
                    (a * ((a + 1) - (a - 1) * cosw - twoSqrtAAlpha) / a0).toFloat(),
                    (-2 * ((a - 1) + (a + 1) * cosw) / a0).toFloat(),
                    (((a + 1) + (a - 1) * cosw - twoSqrtAAlpha) / a0).toFloat(),
                )
            }
        }
    }
}

// ── Vol — linear gain ─────────────────────────────────────────────────────────
private class VolProcessor(private val factor: Float) : BlockProcessor {
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i -> input[i] * factor }
}

// ── Overdrive — pre-gain + cubic soft-clip waveshaper ─────────────────────────
private class OverdriveProcessor(gainDb: Float) : BlockProcessor {
    private val gain = 10.0.pow(gainDb / 20.0).toFloat()
    private val scale = Short.MAX_VALUE.toFloat()
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        val x = (input[i] / scale * gain).coerceIn(-1f, 1f)
        // Cubic soft clip: maps [-1,1] → [-2/3,2/3]; the pre-gain pushes the
        // signal into saturation for the distorted "overdrive" timbre.
        (x - x * x * x / 3f) * scale
    }
}

// ── Echo — single feed-forward delay tap (sox `echo`) ─────────────────────────
private class EchoProcessor(b: EffectBlock.Echo, sr: Int) : BlockProcessor {
    private val gainIn = b.gainIn
    private val tapGain = b.gainOut * b.decay
    private val delay = ((b.delayMs * sr) / 1000f).toInt().coerceAtLeast(1)
    private val buf = FloatArray(delay)
    private var idx = 0

    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        val echo = buf[idx]
        buf[idx] = input[i]
        idx++; if (idx >= delay) idx = 0
        gainIn * input[i] + tapGain * echo
    }

    // After input ends, the last `delay` inputs still owe their echo.
    override fun flush(): FloatArray = FloatArray(delay) {
        val echo = buf[idx]
        buf[idx] = 0f
        idx++; if (idx >= delay) idx = 0
        tapGain * echo
    }
}

// ── Reverb — freeverb (8 parallel LBCF combs + 4 series allpass) ──────────────
private class ReverbProcessor(reverberance: Float, private val sampleRate: Int) : BlockProcessor {
    // Lowpass-feedback comb filter.
    private class Comb(size: Int, private val feedback: Float, private val damp1: Float) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f
        fun step(x: Float): Float {
            val out = buf[idx]
            store = out * (1 - damp1) + store * damp1
            buf[idx] = x + store * feedback
            idx++; if (idx >= buf.size) idx = 0
            return out
        }
    }

    private class Allpass(size: Int, private val feedback: Float) {
        private val buf = FloatArray(size)
        private var idx = 0
        fun step(x: Float): Float {
            val bufout = buf[idx]
            buf[idx] = x + bufout * feedback
            idx++; if (idx >= buf.size) idx = 0
            return -x + bufout
        }
    }

    private val feedback = (reverberance / 100f) * 0.28f + 0.7f
    private val damp1 = 0.2f // HF-damping 50% (sox default) → 0.5 × scaledamp(0.4)
    private val dry = 1.0f
    private val wet = (reverberance / 100f) * 0.8f
    private val fixedGain = 0.015f

    private fun scale(tuning: Int): Int = (tuning.toLong() * sampleRate / 44100).toInt().coerceAtLeast(1)
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        .map { Comb(scale(it), feedback, damp1) }
    private val allpasses = intArrayOf(556, 441, 341, 225).map { Allpass(scale(it), 0.5f) }

    private fun tick(x: Float): Float {
        val input = x * fixedGain
        var s = 0f
        for (c in combs) s += c.step(input)
        for (a in allpasses) s = a.step(s)
        return x * dry + s * wet
    }

    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i -> tick(input[i]) }

    // Decay-aware tail: feed silence until the reverb rings out (peak below a
    // near-inaudible floor), capped at 2 s so a huge room can't run forever.
    override fun flush(): FloatArray {
        if (wet <= 0f) return FloatArray(0)
        val cap = sampleRate * 2
        val block = 256
        var out = FloatArray(block)
        var count = 0
        while (count < cap) {
            if (count + block > out.size) out = out.copyOf((out.size * 2).coerceAtMost(cap))
            var peak = 0f
            var produced = 0
            while (produced < block && count < cap) {
                val v = tick(0f)
                out[count++] = v
                val a = if (v < 0f) -v else v
                if (a > peak) peak = a
                produced++
            }
            if (peak < 2.0f) break // decayed to silence (< ~-84 dBFS)
        }
        return out.copyOf(count)
    }
}

// ── Pitch — delay-line crossfade shifter (sox `pitch`) ────────────────────────
// Shifts pitch by ratio = 2^(cents/1200) WITHOUT changing duration (one output
// per input). Two read pointers sweep a ring buffer at (1 - ratio) samples/step,
// offset by half the buffer, crossfaded with Hann windows that sum to 1 so the
// wrap seam is inaudible. ~50 ms grain buffer (latency negligible for TTFA).
private class PitchProcessor(cents: Float, sr: Int) : BlockProcessor {
    private val n = (sr * 0.05).toInt().coerceAtLeast(2) // ~50 ms grain buffer
    private val buf = FloatArray(n)
    private var writeIdx = 0
    private var phase = 0.0 // read delay behind write, in [0, n)
    private val inc = 1.0 - 2.0.pow(cents / 1200.0) // phase step/sample

    override fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            buf[writeIdx] = input[i]
            var p2 = phase + n / 2.0
            if (p2 >= n) p2 -= n
            val w1 = 0.5 * (1 - cos(2.0 * PI * phase / n))
            val w2 = 0.5 * (1 - cos(2.0 * PI * p2 / n))
            out[i] = (w1 * read(phase) + w2 * read(p2)).toFloat()
            writeIdx++; if (writeIdx >= n) writeIdx = 0
            phase += inc
            if (phase >= n) phase -= n
            if (phase < 0) phase += n
        }
        return out
    }

    // Read `delay` samples behind the write head, fractional, wrapping mod n.
    private fun read(delay: Double): Double {
        var pos = writeIdx - delay
        while (pos < 0) pos += n
        val lo = pos.toInt()
        val hi = if (lo + 1 >= n) 0 else lo + 1
        val frac = pos - lo
        return buf[lo] * (1 - frac) + buf[hi] * frac
    }
}

// ── Tempo — overlap-add time-stretch (sox `tempo`) ────────────────────────────
// Changes duration by `factor` (>1 faster/shorter, <1 slower/longer) WITHOUT
// changing pitch: read Hann frames from the input at analysis hop Ha = Hs·factor
// and overlap-add them at synthesis hop Hs. Window sums normalize the overlap so
// level stays flat. Streams with ~one-frame latency; state carries across chunks
// (pinned by the chunked==whole test). A plain OLA — some phasiness on tonal
// input, fine for the character presets; a WSOLA similarity search is the
// upgrade if it sounds smeary.
private class TempoProcessor(factor: Float) : BlockProcessor {
    private val w = 1024
    private val hs = 512                                   // synthesis hop (50% overlap)
    private val ha = (hs * factor).toDouble().coerceAtLeast(1.0) // analysis hop
    private val window = FloatArray(w) { (0.5 * (1 - cos(2.0 * PI * it / (w - 1)))).toFloat() }

    private var inBuf = FloatArray(w * 2)
    private var inLen = 0
    private var readPos = 0.0          // analysis position in inBuf

    private var acc = FloatArray(w * 2)  // overlap-add accumulator
    private var accW = FloatArray(w * 2) // parallel window-sum
    private var accLen = 0               // high-water of written output
    private var synth = 0                // next frame placement in acc

    override fun process(input: FloatArray): FloatArray {
        appendInput(input)
        return runFrames()
    }

    override fun flush(): FloatArray {
        // Emit whatever is accumulated, including the trailing (non-finalized)
        // overlap region — there are no more frames to add to it.
        val tail = normalize(0, accLen)
        accLen = 0; synth = 0
        return tail
    }

    private fun appendInput(input: FloatArray) {
        if (inLen + input.size > inBuf.size) {
            inBuf = inBuf.copyOf((inLen + input.size).coerceAtLeast(inBuf.size * 2))
        }
        System.arraycopy(input, 0, inBuf, inLen, input.size)
        inLen += input.size
    }

    private fun runFrames(): FloatArray {
        val emitted = ArrayList<Float>()
        while (readPos + w <= inLen) {
            if (synth + w > acc.size) {
                val cap = (synth + w).coerceAtLeast(acc.size * 2)
                acc = acc.copyOf(cap); accW = accW.copyOf(cap)
            }
            val base = readPos.toInt()
            val frac = (readPos - base).toFloat()
            for (k in 0 until w) {
                val lo = base + k
                val s = if (lo + 1 < inLen) inBuf[lo] * (1 - frac) + inBuf[lo + 1] * frac else inBuf[lo]
                acc[synth + k] += window[k] * s
                accW[synth + k] += window[k]
            }
            if (synth + w > accLen) accLen = synth + w
            readPos += ha
            synth += hs
            // Output below `synth` is finalized — no future frame (placed at
            // ≥ synth) touches it. Emit + left-shift the accumulators.
            for (s in normalize(0, synth)) emitted.add(s)
            shiftAcc(synth)
            accLen -= synth
            synth = 0
            // Drop consumed input below the next frame's start.
            val drop = readPos.toInt()
            if (drop > 0) {
                System.arraycopy(inBuf, drop, inBuf, 0, inLen - drop)
                inLen -= drop
                readPos -= drop
            }
        }
        return emitted.toFloatArray()
    }

    private fun normalize(from: Int, to: Int): FloatArray = FloatArray(to - from) { i ->
        val j = from + i
        if (accW[j] < 1e-4f) 0f else acc[j] / accW[j]
    }

    private fun shiftAcc(by: Int) {
        if (by <= 0) return
        System.arraycopy(acc, by, acc, 0, acc.size - by)
        System.arraycopy(accW, by, accW, 0, accW.size - by)
        for (j in (acc.size - by) until acc.size) { acc[j] = 0f; accW[j] = 0f }
    }
}

// ── Tremolo — amplitude LFO (sox `tremolo`) ───────────────────────────────────
// Multiplies the signal by a sine that dips the gain between (1 − depth) and 1
// at `speedHz`. LFO phase persists across chunks. Starts at full gain (cos LFO),
// so the chunked path matches the whole-buffer path sample-for-sample.
private class TremoloProcessor(speedHz: Float, depth: Float, sr: Int) : BlockProcessor {
    private val depth = depth.coerceIn(0f, 1f)
    private val inc = 2.0 * PI * speedHz / sr
    private var phase = 0.0
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        val lfo = 0.5 - 0.5 * cos(phase) // 0 at phase 0 → starts at full gain
        val gain = 1.0 - depth * lfo
        phase += inc; if (phase >= 2 * PI) phase -= 2 * PI
        (input[i] * gain).toFloat()
    }
}

// ── Modulated delay — shared engine for Flanger / Chorus / Phaser ─────────────
// A delay line whose read position is swept by a sine LFO between
// (baseDelay) and (baseDelay + depth) samples. Output = dry·in + wet·delayed;
// the write tap mixes in feedback·delayed (Flanger/Chorus use 0, Phaser > 0 for
// resonant notches). Fractional reads are linearly interpolated. The ring
// buffer + LFO phase persist across chunks, so chunked == whole.
private class ModDelayProcessor(
    baseDelayMs: Float,
    depthMs: Float,
    speedHz: Float,
    private val feedback: Float,
    private val dry: Float,
    private val wet: Float,
    sr: Int,
) : BlockProcessor {
    private val baseDelay = baseDelayMs * sr / 1000f
    private val depth = (depthMs * sr / 1000f).coerceAtLeast(0f)
    private val buf = FloatArray((baseDelay + depth).toInt() + 4)
    private var widx = 0
    private val inc = 2.0 * PI * speedHz / sr
    private var phase = 0.0

    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        val mod = baseDelay + depth * (0.5f - 0.5f * cos(phase).toFloat()) // ≥ 0
        val delayed = readFrac(mod)
        val out = dry * input[i] + wet * delayed
        buf[widx] = input[i] + feedback * delayed
        widx++; if (widx >= buf.size) widx = 0
        phase += inc; if (phase >= 2 * PI) phase -= 2 * PI
        out
    }

    // Read `delay` samples behind the write head, fractional, wrapping mod size.
    private fun readFrac(delay: Float): Float {
        val size = buf.size
        var pos = widx - delay
        while (pos < 0f) pos += size
        val lo = pos.toInt() % size
        val hi = (lo + 1) % size
        val frac = pos - pos.toInt()
        return buf[lo] * (1f - frac) + buf[hi] * frac
    }
}

// ── Bitcrush — bit-depth quantize + sample-rate decimation (lo-fi) ────────────
// Quantizes each sample to `levels = 2^bits` steps and holds the value for
// `downsample` samples (sample-and-hold), dropping the effective rate. The
// hold counter + held value persist across chunks, so chunked == whole.
private class BitcrushProcessor(bits: Float, downsample: Float) : BlockProcessor {
    private val levels = 2.0.pow(bits.coerceIn(1f, 16f).toDouble()).toFloat()
    private val hold = downsample.toInt().coerceAtLeast(1)
    private val scale = Short.MAX_VALUE.toFloat()
    private var counter = 0
    private var held = 0f

    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        if (counter == 0) {
            val norm = (input[i] / scale).coerceIn(-1f, 1f)
            held = round(norm * levels) / levels * scale
        }
        counter++; if (counter >= hold) counter = 0
        held
    }
}

// ── Monotone — TD-PSOLA pitch flattener ──────────────────────────────────────
// Re-emits the input's own pitch periods on a fixed output grid at targetHz.
// Flatness is exact by construction: there is no "how many cents of correction"
// control loop to lag behind the input, because the output period is a constant
// and the detector only decides WHICH grain to place, not where.
//
// That framing is the whole fix. The previous implementation chased the input's
// F0 with a glide-smoothed cents offset driven into a delay-line shifter, which
// failed three ways at once: the loop (85 ms hop, median-of-3, 30-cent
// hysteresis, 200 ms glide) was an order of magnitude slower than speech F0, so
// most of the original intonation survived and the corrections that did land
// arrived as portamento swoops; near zero shift the two read taps froze at an
// arbitrary phase, leaving a static comb filter — and targetHz near the voice's
// median, the normal case, is exactly where that happens; and the resampling
// dragged formants with the pitch. PSOLA has none of those: it's formant
// preserving, transient safe, and has no shift-rate state to freeze.
//
// (A phase vocoder was tried and rejected once already — catalog v19/v20. This
// is not that: time-domain, no spectral phase, no transient smearing.)
//
// Three stages, all state keyed on ABSOLUTE sample positions so the result is
// independent of how the input is chunked (pinned by StreamingEffectChainTest):
//
//  1. Detection. Sliding YIN — 1024-sample window, 256-sample hop (10.7 ms), so
//     the pitch track actually resolves speech. RMS gate + the YIN threshold
//     give the voiced/unvoiced decision; median-of-5 and an octave-collapse
//     guard clean up the track. One entry per hop, indexed by hop number.
//
//  2. Analysis marks. Inside voiced runs, marks land one detected period apart,
//     each snapped to the nearest local waveform peak so grains are cut at
//     consistent points in the glottal cycle. Unvoiced runs get fixed 5 ms
//     marks.
//
//  3. Synthesis. Walk an output cursor forward by sr/targetHz while voiced (the
//     flattening) or by the local analysis spacing while unvoiced (so
//     fricatives and silence pass through untouched), and overlap-add the
//     nearest analysis mark's two-period Hann grain at each step. Duration is
//     preserved — one grain slot per output mark over the same time base.
//
// Grains are widened to the synthesis spacing when targetHz is more than an
// octave below the input, which is the only case where period-length grains
// would leave gaps between output marks.
private class MonotoneProcessor(targetHz: Float, private val sr: Int) : BlockProcessor {
    private val target = targetHz.coerceIn(50f, 800f)
    private val outPeriod = sr / target.toDouble()   // synthesis grid spacing

    // -- Detection -----------------------------------------------------------
    private val win = 1024
    private val hop = 256
    private val minTau = (sr / 400f).toInt().coerceAtLeast(2)          // 400 Hz ceiling
    private val maxTau = (sr / 80f).toInt().coerceAtMost(win / 2 - 1)  // 80 Hz floor
    private val yinThreshold = 0.15f
    private val rmsGate = 200f          // ~-44 dBFS at int16 scale — silence/tail
    private val unvoicedPeriod = (sr * 0.005).toInt().coerceAtLeast(2) // 5 ms marks

    /** Ring of per-hop smoothed F0 (Hz), or -1 for unvoiced. Indexed by hop number. */
    private val hopF0 = FloatArray(HOP_HISTORY) { -1f }
    private var nextHop = 0             // next hop number to analyse
    private val rawRing = FloatArray(5) { -1f }
    private var rawIdx = 0

    // -- Input, addressed absolutely -----------------------------------------
    private var inBuf = FloatArray(win * 4)
    private var inLen = 0
    private var inBase = 0L             // absolute index of inBuf[0]
    private var ended = false

    // -- Analysis marks ------------------------------------------------------
    private class Mark(val pos: Long, val half: Int, val voiced: Boolean, val spacing: Int)

    private val marks = ArrayDeque<Mark>()
    private var nextMarkPos = 0L
    /** Widest grain any future mark can carry — the finalization horizon. */
    private val maxHalf = maxOf(maxTau, ceil(outPeriod).toInt())

    // -- Synthesis / overlap-add ---------------------------------------------
    private var outPos = 0.0            // absolute output position
    private var acc = FloatArray(win * 4)
    private var accW = FloatArray(win * 4)
    private var accBase = 0L            // absolute output index of acc[0]
    private var accLen = 0              // high-water mark written into acc

    override fun process(input: FloatArray): FloatArray {
        append(input)
        return advance()
    }

    override fun flush(): FloatArray {
        // No more input is coming: place marks and grains against whatever is
        // buffered (reads past the end return 0), then drain the accumulator.
        ended = true
        val body = advance()
        val tail = normalize(accLen)
        accLen = 0
        accBase += 0
        return if (body.isEmpty()) tail else body + tail
    }

    /**
     * Run detection → marks → synthesis as far as the buffered input allows,
     * looping because the three are interleaved: detection is capped to stay
     * inside the pitch-track ring, so placing marks is what lets it advance
     * further. Without the loop, a whole-buffer call would stall after one ring
     * window.
     */
    private fun advance(): FloatArray {
        val emitted = ArrayList<Float>()
        while (true) {
            val hopBefore = nextHop
            val markBefore = nextMarkPos
            runDetection()
            runMarks()
            runSynthesis(emitted)
            if (nextHop == hopBefore && nextMarkPos == markBefore) break
        }
        trimInput()
        return emitted.toFloatArray()
    }

    // ── 1. Detection ────────────────────────────────────────────────────────

    private fun runDetection() {
        while (true) {
            val start = nextHop.toLong() * hop
            // Strictly buffered — unlike grain reads, an analysis window may
            // not run off the end of the input, or flush() would never stop.
            if (inBase + inLen < start + win) return
            // Never outrun the pitch-track ring: the next mark still has to
            // read the hop covering its own position, and a wrapped ring would
            // hand it some other hop's period — which also made the result
            // depend on how far ahead detection had got, i.e. on chunking.
            if (nextHop >= hopIndexFor(nextMarkPos) + HOP_HISTORY) return
            val raw = yinAt(start)
            rawRing[rawIdx] = raw
            rawIdx = (rawIdx + 1) % rawRing.size
            hopF0[nextHop % HOP_HISTORY] =
                if (raw <= 0f) -1f else octaveCorrect(raw, medianOfValid(rawRing))
            nextHop++
        }
    }

    /**
     * Smoothed F0 at absolute position [pos] — the hop whose window contains it.
     * Deterministic given [pos] alone, which is what keeps mark placement
     * independent of how far detection happens to have run.
     */
    private fun f0At(pos: Long): Float =
        hopF0[hopIndexFor(pos).coerceAtMost(nextHop - 1) % HOP_HISTORY]

    /** Hop number whose analysis window covers absolute [pos]. */
    private fun hopIndexFor(pos: Long): Int =
        ((pos - win / 2) / hop).coerceAtLeast(0).toInt()

    /** Position of the last sample the hop covering [pos] needs. */
    private fun hopEndFor(pos: Long): Long = (hopIndexFor(pos) + 1).toLong() * hop + win

    // ── 2. Analysis marks ───────────────────────────────────────────────────

    private fun runMarks() {
        while (true) {
            // The mark's period comes from the hop covering it, so that hop
            // must have been analysed first.
            if (!ended && inBase + inLen < hopEndFor(nextMarkPos)) return
            if (nextHop == 0) return
            // Once the input has ended, `have` stops gating — so stop here, or
            // marks would march past the end of the buffer forever.
            if (ended && nextMarkPos >= inBase + inLen) return
            val f0 = f0At(nextMarkPos)
            val voiced = f0 > 0f
            val period = if (voiced) (sr / f0).toInt().coerceIn(minTau, maxTau) else unvoicedPeriod
            val search = if (voiced) period / 4 else 0
            val half = if (voiced) maxOf(period, ceil(outPeriod).toInt()) else period
            if (!have(nextMarkPos - half - search, half * 2 + search * 2 + 1)) return

            // Snap voiced marks to the nearest waveform peak so every grain is
            // cut at the same point in the glottal cycle. Signed max, not |max|
            // — consistent polarity, or the overlap-add cancels itself.
            var pos = nextMarkPos
            if (search > 0) {
                var best = Float.NEGATIVE_INFINITY
                for (c in (nextMarkPos - search)..(nextMarkPos + search)) {
                    val v = sampleAt(c)
                    if (v > best) { best = v; pos = c }
                }
            }
            marks.addLast(Mark(pos, half, voiced, period))
            nextMarkPos = pos + period
        }
    }

    // ── 3. Synthesis ────────────────────────────────────────────────────────

    private fun runSynthesis(emitted: ArrayList<Float>) {
        while (marks.isNotEmpty()) {
            // Drop marks the cursor has moved past — the head is then the
            // nearest one, which needs its successor present to be sure.
            while (marks.size >= 2 && abs(marks.elementAt(1).pos - outPos) <= abs(marks.first().pos - outPos)) {
                marks.removeFirst()
            }
            if (marks.size < 2 && !ended) return
            val mark = marks.first()
            if (!started) { outPos = mark.pos.toDouble(); started = true }
            if (ended && marks.size == 1 && outPos > mark.pos + mark.half) break

            placeGrain(mark)
            outPos += if (mark.voiced) outPeriod else mark.spacing.toDouble()

            // Every future grain starts at or after (outPos - maxHalf), so
            // output below that is finalized. Emit and left-shift.
            val finalized = (outPos.toLong() - maxHalf - accBase).toInt()
            if (finalized > 0) {
                for (s in normalize(finalized)) emitted.add(s)
                shiftAcc(finalized)
            }
        }
    }

    private var started = false

    private fun placeGrain(mark: Mark) {
        val centre = outPos.roundToLong()
        val half = mark.half
        val span = 2 * half
        val need = (centre + half - accBase).toInt() + 1
        if (need > acc.size) {
            val cap = need.coerceAtLeast(acc.size * 2)
            acc = acc.copyOf(cap); accW = accW.copyOf(cap)
        }
        for (k in -half..half) {
            val idx = (centre + k - accBase).toInt()
            if (idx < 0) continue
            val w = (0.5 * (1 - cos(2.0 * PI * (k + half) / span))).toFloat()
            acc[idx] += w * sampleAt(mark.pos + k)
            accW[idx] += w
            if (idx + 1 > accLen) accLen = idx + 1
        }
    }

    private fun normalize(to: Int): FloatArray {
        val n = to.coerceAtMost(accLen).coerceAtLeast(0)
        return FloatArray(n) { if (accW[it] < 1e-4f) 0f else acc[it] / accW[it] }
    }

    private fun shiftAcc(by: Int) {
        System.arraycopy(acc, by, acc, 0, acc.size - by)
        System.arraycopy(accW, by, accW, 0, accW.size - by)
        for (j in (acc.size - by) until acc.size) { acc[j] = 0f; accW[j] = 0f }
        accBase += by
        accLen = (accLen - by).coerceAtLeast(0)
    }

    // ── Input buffer ────────────────────────────────────────────────────────

    private fun append(input: FloatArray) {
        if (inLen + input.size > inBuf.size) {
            inBuf = inBuf.copyOf((inLen + input.size).coerceAtLeast(inBuf.size * 2))
        }
        System.arraycopy(input, 0, inBuf, inLen, input.size)
        inLen += input.size
    }

    /** True once [len] samples from absolute [from] are buffered (or input ended). */
    private fun have(from: Long, len: Int): Boolean = ended || inBase + inLen >= from + len

    private fun sampleAt(pos: Long): Float {
        val i = (pos - inBase).toInt()
        return if (i < 0 || i >= inLen) 0f else inBuf[i]
    }

    /**
     * Drop input below what detection and the next mark still need to read.
     * The maxTau slack covers marks left queued for the next call — synthesis
     * drains the queue to its last two entries, so they sit within a couple of
     * periods of [nextMarkPos].
     */
    private fun trimInput() {
        val keepFrom = minOf(nextHop.toLong() * hop, nextMarkPos - maxHalf - 4 * maxTau)
        val drop = (keepFrom - inBase).toInt()
        if (drop <= 0) return
        System.arraycopy(inBuf, drop, inBuf, 0, inLen - drop)
        inLen -= drop
        inBase += drop
    }

    // ── YIN ─────────────────────────────────────────────────────────────────

    /**
     * YIN over the window starting at absolute [start]. Returns Hz, or -1 for
     * silence / no clear pitch (which is also the voiced/unvoiced decision).
     */
    private fun yinAt(start: Long): Float {
        val base = (start - inBase).toInt()
        var sumSq = 0.0
        for (i in 0 until win) {
            val s = sampleAt(start + i)
            sumSq += s.toDouble() * s
        }
        if (sqrt(sumSq / win) < rmsGate) return -1f

        val limit = win - maxTau
        val d = FloatArray(maxTau + 1)
        for (tau in minTau..maxTau) {
            var sum = 0f
            for (i in 0 until limit) {
                val a = if (base + i in 0 until inLen) inBuf[base + i] else 0f
                val b = if (base + i + tau in 0 until inLen) inBuf[base + i + tau] else 0f
                val diff = a - b
                sum += diff * diff
            }
            d[tau] = sum
        }
        val dPrime = FloatArray(maxTau + 1)
        dPrime[minTau] = 1f
        var runningSum = d[minTau]
        for (tau in minTau + 1..maxTau) {
            runningSum += d[tau]
            dPrime[tau] = if (runningSum > 0f) d[tau] * (tau - minTau + 1) / runningSum else 1f
        }
        var chosenTau = -1
        for (tau in minTau..maxTau) {
            if (dPrime[tau] < yinThreshold) {
                chosenTau = tau
                while (chosenTau + 1 <= maxTau && dPrime[chosenTau + 1] < dPrime[chosenTau]) {
                    chosenTau++
                }
                break
            }
        }
        if (chosenTau < 0) return -1f
        val refined = if (chosenTau in (minTau + 1) until maxTau) {
            parabolicInterp(dPrime[chosenTau - 1], dPrime[chosenTau], dPrime[chosenTau + 1], chosenTau.toFloat())
        } else {
            chosenTau.toFloat()
        }
        return if (refined > 0f) sr / refined else -1f
    }

    private fun parabolicInterp(a: Float, b: Float, c: Float, x: Float): Float {
        val denom = a - 2f * b + c
        if (abs(denom) < 1e-9f) return x
        return x + 0.5f * (a - c) / denom
    }

    private fun medianOfValid(buf: FloatArray): Float {
        val valid = buf.filter { it > 0f }.sorted()
        if (valid.isEmpty()) return -1f
        return valid[valid.size / 2]
    }

    /** YIN drops or doubles an octave on transients; collapse those back. */
    private fun octaveCorrect(detected: Float, median: Float): Float {
        if (median <= 0f) return detected
        val ratio = detected / median
        return when {
            ratio in 1.7f..2.3f -> detected / 2f
            ratio in 0.43f..0.58f -> detected * 2f
            else -> detected
        }
    }

    private companion object {
        /** Hops of pitch track kept — marks lag detection by far less than this. */
        const val HOP_HISTORY = 64
    }
}
// ── Ring modulator — multiply by a carrier sine, dry/wet blend ────────────────
// out = (1−mix)·in + mix·(in·sin(2π·freq·t)). Carrier phase persists across
// chunks. Creating metallic/robotic sidebands; mix keeps intelligibility.
private class RingModProcessor(freqHz: Float, mix: Float, sr: Int) : BlockProcessor {
    private val inc = 2.0 * PI * freqHz / sr
    private val mix = mix.coerceIn(0f, 1f)
    private var phase = 0.0
    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        val carrier = sin(phase).toFloat()
        phase += inc; if (phase >= 2 * PI) phase -= 2 * PI
        input[i] * (1f - mix) + input[i] * carrier * mix
    }
}

// ── Compressor — feed-forward downward compression ────────────────────────────
// A peak envelope follower (5 ms attack / 100 ms release) drives a static
// downward curve: below [thresholdDb] the gain is unity; above it, the level
// over threshold is reduced by [ratio]:1 (in dB). No make-up gain. Envelope
// state persists across chunks. Simplified vs sox `compand` (no multi-segment
// transfer / per-segment timing) — the CLI side maps to a two-point compand.
private class CompressorProcessor(thresholdDb: Float, ratio: Float, sr: Int) : BlockProcessor {
    private val scale = Short.MAX_VALUE.toFloat()
    private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat() * scale
    private val ratio = ratio.coerceAtLeast(1f)
    private val attack = exp(-1.0 / (0.005 * sr)).toFloat()  // 5 ms
    private val release = exp(-1.0 / (0.100 * sr)).toFloat() // 100 ms
    private var env = 0f

    override fun process(input: FloatArray): FloatArray = FloatArray(input.size) { i ->
        val x = input[i]
        val rect = abs(x)
        env = if (rect > env) attack * env + (1f - attack) * rect
        else release * env + (1f - release) * rect
        val gain = if (env <= threshold || env <= 0f) {
            1f
        } else {
            val overDb = 20f * log10(env / threshold)
            10.0.pow((overDb * (1f / ratio - 1f) / 20f).toDouble()).toFloat() // < 1
        }
        x * gain
    }
}
