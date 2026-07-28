package app.marmalade.tts.phonemizer

// -----------------------------------------------------------------------------
// EnPhonemeFixups — post-espeak corrections for English IPA output.
//
// espeak-ng (verified on 1.51 and the pinned 1.52 submodule) has no
// dictionary entry for some common informal words and falls back to
// letter-to-sound rules that get them wrong. The canonical case is
// "yeah": en_list/en_extra carry no entry, so LTS produces /jɛh/ with a
// literal aspirated [h] — audibly "yeh-h" — instead of /jɛə/. Misaki
// (Kokoro's reference phonemizer) transcribes it jˈɛə.
//
// Fixing the compiled en_dict inside the engine bundle would require a
// bundle re-spin every espeak data update; a phoneme-level substitution
// here covers every English-voiced engine (Kitten, Kokoro) at once.
//
// Entries match at a word start (string start or after the space that
// separates words in espeak's sentence-mode output) and deliberately do
// NOT require a right-hand boundary: espeak phonemizes "yeah's" as
// /jɛhz/, which must also be caught. No real English word begins
// /jɛh/ + consonant, so the open right side is safe.
// -----------------------------------------------------------------------------

internal object EnPhonemeFixups {

    // (regex, replacement) pairs. $1 preserves espeak's stress mark
    // (primary ˈ, secondary ˌ, or none), which precedes the vowel.
    private val FIXUPS: List<Pair<Regex, String>> = listOf(
        // yeah: LTS /jɛh/ → /jɛə/ (final [h] becomes a schwa off-glide)
        Regex("""(?<=^| )j([ˈˌ]?)ɛh""") to "j$1ɛə",
    )

    /** Apply all fixups to an espeak IPA string produced with an English voice. */
    fun apply(phonemes: String): String {
        var out = phonemes
        for ((re, replacement) in FIXUPS) {
            out = re.replace(out, replacement)
        }
        return out
    }
}
