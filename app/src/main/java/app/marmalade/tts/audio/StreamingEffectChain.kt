package app.marmalade.tts.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
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

// ── Monotone — YIN pitch detect + dynamic pitch shift toward a target ─────────
// Two coupled stages, all state persistent across chunks (chunk-invariance is
// pinned by StreamingEffectChainTest):
//
//  1. Analyser. Collect non-overlapping 2048-sample windows. RMS-gate to skip
//     near-silent windows so the detector doesn't re-lock on decaying tails.
//     On voiced windows run YIN (cumulative-mean normalized difference over
//     τ in [sr/400, sr/80]); take the median over the last 3 detections;
//     octave-error guard against ~2×/~0.5× outliers. Convert to a target
//     cents = 1200·log2(targetHz / pitch) clamped to ±900 cents.
//
//  2. Shifter. Same grain-buffer + Hann-crossfade architecture as the static
//     PitchProcessor, but `inc` is recomputed every sample from a glide-
//     smoothed `currentCents` (~120 ms time constant — the whole point of
//     monotone is that the correction shouldn't track micro-pitch wobble).
//     On the *first* valid detection we snap currentCents directly to the
//     target instead of gliding from 0 — that's what kills the audible
//     start-of-utterance warble.
//
// Cost-wise the YIN inner loop is still the hot path (~240 × 1750 ≈ 420 K
// multiplications every 2048 samples ≈ 5 M ops/s at 24 kHz).
private class MonotoneProcessor(targetHz: Float, private val sr: Int) : BlockProcessor {
    // -- Detector state ------------------------------------------------------
    private val analysisWindow = 2048
    private val analysisBuf = FloatArray(analysisWindow)
    private var analysisFill = 0
    private val minTau = (sr / 400f).toInt().coerceAtLeast(2) // 400 Hz max
    private val maxTau = (sr / 80f).toInt().coerceAtMost(analysisWindow / 2 - 1) // 80 Hz min
    private val yinThreshold = 0.15f
    private val target = targetHz.coerceIn(50f, 800f)
    private val rmsGate = 200f      // skip ~< -40 dBFS — silence/tail
    private val maxAbsCents = 900f  // ±7.5 semitones — clip extreme corrections
    private val hysteresisCents = 30f // hold target if change < ¼ semitone
    private val recentHz = FloatArray(3) { -1f }
    private var recentIdx = 0
    private var hasLocked = false   // first valid detection snaps; later glides

    // -- Smoothed correction state ------------------------------------------
    private var targetCents = 0f
    private var currentCents = 0f
    // ~200 ms glide. Even slower than before — the time-domain crossfade
    // shifter renders fast cents changes as audible "turntable wow," so we
    // want targetCents to settle for a long time between updates.
    private val glideCoeff = kotlin.math.exp(-1.0 / (0.200 * sr)).toFloat()

    // -- Shifter state (grain-buffer crossfade, same idiom as PitchProcessor) -
    private val n = (sr * 0.05).toInt().coerceAtLeast(2)
    private val grainBuf = FloatArray(n)
    private var writeIdx = 0
    private var phase = 0.0

    override fun process(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        for (i in input.indices) {
            // 1. Feed the analyser. When a window fills, run the detection
            //    pipeline (RMS gate → YIN → median → octave guard).
            analysisBuf[analysisFill++] = input[i]
            if (analysisFill >= analysisWindow) {
                detectAndUpdate()
                analysisFill = 0
            }

            // 2. Glide currentCents toward targetCents (clamped).
            currentCents = currentCents * glideCoeff + targetCents * (1f - glideCoeff)
            if (currentCents > maxAbsCents) currentCents = maxAbsCents
            else if (currentCents < -maxAbsCents) currentCents = -maxAbsCents

            // 3. Dynamic pitch shift (same crossfade engine as PitchProcessor).
            grainBuf[writeIdx] = input[i]
            val inc = 1.0 - 2.0.pow(currentCents.toDouble() / 1200.0)
            var p2 = phase + n / 2.0
            if (p2 >= n) p2 -= n
            val w1 = 0.5 * (1 - kotlin.math.cos(2.0 * PI * phase / n))
            val w2 = 0.5 * (1 - kotlin.math.cos(2.0 * PI * p2 / n))
            out[i] = (w1 * grainRead(phase) + w2 * grainRead(p2)).toFloat()
            writeIdx++; if (writeIdx >= n) writeIdx = 0
            phase += inc
            if (phase >= n) phase -= n
            if (phase < 0) phase += n
        }
        return out
    }

