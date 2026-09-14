package app.marmalade.tts.engine.vits

import java.text.Normalizer

/**
 * One clause of input: the text (or phonemes) plus the punctuation mark that
 * ended it in the source. `null` terminator means the clause ran to the end of
 * the input without punctuation.
 */
data class VitsClause(val text: String, val terminator: Char?)

/**
 * Text → phoneme-id mapping for Piper-class VITS checkpoints.
 *
 * Pure functions only — no espeak, no ORT, no Android. The engine
 * ([VitsDirectEngine]) phonemizes each clause and hands the results here.
 *
 * ## Why this is reimplemented rather than ported
 *
 * The maintained Piper fork (OHF-Voice/piper1-gpl) is GPL-3.0 and no line of
 * it is used or distributed. What follows is a fresh Kotlin implementation of
 * the *semantics* (as documented by the MIT-era `rhasspy/piper-phonemize`
 * behaviour and verified against the real checkpoint on desktop, 2026-09-13):
 *
 *  1. Phonemize the clause with espeak in IPA mode using the pack's
 *     `espeak.voice`.
 *  2. NFD-normalise the IPA (the map's keys are decomposed single
 *     codepoints — a precomposed IPA character would miss).
 *  3. Walk **codepoints**, not chars: some IPA lives outside the BMP, and a
 *     surrogate half is meaningless as a map key.
 *  4. Drop espeak language-switch flags — everything from `(` through `)`.
 *  5. Append the clause's own terminator phoneme (`.`/`?`/`!`/`,`/…), which
 *     is itself an entry in the phoneme map; espeak's IPA output carries no
 *     punctuation, so prosody would otherwise be lost. `,`/`:`/`;` get a
 *     following space, matching how espeak separates a continued clause.
 *  6. Interleave the pad symbol: `^ _ (p _)* $`.
 *
 * Phonemes absent from the map are skipped and counted so the engine can log
 * them once per utterance instead of failing the synth — a missing symbol
 * costs one sound, not the sentence.
 *
 * ## Grapheme ("text") packs
 *
 * Some checkpoints are trained on characters rather than IPA
 * (`phoneme_type: "text"`). Those go through [encodeText] instead: no espeak,
 * no clause splitting, no terminator synthesis — punctuation is already in the
 * map and reaches the model as itself. See that function.
 */
object VitsPhonemeIds {

    /** Punctuation that ends a sentence. */
    private const val SENTENCE_MARKS = ".!?"

    /** Punctuation that ends a clause within a sentence. */
    private const val CLAUSE_MARKS = ",;:"

    /**
     * Terminator used for a clause the source didn't punctuate (typically the
     * tail of an unpunctuated chunk). A full stop is the safe default: it
     * gives the model the sentence-final prosody it was trained to end on.
     */
    const val DEFAULT_TERMINATOR: Char = '.'

