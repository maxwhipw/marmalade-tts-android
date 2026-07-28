package app.marmalade.tts.util

import java.net.URLEncoder

// -----------------------------------------------------------------------------
// BugReportUrl — prefilled GitHub new-issue link for Settings → Report a bug.
//
// Deliberately a lightweight markdown body, not a GitHub issue form: the
// bar for reporting should be "type what went wrong and hit submit". The
// environment block users can never remember (version, flavor, Android,
// device) is filled in for them; the prompts are questions, not fields.
//
// Pronunciation reports feed the per-engine phoneme patch table in
// phonemizer/EnPhonemeFixups.kt — hence the explicit ask for the exact
// sentence and voice.
// -----------------------------------------------------------------------------

object BugReportUrl {

    private const val NEW_ISSUE = "https://github.com/maxwhipw/marmalade-tts-android/issues/new"

    fun build(
        versionName: String,
        flavor: String,
        androidVersion: String,
        deviceModel: String,
    ): String {
        val body = """
            **What happened?**


            **What did you expect instead?**


            **If a word or sentence sounds wrong:** paste the exact text, and name the voice you were using.

            ---
            _Marmalade TTS $versionName ($flavor) · Android $androidVersion · ${deviceModel}_
        """.trimIndent()
        return "$NEW_ISSUE?body=${encode(body)}"
    }

    /** URLEncoder is form-encoding (space → `+`); GitHub wants percent-encoding. */
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
