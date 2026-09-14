package app.marmalade.tts.engine.vits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phoneme→id mapper, against the REAL `uk-lada-x_low` phoneme map.
 *
 * This is the part of the engine most worth unit-testing: it's pure, and a
 * single wrong id or a dropped pad produces plausible-looking output that
 * renders as noise on device. The golden vector below came from a desktop run
 * against the actual checkpoint (2026-09-13) whose audio was listened to, so
 * it pins the mapping to something known-good rather than to itself.
 *
 * Robolectric only because the fixture is parsed with [VitsPackConfig]
 * (`org.json` is stubbed on a plain JVM); the code under test is pure Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VitsPhonemeIdsTest {

    private val idMap: Map<String, List<Int>> = VitsTestFixtures.ukLadaConfig().phonemeIdMap

    /**
     * espeak-ng (voice "uk") IPA for "Привіт." and
     * "Це тестовий голос Лада для мармелад." — already NFD-clean and free of
     * language-switch flags, as captured on desktop.
     */
    private val clause1 = "privˈit"
    private val clause2 = "tsˈɛ tistˈovij hˈoɭʌs ɭˈɑda dɭˈja marmˈeɭad"

    /**
     * Verified desktop reference for the two clauses as ONE utterance.
     *
     * Note for anyone comparing against the original task note: this vector
     * DOES include the per-clause `.` terminator (id 10) — `…32, 0, 10, 0,
     * 32…` at the clause join and `…10, 0, 2` at the end. Re-derived here
     * from the pack's own map; the "terminators disabled" reading of it was
     * mistaken, and [encode] with `appendTerminators = false` produces a
     * vector four entries shorter (see
     * [terminatorsAccountForExactlyTwoPadPairs]).
     */
    private val goldenIds = intArrayOf(
        1, 0, 28, 0, 30, 0, 21, 0, 34, 0, 120, 0, 21, 0, 32, 0, 10, 0, 32, 0, 31, 0, 120, 0,
        61, 0, 3, 0, 32, 0, 21, 0, 31, 0, 32, 0, 120, 0, 27, 0, 34, 0, 21, 0, 22, 0, 3, 0,
        20, 0, 120, 0, 27, 0, 77, 0, 102, 0, 31, 0, 3, 0, 77, 0, 120, 0, 51, 0, 17, 0, 14, 0,
        3, 0, 17, 0, 77, 0, 120, 0, 22, 0, 14, 0, 3, 0, 25, 0, 14, 0, 30, 0, 25, 0, 120, 0,
        18, 0, 77, 0, 14, 0, 17, 0, 10, 0, 2,
    )

    private val twoSentences = listOf(
        VitsClause(clause1, '.'),
        VitsClause(clause2, '.'),
    )

    @Test
    fun goldenUtteranceMatchesTheVerifiedDesktopReference() {
        val encoded = VitsPhonemeIds.encode(twoSentences, idMap)

        assertEquals("no phoneme should be missing from the pack's own map", emptyMap<Int, Int>(), encoded.missing)
        assertEquals(goldenIds.toList(), encoded.ids.toList())
    }

    @Test
    fun idsAreDerivedFromTheMapNotHardcoded() {
        // Independent derivation straight off the map: ^ _ then each phoneme
        // followed by _, terminator included, then $. If the mapper and this
        // loop ever disagree, one of them changed the contract.
        val expected = ArrayList<Int>()
        expected += idMap.getValue("^")
        expected += idMap.getValue("_")
        for (clause in twoSentences) {
            for (ch in clause.text + clause.terminator) {
                expected += idMap.getValue(ch.toString())
                expected += idMap.getValue("_")
            }
        }
        expected += idMap.getValue("$")

        assertEquals(expected, VitsPhonemeIds.encode(twoSentences, idMap).ids.toList())
        assertEquals("the independent derivation must also match the golden vector", goldenIds.toList(), expected)
    }

    @Test
    fun padInterspersalStructureHolds() {
        val ids = VitsPhonemeIds.encode(twoSentences, idMap).ids
        val pad = idMap.getValue("_").single()

        assertEquals("starts with the BOS marker", idMap.getValue("^").single(), ids.first())
        assertEquals("BOS is followed by a pad", pad, ids[1])
        assertEquals("ends with the EOS marker", idMap.getValue("$").single(), ids.last())
        // Every odd index up to EOS is a pad, every even index a real phoneme.
        for (i in 1 until ids.size - 1 step 2) {
            assertEquals("index $i should be a pad", pad, ids[i])
        }
        for (i in 2 until ids.size - 1 step 2) {
            assertTrue("index $i should be a phoneme id, was pad", ids[i] != pad)
        }
    }

    @Test
    fun terminatorsAccountForExactlyTwoPadPairs() {
        val withTerminators = VitsPhonemeIds.encode(twoSentences, idMap).ids
        val without = VitsPhonemeIds.encode(twoSentences, idMap, appendTerminators = false).ids

        // Two clauses × (terminator id + pad).
        assertEquals(withTerminators.size - 4, without.size)
        val fullStop = idMap.getValue(".").single()
        assertTrue("terminators must be absent", without.none { it == fullStop })
    }

    @Test
    fun clauseMarksGetATrailingSpacePhoneme() {
        val comma = idMap.getValue(",").single()
        val space = idMap.getValue(" ").single()
        val ids = VitsPhonemeIds.encode(listOf(VitsClause("privˈit", ',')), idMap).ids.toList()

        val commaAt = ids.indexOf(comma)
        assertTrue("comma id should be present", commaAt >= 0)
        assertEquals("comma is pad-separated", idMap.getValue("_").single(), ids[commaAt + 1])
        assertEquals("a clause mark is followed by a space phoneme", space, ids[commaAt + 2])
    }

    @Test
    fun anUnpunctuatedClauseGetsTheDefaultTerminator() {
        val withNull = VitsPhonemeIds.encode(listOf(VitsClause("privˈit", null)), idMap).ids
        val withStop = VitsPhonemeIds.encode(listOf(VitsClause("privˈit", '.')), idMap).ids

        assertEquals('.', VitsPhonemeIds.DEFAULT_TERMINATOR)
        assertEquals(withStop.toList(), withNull.toList())
    }

    @Test
    fun espeakLanguageSwitchFlagsAreDropped() {
        // espeak emits "(en)word(uk)" when it switches language mid-clause.
        // Neither the parentheses nor the code inside them are phonemes.
        val flagged = VitsPhonemeIds.encode(listOf(VitsClause("pri(en)vˈit", '.')), idMap)
        val clean = VitsPhonemeIds.encode(listOf(VitsClause("privˈit", '.')), idMap)

        assertEquals(emptyMap<Int, Int>(), flagged.missing)
        assertEquals(clean.ids.toList(), flagged.ids.toList())
    }

    @Test
    fun inputIsNfdNormalizedBeforeLookup() {
        // "í" (U+00ED) is not a key in the map; its NFD decomposition is
        // "i" + U+0301, and "i" is. So a mapper that normalizes emits the /i/
        // and reports only the combining accent as missing — one that doesn't
        // would emit nothing and report U+00ED.
        val encoded = VitsPhonemeIds.encode(listOf(VitsClause("í", '.')), idMap)

        assertEquals(mapOf(0x0301 to 1), encoded.missing)
        assertTrue(
            "the base vowel must still be emitted",
            encoded.ids.contains(idMap.getValue("i").single()),
        )
    }

    @Test
    fun unknownPhonemesAreSkippedAndCounted() {
        // "🙂" is outside the BMP — proof the walk is codepoint-based: a
        // char-based loop would report two missing surrogate halves.
        val encoded = VitsPhonemeIds.encode(listOf(VitsClause("pri🙂🙂t", '.')), idMap)

        assertEquals(mapOf(0x1F642 to 2), encoded.missing)
        assertEquals("U+1F642×2", VitsPhonemeIds.describeMissing(encoded.missing))
        // The surrounding phonemes survive — a missing symbol costs a sound,
        // not the sentence.
        assertEquals(
            VitsPhonemeIds.encode(listOf(VitsClause("prit", '.')), idMap).ids.toList(),
            encoded.ids.toList(),
        )
    }

    @Test
    fun emptyInputEncodesToTheBareWrapping() {
        val encoded = VitsPhonemeIds.encode(emptyList(), idMap)

        assertEquals(
            listOf(idMap.getValue("^").single(), idMap.getValue("_").single(), idMap.getValue("$").single()),
            encoded.ids.toList(),
        )
    }

    // -- clause splitting ----------------------------------------------------

    @Test
    fun clausesSplitAtSentenceAndClauseMarks() {
        assertEquals(
            listOf(
                VitsClause("Привіт", '.'),
                VitsClause("це тест", ','),
                VitsClause("справді", '!'),
            ),
            VitsPhonemeIds.splitClauses("Привіт. це тест, справді!"),
        )
    }

    @Test
    fun anUnpunctuatedTailBecomesAClauseWithoutATerminator() {
        assertEquals(
            listOf(VitsClause("Привіт", '.'), VitsClause("ще щось", null)),
            VitsPhonemeIds.splitClauses("Привіт. ще щось"),
        )
    }

    @Test
    fun runsOfMarksCollapseAndBlankClausesAreDropped() {
        // "?!" is one boundary, not two, and the whitespace between "…" marks
        // must not become an empty clause (which would emit a bare terminator).
        assertEquals(
            listOf(VitsClause("Що", '?'), VitsClause("ага", '.')),
            VitsPhonemeIds.splitClauses("Що?! ... ага."),
        )
        assertEquals(emptyList<VitsClause>(), VitsPhonemeIds.splitClauses("   ,. !  "))
        assertEquals(emptyList<VitsClause>(), VitsPhonemeIds.splitClauses(""))
    }
}
