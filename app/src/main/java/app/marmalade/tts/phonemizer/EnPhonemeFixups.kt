package app.marmalade.tts.phonemizer

// -----------------------------------------------------------------------------
// EnPhonemeFixups — post-espeak corrections for English IPA output.
//
// espeak-ng (verified on 1.51, the pinned 1.52 submodule, and current
// upstream master) has no dictionary entry for some common informal
// words and falls back to letter-to-sound rules that get them wrong.
// The canonical case is "yeah": no en_list/en_extra entry, so LTS
// produces /jɛh/ with a literal aspirated [h] — audibly "yeh-h".
//
// The replacement is per acoustic model, chosen by ear (2026-07-27
// A/B lab, scratch/yeah):
//   - Kokoro gets /jɛə/ — misaki's gold-lexicon transcription, i.e.
//     the exact string Kokoro saw for "yeah" in training.
//   - Kitten renders /jɛə/ poorly (15M params; the ɛ→ə glide comes
//     out mangled on every model size), so it gets flat /jæ/ ("ya"),
//     which won the listening test bar none.
//
// Entries match at a word start (string start or after the space that
// separates words in espeak's sentence-mode output) and deliberately do
// NOT require a right-hand boundary: espeak phonemizes "yeah's" as
// /jɛhz/, which must also be caught. No real English word begins
// /jɛh/ + consonant, so the open right side is safe.
// -----------------------------------------------------------------------------

object EnPhonemeFixups {

    /** Which acoustic model the phonemes are destined for. */
    enum class Model { KITTEN, KOKORO }

    // $1 preserves espeak's stress mark (primary ˈ, secondary ˌ, or
    // none), which precedes the vowel.
    private val YEAH = Regex("""(?<=^| )j([ˈˌ]?)ɛh""")

    private val YEAH_REPLACEMENT = mapOf(
        Model.KITTEN to "j$1æ",
        Model.KOKORO to "j$1ɛə",
    )

    /** Apply all fixups to an espeak IPA string produced with an English voice. */
    fun apply(phonemes: String, model: Model): String =
        YEAH.replace(phonemes, YEAH_REPLACEMENT.getValue(model))
}
