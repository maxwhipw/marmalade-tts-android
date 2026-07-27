package app.marmalade.tts.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the streaming DSP's **chunked == whole** guarantee: feeding a buffer
 * through [StreamingEffectChain] in pieces produces the exact same samples as
 * one big chunk. This is the cross-chunk state-continuity contract — if a
 * reverb ring buffer, echo delay line, or biquad filter memory reset at a seam,
 * the split result would diverge.
 *
 * Holds for every deterministic block (reverb/echo/bandpass/EQ/vol/overdrive,
 * and the Pitch/Tempo passthrough stubs). [EffectChain.applyChain] just runs
 * this engine whole-buffer, so it inherits the same output.
 *
 * Pure DSP — no `org.json`, no Android — so this runs on the plain JVM.
 */
class StreamingEffectChainTest {

    private val sr = 24_000

    private fun signal(n: Int): ShortArray =
        ShortArray(n) { i -> (1000.0 * sin(2.0 * PI * 220.0 * i / sr)).toInt().toShort() }

    private fun streamWhole(blocks: List<EffectBlock>, pcm: ShortArray): ShortArray {
        val chain = StreamingEffectChain(blocks, sr)
        return chain.process(pcm) + chain.flush()
    }

    private fun streamChunked(blocks: List<EffectBlock>, pcm: ShortArray, chunk: Int): ShortArray {
        val chain = StreamingEffectChain(blocks, sr)
        val out = ArrayList<Short>()
        var off = 0
        while (off < pcm.size) {
            val end = minOf(off + chunk, pcm.size)
            for (s in chain.process(pcm.copyOfRange(off, end))) out.add(s)
            off = end
        }
        for (s in chain.flush()) out.add(s)
        return out.toShortArray()
    }

    @Test
    fun `cave chunked equals whole`() {
        val pcm = signal(20_000)
        assertArrayEquals(
            streamWhole(EffectChain.CAVE_BLOCKS, pcm),
            streamChunked(EffectChain.CAVE_BLOCKS, pcm, 1024),
        )
    }

    @Test
    fun `telephone chunked equals whole with odd chunk size`() {
        // 777-sample chunks land seams at arbitrary offsets to stress the
        // bandpass biquad + overdrive state carry-over.
        val pcm = signal(20_000)
        assertArrayEquals(
            streamWhole(EffectChain.TELEPHONE_BLOCKS, pcm),
            streamChunked(EffectChain.TELEPHONE_BLOCKS, pcm, 777),
        )
    }

    @Test
    fun `vol + treble + reverb chunked equals whole`() {
        // Exercises the high-shelf EQ (Treble) + reverb tail across seams. An
        // ad-hoc chain rather than a preset: no shipped built-in uses Treble
        // anymore (was the Whisper preset, removed in v9), but the editor can
        // still build one, so the shelf-EQ seam coverage is worth keeping.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Vol(0.4f), EffectBlock.Treble(4f), EffectBlock.Reverb(20f))
        assertArrayEquals(
            streamWhole(chain, pcm),
            streamChunked(chain, pcm, 512),
        )
    }

    @Test
    fun `deep (pitch + bass) chunked equals whole`() {
        // Pins the pitch-shifter's ring buffer + phase carry-over across seams.
        val pcm = signal(20_000)
        assertArrayEquals(
            streamWhole(EffectChain.DEEP_BLOCKS, pcm),
            streamChunked(EffectChain.DEEP_BLOCKS, pcm, 640),
        )
    }

    @Test
    fun `chipmunk (pitch + tempo) chunked equals whole`() {
        // Pins the OLA time-stretch's input/output accumulators + the pitch
        // shifter across seams (tempo changes the output length, so this also
        // checks the emission boundary is chunk-invariant).
        val pcm = signal(20_000)
        assertArrayEquals(
            streamWhole(EffectChain.CHIPMUNK_BLOCKS, pcm),
            streamChunked(EffectChain.CHIPMUNK_BLOCKS, pcm, 900),
        )
    }

