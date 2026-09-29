package app.marmalade.tts.reader

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.service.TtsLocales

// -----------------------------------------------------------------------------
//   Which voice reads an article (Max, 2026-09-28)
// -----------------------------------------------------------------------------
//
//   The reader normally reads in the primary alias — it sends no voice, and
//   MarmaladeSynthService routes to the primary. That alias's voice may not
//   speak the article's language, though: an English Kokoro voice handed a
//   Chinese article phonemizes it as Chinese but speaks it with the English
//   speaker. So once per article, before the first block is sent:
//
//     article language (LangDetector over a sample of the text)
//       │   unknown → primary alias (today's behaviour)
//       ▼
//     primary alias's voice speaks it?      → primary alias
//       ▼ no
//     another alias whose voice speaks it?  → that alias (createdAt order —
//       │                                    the alias list's own order)
//       ▼ none
//     an installed, released, on-device     → that voice, at 1.0x
//       │ voice of that language?             (Kokoro first, catalog order —
//       │                                    TtsLocales.defaultVoiceFor)
//       ▼ none
//     primary alias (today's behaviour)
//
//   Decided once per article, not per block, so the voice never flips
//   mid-article; a rebind to an article already held keeps the decision.
//   "Speaks it" is the voice's own VoiceMeta.languageCode, compared on the
//   language subtag only (en-US and en-GB are both English).
// -----------------------------------------------------------------------------

/** The voice an article is read in — what [ReaderSpeechClient.speak] routes to. */
sealed interface ReaderVoice {

    /** The primary alias, resolved by the service exactly as before. */
    data object Primary : ReaderVoice

    /** Another of the user's aliases: its voice, effect and language, starting at its speed. */
    data class Alias(
        val aliasId: String,
        val voiceId: String,
        val engine: String,
        val speed: Float,
    ) : ReaderVoice

    /** An installed voice no alias uses; spoken dry, starting at 1.0x. */
    data class Installed(val voiceId: String, val engine: String) : ReaderVoice
}

/** Picks the voice for a freshly loaded article. An interface so the reader's ViewModel tests can fake it. */
fun interface ReaderVoicePicker {
    suspend fun voiceFor(article: ReaderArticle): ReaderVoice
}

/** A choice plus why it was made, for the one log line per article. */
data class ReaderVoiceDecision(
    val voice: ReaderVoice,
    /** The detected article language (ISO-639-1), or null when detection abstained. */
    val language: String?,
    val reason: Reason,
) {
    enum class Reason {
        /** Detection abstained — no reason to move off the primary. */
        LanguageUnknown,

        /** The primary alias's voice has no catalog row, so its language is unknown. */
        PrimaryVoiceUnknown,

        /** The primary alias's voice speaks the article's language. */
        PrimarySupports,

        /** Another alias's voice speaks it. */
        OtherAlias,

        /** An installed voice speaks it; no alias does. */
        InstalledVoice,

        /** Nothing installed speaks it — keep the primary, as before. */
        NoVoiceForLanguage,
    }
}

object ReaderVoiceChooser {

    /**
     * How much article text language detection looks at. Plenty for the
     * script check and the trigram model, and it keeps a very long article
     * from costing anything noticeable.
     */
    const val DETECTION_SAMPLE_CHARS = 2_000

    /** The start of [article] — title, then blocks in order — capped at [DETECTION_SAMPLE_CHARS]. */
    fun detectionSample(article: ReaderArticle): String = buildString {
        article.title?.let { append(it).append('\n') }
        for (block in article.blocks) {
            if (length >= DETECTION_SAMPLE_CHARS) break
            append(block.text).append('\n')
        }
    }.take(DETECTION_SAMPLE_CHARS)

