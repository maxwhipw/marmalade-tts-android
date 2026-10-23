package app.marmalade.tts.playback

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.preprocessing.Emotion
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

// -----------------------------------------------------------------------------
// The Narrator's public surface: commands in (NarratorControl, NarratorShell),
// one StateFlow out, and the ports the service shell implements. See
// docs/release/READER-SESSION-PLAN.md (local) §2.4 for the design and
// REPO-MAP.md "Narrator" for the current wiring status.
// -----------------------------------------------------------------------------

/**
 * Commands from the reader, the Speak screen and the service shell. Every
 * method is a non-suspending `trySend` onto the Narrator's command loop, safe
 * from any thread (Main, binder, focus callback, notification).
 */
interface NarratorControl {
    val state: StateFlow<NarratorState>

    /** Load an article. The same [key] again is a rebind and changes nothing. */
    fun openArticle(
        key: String,
        segments: List<String>,
        voice: VoiceChoice,
        speed: Float,
        autoplay: Boolean,
    )

    /**
     * Idle → cursor, Finished → segment 0, Paused → resume (re-requests focus if lost).
     * Acts on the one-shot on air if there is one, else the article (the
     * notification and media keys: "the thing you hear").
     */
    fun play()

    /** [pause] for the one-shot on air if there is one, else the article; Navigation always the article. */
    fun pause(reason: PauseReason = PauseReason.User)

    fun togglePlayPause()

    /**
     * [play] for the article only — the reader's own button. With a one-shot
     * on air the article starts behind it (or, interrupted, resumes after it).
     */
    fun playArticle()

    /**
     * A user pause of the article only — the reader's own button. An article
     * a one-shot is reading over then stays paused after it instead of resuming.
     */
    fun pauseArticle()

    /** Tap / contents pick: `play = true`; a transport seek keeps a pause. */
    fun jumpTo(index: Int, play: Boolean)

    /**
     * The next segment; past the last one the article is Finished. Keeps a
     * pause; from Idle / Finished it starts playing at the target.
     */
    fun next()

    /**
     * Previous block within [NarratorConfig.restartWindowMs] of the segment's
     * first HEARD frame, else restart it. Keeps a pause; from Idle / Finished
     * it starts playing at the target.
     */
    fun previous()

    /**
     * The article session's speed, clamped to [MIN_SPEED]..[MAX_SPEED]; applied live, no re-render.
     * The speed it already has changes nothing but whether it is an override (#67).
     */
    fun setSpeed(speed: Float)

    /**
     * Read the article [key] in [voice] from now on (#66): the primary alias
     * changed under it, or the user picked an alias in the reader. The voice
     * is resolved afresh even when [voice] equals the old choice — the
     * primary it names is what changed. Every rendered chunk is dropped and
     * the listener's block restarts from its start in the new voice; a
     * playing article keeps playing, a paused one stays paused. The session
     * speed follows [startingSpeed] unless the user had set another with
     * [setSpeed] — an override, kept across voice changes until the user
     * picks the voice's own speed again (#67).
     * Ignored when [key] is not the loaded article.
     */
    fun setArticleVoice(key: String, voice: VoiceChoice, startingSpeed: Float)

    /** Stop everything. The article stays loaded with its cursor kept. */
    fun stop()

    fun speakOneShot(request: OneShotRequest): OneShotHandle

    /** Stop one one-shot (the `ACTION_STOP_REQUEST` shim); others are untouched. */
    fun cancelOneShot(id: Long)

    companion object {
        /** The article's speed range (the reader's slider uses 0.5–2 of it). */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        /**
         * A one-shot's speed range (Speak screen, previews, shares, Tasker's
         * `EXTRA_SPEED`): what system TTS and the cloud engine accept. Before
         * C1 the intent's speed went to the engine or Tempo unclamped; the
         * writer's Tempo is tested across this range (StreamingEffectChainTest).
         */
        const val MIN_ONE_SHOT_SPEED = 0.25f
        const val MAX_ONE_SHOT_SPEED = 4.0f
    }
}

/** Android lifecycle signals, delivered by the service shell (implemented by the Narrator). */
interface NarratorShell {
    fun onFocusChange(change: FocusChange)

    /** The host service became foreground (true) or was demoted/destroyed (false). */
    fun onHostChanged(foreground: Boolean)

    /**
     * The shell has shown [NarratorState.notice] for this one-shot; it is
     * cleared, so a later host (a new service instance) never posts it again.
     */
    fun onNoticeShown(oneShotId: Long)
}

enum class PauseReason {
    User,

    /** The user left the reader: pause AND stop preparing ahead. */
    Navigation,
}

enum class PausedBy {
    User,

    /** Transient loss (call, notification duck): resumes on GAIN. */
    Focus,

    /**
     * Permanent loss (another app started playing, §6 Q6): paused with the
     * place kept; only an explicit play resumes, and the shell may make the
     * notification swipe-away-able ([NarratorState.focusHeld] is false).
     */
    FocusLoss,
    Navigation,

    /** A one-shot is reading over the article; it resumes when the one-shot ends. */
    Interrupt,
}

enum class FailureKind {
    MODEL_MISSING,
    FAILED,
    FOCUS_DENIED,

    /**
     * The foreground host never came up: Android refuses to start a
     * foreground service from the background (targetSdk 31+). Play works
     * again once the app is in front.
     */
    HOST_REFUSED,
}

sealed interface Status {
    data object Idle : Status
    data object Buffering : Status
    data object Playing : Status
    data class Paused(val by: PausedBy) : Status
    data object Finished : Status
    data class Failed(val kind: FailureKind) : Status
}

data class ArticleView(
    val key: String,
    val segmentCount: Int,
    /** The segment the listener is at: moves when a new segment is HEARD. */
    val index: Int,
    val speed: Float,
    val startingSpeed: Float,
    val status: Status,
    /** The text of segment [index] — what the notification quotes. */
    val text: String = "",
)

