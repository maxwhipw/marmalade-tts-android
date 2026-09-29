package app.marmalade.tts.lang

import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.service.TtsLocales

// -----------------------------------------------------------------------------
//   Which voice reads a text in its own language (Max, 2026-09-28)
// -----------------------------------------------------------------------------
//
//   The reader and plain-text shares normally speak in the primary alias. That
//   alias's voice may not speak the text's language, though: an English Kokoro
//   voice handed Chinese phonemizes it as Chinese but speaks it with the
//   English speaker. So once per article (reader) or per shared text (share):
//
//     text language (LangDetector)
//       │   unknown → primary alias (the old behaviour)
//       ▼
//     primary alias's voice speaks it?      → primary alias
//       ▼ no
//     another alias whose voice speaks it?  → that alias (createdAt order —
//       │                                    the alias list's own order)
//       ▼ none
//     an installed, released, on-device     → that voice, dry, at 1.0x
//       │ voice of that language?             (Kokoro first, catalog order —
//       │                                    TtsLocales.defaultVoiceFor)
//       ▼ none
//     primary alias (the old behaviour)
//
//   "Speaks it" is the voice's own VoiceMeta.languageCode, compared on the
//   language subtag only (en-US and en-GB are both English). Never an
//   installed cloud voice: sending text to a provider is something the user
//   opts into per alias. Callers: reader/LanguageAwareReaderVoicePicker and
//   MarmaladeSynthService's share route, both through LanguageVoiceSelector.
//   System TTS (MarmaladeTtsService) has its own per-utterance rerouting and
//   doesn't use this.
// -----------------------------------------------------------------------------

/** The voice a text is spoken in. */
sealed interface VoiceChoice {

    /** The primary alias, resolved by the service exactly as before. */
    data object Primary : VoiceChoice

    /** Another of the user's aliases: its voice, effect and language, at its speed. */
    data class Alias(
        val aliasId: String,
        val voiceId: String,
        val engine: String,
        val speed: Float,
    ) : VoiceChoice

    /** An installed voice no alias uses; spoken dry, at 1.0x. */
    data class Installed(val voiceId: String, val engine: String) : VoiceChoice
}

/** A choice plus why it was made, for the one log line per text. */
data class VoiceDecision(
    val voice: VoiceChoice,
    /** The detected language (ISO-639-1), or null when detection abstained. */
    val language: String?,
    val reason: Reason,
) {
    enum class Reason {
        /** Detection abstained — no reason to move off the primary. */
        LanguageUnknown,

        /** The primary route's voice has no catalog row, so its language is unknown. */
        PrimaryVoiceUnknown,

        /** The primary alias's voice speaks the language. */
        PrimarySupports,

        /** Another alias's voice speaks it. */
        OtherAlias,

        /** An installed voice speaks it; no alias does. */
        InstalledVoice,

        /** Nothing installed speaks it — keep the primary, as before. */
        NoVoiceForLanguage,
    }
}

object VoiceForLanguage {

    /** The language subtag of a BCP-47 tag or bare code, lowercased: `en-GB` → `en`. */
    fun languageOf(tag: String?): String? =
        tag?.trim()?.split('-', '_')?.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** True when [voiceLanguage] (a VoiceMeta.languageCode) is [language], region ignored. */
    fun speaks(voiceLanguage: String?, language: String): Boolean =
        languageOf(voiceLanguage) == languageOf(language)

    /**
     * The voice to speak in, given:
     *
     * @param language     the text's detected language, or null.
     * @param primary      the primary alias, or null when none is set.
     * @param primaryVoice the catalog row for the voice the primary route
     *                     speaks with — the primary alias's voice, or the
     *                     service's default voice when there is no primary.
     *                     Null when there is no such row.
     * @param aliases      every alias, in the alias list's order.
     * @param pickable     the voices a user could pick right now
     *                     ([app.marmalade.tts.data.pickableVoices]): on disk,
     *                     released, developer-gated.
     */
    fun choose(
        language: String?,
        primary: VoiceAlias?,
        primaryVoice: VoiceMeta?,
        aliases: List<VoiceAlias>,
        pickable: List<VoiceMeta>,
    ): VoiceDecision {
        fun keep(reason: VoiceDecision.Reason) = VoiceDecision(VoiceChoice.Primary, language, reason)

        if (language == null) return keep(VoiceDecision.Reason.LanguageUnknown)
        if (primaryVoice == null) return keep(VoiceDecision.Reason.PrimaryVoiceUnknown)
        if (speaks(primaryVoice.languageCode, language)) {
            return keep(VoiceDecision.Reason.PrimarySupports)
        }

        val pickableById = pickable.associateBy { it.id }
        aliases.firstOrNull { alias ->
            alias.id != primary?.id &&
                pickableById[alias.voiceId]?.let { speaks(it.languageCode, language) } == true
        }?.let { alias ->
            return VoiceDecision(
                voice = VoiceChoice.Alias(
                    aliasId = alias.id,
                    voiceId = alias.voiceId,
                    engine = alias.engine,
                    speed = alias.speed,
                ),
                language = language,
                reason = VoiceDecision.Reason.OtherAlias,
            )
        }

        // On-device voices only: sending the text to a cloud provider is
        // something the user opts into per alias, never something decided
        // for them here.
        val onDevice = pickable.filter { it.engine != CloudApiVoiceCatalog.ENGINE }
        TtsLocales.defaultVoiceFor(language, null, onDevice)?.let { voice ->
            return VoiceDecision(
                voice = VoiceChoice.Installed(voiceId = voice.id, engine = voice.engine),
                language = language,
                reason = VoiceDecision.Reason.InstalledVoice,
            )
        }
        return keep(VoiceDecision.Reason.NoVoiceForLanguage)
    }

    /**
     * The one log line for [decision] about a [subject] (`article`, `share`),
     * e.g. `article lang=zh primary=kokoro-direct-v1_0:af_heart unsupported ->
     * kokoro-direct-v1_0:zf_xiaobei (installed voice)`.
     */
    fun describe(subject: String, decision: VoiceDecision, primaryVoiceId: String?): String {
        val head = "$subject lang=${decision.language ?: "?"} primary=${primaryVoiceId ?: "none"}"
        return head + when (val voice = decision.voice) {
            is VoiceChoice.Alias -> " unsupported -> ${voice.voiceId} (alias ${voice.aliasId})"
            is VoiceChoice.Installed -> " unsupported -> ${voice.voiceId} (installed voice)"
            VoiceChoice.Primary -> when (decision.reason) {
                VoiceDecision.Reason.LanguageUnknown -> " language undetected -> primary"
                VoiceDecision.Reason.PrimaryVoiceUnknown -> " voice language unknown -> primary"
                VoiceDecision.Reason.NoVoiceForLanguage ->
                    " unsupported, no installed voice speaks it -> primary"
                else -> " supported -> primary"
            }
        }
    }
}