    /** The language subtag of a BCP-47 tag or bare code, lowercased: `en-GB` → `en`. */
    fun languageOf(tag: String?): String? =
        tag?.trim()?.split('-', '_')?.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** True when [voiceLanguage] (a VoiceMeta.languageCode) is [language], region ignored. */
    fun speaks(voiceLanguage: String?, language: String): Boolean =
        languageOf(voiceLanguage) == languageOf(language)

    /**
     * The voice to read in, given:
     *
     * @param language   the article's detected language, or null.
     * @param primary    the primary alias, or null when none is set.
     * @param primaryVoice the catalog row for the voice the primary route
     *                   speaks with — the primary alias's voice, or the
     *                   service's default voice when there is no primary.
     *                   Null when there is no such row.
     * @param aliases    every alias, in the alias list's order.
     * @param pickable   the voices a user could pick right now
     *                   ([app.marmalade.tts.data.pickableVoices]): on disk,
     *                   released, developer-gated.
     */
    fun choose(
        language: String?,
        primary: VoiceAlias?,
        primaryVoice: VoiceMeta?,
        aliases: List<VoiceAlias>,
        pickable: List<VoiceMeta>,
    ): ReaderVoiceDecision {
        fun keep(reason: ReaderVoiceDecision.Reason) =
            ReaderVoiceDecision(ReaderVoice.Primary, language, reason)

        if (language == null) return keep(ReaderVoiceDecision.Reason.LanguageUnknown)
        if (primaryVoice == null) return keep(ReaderVoiceDecision.Reason.PrimaryVoiceUnknown)
        if (speaks(primaryVoice.languageCode, language)) {
            return keep(ReaderVoiceDecision.Reason.PrimarySupports)
        }

        val pickableById = pickable.associateBy { it.id }
        aliases.firstOrNull { alias ->
            alias.id != primary?.id &&
                pickableById[alias.voiceId]?.let { speaks(it.languageCode, language) } == true
        }?.let { alias ->
            return ReaderVoiceDecision(
                voice = ReaderVoice.Alias(
                    aliasId = alias.id,
                    voiceId = alias.voiceId,
                    engine = alias.engine,
                    speed = alias.speed,
                ),
                language = language,
                reason = ReaderVoiceDecision.Reason.OtherAlias,
            )
        }

        // On-device voices only: sending the article to a cloud provider is
        // something the user opts into per alias, never something the reader
        // decides for them.
        val onDevice = pickable.filter { it.engine != CloudApiVoiceCatalog.ENGINE }
        TtsLocales.defaultVoiceFor(language, null, onDevice)?.let { voice ->
            return ReaderVoiceDecision(
                voice = ReaderVoice.Installed(voiceId = voice.id, engine = voice.engine),
                language = language,
                reason = ReaderVoiceDecision.Reason.InstalledVoice,
            )
        }
        return keep(ReaderVoiceDecision.Reason.NoVoiceForLanguage)
    }

    /**
     * The one log line for [decision], e.g.
     * `article lang=zh primary=kokoro-direct-v1_0:af_heart unsupported -> kokoro-direct-v1_0:zf_xiaobei (installed voice)`.
     */
    fun describe(decision: ReaderVoiceDecision, primaryVoiceId: String?): String {
        val head = "article lang=${decision.language ?: "?"} primary=${primaryVoiceId ?: "none"}"
        return head + when (val voice = decision.voice) {
            is ReaderVoice.Alias -> " unsupported -> ${voice.voiceId} (alias ${voice.aliasId})"
            is ReaderVoice.Installed -> " unsupported -> ${voice.voiceId} (installed voice)"
            ReaderVoice.Primary -> when (decision.reason) {
                ReaderVoiceDecision.Reason.LanguageUnknown -> " language undetected -> primary"
                ReaderVoiceDecision.Reason.PrimaryVoiceUnknown -> " voice language unknown -> primary"
                ReaderVoiceDecision.Reason.NoVoiceForLanguage ->
                    " unsupported, no installed voice speaks it -> primary"
                else -> " supported -> primary"
            }
        }
    }
}
