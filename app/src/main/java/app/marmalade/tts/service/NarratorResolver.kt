package app.marmalade.tts.service

import android.util.Log
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectResolver
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SentencePause
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.lang.LanguageVoiceSelector
import app.marmalade.tts.lang.UtteranceLanguage
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.PreparedSegment
import app.marmalade.tts.playback.ResolveTarget
import app.marmalade.tts.playback.ResolvedVoice
import app.marmalade.tts.playback.SegmentPreparer
import app.marmalade.tts.playback.SessionResolver
import app.marmalade.tts.preprocessing.EmojiProsody
import app.marmalade.tts.preprocessing.Preprocessor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Turns what a session asked for into the voice the engine is given — once
 * per session, off the Narrator's loop (Room, DataStore and language
 * detection are I/O). The rules are the ones MarmaladeSynthService applied
 * per request before the Narrator:
 *
 *  - an article: its [VoiceChoice] (picked by the reader once per article)
 *    → the alias's voice, effect chain, phonemizer language and speed; an
 *    installed voice no alias uses is spoken dry at 1.0x with the engine's
 *    own language detection. An alias deleted mid-read falls back to the
 *    primary ([TtsRouter.resolveAlias]). The session speed replaces the
 *    alias's speed separately (the Narrator's live tempo), so the rest of the
 *    alias still applies;
 *  - a one-shot: an explicit voice as given (Speak screen, previews, Tasker
 *    naming a voice); otherwise the primary alias, or — shared text — the
 *    voice that speaks its language ([LanguageVoiceSelector]);
 *  - no alias at all: the caller's own extras ([OneShotVoice.Routed.fallback])
 *    or the Kitten default voice.
 *
 * Unknown engines narrow to Kokoro Direct ([EngineRegistry.knownEngineOrDefault]).
 */
@Singleton
class NarratorResolver internal constructor(
    /** Alias by id, else the primary (per-app routing never applies in-app). */
    private val aliasFor: suspend (aliasId: String?) -> VoiceAlias?,
    private val blocksFor: suspend (effectId: String?) -> List<EffectBlock>,
    /** The language-aware choice for shared text. */
    private val shareChoice: suspend (text: String) -> VoiceChoice,
    private val rulesFor: suspend (engine: String) -> Set<String>,
) : SessionResolver {

    @Inject
    constructor(
        router: TtsRouter,
        effects: EffectResolver,
        languageVoices: LanguageVoiceSelector,
        settings: SettingsRepository,
    ) : this(
        aliasFor = { id -> router.resolveAlias(callerPackage = null, aliasId = id) },
        blocksFor = effects::blocksFor,
        shareChoice = { text -> languageVoices.select(text, subject = "share", logTag = SHARE_VOICE_TAG) },
        rulesFor = { engine -> settings.enabledRules(engine).first() },
    )

    override suspend fun resolve(target: ResolveTarget): ResolvedVoice {
        val base = when (target) {
            is ResolveTarget.Article -> routed(target.choice, fallback = null)
            is ResolveTarget.OneShot -> when (val v = target.request.voice) {
                is OneShotVoice.Explicit -> explicit(v)
                is OneShotVoice.Routed -> routed(v.choice, v.fallback)
                is OneShotVoice.LanguageAware -> routed(shareChoice(target.request.text), v.fallback)
            }
        }
        val engine = EngineRegistry.knownEngineOrDefault(base.engine)
        if (engine != base.engine) Log.w(TAG, "Engine '${base.engine}' not supported — using $engine")
        return base.copy(
            engine = engine,
            cloud = engine == CloudApiVoiceCatalog.ENGINE,
            rules = rulesFor(engine),
            sentencePauseMs = if (SentencePause.appliesTo(engine)) base.sentencePauseMs else 0,
        )
    }

    private suspend fun routed(choice: VoiceChoice, fallback: OneShotVoice.Explicit?): ResolvedVoice {
        if (choice is VoiceChoice.Installed) return installed(choice)
        val alias = aliasFor((choice as? VoiceChoice.Alias)?.aliasId)
            ?: return explicit(fallback ?: DEFAULT_VOICE)
        return ResolvedVoice(
            engine = alias.engine,
            voiceId = alias.voiceId,
            effectBlocks = blocksFor(alias.effectId),
            phonemizationLanguage = alias.phonemizationLanguage,
            aliasSpeed = alias.speed,
            cloud = false,
            rules = emptySet(),
            expressiveness = alias.expressiveness,
            sentencePauseMs = alias.sentencePauseMs,
        )
    }

    companion object {
        private const val TAG = "NarratorResolver"

        /** Logcat tag for the share route's per-text voice decision. */
        private const val SHARE_VOICE_TAG = "ShareVoice"

        /** No alias and no caller extras: what the service always spoke then. */
        internal val DEFAULT_VOICE = OneShotVoice.Explicit(voiceId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)

        /** Exactly the voice named, with its own chain, language and speed. */
        internal fun explicit(v: OneShotVoice.Explicit) = ResolvedVoice(
            engine = v.engine ?: EngineRegistry.engineNameFor(v.voiceId),
            voiceId = v.voiceId,
            effectBlocks = v.effectBlocks,
            phonemizationLanguage = v.phonemizationLanguage,
            aliasSpeed = v.speed,
            cloud = false,
            rules = emptySet(),
            expressiveness = v.expressiveness,
            sentencePauseMs = v.sentencePauseMs,
        )

        /** An installed voice no alias uses: that voice, dry, at 1.0x, the engine's own language detection. */
        internal fun installed(choice: VoiceChoice.Installed) = ResolvedVoice(
            engine = choice.engine,
            voiceId = choice.voiceId,
            effectBlocks = emptyList(),
            phonemizationLanguage = null,
            aliasSpeed = 1f,
            cloud = false,
            rules = emptySet(),
        )
    }
}

/**
 * Per-segment text preparation, in the render job: the canonical order of
 * [app.marmalade.tts.audio.runSynthesisPipeline] — emotion from the raw text
 * (the emoji are the signal), the user's preprocessing rules, then the emoji
 * strip. The phonemizer language is resolved per segment
 * ([UtteranceLanguage]), so a mixed-language page follows its paragraphs.
 */
@Singleton
class NarratorPreparer @Inject constructor(
    private val preprocessor: Preprocessor,
    private val langDetector: LangDetector,
) : SegmentPreparer {

    override suspend fun prepare(text: String, voice: ResolvedVoice): PreparedSegment {
        val emotion = EmojiProsody.detect(text).emotion
        val stripped = EmojiProsody.stripEmojis(preprocessor.apply(text, voice.rules))
        val language = UtteranceLanguage.resolve(
            detector = langDetector,
            engineName = voice.engine,
            voiceId = voice.voiceId,
            stored = voice.phonemizationLanguage,
            text = text,
        )
        return PreparedSegment(stripped, language, emotion)
    }
}