    // ── new effect blocks (E-J) — all must stream chunk-invariantly ──────────

    @Test
    fun `mid + lowpass + highpass chunked equals whole`() {
        // Three biquads in series — pins the peaking-EQ coefficients + filter
        // state carry-over across seams.
        val pcm = signal(20_000)
        val chain = listOf(
            EffectBlock.Highpass(150f),
            EffectBlock.Mid(1000f, 6f),
            EffectBlock.Lowpass(6000f),
        )
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 333))
    }

    @Test
    fun `tremolo chunked equals whole`() {
        // LFO phase must persist across chunks.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Tremolo(5f, 0.5f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 512))
    }

    @Test
    fun `flanger chunked equals whole`() {
        // Modulated delay line + LFO phase across seams (no feedback).
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Flanger(0.5f, 2f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 640))
    }

    @Test
    fun `chorus chunked equals whole`() {
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Chorus(0.25f, 2f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 700))
    }

    @Test
    fun `phaser chunked equals whole`() {
        // Feedback path (decay) makes the delay-line state carry essential —
        // a reset at a seam would diverge immediately.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Phaser(0.5f, 0.4f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 256))
    }

    @Test
    fun `compressor chunked equals whole`() {
        // Envelope-follower state must persist so gain reduction is identical
        // regardless of where the chunk boundaries fall.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Compressor(-20f, 4f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 480))
    }

    @Test
    fun `bitcrush chunked equals whole`() {
        // Sample-and-hold counter + held value must persist across seams.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Bitcrush(6f, 6f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 333))
    }

    @Test
    fun `ringmod chunked equals whole`() {
        // Carrier phase must persist; a reset at a seam would phase-jump.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.RingMod(60f, 0.7f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 512))
    }

    @Test
    fun `monotone chunked equals whole`() {
        // Every stage of the PSOLA flattener carries state across seams: the
        // sliding-YIN hop counter and its pitch-track ring, the analysis-mark
        // queue and peak-search cursor, and the overlap-add accumulator plus
        // its output cursor. All of it is keyed on absolute sample positions
        // precisely so this holds — a single decision made from "how much
        // input arrived this call" would break it.
        val pcm = glide(20_000, 180.0, 260.0)
        val chain = listOf(EffectBlock.Monotone(160f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 333))
    }

    @Test
    fun `monotone flattens a glide that starts after silence`() {
        // Real TTS output opens with a beat of silence, and that used to be
        // enough to disable the block entirely in whole-buffer mode. Marks
        // were gated on the input being *buffered* rather than *analysed*, so
        // handed the whole utterance at once they ran to the end of the buffer
        // while detection was still capped to its pitch-track ring. `f0At`
        // clamps a mark past the analysed region to the last analysed hop —
        // here, the silence — so every mark came out unvoiced, and unvoiced
        // marks are a passthrough by construction: Hann grains at their own
        // analysis spacing reconstruct the input exactly. The output was the
        // dry signal, sample for sample, and every existing test still passed
        // because the old fixture was voiced from sample 0.
        val lead = sr / 2
        val pcm = ShortArray(lead) + glide(48_000, 180.0, 260.0)
        val out = streamWhole(listOf(EffectBlock.Monotone(160f)), pcm)

        val dry = pcm.copyOfRange(lead, pcm.size)
        val shared = minOf(dry.size, out.size - lead)
        var diff = 0.0
        for (i in 0 until shared) diff += abs(dry[i] - out[lead + i].toInt()).toDouble()
        assertTrue("monotone must not degrade to a passthrough", diff / shared > 50.0)

        var checked = 0
        for (start in lead + sr / 4 until out.size - 2048 step 4096) {
            val hz = detectHz(out, start) ?: continue
            val cents = 1200.0 * kotlin.math.ln(hz / 160.0) / kotlin.math.ln(2.0)
            assertTrue(
                "output should sit on 160 Hz, got %.1f Hz (%.0f cents off) at %d".format(hz, cents, start),
                abs(cents) < 60.0,
            )
            checked++
        }
        assertTrue("expected several voiced measurements, got $checked", checked >= 5)
    }

    @Test
    fun `monotone flattens a pitch glide onto its target`() {
        // The contract nothing used to check: input F0 sweeps 180 → 260 Hz,
        // output must sit at the 160 Hz target throughout. The old
        // correction-chasing implementation passed everything faster than
        // ~200 ms straight through, so it would fail this by hundreds of
        // cents while still looking fine to the chunked==whole test.
        val pcm = glide(48_000, 180.0, 260.0)
        val out = streamWhole(listOf(EffectBlock.Monotone(160f)), pcm)

        // Skip the detector warm-up, then check across the whole sweep.
        val skip = sr / 4
        var checked = 0
        for (start in skip until out.size - 2048 step 4096) {
            val hz = detectHz(out, start) ?: continue
            val cents = 1200.0 * kotlin.math.ln(hz / 160.0) / kotlin.math.ln(2.0)
            assertTrue(
                "output should sit on 160 Hz, got %.1f Hz (%.0f cents off) at %d".format(hz, cents, start),
                kotlin.math.abs(cents) < 60.0,
            )
            checked++
        }
        assertTrue("expected several voiced measurements, got $checked", checked >= 5)
    }

    /** Sawtooth-ish harmonic tone whose F0 sweeps [fromHz] → [toHz]. */
    private fun glide(n: Int, fromHz: Double, toHz: Double): ShortArray {
        var phase = 0.0
        return ShortArray(n) { i ->
            val f = fromHz + (toHz - fromHz) * i / n
            phase += 2.0 * PI * f / sr
            var v = 0.0
            for (h in 1..6) v += sin(h * phase) / h
            (3000.0 * v).toInt().toShort()
        }
    }

    /**
     * Normalized-autocorrelation pitch detector, for asserting on output only.
     * Takes the FIRST lag to clear the threshold rather than the strongest —
     * raw autocorrelation peaks just as hard at 2× the period, and picking the
     * max reports an octave down.
     */
    private fun detectHz(pcm: ShortArray, start: Int): Double? {
        val n = 2048
        val minTau = sr / 400
        val maxTau = sr / 80
        val len = n - maxTau
        var e0 = 0.0
        for (i in 0 until len) e0 += pcm[start + i].toDouble() * pcm[start + i]
        if (e0 / len < 200.0 * 200.0) return null
        var bestTau = -1
        var bestR = 0.0
        for (tau in minTau..maxTau) {
            var sum = 0.0
            var eT = 0.0
            for (i in 0 until len) {
                val a = pcm[start + i].toDouble()
                val b = pcm[start + i + tau].toDouble()
                sum += a * b
                eT += b * b
            }
            val r = if (eT > 0) sum / kotlin.math.sqrt(e0 * eT) else 0.0
            if (r > 0.85) return sr.toDouble() / tau
            if (r > bestR) { bestR = r; bestTau = tau }
        }
        return if (bestTau > 0 && bestR > 0.5) sr.toDouble() / bestTau else null
    }

    @Test
    fun `empty chain is a passthrough`() {
        val pcm = signal(1_000)
        val chain = StreamingEffectChain(emptyList(), sr)
        assertTrue(chain.isEmpty)
        assertArrayEquals(pcm, chain.process(pcm))
        assertEquals(0, chain.flush().size)
    }

    @Test
    fun `cave emits a reverb + echo tail`() {
        // The streamed output must be longer than the dry input — the reverb
        // rings out (decay-aware flush) and the echo tap trails the last input.
        val pcm = signal(4_000)
        val out = streamWhole(EffectChain.CAVE_BLOCKS, pcm)
        assertTrue("expected a tail beyond the input, got ${out.size} vs ${pcm.size}", out.size > pcm.size)
    }
}
