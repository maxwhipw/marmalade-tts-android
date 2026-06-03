package app.marmalade.tts.audio

import kotlin.math.PI
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
        // Carries multiple coupled states across seams: the 1024-sample
        // analysis-buffer fill index, the smoothed-correction cents, and the
        // grain-buffer + phase of the dynamic pitch shifter. A reset of any
        // one of them would change downstream samples — bit-identical
        // chunked == whole pins all three.
        val pcm = signal(20_000)
        val chain = listOf(EffectBlock.Monotone(160f))
        assertArrayEquals(streamWhole(chain, pcm), streamChunked(chain, pcm, 333))
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
