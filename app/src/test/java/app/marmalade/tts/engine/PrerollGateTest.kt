package app.marmalade.tts.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [PrerollGate], the speed-aware pre-roll behind the Kokoro-at-2×
 * between-sentence underrun fix (measured 2026-09-19 on the Pixel 8a:
 * warm RTF 0.51–0.56 on the engine clock ≈ realtime with no headroom
 * once a 2× time-stretch halves the played duration).
 */
class PrerollGateTest {

    /** Feed [renders] as (renderMs, audioMs) chunks; return emit counts after each. */
    private fun emitsPerChunk(
        playbackRate: Float,
        renders: List<Pair<Long, Long>>,
    ): Pair<PrerollGate<Int>, List<Int>> {
        val gate = PrerollGate<Int>(playbackRate, totalChunks = renders.size)
        val counts = renders.mapIndexed { idx, (renderMs, audioMs) ->
            gate.onChunkRendered(idx, renderMs, audioMs).size
        }
        return gate to counts
    }

    @Test
    fun `realtime headroom at 1x keeps K at 1 and emits as rendered`() {
        // The measured 8a warm case: RTF ~0.55, no stretch.
        val (gate, counts) = emitsPerChunk(1.0f, List(4) { 550L to 1000L })
        assertEquals(1, gate.prerollChunks)
        assertEquals(listOf(1, 1, 1, 1), counts)
        assertEquals(0, gate.drain().size)
    }

    @Test
    fun `same engine at 1_5x still has headroom so K stays 1`() {
        // 0.55 × 1.5 = 0.825 played RTF, under the 0.9 margin: no arbitrary
        // speed threshold, the decision falls out of the measured rate.
        val (gate, counts) = emitsPerChunk(1.5f, List(3) { 550L to 1000L })
        assertEquals(1, gate.prerollChunks)
        assertEquals(listOf(1, 1, 1), counts)
    }

    @Test
    fun `the measured 2x underrun case holds chunk 0 and flushes both on chunk 1`() {
        // Acceptance case: 2-sentence text at 2.0×. RTF 0.56 → played RTF
        // 1.12; deficit over the 1 remaining chunk needs 1 banked chunk.
        val (gate, counts) = emitsPerChunk(2.0f, List(2) { 1120L to 2000L })
        assertEquals(2, gate.prerollChunks)
        assertEquals(listOf(0, 2), counts)
    }

    @Test
    fun `mild deficit on a long text banks one chunk not the whole text`() {
        // played RTF 1.02 over 8 chunks: 7 × 0.12 (vs the 0.9 margin) → K=2.
        val (gate, counts) = emitsPerChunk(2.0f, List(8) { 1020L to 2000L })
        assertEquals(2, gate.prerollChunks)
        assertEquals(listOf(0, 2, 1, 1, 1, 1, 1, 1), counts)
    }

    @Test
    fun `K is capped so TTFA stays bounded`() {
        // Grossly sub-realtime: uncapped K would be 1 + ceil(9 × 1.1) = 11.
        val (gate, _) = emitsPerChunk(2.0f, List(10) { 4000L to 2000L })
        assertEquals(PrerollGate.MAX_PREROLL_CHUNKS, gate.prerollChunks)
    }

    @Test
    fun `a single-chunk utterance never waits`() {
        val (gate, counts) = emitsPerChunk(2.0f, listOf(4000L to 2000L))
        assertEquals(listOf(1), counts)
        assertEquals(0, gate.drain().size)
    }

    @Test
    fun `last chunk always flushes even inside the preroll window`() {
        // K would be 3, but the text only has 2 chunks: chunk 1 is the last,
        // so it flushes rather than waiting for a chunk 2 that never comes.
        val gate = PrerollGate<Int>(playbackRate = 2.0f, totalChunks = 2, maxPreroll = 3)
        assertEquals(0, gate.onChunkRendered(0, 4000L, 2000L).size)
        assertEquals(2, gate.onChunkRendered(1, 4000L, 2000L).size)
    }

    @Test
    fun `drain returns held chunks when the producer ends early`() {
        // 3 nominal chunks, K=3, but chunk 2 phonemized to nothing so
        // onChunkRendered is never called for it: the flush never triggers
        // and drain() must hand back chunks 0 and 1 in order.
        val gate = PrerollGate<Int>(playbackRate = 2.0f, totalChunks = 3)
        gate.onChunkRendered(0, 4000L, 2000L)
        gate.onChunkRendered(1, 4000L, 2000L)
        assertEquals(listOf(0, 1), gate.drain())
        assertEquals(0, gate.drain().size)
    }

    @Test
    fun `zero-length audio on chunk 0 leaves K at 1`() {
        val gate = PrerollGate<Int>(playbackRate = 2.0f, totalChunks = 3)
        gate.onChunkRendered(0, 100L, 0L)
        assertEquals(1, gate.prerollChunks)
    }
}