enum class Origin {
    /** Speak screen, previews, the reader's "read as-is": a UI shows errors. */
    InApp,

    /** Share sheet, PROCESS_TEXT, Tasker: nothing on screen, so the shell posts errors. */
    External,
}

data class OneShotView(
    val id: Long,
    val origin: Origin,
    val status: Status,
    /** One-shots waiting behind this one. */
    val queued: Int,
    /** The text of the paragraph the listener is at — what the notification quotes. */
    val text: String = "",
)

/**
 * A failure the shell must surface as a notification (UI-less sessions only).
 * Held until the shell acknowledges it ([NarratorShell.onNoticeShown]) or the
 * next session is started.
 */
data class ErrorNotice(val oneShotId: Long, val kind: FailureKind, val engine: String?)

data class NarratorState(
    val article: ArticleView? = null,
    val oneShot: OneShotView? = null,
    val focusHeld: Boolean = false,
    val notice: ErrorNotice? = null,
)

data class OneShotRequest(
    val text: String,
    val voice: OneShotVoice,
    val origin: Origin,
    /** Absolute speed; null = the resolved voice's own (alias) speed. */
    val speed: Float? = null,
)

sealed interface OneShotVoice {
    /** Speak screen / previews / a Tasker call naming a voice: exactly this voice, speed and chain. */
    data class Explicit(
        val voiceId: String,
        val effectBlocks: List<EffectBlock> = emptyList(),
        val phonemizationLanguage: String? = null,
        /** The voice's own speed (the one-shot's speed when its request names none). */
        val speed: Float = 1f,
        /** The engine, when the caller named one; else the voice id's prefix. */
        val engine: String? = null,
        /** The applied alias's expressiveness (Pocket's temperature); null = engine default. */
        val expressiveness: Float? = null,
        /** The applied alias's pause between sentences, ms ([app.marmalade.tts.data.SentencePause]); 0 = Natural. */
        val sentencePauseMs: Int = 0,
    ) : OneShotVoice

    /**
     * The primary alias or a specific alias. [fallback] is spoken when no
     * alias resolves (no primary alias set): a Tasker call's own engine,
     * speed and effect extras.
     */
    data class Routed(val choice: VoiceChoice, val fallback: Explicit? = null) : OneShotVoice

    /** Shared text: the language-aware rule (detect once, pick a voice that speaks it); [fallback] as for [Routed]. */
    data class LanguageAware(val fallback: Explicit? = null) : OneShotVoice
}

sealed interface Outcome {
    data object Played : Outcome
    data object Stopped : Outcome
    data class Failed(val kind: FailureKind, val message: String?) : Outcome
}

class OneShotHandle(val id: Long, val outcome: Deferred<Outcome>)

// -- ports ---------------------------------------------------------------------

/** What a session resolved to, once, off the loop (Room/DataStore/LangDetector I/O). */
data class ResolvedVoice(
    val engine: String,
    val voiceId: String,
    /** The alias's effect chain; the session speed is applied separately (live Tempo). */
    val effectBlocks: List<EffectBlock>,
    val phonemizationLanguage: String?,
    /** The routed alias's own speed — a one-shot's speed when its request names none. */
    val aliasSpeed: Float,
    /** Cloud voices prepare only as far as today (money + text sent to a provider). */
    val cloud: Boolean,
    /** Enabled preprocessing rules for [engine]. */
    val rules: Set<String>,
    /** The alias's expressiveness, handed to the engine per render; null = engine default. */
    val expressiveness: Float? = null,
    /**
     * The alias's pause between sentences, ms ([app.marmalade.tts.data.SentencePause]):
     * the engine adds it inside a segment, the renderer 1.5× of it between
     * segments (paragraphs). 0 = Natural; always 0 for a cloud voice.
     */
    val sentencePauseMs: Int = 0,
)

sealed interface ResolveTarget {
    data class Article(val choice: VoiceChoice, val sampleText: String) : ResolveTarget
    data class OneShot(val request: OneShotRequest) : ResolveTarget
}

/** Voice + rules resolution. Suspends; runs as a child job, never on the loop. */
fun interface SessionResolver {
    suspend fun resolve(target: ResolveTarget): ResolvedVoice
}

/** Text as the engine must see it, plus the per-segment phonemizer language and emotion. */
data class PreparedSegment(
    val text: String,
    val phonemizationLanguage: String?,
    val emotion: Emotion = Emotion.Neutral,
)

/** Per-segment preparation (preprocess, strip emoji, detect language). Runs in the render job. */
fun interface SegmentPreparer {
    suspend fun prepare(text: String, voice: ResolvedVoice): PreparedSegment
}

fun interface EngineLookup {
    fun engine(name: String): TtsEngine
}

interface FocusPort {
    /** Request audio focus; true when granted. Called only while not held (one request per hold). */
    fun request(): Boolean

    /** Abandon the held request (the same request object the grant was for). */
    fun abandon()
}

enum class FocusChange { LossTransient, LossTransientCanDuck, Loss, Gain }

fun interface HostPort {
    /** True while the host service is foreground NOW (not merely attached). */
    fun isForeground(): Boolean
}

interface ResidencyPort {
    fun beginSynth(engine: String)
    fun endSynth(engine: String)
}

fun interface KeepalivePort {
    fun onSessionStarted()
}

interface SynthStatsPort {
    /** Time to first audio of one render job. */
    fun recordLatency(voiceId: String, engine: String, millis: Long, chars: Int)

    /** Warm, unskewed render-time / audio-time of one render job in [language] (null: the engine's default). */
    fun recordRtf(engine: String, rtf: Double, language: String?)
}