    /** Result of [encode]. */
    data class Encoded(
        /** The model's `input` tensor contents, pad-interspersed and wrapped. */
        val ids: IntArray,
        /**
         * Phonemes absent from the pack's map, as codepoint → occurrence
         * count, in first-seen order. Empty on a clean encode.
         */
        val missing: Map<Int, Int>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Encoded) return false
            return ids.contentEquals(other.ids) && missing == other.missing
        }

        override fun hashCode(): Int = 31 * ids.contentHashCode() + missing.hashCode()
    }

    /**
     * Split [text] into clauses at sentence and clause punctuation, keeping
     * each clause's terminator.
     *
     * Punctuation is consumed into the terminator, so the text handed to
     * espeak is punctuation-free (espeak ignores it for phonemes anyway) and
     * the terminator reaches the model as its own phoneme id. Runs of marks
     * (`?!`, `...`) collapse to the first one. Whitespace-only clauses are
     * dropped.
     */
    fun splitClauses(text: String): List<VitsClause> {
        val out = ArrayList<VitsClause>()
        val buf = StringBuilder()
        for (ch in text) {
            if (ch in SENTENCE_MARKS || ch in CLAUSE_MARKS) {
                val body = buf.toString().trim()
                buf.setLength(0)
                // A mark with nothing in front of it (a run like "?!" or a
                // leading comma) adds no clause of its own; the first mark
                // already terminated the clause it belonged to.
                if (body.isNotEmpty()) out.add(VitsClause(body, ch))
            } else {
                buf.append(ch)
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) out.add(VitsClause(tail, null))
        return out
    }

    /**
     * Map already-phonemized [clauses] to the model's id sequence.
     *
     * @param clauses each clause's espeak IPA plus the terminator to append.
     * @param idMap the pack's `phoneme_id_map`.
     * @param appendTerminators false suppresses step 5 above — used by tests
     *        that want the bare phoneme mapping.
     */
    fun encode(
        clauses: List<VitsClause>,
        idMap: Map<String, List<Int>>,
        appendTerminators: Boolean = true,
    ): Encoded {
        val padIds = marker(idMap, VitsPackConfig.PAD)
        val bosIds = marker(idMap, VitsPackConfig.BOS)
        val eosIds = marker(idMap, VitsPackConfig.EOS)

        val ids = IntList(initialCapacity = 64)
        val missing = LinkedHashMap<Int, Int>()

        ids.addAll(bosIds)
        ids.addAll(padIds)

        for (clause in clauses) {
            val normalized = Normalizer.normalize(clause.text, Normalizer.Form.NFD)
            var inLanguageSwitch = false
            var i = 0
            while (i < normalized.length) {
                val cp = normalized.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp == '('.code -> inLanguageSwitch = true
                    cp == ')'.code -> inLanguageSwitch = false
                    inLanguageSwitch -> Unit
                    else -> appendPhoneme(cp, idMap, padIds, ids, missing)
                }
            }
            if (appendTerminators) {
                val terminator = clause.terminator ?: DEFAULT_TERMINATOR
                appendPhoneme(terminator.code, idMap, padIds, ids, missing)
                // espeak renders a mid-sentence break as "<mark> " — the
                // trailing space is a trained token, so a comma without it
                // reads as a tighter join than the model expects.
                if (terminator in CLAUSE_MARKS) {
                    appendPhoneme(' '.code, idMap, padIds, ids, missing)
                }
            }
        }

        ids.addAll(eosIds)
        return Encoded(ids = ids.toIntArray(), missing = missing)
    }

    /**
     * Map raw [text] to the id sequence of a **grapheme** ("text") pack.
     *
     * These checkpoints learned characters→audio, so there is no phonemizer in
     * the path at all. Reimplemented from the semantics of the MIT-era
     * `rhasspy/piper-phonemize` `phonemize_codepoints` with its default
     * `CASING_FOLD`, as documented in each pack's `PROVENANCE.md`:
     *
     *  1. Case-fold the text. **Deviation:** the JDK has no case-folding API
     *     (`java.lang.Character` offers only `toLowerCase`), so this uses
     *     locale-independent [String.lowercase], which agrees with full
     *     case-folding for every script these packs cover. It differs for a
     *     handful of characters elsewhere — German `ß` folds to `ss` but
     *     lowercases to itself — which costs at most one character's id in a
     *     language no text-mode pack ships.
     *  2. NFD-normalise, because the map's keys are decomposed single
     *     codepoints (Ukrainian's stress mark is its own entry).
     *  3. Walk codepoints and map each through `phoneme_id_map`, with the same
     *     `^ _ (p _)* $` interspersal the espeak path uses.
     *
     * What this deliberately does NOT do, unlike [encode]:
     *  - no clause splitting: the model was trained on punctuated text and the
     *    punctuation marks are themselves entries in the map, so they flow
     *    through as literal characters;
     *  - no terminator synthesis, for the same reason — adding a full stop
     *    would put a sound in the input the writer didn't;
     *  - no `(lang)`-flag stripping: those are espeak artefacts and cannot
     *    appear in user text (a literal parenthesis is just a character, and
     *    typically one the map doesn't have, so it is skipped and counted).
     *
     * Missing codepoints are skipped and tallied exactly as in [encode].
     */
    fun encodeText(text: String, idMap: Map<String, List<Int>>): Encoded {
        val padIds = marker(idMap, VitsPackConfig.PAD)
        val bosIds = marker(idMap, VitsPackConfig.BOS)
        val eosIds = marker(idMap, VitsPackConfig.EOS)

        val ids = IntList(initialCapacity = 64)
        val missing = LinkedHashMap<Int, Int>()

        ids.addAll(bosIds)
        ids.addAll(padIds)

        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            appendPhoneme(cp, idMap, padIds, ids, missing)
        }

        ids.addAll(eosIds)
        return Encoded(ids = ids.toIntArray(), missing = missing)
    }

    /** Format a missing-phoneme tally as `U+0069×2, U+02C8×1` for logging. */
    fun describeMissing(missing: Map<Int, Int>): String =
        missing.entries.joinToString(", ") { (cp, count) ->
            "U+%04X×%d".format(cp, count)
        }

    /** A wrapping marker's ids, or a loud failure — [VitsPackConfig] requires them. */
    private fun marker(idMap: Map<String, List<Int>>, key: String): List<Int> =
        idMap[key] ?: error("phoneme_id_map has no '$key' entry")

    private fun appendPhoneme(
        codePoint: Int,
        idMap: Map<String, List<Int>>,
        padIds: List<Int>,
        out: IntList,
        missing: LinkedHashMap<Int, Int>,
    ) {
        val key = String(Character.toChars(codePoint))
        val mapped = idMap[key]
        if (mapped == null) {
            missing[codePoint] = (missing[codePoint] ?: 0) + 1
            return
        }
        out.addAll(mapped)
        out.addAll(padIds)
    }

    /**
     * Minimal growable int buffer. An `ArrayList<Int>` would box every id;
     * this is the same primitive-cursor pattern the Kokoro/Kitten encoders
     * use, just factored out because the length isn't known up front (pad
     * interspersal and multi-id phonemes both change the ratio).
     */
    private class IntList(initialCapacity: Int) {
        private var buf = IntArray(initialCapacity.coerceAtLeast(8))
        private var size = 0

        fun addAll(values: List<Int>) {
            ensure(size + values.size)
            for (v in values) buf[size++] = v
        }

        fun toIntArray(): IntArray = buf.copyOf(size)

        private fun ensure(capacity: Int) {
            if (capacity <= buf.size) return
            buf = buf.copyOf(maxOf(buf.size * 2, capacity))
        }
    }
}