    // Runs once per filled analysis window. Updates targetCents only when we
    // have a confident detection — silence and tail decay HOLD the previous
    // value, which is what prevents the end-of-utterance hard shift.
    private fun detectAndUpdate() {
        // RMS gate — skip near-silent windows so the detector doesn't run on
        // utterance tails / inter-word gaps.
        var sumSq = 0.0
        for (s in analysisBuf) sumSq += s * s
        val rms = kotlin.math.sqrt(sumSq / analysisBuf.size).toFloat()
        if (rms < rmsGate) return

        val detected = yin()
        if (detected <= 0f) return

        // Push into the recent-detection ring + compute median for stability.
        recentHz[recentIdx] = detected
        recentIdx = (recentIdx + 1) % recentHz.size
        val median = medianOfValid(recentHz)
        if (median <= 0f) return

        // Octave-error guard: YIN occasionally drops/doubles an octave on
        // transients. If `detected` is ~2× or ~0.5× the median, collapse it
        // back to the median's octave before computing the correction.
        val corrected = octaveCorrect(detected, median)

        val ratio = target / corrected
        if (ratio <= 0f) return
        val newTargetCents = (1200.0 * (ln(ratio.toDouble()) / LN2)).toFloat()
            .coerceIn(-maxAbsCents, maxAbsCents)

        if (!hasLocked) {
            // First lock — snap so the start of speech doesn't audibly glide
            // from "no shift" (cents=0) up to the correction.
            targetCents = newTargetCents
            currentCents = newTargetCents
            hasLocked = true
        } else if (kotlin.math.abs(newTargetCents - targetCents) >= hysteresisCents) {
            // Hysteresis: only update if the new estimate moves more than
            // ¼ semitone. Smaller drifts (vibrato, vowel pitch sweep) hold —
            // that's what kills the residual turntable wobble within voiced
            // segments without blocking real pitch changes between phrases.
            targetCents = newTargetCents
        }
    }

    private fun medianOfValid(buf: FloatArray): Float {
        // Tiny ring (3 elements) — sort in place via three comparisons.
        var a = -1f; var b = -1f; var c = -1f
        for (v in buf) {
            if (v <= 0f) continue
            when {
                a < 0f -> a = v
                b < 0f -> b = v
                else -> c = v
            }
        }
        return when {
            a < 0f -> -1f
            b < 0f -> a
            c < 0f -> (a + b) / 2f
            else -> {
                // median of three
                val hi = maxOf(a, b, c)
                val lo = minOf(a, b, c)
                a + b + c - hi - lo
            }
        }
    }

    private fun octaveCorrect(detected: Float, median: Float): Float {
        if (median <= 0f) return detected
        val ratio = detected / median
        return when {
            ratio in 1.7f..2.3f -> detected / 2f
            ratio in 0.43f..0.58f -> detected * 2f
            else -> detected
        }
    }

    // Read `delay` samples behind the write head, fractional, wrapping mod n.
    private fun grainRead(delay: Double): Double {
        var pos = writeIdx - delay
        while (pos < 0) pos += n
        val lo = pos.toInt()
        val hi = if (lo + 1 >= n) 0 else lo + 1
        val frac = pos - lo
        return grainBuf[lo] * (1 - frac) + grainBuf[hi] * frac
    }

    /**
     * YIN pitch detector. Returns Hz, or -1 if no clear pitch was found
     * (silence, noise, octave ambiguity). Operates on [analysisBuf].
     */
    private fun yin(): Float {
        val n = analysisWindow
        val d = FloatArray(maxTau + 1)
        for (tau in minTau..maxTau) {
            var sum = 0f
            val limit = n - maxTau
            for (i in 0 until limit) {
                val diff = analysisBuf[i] - analysisBuf[i + tau]
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
        if (kotlin.math.abs(denom) < 1e-9f) return x
        return x + 0.5f * (a - c) / denom
    }

    companion object {
        private val LN2 = ln(2.0)
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
