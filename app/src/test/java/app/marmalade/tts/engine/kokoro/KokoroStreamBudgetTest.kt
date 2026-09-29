package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.audio.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Test

/** [kokoroStreamBudget]: first-piece sizing from the measured speed (T9). */
class KokoroStreamBudgetTest {

    private val base = TextChunker.TokenBudget(mergeFloor = 90, target = 200, firstPiece = 40, growth = 1.5)

    @Test
    fun unmeasuredKeepsTheDefaults() {
        val b = kokoroStreamBudget(base, Double.NaN, Double.NaN, 1f)
        assertEquals(40, b.firstPiece)
        assertEquals(1.5, b.growth, 1e-9)
        assertEquals(base.mergeFloor, b.mergeFloor)
    }

    @Test
    fun firstPieceAimsAtAboutOnePointOneSecondOfRender() {
        // Pixel 8a: ~27 ms/token cool (big chunks) → ~41 tokens; ~52 hot → ~21.
        assertEquals(41, kokoroStreamBudget(base, 27.0, 0.5, 1f).firstPiece)
        assertEquals(21, kokoroStreamBudget(base, 52.0, 0.95, 1f).firstPiece)
        // Clamped to 20..45.
        assertEquals(45, kokoroStreamBudget(base, 10.0, 0.2, 1f).firstPiece)
        assertEquals(20, kokoroStreamBudget(base, 100.0, 1.8, 1f).firstPiece)
    }

    @Test
    fun piecesGrowAsFastAsPlaybackAllows() {
        assertEquals(1.8, kokoroStreamBudget(base, 27.0, 0.5, 1f).growth, 1e-9)
        // Hot at 1× or cool at 2×: no headroom, pieces stay the same size.
        assertEquals(1.0, kokoroStreamBudget(base, 52.0, 0.95, 1f).growth, 1e-9)
        assertEquals(1.0, kokoroStreamBudget(base, 27.0, 0.5, 2f).growth, 1e-9)
        assertEquals(2.0, kokoroStreamBudget(base, 10.0, 0.2, 1f).growth, 1e-9)
    }
}
