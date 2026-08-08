package app.marmalade.tts.lang

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog

/**
 * Per-engine rules for turning an alias's stored phonemization language
 * into the espeak code one utterance is actually phonemized with.
 *
 * The three synthesis routes (system TTS service, the share/clipboard
 * foreground service, the Speak screen) each resolve this once per
 * utterance before any chunking, and they must agree — hence one place
 * that decides, rather than the same `if` written three times.
 *
 * The rules, per [Max's 2026-08-07 design][LangDetector] plus his
 * 2026-08-08 call on Kitten:
 *
 * - **Kokoro** phonemizes through espeak in every language we ship, and
 *   auto-detect is its default: a null column means the same as the
 *   [LangDetector.AUTO] sentinel, which is what lets rows written before
 *   detection existed behave correctly with no migration.
 * - **Kitten** is trained on en-us IPA but phonemizes through espeak too,
 *   and the APK carries the full espeak data tree. So an alias that opts
 *   into auto-detect gets the detected language's espeak rules on the
 *   Kitten voice — accented and imperfect, and better than reading
 *   Spanish through English letter rules. It is opt-in, not the default:
 *   a null column stays English.
 * - **Pocket** doesn't phonemize through espeak at all, so there is
 *   nothing to point anywhere; a leftover sentinel clears to null.
 */
object UtteranceLanguage {

    /**
     * Whether [stored] means "detect this utterance's language" on
     * [engineName]. Only Kokoro reads a null column as auto-detect.
     */
    fun isAuto(engineName: String, stored: String?): Boolean =
        stored == LangDetector.AUTO ||
            (stored == null && engineName == KokoroDirectVoiceCatalog.ENGINE)

    /**
     * The espeak code [engineName] should phonemize [language] with on
     * [voiceId], or null for "the engine's own default".
     *
     * [language] is a detected ISO-639-1 code (or the request locale's,
     * when detection abstained), not an espeak code — the region comes
     * from the voice, never from the text.
     */
    fun espeakFor(engineName: String, voiceId: String, language: String?): String? =
        when (engineName) {
            KokoroDirectVoiceCatalog.ENGINE -> LangDetector.espeakCodeFor(
                language,
                KokoroDirectVoiceCatalog.espeakVoiceFor(voiceId.substringAfter(':')),
            )
            KittenDirectVoiceCatalog.ENGINE -> LangDetector.espeakCodeFor(
                language,
                KittenDirectVoiceCatalog.ESPEAK_VOICE,
            )
            else -> null
        }

    /**
     * [stored] resolved against this utterance's own [text].
     *
     * An explicit language always outranks detection. [fallback] is the
     * ISO-639-1 language to use when detection abstains (too little text,
     * or two languages too close to call) — the system TTS route passes
     * the request's locale there; the routes that have no request locale
     * pass null and let the engine's own default stand.
     *
     * This is the no-rerouting form: the voice never changes. The system
     * TTS service adds the one case that does move the voice — handing a
     * non-English utterance to an installed Kokoro voice — around this.
     */
    fun resolve(
        detector: LangDetector,
        engineName: String,
        voiceId: String,
        stored: String?,
        text: String,
        fallback: String? = null,
    ): String? {
        if (!isAuto(engineName, stored)) return stored
        return espeakFor(engineName, voiceId, detector.detect(text) ?: fallback)
    }
}
