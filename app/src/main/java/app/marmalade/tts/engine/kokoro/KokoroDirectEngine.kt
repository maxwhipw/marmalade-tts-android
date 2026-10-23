package app.marmalade.tts.engine.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.SilenceCompressor
import app.marmalade.tts.audio.TailTrim
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineDevKnobs
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.OrtRunAbort
import app.marmalade.tts.engine.PlaybackHorizon
import app.marmalade.tts.engine.PrerollGate
import app.marmalade.tts.engine.RenderMode
import app.marmalade.tts.engine.SentenceGaps
import app.marmalade.tts.engine.SliceCost
import app.marmalade.tts.engine.SliceEstimate
import app.marmalade.tts.engine.TimedPieceGate
import app.marmalade.tts.engine.abortableInference
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.kitten.KittenStreamSlicing
import app.marmalade.tts.engine.kitten.PAD_TOKEN
import app.marmalade.tts.engine.kitten.encodePhonemes
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.CutletJaG2P
import app.marmalade.tts.phonemizer.EnPhonemeFixups
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.GermanG2P
import app.marmalade.tts.phonemizer.KokoroEspeakG2P
import app.marmalade.tts.phonemizer.KokoroLangSubstitutions
import app.marmalade.tts.phonemizer.OpenJtalkPhonemizer
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// KokoroDirectEngine — Kokoro v1.0 without sherpa-onnx
// -----------------------------------------------------------------------------
//
// Runs Kokoro-82M v1.0 (upstream's `kokoro-multi-lang-v1_0`, static QDQ int8
// since bundle v23) directly on `com.microsoft.onnxruntime:onnxruntime-android`,
// with KittenDirect's perf habits (direct ByteBuffers, thread autodetect,
// warm-up after load). Espeak-ng (GPL-3.0-or-later) is compiled from source
// into the APK as libespeak-ng.so and dlopen'd by the JNI shim — same
// pattern as KittenDirect; see phonemizer/EspeakPhonemizer.kt.
//
// Two render paths, picked once per load from the bundle's files
// ([renderPathFor]); the `render path:` line logged at load names the one in
// use and its session settings:
//
//   Streaming (issue #13 "E", prototyped in #5): the model ships cut in two,
//   weights unchanged. Each piece is planned ONCE by the planner graph (text
//   model, durations, pitch/energy, the decoder's frame-rate front and the
//   harmonic source, with the sentence's one style row), and the generator
//   graph (iSTFTNet) renders the planned frames in slices of 0.5 s, 1 s, then
//   doubling to 4 s with 4 frames of context ([KokoroStreamSlicing]), each
//   sent the moment it exists. The generator's 48 InstanceNorms take their
//   statistics from its `gn` input, computed per piece from the voice's small
//   linear model ([KokoroGenStats]) and the planned durations; that makes it
//   purely convolutional, so the slices join exactly. Sentences stay whole up
//   to the 500-token cap, except a long first sentence whose plan would be
//   heard, which gets an ~80-token head ([kokoroStreamCutFirst]), and a
//   later one whose plan would outrun the audio still to play, cut at a
//   clause while rendering ([kokoroLaterHead], #49; the whole path does the
//   same with its render time). Pre-roll
//   above 1× banks slices worth of audio, sized up front from the last
//   measured streamed RTF ([PrerollGate.streamBankMs]). Both graphs run on
//   the CPU EP with intra-op spinning (#5: first sound median 1.81 s →
//   0.94 s on a Pixel 8a).
//
//   The planner is one graph (`planner`, v24 layout) or, since bundle v26
//   (issue #25), a shared `planner-rest` fed by one of two small text
//   encoders: `textenc-en` (Marmalade's trained English student, #16) for a
//   piece of English phonemes, `textenc` (Kokoro's ALBERT with mixed int8
//   weights, #16 "int8f") for every other piece ([textEncoderFor]). Each
//   encoder session opens the first time a piece needs it and stays open.
//
//   Whole: one call of the unsplit model per chunk, the short first piece
//   (T9) and chunk-grain pre-roll ([PrerollGate]). A bundle without the
//   streaming files renders this way: v23 and older, the German engine's v25
//   bundle, the debug 7M student. Debug builds can force it while model.onnx is present
//   (`kokoro.stream=false`, [EngineDevKnobs]) for an A/B.
//
// Bundle layout (`${filesDir}/engines/kokoro-direct-v1_0/`):
//   planner.onnx, gen.onnx              — the streaming pair (v24)
//   planner-rest.onnx, textenc.onnx,    — the v26 planner: the planner minus
//   textenc-en.onnx (optional)            ALBERT, and its text encoders
//                                         (tokens → d_en); English uses
//                                         textenc when textenc-en is absent
//   genstats/<voice>.bin                — float32 [9, S] generator-stats model,
//                                         one per voice ([genStatsVoiceKeys]:
//                                         all 53 here, thorsten for German)
//   model.onnx                          — the unsplit model (v23 and older)
//   voices.bin                          — float32 [53, 510, 256] style table
//   tokens.txt                          — phoneme→ID lookup (114 entries)
//   model-format.txt                    — `qdq…` marks the int8 model (CPU EP)
//   lexicon-zh.txt, openjtalk_dic/      — Mandarin and Japanese frontends
//   phonemizer/                         — legacy, v23 and older (espeak data +
//                                         older .so); ignored — the APK carries
//                                         both the lib and the shared data
// Any graph may ship as a `.ort` instead; [modelFile] prefers it.
//
// Differences from KittenDirect (each verified against sherpa-onnx
// `csrc/offline-tts-kokoro-*` source — see [[compare-at-model-boundary]]):
//
//   1. NO speed priors. Sherpa's `OfflineTtsKokoroModelMetaData` does not
//      read a `speaker_speed_priors` field, and the v1.0 add_meta_data.py
//      script doesn't write one — so nothing scales the speed tensor here
//      the way KittenDirect's per-voice prior does. Since 2026-09-12 the
//      tensor is always fed 1.0 anyway: user speed is a time-stretch on
//      the output, not a model parameter (see [supportsNativeSpeed]).
//
//   2. Wrapping is `[0, ...ids..., 0]` — no Kitten-style end token (10).
//
//   3. Voice indexing is by phoneme-token count, not text length:
//      `styles[sid * 510 * 256 + len * 256]` where `len = total_tokens - 2`.
//      Sherpa hardcodes max_token_len = style_dim[0] = 510.
//
//   4. Single `voices.bin` (53 speakers × 510 × 256 floats = 27.7 MB)
//      rather than KittenDirect's per-voice files. We mmap it once at
//      load and slice rows per request.
//
//   5. Period special case: emit `id('.') id(' ')` (token 4 then 16) so
//      sentence-final punctuation gets a trailing space, matching sherpa's
//      PiperPhonemesToIdsKokoroOrKitten. Handled inside [encodePhonemes]
//      since it's a phoneme→token concern, not a wrapping concern.
//
//   6. Espeak voice is chosen per-voice via
//      [app.marmalade.tts.data.KokoroDirectVoiceCatalog.espeakVoiceFor]
//      (American/British map to en-us/en-gb, European voices to their
//      own codes; Mandarin routes through lexicon-zh and Japanese through
//      Open JTalk rather than espeak). An explicit phonemizationLanguage
//      override wins when provided.
// -----------------------------------------------------------------------------

private const val TAG = "KokoroDirectEngine"

/** Logcat tag for the P-A streaming perf diagnostic — `adb logcat -s StreamPerf`. */
private const val PERF_TAG = "StreamPerf"

/** Style-vector dimension; fixed by the v1.0 model architecture. */
private const val STYLE_DIM = 256

/**
 * Maximum phoneme positions Kokoro's voice table allocates a style row
 * for (= `style_dim[0]` in the ONNX metadata). Total wrapped tokens
 * across a single synth call must stay strictly below this — the
 * voice-style lookup uses `len = total - 2` and indexes
 * `styles[sid * 510 * 256 + len * 256]`, which goes off-end at len ≥ 510.
 */
private const val MAX_TOKEN_LEN = 510

/**
 * Soft cap for `(phoneme_count + 2)` per inference call. One position
 * shy of [MAX_TOKEN_LEN] so the style-table lookup stays in bounds even
 * with the `[0, ...ids..., 0]` wrapping accounted for. Sherpa's lexicon
 * splits at `max_len - 1`, matching this number.
 */
private const val MAX_PHONEMES_PER_CHUNK = 500

/**
 * Kokoro's style-table row for every model call of a chunk of
 * [chunkTokens] (unwrapped) tokens. Upstream picks the row by the call's
 * own token count; a chunk the planner cut from a longer sentence uses the
 * whole sentence's count, [sentenceTokens], instead — a piece's own short
 * count selects the brisk short-utterance register and voices the cut like
 * a sentence end (T6, Max's blind A/B variant S2, 2026-09-28). The pieces
 * the cap re-split makes of one chunk all share the chunk's row. Clamped
 * into the table, so a run-on past 509 tokens uses the last row.
 */
internal fun kokoroStyleRow(chunkTokens: Int, sentenceTokens: Int?): Int =
    (sentenceTokens ?: chunkTokens).coerceIn(0, MAX_TOKEN_LEN - 1)

/** Render time a short first piece aims for (T9): ~1.1 s. */
private const val FIRST_PIECE_RENDER_MS = 1100.0

/** Adaptive first-piece target range: ~20 tokens hot, ~40–45 cool. */
private const val FIRST_PIECE_TARGET_MIN = 20
private const val FIRST_PIECE_TARGET_MAX = 45

/** Chunks below this many tokens don't update the speed memory. */
private const val MEASURE_MIN_TOKENS = 20

/** Assumed ms per token before any chunk was measured (between cool 24 and hot 52). */
private const val DEFAULT_MS_PER_TOKEN = 30.0

/**
 * Streamed RTF assumed for the up-front pre-roll before any piece was
 * measured: the #5 clean phone bench (Pixel 8a, Heart) measured 0.79–0.85.
 */
private const val DEFAULT_STREAM_RTF = 0.85

/** Audio per model token at speed 1 (Pixel 8a, Heart: 56 ms over 144 pieces). */
private const val AUDIO_MS_PER_TOKEN = 56.0

/**
 * The streaming planner's cost per token, for deciding whether a first
 * sentence's plan would be heard: 3.3–6.3 ms on the 8a (cool to warm).
 */
private const val STREAM_PLAN_MS_PER_TOKEN = 6.0

/**
 * The streaming path's first-slice time (0.6 s of audio) before one was
 * measured, for a later chunk's wait (#49; Pixel 6a ~190 ms).
 */
private const val DEFAULT_FIRST_SLICE_MS = 200.0

/**
 * The streaming planner's cost line before two pieces were measured
 * ([PieceCostModel], #49). The v26 planner on the Pixel 6a: ~100 ms fixed
 * and ~1 ms a token; the v24 whole planner ~5 ms a token. 3 ms errs towards
 * a cut while it learns.
 */
private const val DEFAULT_PLAN_FIXED_MS = 100.0
private const val DEFAULT_PLAN_MS_PER_TOKEN = 3.0

/**
 * First sentences up to this many tokens stay whole on the streaming path:
 * their plan is ≤ ~0.8 s, and every cut is a prosody seam.
 */
private const val STREAM_CUT_MIN_TOKENS = 160

/**
 * Whether the streaming path cuts the request's first sentence of
 * [firstTokens] tokens into a short head ([TextChunker.planByTokens] step
 * 4, clause marks only). Only a long one, and only when its plan would be
 * heard: nothing playing covers it ([aheadMs], the audio this engine sent
 * that is still to play), or a pre-roll will be banked ([holding]), which
 * then fills from the short head instead of the whole sentence.
 */
internal fun kokoroStreamCutFirst(firstTokens: Int, aheadMs: Long, holding: Boolean): Boolean =
    firstTokens > STREAM_CUT_MIN_TOKENS && (holding || aheadMs < firstTokens * STREAM_PLAN_MS_PER_TOKEN)

/**
 * A later chunk's head (#49) holds at least this many tokens, about one
 * short clause ("The sun is out today,"); a cut never makes a smaller one.
 */
private const val LATER_HEAD_MIN_TOKENS = 15

/**
 * A later chunk whose first sound would come at most this long after the
 * audio ahead of it ran out stays whole (#49): a wait that short falls in
 * the pause at the sentence seam, and every cut is a prosody seam.
 */
private const val SEAM_WAIT_TOLERANCE_MS = 150

/**
 * Head size, in tokens, to cut a chunk after the request's first to (#49),
 * or null to keep it whole. Its first sound comes [fixedMs] +
 * [tokens] × [msPerToken] after its work starts: the plan plus the first
 * slice on the streaming path, the whole render on the whole path. When
 * that outruns [aheadMs], the audio already rendered that is still to play,
 * the chunk is cut at a clause into a head sized to fit what's ahead
 * (within [LATER_HEAD_MIN_TOKENS]..[maxHead]); the rest is checked again
 * when its turn comes, with the head's audio ahead of it.
 */
internal fun kokoroLaterHead(tokens: Int, aheadMs: Long, msPerToken: Double, fixedMs: Double, maxHead: Int): Int? {
    if (fixedMs + tokens * msPerToken <= aheadMs + SEAM_WAIT_TOLERANCE_MS) return null
    return ((aheadMs - fixedMs) / msPerToken).toInt().coerceIn(LATER_HEAD_MIN_TOKENS, maxHead)
}

/**
 * [budget] for cutting a later chunk at a head of about [head] tokens (#49).
 * The head may run over [head] as far as the packer's top-up allows (one
 * more clause when the first is under half of it, up to
 * [TextChunker.WHOLE_TOL] × [head]): rejecting that head would leave the
 * whole chunk, the worse wait.
 */
internal fun kokoroLaterCutBudget(budget: TextChunker.TokenBudget, head: Int): TextChunker.TokenBudget =
    budget.copy(firstPiece = head, firstPieceMin = LATER_HEAD_MIN_TOKENS, firstPieceMax = (head * TextChunker.WHOLE_TOL).toInt())

/** Samples per duration frame (25 ms at 24 kHz), the generator's grid. */
private const val FRAME_SAMPLES = TailTrim.FRAME_SAMPLES

/** One frame's audio (25 ms). */
private const val FRAME_MS = FRAME_SAMPLES * 1000.0 / KokoroDirectVoiceCatalog.SAMPLE_RATE

/**
 * [base] with the short-first-piece sizes adapted to the measured speed
 * (T9). The first piece aims at [FIRST_PIECE_RENDER_MS] of render time
 * from [msPerToken] (clamped to 20–45 tokens; the planner never cuts below
 * one clause anyway). Each later piece may grow by as much as it can while
 * the previous one plays: `0.9 / (rtf × playbackRate)`, clamped to 1–2×. A
 * NaN measurement keeps [base]'s value.
 */
internal fun kokoroStreamBudget(
    base: TextChunker.TokenBudget,
    msPerToken: Double,
    rtf: Double,
    playbackRate: Float,
): TextChunker.TokenBudget {
    val firstPiece = if (msPerToken > 0) {
        Math.round(FIRST_PIECE_RENDER_MS / msPerToken).toInt()
            .coerceIn(FIRST_PIECE_TARGET_MIN, FIRST_PIECE_TARGET_MAX)
    } else {
        base.firstPiece
    }
    val playedRtf = rtf * (if (playbackRate > 0f) playbackRate else 1f)
    val growth = if (playedRtf > 0) (0.9 / playedRtf).coerceIn(1.0, 2.0) else base.growth
    return base.copy(firstPiece = firstPiece, growth = growth)
}

/** Default espeak phonemization voice — see file comment. */
private const val ESPEAK_VOICE = "en-us"

/**
 * `open` solely to enable a JVM test double (see
 * `MarmaladeTtsServiceTest.FakeKokoroDirectEngine`) — the service routes a
 * `kokoro-direct-v1_0:*` voice to this concrete type, so the fake must be a
 * subclass for the reflective field injection in that test to accept it.
 * Mirrors the same convention on [app.marmalade.tts.engine.kitten.KittenDirectEngine].
 */
@Singleton
open class KokoroDirectEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val sharedEspeak: SharedEspeakData,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME
    override val sampleRate: Int = KokoroDirectVoiceCatalog.SAMPLE_RATE

    /**
     * The model's `speed` tensor works, but not well enough to use: it
     * degrades articulation and saturates — desktop measurement
     * 2026-09-12 got only ~2.2x real speed-up for a requested 3.0x. The
     * services time-stretch the rendered audio instead
     * (`applySpeedFallback` → [app.marmalade.tts.audio.EffectBlock.Tempo]),
     * which hits the requested factor exactly and leaves pronunciation
     * alone. Mirrors the CLI, which moved every engine to `sox tempo`
     * (marmalade-tts-cli `6934ca6`). See [TtsEngine.supportsNativeSpeed].
     *
     * Consequence: [inferTokens] now receives speed = 1.0 on every
     * service-driven call and feeds that straight into the tensor. The
     * argument stays plumbed so a direct caller (benchmarks, capability
     * probe — both pass 1.0 today) keeps a working knob.
     */
    override val supportsNativeSpeed: Boolean = false

    // -- subclass seams -------------------------------------------------------
    //
    // KokoroGermanEngine (`kokoro-de-v1_0`) reuses this whole ORT/streaming
    // machinery unchanged and only redirects the catalog-shaped lookups: its
    // bundle is a German fine-tune of the same graph with one speaker and a
    // fixed espeak-de phonemization language. Overriding these four members
    // (plus [engineName] and [sampleRate]) is all it needs — the base
    // behaviour stays byte-identical because each seam resolves against
    // [KokoroDirectVoiceCatalog], exactly as before.

    /** Speaker-row index into voices.bin for [voiceKey]. -1 when unknown. */
    protected open fun speakerIdFor(voiceKey: String): Int =
        KokoroDirectVoiceCatalog.speakerIdFor(voiceKey)

    /**
     * The key a request for [voiceKey] renders with: a retired voice's
     * replacement, else itself. Not a seam: no subclass key is retired.
     */
    private fun liveVoiceKey(voiceKey: String): String =
        KokoroDirectVoiceCatalog.liveVoiceKey(voiceKey)

    /** espeak phonemization voice for [voiceKey] — see [espeakPhonemes]. */
    protected open fun espeakVoiceFor(voiceKey: String): String =
        KokoroDirectVoiceCatalog.espeakVoiceFor(voiceKey)

    /** Raw voice key the async warmup synthesizes one character with. */
    protected open val warmupVoiceKey: String
        get() = KokoroDirectVoiceCatalog.voices.first().id.substringAfter(':')

    /**
     * Raw keys of the voices a streaming bundle ships `genstats/<key>.bin`
     * for, speaker 0 first: the layout check needs every one, and an unknown
     * voice renders with the first ([genStatsKeyFor]).
     */
    protected open val genStatsVoiceKeys: List<String>
        get() = CATALOG_VOICE_KEYS

    /**
     * English IPA for [text] — see [espeakPhonemes]. Kokoro-82M takes untied
     * espeak + EnPhonemeFixups as is. The debug-only Kokoro-7M student
     * (issue #4) overrides this: it only works on misaki's spellings.
     */
    protected open fun englishPhonemes(phon: EspeakPhonemizer, text: String, lang: String): String =
        phon.phonemize(text, lang)

    private val engineDir: File get() = File(ctx.filesDir, "engines/$engineName")
    private val voicesFile: File get() = File(engineDir, VOICES_FILE)

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    /** Shrink the CPU arena after each generator run ([OrtRunAbort]); debug A/B knob `shrink=false`. */
    @Volatile private var shrinkArena = true

    // -- live state (null while not loaded) -----------------------------------

    @Volatile private var env: OrtEnvironment? = null

    /** Which render path this load uses (see the file comment); set before [env] is published. */
    @Volatile private var streaming = false

    /** The unsplit model (whole path). */
    private var acousticSession: OrtSession? = null

    /** The streaming pair: planner (the whole planner, or planner-rest in the v26 layout) and generator. */
    private var plannerSession: OrtSession? = null
    private var genSession: OrtSession? = null

    /** Whether [plannerSession] is planner-rest, fed by [textEncoders] (the v26 layout). */
    @Volatile private var splitPlanner = false

    /**
     * The v26 layout's text encoder sessions by graph name, each opened the
     * first time a piece needs it ([textEncoderSession]). Guarded by
     * [synthLock].
     */
    private val textEncoders = HashMap<String, OrtSession>()

    /** Session settings for the streaming graphs, kept for the encoders opened after load. */
    private var streamThreads = 1
    private var streamSpin = true

    /** ORT `session.intra_op_thread_affinities` for the streaming graphs; debug knob `kokoro.affinity`, else none. */
    private var streamAffinity: String? = null

    /** Generator-stats models ([KokoroGenStats]) per voice key, loaded on first use. Guarded by [synthLock]. */
    private val genStatsModels = HashMap<String, FloatArray>()

    private var phonemizer: EspeakPhonemizer? = null

    /**
     * Mandarin lexicon from the bundle's `lexicon-zh.txt`, loaded the first
     * time a text has Han characters ([mandarinLexicon]), not at engine load:
     * building its 68k entries took 0.6–0.8 s of every load on a Pixel 8a
     * (issue #13 "D2"), almost always for text that has none. Null when the
     * installed bundle predates zh support (graceful degradation — CJK then
     * falls back to the espeak path, which is broken for Mandarin but doesn't
     * crash). Bundles from v17 onward ship it. Guarded by [synthLock], like
     * every encode.
     */
    private var lexiconZh: LexiconZh? = null
    private var lexiconZhTried = false

    /**
     * Japanese frontend (Open JTalk), loaded from the bundle's `openjtalk_dic`.
     * Null when the bundle predates ja support — ja then falls back to the
     * espeak path (broken for kanji, but no crash). Bundles from v18 onward
     * ship the dict. Paired with [CutletJaG2P] for the reading→IPA step.
     */
    private var openJtalk: OpenJtalkPhonemizer? = null

    /**
     * Memory-mapped view of voices.bin (53 × 510 × 256 = 6,919,680 floats).
     * Lives in the OS page cache, never on the Java heap. Per-call lookup
     * pages in only the 256-float style row needed; the rest stays cold.
     *
     * The original `readBytes()` + `ByteBuffer.wrap` + `FloatArray.get`
     * load path allocated two 27.7 MB transient heaps. This eliminates
     * both — the only Java-side cost is the small `FloatBuffer` wrapper.
     */
    private var voicesFloatView: FloatBuffer? = null

    /**
     * Reusable direct ByteBuffer for the style tensor — shape is always
     * [1, STYLE_DIM], so we can allocate once at engine load and rewind+
     * refill on each inference call. Skips the per-call
     * `ByteBuffer.allocateDirect` (which pins a native page through the
     * JVM's off-heap allocator). PocketEngine's FlowEulerScratch (PE.kt:
     * 1140) is the same pattern.
     *
     * Single-engine + `synthLock`-serialised inference, so no contention.
     */
    private var styleScratchBuf: ByteBuffer? = null
    private var styleScratchFloat: java.nio.FloatBuffer? = null

    /** Same scratch pattern as [styleScratchBuf] but for the 1-float `speed` tensor. */
    private var speedScratchBuf: ByteBuffer? = null
    private var speedScratchFloat: java.nio.FloatBuffer? = null

    /**
     * P-D: see [KittenDirectEngine.warmupScope] for the rationale.
     * Async warmup hides the ~400 ms warmup behind the gap between
     * Speak-screen mount and the user tapping Speak.
     */
    private val warmupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    private var warmupJob: kotlinx.coroutines.Job? = null

    /** How much of this engine's emitted audio is still ahead of the listener (T8). */
    private val horizon = PlaybackHorizon()

    /**
     * Last measured render cost (ms per token) and real-time factor of a
     * chunk of ≥ [MEASURE_MIN_TOKENS] tokens on this engine — NaN until the
     * first one. Sizes the next request's short first piece (T9): the phone
     * runs ~24 ms/token cool and ~52 hot, so a fixed size would double its
     * time-to-first-audio as it heats up.
     */
    @Volatile private var measuredMsPerToken = Double.NaN
    @Volatile private var measuredRtf = Double.NaN

    /**
     * Real-time factor (plan + generator time ÷ audio) of the last streamed
     * piece of ≥ [MEASURE_MIN_TOKENS] tokens — NaN until the first. Decides
     * the next request's up-front pre-roll on the streaming path.
     */
    @Volatile private var measuredStreamRtf = Double.NaN

    /** The generator's cost per streamed slice, for chunk mode's hold ([PieceGate], #54). */
    private val sliceCost = SliceCost()

    /**
     * What predicts a later chunk's wait for its first sound (#49): the
     * streaming planner's cost and the last first-slice time (NaN until
     * one), or the whole path's render cost. Warm-up pieces aren't recorded:
     * the first run after load is a cold outlier.
     */
    private val planCost = PieceCostModel(DEFAULT_PLAN_FIXED_MS, DEFAULT_PLAN_MS_PER_TOKEN)
    @Volatile private var measuredFirstSliceMs = Double.NaN
    private val renderCost = PieceCostModel(0.0, DEFAULT_MS_PER_TOKEN)

    override fun isInstalled(): Boolean = isInstalledIn(engineDir, genStatsVoiceKeys)

    /**
     * [env] is the publish-last marker `doLoad` writes only after every
     * field the synth path touches is populated, and `releaseInternal`
     * nulls first — so a plain volatile read is the whole answer, with no
     * lock and no risk of reporting a half-loaded engine as ready.
     */
    override fun isLoaded(): Boolean = env != null

    override fun ensureModelLoaded() {
        if (env != null) return
        kotlinx.coroutines.runBlocking { ensureLoadedSuspending() }
    }

    private suspend fun ensureLoadedSuspending() {
        if (env != null) return
        loadLock.withLock {
            if (env != null) return
            if (!isInstalled()) throw EngineNotInstalledException(engineName)
            val manualThreads = settings.intraOpThreads.firstOrNull()
            val threadCount = EngineDevKnobs.get(ctx.filesDir, "threads")?.toIntOrNull()
                ?: manualThreads ?: CpuClusterDetector.detectPerfCoreCount()
            val t0 = System.currentTimeMillis()
            try {
                doLoad(threadCount)
                Log.i(TAG, "loaded in ${System.currentTimeMillis() - t0} ms")
            } catch (t: Throwable) {
                releaseInternal()
                throw IllegalStateException("KokoroDirect load failed: ${t.message}", t)
            }
        }
    }

    private fun doLoad(intraOpThreads: Int) {
        // Build everything against a local [ort]; only publish to the
        // [env] field at the END (see [KittenDirectEngine.doLoad] for
        // the race rationale).
        val ort = OrtEnvironment.getEnvironment()
        // ORT copies the options into the native session; close them after
        // so keepalive evict/reload cycles don't leak native memory.
        shrinkArena = EngineDevKnobs.get(ctx.filesDir, "shrink") != "false"
        val path = renderPathFor(engineDir, EngineDevKnobs.get(ctx.filesDir, "kokoro.stream"), genStatsVoiceKeys)
        val sessionsStart = System.currentTimeMillis()
        if (path == RenderPath.STREAMING) {
            val spin = streamSpinning(EngineDevKnobs.get(ctx.filesDir, "kokoro.spin"))
            streamThreads = intraOpThreads
            streamSpin = spin
            streamAffinity = EngineDevKnobs.get(ctx.filesDir, "kokoro.affinity")
            splitPlanner = usesSplitPlanner(engineDir, EngineDevKnobs.get(ctx.filesDir, "kokoro.planner"))
            val planner = modelFile(engineDir, if (splitPlanner) PLANNER_REST_GRAPH else PLANNER_GRAPH)
            val gen = modelFile(engineDir, GEN_GRAPH)
            plannerSession = streamSessionOptions().use { createSession(ort, it, planner) }
            genSession = streamSessionOptions().use { createSession(ort, it, gen) }
            val encoders = if (!splitPlanner) {
                ""
            } else {
                listOf(TEXT_ENCODER_EN_GRAPH, TEXT_ENCODER_GRAPH)
                    .map { modelFile(engineDir, it) }.filter { it.isFile }
                    .joinToString(" | ", prefix = " fed by ", postfix = " (opened on first use)") { it.name }
            }
            Log.i(
                TAG,
                "render path: streaming (${planner.name}$encoders + ${gen.name}, CPU EP), " +
                    "sessions in ${System.currentTimeMillis() - sessionsStart} ms, threads=$intraOpThreads " +
                    "spin=$spin affinity=${streamAffinity ?: "none"} shrink=$shrinkArena, slices ${KittenStreamSlicing.FIRST_SLICE_FRAMES}, " +
                    "${KittenStreamSlicing.SLICE_FRAMES}, doubling to ${KittenStreamSlicing.MAX_SLICE_FRAMES} " +
                    "frames, context ${KittenStreamSlicing.CONTEXT_FRAMES}",
            )
        } else {
            val model = modelFile(engineDir, MODEL_GRAPH)
            acousticSession = buildSessionOptions(intraOpThreads).use { createSession(ort, it, model) }
            Log.i(
                TAG,
                "render path: whole (${model.name}), session in ${System.currentTimeMillis() - sessionsStart} ms, " +
                    "threads=$intraOpThreads spin=false shrink=$shrinkArena",
            )
        }
        streaming = path == RenderPath.STREAMING
        voicesFloatView = mmapVoicesAsFloatBuffer(voicesFile)
        Log.i(TAG, "mmap'd voices.bin (${voicesFloatView?.limit() ?: 0} floats)")

        // Pre-allocate fixed-shape input tensor scratch — reused across
        // every chunk. See the field doc on [styleScratchBuf] for why.
        styleScratchBuf = ByteBuffer.allocateDirect(STYLE_DIM * 4).order(ByteOrder.nativeOrder())
        styleScratchFloat = styleScratchBuf!!.asFloatBuffer()
        speedScratchBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        speedScratchFloat = speedScratchBuf!!.asFloatBuffer()

        // libespeak-ng.so is compiled from source into the APK, and the data
        // is the app-level shared full-language tree — bundle-shipped espeak
        // data is ignored legacy. See phonemizer/SharedEspeakData.kt.
        val espeak = EspeakPhonemizer(
            libPath = EspeakPhonemizer.APK_LIB_NAME,
            dataPath = sharedEspeak.ensure().absolutePath,
            voice = ESPEAK_VOICE,
            fixupModel = EnPhonemeFixups.Model.KOKORO,
        )
        val rate = espeak.open()
        if (rate < 0) {
            throw IllegalStateException("espeak failed to open (status=$rate)")
        }
        phonemizer = espeak
        Log.i(TAG, "espeak version=${espeak.version()}")

        // Japanese frontend (Open JTalk). Optional — older bundles don't ship
        // the dict, in which case ja degrades to espeak.
        val ojtDictDir = File(engineDir, OPENJTALK_DICT_DIR)
        if (File(ojtDictDir, "sys.dic").isFile) {
            val t2 = System.currentTimeMillis()
            val ojt = OpenJtalkPhonemizer(ojtDictDir.absolutePath)
            if (ojt.open()) {
                openJtalk = ojt
                Log.i(TAG, "loaded Open JTalk (ja) in ${System.currentTimeMillis() - t2} ms")
            } else {
                Log.w(TAG, "Open JTalk open() failed — Japanese falls back to espeak (degraded)")
            }
        } else {
            Log.w(TAG, "openjtalk_dic absent — Japanese falls back to espeak (degraded)")
        }

        // Publish only after every field a sibling caller's inference
        // touches is non-null.
        env = ort

        // P-D: warmup runs async — see KittenDirectEngine for details.
        warmupJob = warmupScope.launch { warmupSynth() }
    }

    /**
     * The streaming graphs' sessions ([streamThreads] intra-op threads,
     * pinned per [streamAffinity] when set): the plain CPU EP (the int8 QDQ
     * graph SIGSEGVs under XNNPACK, see [buildSessionOptions]), with intra-op
     * spinning unless [streamSpin] is off. The planner and every slice are many
     * short ORT calls, and pool threads that sleep between them pay a wake-up
     * on each: #5's clean phone bench (Pixel 8a, Heart, B1 set) measured first
     * sound median / worst 1.36 s / 2.3 s without spinning and 0.94 s / 1.38 s
     * with it, RTF 0.85 → 0.79. The whole path keeps spinning off (RTF 0.75
     * vs 0.78 with it).
     */
    private fun streamSessionOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(streamThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            addConfigEntry("session.intra_op.allow_spinning", if (streamSpin) "1" else "0")
            streamAffinity?.let { addConfigEntry("session.intra_op_thread_affinities", it) }
        }

    private fun buildSessionOptions(intraOpThreads: Int): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            // The v23+ bundle ships a quantized (static QDQ int8) model and
            // marks it with a model-format file. Registering the XNNPACK EP
            // for that graph SIGSEGVs inside ORT-Android 1.26 session setup
            // (measured Pixel 8a, 2026-08-01) — and the CPU EP's own int8
            // kernels are what make the quantized model fast anyway (bench:
            // QDQ/CPU 0.77 vs fp32/XNNPACK 1.01 mean RTF, same run). fp32
            // bundles (≤v22, no marker) keep XNNPACK: it's worth ~18% there
            // (fp32/CPU measured 1.22).
            if (isQuantizedBundle()) {
                Log.i(TAG, "quantized model bundle — CPU EP (intraOpThreads=$intraOpThreads)")
            } else try {
                addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                Log.i(TAG, "XNNPACK EP enabled (intraOpThreads=$intraOpThreads)")
            } catch (t: Throwable) {
                Log.w(TAG, "XNNPACK EP unavailable; CPU EP only", t)
            }
        }
    }

    private fun isQuantizedBundle(): Boolean =
        runCatching { File(engineDir, "model-format.txt").readText().trim().startsWith("qdq") }
            .getOrDefault(false)

    /**
     * Touch the sessions (the streaming pair, or the unsplit model) with a
     * one-word synth whose audio is dropped, so ORT's first-call kernel
     * compilation costs fold into engine load. Mirrors the Pocket and
     * KittenDirect warmup strategy. Takes [synthLock] per ORT call, like any
     * synth. The one word is in [warmupVoiceKey]'s language (English here,
     * so with the v26 planner it opens the English encoder; German in
     * [KokoroGermanEngine], which opens `textenc`); when the default voice is
     * one of this engine's non-English voices, the warmup opens the int8
     * ALBERT encoder too (~230 ms on a Pixel 6a), so that voice's first
     * sentence doesn't wait for it.
     */
    private suspend fun warmupSynth() {
        val t0 = System.currentTimeMillis()
        try {
            if (splitPlanner) {
                val defaultKey = settings.defaultVoiceId.firstOrNull()
                    ?.takeIf { it.startsWith("$engineName:") }?.substringAfter(':')
                if (defaultKey?.let { KokoroDirectVoiceCatalog.languageFor(it) }?.startsWith("en") == false) {
                    val ort = env ?: return
                    synthLock.withLock { textEncoderSession(ort, TEXT_ENCODER_GRAPH) }
                }
            }
            val voiceKey = warmupVoiceKey
            val lang = espeakVoiceFor(voiceKey)
            val pieces = synthLock.withLock { tokenPieces("Hi.", lang) }
            for (piece in pieces) {
                if (streaming) {
                    renderSliced(piece, voiceKey, lang, speed = 1.0f, warmup = true) { _, _, _, _ -> }
                } else {
                    synthLock.withLock { inferTokens(piece.text, piece.ids, voiceKey, speed = 1.0f, piece.styleRow) }
                }
            }
            Log.i(TAG, "warmup synth done in ${System.currentTimeMillis() - t0} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "warmup failed (non-fatal): ${t.message}")
        }
    }

    // -- inference ------------------------------------------------------------

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): SynthAudio = withContext(Dispatchers.Default) {
        val parts = ArrayList<ShortArray>()
        synthesizeStream(text, voiceId, speed, phonemizationLanguage, sentencePauseMs = sentencePauseMs).collect { parts.add(it.pcm) }
        if (parts.isEmpty()) return@withContext SynthAudio(ShortArray(0), sampleRate)
        val total = parts.sumOf { it.size }
        val merged = ShortArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, merged, pos, p.size)
            pos += p.size
        }
        SynthAudio(pcm = merged, sampleRate = sampleRate)
    }

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        playbackRate: Float,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic — see KittenDirectEngine for the rationale.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        // A retired voice (a stale caller, a not-yet-migrated alias) renders
        // as its replacement, style row and generator statistics alike.
        val voiceName = liveVoiceKey(voiceId.substringAfter(':', voiceId))
        // Resolve effective phonemization language: explicit override > voice's
        // natural language. espeakVoiceFor maps each voice prefix (af_*→en-us,
        // jf_*→ja, zf_*→en-us-since-zh-goes-through-lexicon). The value also
        // selects the phonemizer in encodeTextToTokens: "ja" → Open JTalk +
        // cutlet, else espeak (+ zh lexicon for CJK runs). setVoice here is
        // only a warm-up — per-chunk phonemize(text, lang) re-asserts the
        // voice atomically, since espeak's voice is process-global and a
        // concurrent synth on the other engine can flip it between chunks.
        // The "auto" sentinel is resolved per utterance at the call sites
        // (all three synthesis routes). Treating a leftover one as "no
        // override" rather than passing it on keeps a plumbing gap from
        // reaching espeak_SetVoiceByName as a nonexistent voice.
        val effectiveLang = phonemizationLanguage?.takeIf { it != LangDetector.AUTO }
            ?: espeakVoiceFor(voiceName)
        phonemizer?.setVoice(effectiveLang)
        if (streaming) {
            streamSliced(text, voiceName, effectiveLang, speed, playbackRate, streamStartNs, loadWaitMs, sentencePauseMs)
            return@channelFlow
        }
        // Whole path (see the file comment).
        //
        // The alias's pause between sentences (#96) goes into each piece's
        // audio at its sentence ends, as on the streaming path, placed from
        // the model's own duration output.
        //
        // Chunks are sized in the model's own tokens (TextChunker.planByTokens):
        // Kokoro's cost is linear in tokens, and a character is ≈1.1 tokens in
        // English but ≈3.4 in Chinese. Counting encodes each sentence once;
        // the ids are kept so a chunk rendered as planned isn't encoded twice.
        //
        // Short first piece (T1/T8/T9): when the audio this engine already
        // sent can't cover rendering the first sentence whole — nothing
        // playing, or too little left — the first sentence is cut at a clause
        // mark into a small piece sized from the measured speed, then growing
        // pieces. A request prefetched behind a playing one keeps it whole.
        val planStartNs = System.nanoTime()
        val msPerToken = measuredMsPerToken
        val budget = kokoroStreamBudget(TOKEN_BUDGET, msPerToken, measuredRtf, playbackRate)
        val aheadMs = horizon.remainingMs()
        var cutFirst = false
        val encoded = HashMap<String, IntArray>()
        val chunks = synthLock.withLock {
            TextChunker.planByTokens(
                text,
                budget,
                cutFirstSentence = { firstTokens ->
                    cutFirst = aheadMs < firstTokens * (msPerToken.takeIf { it > 0 } ?: DEFAULT_MS_PER_TOKEN)
                    cutFirst
                },
            ) { part ->
                encoded.getOrPut(part) { encodeTextToTokens(part, effectiveLang) }.size
            }
        }
        if (chunks.isEmpty()) return@channelFlow
        // Every piece goes out whole on this path: chunk mode in all but name (#54).
        val pace = RenderMode.paceFor(engineName, settings, ctx.filesDir, playbackRate)
        pace.mode().log(engineName, path = "whole")
        horizon.startRequest(pace.rate())
        val planMs = (System.nanoTime() - planStartNs) / 1_000_000
        Log.d(
            PERF_TAG,
            "kokoro plan chunks=${chunks.size} firstTokens=${chunks.first().tokens} " +
                "maxTokens=${chunks.maxOf { it.tokens }} cutFirst=$cutFirst aheadMs=$aheadMs " +
                "firstPiece=${budget.firstPiece} growth=${"%.2f".format(budget.growth)} " +
                "msPerToken=${"%.1f".format(msPerToken)} planMs=$planMs",
        )

        // P-A diagnostic: per-chunk infer time + real-time factor + producer
        // inter-send gap. See KittenDirectEngine for the rationale.
        //
        // Adaptive pre-roll (2026-09-20): a downstream time-stretch makes
        // the played clock run playbackRate× faster than the engine
        // clock, and warm Kokoro on the 8a is only RTF ~0.55 — at 2×
        // that's realtime with no headroom, and the old emit-as-rendered
        // loop stalled ~1.1 s between sentences. The gate holds early
        // chunks until the buffer covers the measured deficit; at 1.0×
        // it decides K=1 and this loop behaves exactly as before.
        val gate = PrerollGate<SynthAudio>(playbackRate, chunks.size)
        val sender = AudioSender(this, streamStartNs, loadWaitMs, pace::rate) { "K=${gate.prerollChunks}" }
        // Played audio the gate holds: it plays once released, so it is ahead too.
        var heldMs = 0L
        val queue = ArrayDeque(chunks)
        var idx = -1
        try {
            while (queue.isNotEmpty()) {
                idx++
                // A cancelled stream (reader tap, Stop, skip) must stop HERE:
                // inference blocks and the uncontended lock/buffered send never
                // suspend, so without this check a cancelled producer rendered
                // every remaining chunk while holding the service's synth mutex —
                // the next request's TTFA paid for all of them (~11 s on the 8a).
                ensureActive()
                // A later chunk's whole render is heard unless the audio ahead
                // covers it (#49).
                val chunk = if (idx == 0) {
                    queue.removeFirst()
                } else {
                    nextChunk(queue, idx, horizon.remainingMs() + heldMs, budget, renderCost, 0.0, effectiveLang, encoded)
                }
                val pieces = synthLock.withLock { tokenPieces(chunk.text, effectiveLang, encoded[chunk.text], chunk.rowTokens) }
                // An over-cap chunk's pieces go out one at a time, each as soon
                // as it is rendered — joining them first made a CJK run-on wait
                // for its whole second half before any sound (31.6 s on the 8a).
                for ((pi, piece) in pieces.withIndex()) {
                    // Per-piece check: a run-on's next piece is seconds of
                    // inference nobody will hear once the user has pressed Stop.
                    ensureActive()
                    val inferStartNs = System.nanoTime()
                    var dur: LongArray? = null
                    val pcm = synthLock.withLock {
                        inferTokens(
                            piece.text, piece.ids, voiceName, speed, piece.styleRow,
                            durations = if (sentencePauseMs > 0) { d -> dur = d } else null,
                        )
                    }
                    val inferMs = (System.nanoTime() - inferStartNs) / 1_000_000
                    renderCost.record(piece.ids.size, inferMs.toDouble())
                    if (pcm.isEmpty()) continue
                    val audioMs = pcm.size * 1000L / sampleRate
                    val rtf = if (audioMs > 0) inferMs.toDouble() / audioMs else Double.NaN
                    // Speed memory for the next request's first-piece sizing (T9).
                    // Tiny pieces are mostly fixed per-call cost, so skip them.
                    if (piece.ids.size >= MEASURE_MIN_TOKENS && audioMs > 0) {
                        measuredMsPerToken = inferMs.toDouble() / piece.ids.size
                        measuredRtf = rtf
                    }
                    val pieceTag = if (pieces.size > 1) " piece=$pi/${pieces.size}" else ""
                    Log.d(PERF_TAG, "kokoro chunk=$idx/${idx + 1 + queue.size}$pieceTag infer=${inferMs}ms audio=${audioMs}ms rtf=${"%.2f".format(rtf)} tokens=${piece.ids.size} row=${piece.styleRow} textLen=${piece.text.length}")
                    val lastPiece = queue.isEmpty() && pi == pieces.size - 1
                    val paused = wholePiecePause(piece.ids, dur, sentencePauseMs, lastPiece)?.apply(pcm, 0, last = true) ?: pcm
                    val release = gate.onChunkRendered(
                        chunk = SynthAudio(pcm = paused, sampleRate = sampleRate),
                        renderMs = inferMs,
                        audioMs = audioMs,
                    )
                    heldMs = if (release.isEmpty()) heldMs + (audioMs / playbackRate.coerceAtLeast(0.1f)).toLong() else 0L
                    for (audio in release) sender.send(audio)
                }
            }
            // Anything the gate still holds (e.g. the nominal last chunk
            // phonemized to nothing, so the flush never triggered).
            for (audio in gate.drain()) sender.send(audio)
        } catch (t: Throwable) {
            // Cancelled (Stop, reader tap) or failed: what this stream was
            // feeding has been cut off, so the next request starts cold.
            horizon.clear()
            throw t
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Sends a stream's audio downstream: the TTFA line on the first send, an
     * `emit` line per send (audio length, gap since the last send, the
     * pre-roll: K chunks, or the streaming path's bank) and the [horizon]
     * bookkeeping, on the played clock.
     */
    private inner class AudioSender(
        private val out: SendChannel<SynthAudio>,
        private val streamStartNs: Long,
        private val loadWaitMs: Long,
        private val rate: () -> Float,
        private val preroll: () -> String,
    ) {
        private var prevSendNs = 0L
        private var firstSent = false

        suspend fun send(audio: SynthAudio) {
            val gapMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
            if (!firstSent) {
                firstSent = true
                val ttfaMs = (System.nanoTime() - streamStartNs) / 1_000_000
                Log.d(PERF_TAG, "kokoro TTFA=${ttfaMs}ms (loadWait=${loadWaitMs}ms) ${preroll()} streaming=$streaming")
            }
            val audioMs = audio.pcm.size * 1000L / audio.sampleRate
            Log.d(PERF_TAG, "kokoro emit audio=${audioMs}ms gap=${gapMs}ms ${preroll()}")
            out.send(audio)
            horizon.onEmitted((audioMs / rate()).toLong())
            prevSendNs = System.nanoTime()
        }
    }

    /**
     * Takes the next chunk off [queue] (the [idx]th of the request, never
     * the first), cut at a clause first when its wait for a first sound,
     * [cost]'s line for it plus [firstSoundMs], would outrun [aheadMs] of
     * audio still to play ([kokoroLaterHead], #49). The rest goes back on
     * the queue and is checked again in turn. Pieces keep the sentence's
     * style row.
     */
    private suspend fun nextChunk(
        queue: ArrayDeque<TextChunker.TokenChunk>,
        idx: Int,
        aheadMs: Long,
        budget: TextChunker.TokenBudget,
        cost: PieceCostModel,
        firstSoundMs: Double,
        lang: String,
        encoded: HashMap<String, IntArray>,
    ): TextChunker.TokenChunk {
        val chunk = queue.removeFirst()
        val line = cost.lineFor(chunk.tokens)
        val fixedMs = line.fixedMs + firstSoundMs
        val msPerToken = line.msPerToken
        val head = kokoroLaterHead(chunk.tokens, aheadMs, msPerToken, fixedMs, budget.firstPieceMax) ?: return chunk
        val pieces = synthLock.withLock {
            TextChunker.clauseHead(chunk, kokoroLaterCutBudget(budget, head)) { part ->
                encoded.getOrPut(part) { encodeTextToTokens(part, lang) }.size
            }
        }
        Log.d(
            PERF_TAG,
            "kokoro cut chunk=$idx tokens=${chunk.tokens} aheadMs=$aheadMs " +
                "waitMs=${(fixedMs + chunk.tokens * msPerToken).toLong()} head=$head pieces=${pieces.map { it.tokens }}",
        )
        for (p in pieces.drop(1).asReversed()) queue.addFirst(p)
        return pieces.first()
    }

    /**
     * The streaming path's request loop (see the file comment). Sentences
     * stay whole up to the 500-token cap ([STREAM_TOKEN_BUDGET]); each
     * chunk's cap pieces ([tokenPieces]) are rendered by [renderSliced], and
     * each slice goes out as its own [SynthAudio], a resume point only on a
     * piece's first slice (issue #11).
     *
     * The planner reads a whole piece before its first slice (~5 ms a
     * token on the 8a), so a long first sentence is cut at a clause into a
     * ~80-token head when that wait would be heard ([kokoroStreamCutFirst]:
     * nothing playing to cover it, or a pre-roll to bank), and so is any
     * later chunk whose plan would outrun the audio still to play
     * ([nextChunk], #49: "Good morning." then a long sentence).
     *
     * Pre-roll is sized in audio, not chunks: slices are held until they
     * hold [PrerollGate.streamBankMs] of played audio, worked out up front
     * from the last measured streamed RTF ([DEFAULT_STREAM_RTF] before any)
     * and the request's token count, then everything goes out as rendered.
     * None in chunk mode, whose gate holds each piece itself.
     */
    private suspend fun ProducerScope<SynthAudio>.streamSliced(
        text: String,
        voiceName: String,
        lang: String,
        speed: Float,
        playbackRate: Float,
        streamStartNs: Long,
        loadWaitMs: Long,
        sentencePauseMs: Int,
    ) {
        val planStartNs = System.nanoTime()
        val rtf = measuredStreamRtf.takeUnless { it.isNaN() } ?: DEFAULT_STREAM_RTF
        val aheadMs = horizon.remainingMs()
        val holding = rtf * playbackRate > PrerollGate.REALTIME_MARGIN
        var cutFirst = false
        val encoded = HashMap<String, IntArray>()
        val chunks = synthLock.withLock {
            TextChunker.planByTokens(
                text,
                STREAM_TOKEN_BUDGET,
                cutFirstSentence = { firstTokens ->
                    cutFirst = kokoroStreamCutFirst(firstTokens, aheadMs, holding)
                    cutFirst
                },
            ) { part ->
                encoded.getOrPut(part) { encodeTextToTokens(part, lang) }.size
            }
        }
        if (chunks.isEmpty()) return
        val audioMs = (chunks.sumOf { it.tokens } * AUDIO_MS_PER_TOKEN / speed.coerceAtLeast(0.1f)).toLong()
        val pace = RenderMode.paceFor(engineName, settings, ctx.filesDir, playbackRate)
        // In chunk mode the gate already keeps each piece from running dry; a
        // bank would only hold a finished first sentence back until the next
        // one is released (2-2.5 s of silence after a tap, German at 2x, #57).
        val bankMs = if (pace.mode().chunk) 0L else PrerollGate.streamBankMs(playbackRate, rtf, audioMs)
        horizon.startRequest(pace.rate())
        Log.d(
            PERF_TAG,
            "kokoro plan chunks=${chunks.size} firstTokens=${chunks.first().tokens} " +
                "maxTokens=${chunks.maxOf { it.tokens }} streaming cutFirst=$cutFirst aheadMs=$aheadMs " +
                "bank=${bankMs}ms rtf=${"%.2f".format(rtf)} planMs=${(System.nanoTime() - planStartNs) / 1_000_000}",
        )
        val sender = AudioSender(this, streamStartNs, loadWaitMs, pace::rate) { "bank=${bankMs}ms" }
        val held = ArrayList<SynthAudio>()
        var heldPlayedMs = 0.0
        // The up-front bank: slices are held until they hold bankMs of played audio.
        suspend fun bankOrSend(audio: SynthAudio) {
            held += audio
            if (heldPlayedMs < bankMs) {
                heldPlayedMs += audio.pcm.size * 1000.0 / sampleRate / pace.rate()
                if (heldPlayedMs < bankMs) return
            }
            for (h in held) sender.send(h)
            held.clear()
        }
        val gate = TimedPieceGate<SynthAudio>(
            scope = this,
            send = { out -> for (a in out) bankOrSend(a) },
            rate = pace::rate,
            onRelease = { heldMs, slices, rest ->
                Log.d(PERF_TAG, "kokoro chunk-mode release held=${heldMs}ms slices=$slices rest=$rest")
            },
        )
        val queue = ArrayDeque(chunks)
        var idx = -1
        try {
            while (queue.isNotEmpty()) {
                idx++
                // Cancellation checks between chunks, pieces and slices: a
                // stopped stream must not keep rendering (see the whole path).
                currentCoroutineContext().ensureActive()
                // A later chunk's plan is heard unless the audio ahead covers
                // it (#49): the slices still held for the bank play too.
                val chunk = if (idx == 0) {
                    queue.removeFirst()
                } else {
                    val heldMs = held.sumOf { it.pcm.size } * 1000L / sampleRate / pace.rate()
                    nextChunk(
                        queue, idx, horizon.remainingMs() + heldMs.toLong(), STREAM_TOKEN_BUDGET, planCost,
                        firstSoundMs = measuredFirstSliceMs.takeIf { it > 0 } ?: DEFAULT_FIRST_SLICE_MS,
                        lang, encoded,
                    )
                }
                val pieces = synthLock.withLock { tokenPieces(chunk.text, lang, encoded[chunk.text], chunk.rowTokens) }
                for ((pi, piece) in pieces.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    // Chunk mode (#54): the piece's slices wait in the gate until
                    // the rest of it can't run dry, then go through the bank.
                    // Decided per piece, at the speed it will be heard at.
                    val mode = pace.pieceMode()
                    horizon.follow(mode.playbackRate)
                    gate.startPiece(mode.chunk)
                    // The alias's pause between sentences (#96): inside the piece
                    // at its sentence ends, and after it unless it is the last.
                    val lastPiece = queue.isEmpty() && pi == pieces.size - 1
                    renderSliced(
                        piece, voiceName, lang, speed, chunkMode = mode.chunk,
                        sentencePauseMs = sentencePauseMs, lastPiece = lastPiece,
                    ) { pcm, first, rest, extraMs ->
                        val audio = SynthAudio(pcm = pcm, sampleRate = sampleRate, resumePoint = first, extraRenderMs = extraMs)
                        gate.offer(
                            audio, pcm.size * 1000.0 / sampleRate,
                            // This request's audio still ahead, and what the bank holds.
                            aheadMs = { horizon.requestRemainingMs() + (if (held.isEmpty()) 0.0 else heldPlayedMs) },
                        ) {
                            rest.map { SliceEstimate(sliceCost.cautiousMs(it.render.count()), it.keep.count() * FRAME_MS) }
                        }
                    }
                    gate.endPiece()
                }
            }
            // The text ended before the bank filled.
            for (h in held) sender.send(h)
        } catch (t: Throwable) {
            // Cancelled or failed: the next request starts cold (whole path).
            horizon.clear()
            throw t
        }
    }

    /**
     * Render one ≤ [MAX_PHONEMES_PER_CHUNK]-token piece on the streaming
     * path: plan it once, then generate its frames slice by slice, handing
     * each slice's PCM to [emit] (flagged when it is the piece's first sound)
     * as soon as it exists. [synthLock] is held per ORT call, never across
     * [emit]. Logs the piece's plan line, one line per slice and its RTF, and
     * keeps [measuredStreamRtf] current. In [chunkMode] the slices are
     * shorter ([KittenStreamSlicing.CHUNK_MODE_MAX_SLICE_FRAMES]); the
     * generator calls that adds are reported with the piece's first slice
     * ([SynthAudio.extraRenderMs]), so the app's measured RTF doesn't depend
     * on the mode (#54).
     */
    private suspend fun renderSliced(
        piece: TokenPiece,
        voiceName: String,
        lang: String,
        speed: Float,
        warmup: Boolean = false,
        chunkMode: Boolean = false,
        sentencePauseMs: Int = 0,
        lastPiece: Boolean = true,
        emit: suspend (pcm: ShortArray, first: Boolean, rest: List<KittenStreamSlicing.Slice>, extraMs: Long) -> Unit,
    ) {
        if (piece.ids.isEmpty()) return
        val ids = wrapForKokoro(piece.ids)
        val t0 = System.nanoTime()
        val plan = synthLock.withLock { planPiece(piece.text, ids, voiceName, lang, speed, piece.styleRow) }
        val planMs = (System.nanoTime() - t0) / 1_000_000
        val slices = KokoroStreamSlicing.slices(
            plan.totalFrames, plan.tailStart,
            max = if (chunkMode) KittenStreamSlicing.CHUNK_MODE_MAX_SLICE_FRAMES else KittenStreamSlicing.MAX_SLICE_FRAMES,
        )
        val extraCalls = if (chunkMode) slices.size - KokoroStreamSlicing.slices(plan.totalFrames, plan.tailStart, max = KittenStreamSlicing.MAX_SLICE_FRAMES).size else 0
        Log.d(
            PERF_TAG,
            "kokoro plan=${planMs}ms tokens=${ids.size} frames=${plan.totalFrames} tail=${plan.tailStart} " +
                "slices=${slices.size} enc=${plan.encoder}",
        )
        val measure = piece.ids.size >= MEASURE_MIN_TOKENS
        if (!warmup) planCost.record(ids.size, planMs.toDouble())
        val pause = kokoroPiecePause(ids, plan.dur, sentencePauseMs, lastPiece)
        var genMs = 0L
        var samples = 0L
        var started = false
        for ((i, slice) in slices.withIndex()) {
            currentCoroutineContext().ensureActive()
            val tGen = System.nanoTime()
            val pcm = synthLock.withLock { generateSlice(plan, slice, last = i == slices.size - 1) }
            val sliceMs = (System.nanoTime() - tGen) / 1_000_000
            if (i == 0 && !warmup) measuredFirstSliceMs = sliceMs.toDouble()
            if (!warmup) sliceCost.add(slice.render.count(), sliceMs.toDouble())
            genMs += sliceMs
            samples += pcm.size
            Log.d(PERF_TAG, "kokoro slice=$i/${slices.size} frames=${slice.render.count()} gen=${sliceMs}ms")
            if (pcm.isEmpty()) continue
            val paused = pause?.apply(pcm, slice.keep.first * FRAME_SAMPLES, last = i == slices.size - 1) ?: pcm
            val extraMs = if (started) 0L else (extraCalls.coerceAtLeast(0) * sliceCost.fixedMs()).toLong()
            emit(paused, !started, slices.subList(i + 1, slices.size), extraMs)
            started = true
        }
        val audioMs = samples * 1000L / sampleRate
        val rtf = if (audioMs > 0) (planMs + genMs).toDouble() / audioMs else Double.NaN
        // Tiny pieces are mostly fixed per-call cost: they'd overstate the RTF.
        if (measure && audioMs > 0) {
            measuredStreamRtf = rtf
        }
        Log.d(
            PERF_TAG,
            "kokoro piece tokens=${ids.size} row=${piece.styleRow} plan=${planMs}ms gen=${genMs}ms " +
                "slices=${slices.size} audio=${audioMs}ms rtf=${"%.2f".format(rtf)}",
        )
    }

    /**
     * [kokoroPiecePause] for a whole-path piece of raw token [ids] and the
     * model's [dur] output. A model without that output (none ships today)
     * still gets the pause between pieces, logged, never inside one.
     */
    private fun wholePiecePause(ids: IntArray, dur: LongArray?, sentencePauseMs: Int, lastPiece: Boolean): KokoroSentencePause.Piece? {
        if (sentencePauseMs <= 0) return null
        val wrapped = wrapForKokoro(ids)
        if (dur != null && dur.size == wrapped.size) return kokoroPiecePause(wrapped, dur, sentencePauseMs, lastPiece)
        Log.w(TAG, "whole model gave ${dur?.size} durations for ${wrapped.size} tokens: pause only between pieces")
        if (lastPiece || !KokoroSentencePause.endsSentence(wrapped)) return null
        return KokoroSentencePause.Piece(emptyList(), SentenceGaps.samples(sentencePauseMs, sampleRate), trailing = true)
    }

    /**
     * The alias's pause between sentences for one planned piece (#96,
     * [KokoroSentencePause]); null at Natural or when the piece needs none.
     * Logged, so a render's pauses can be told from logcat.
     */
    private fun kokoroPiecePause(ids: IntArray, dur: LongArray, sentencePauseMs: Int, lastPiece: Boolean): KokoroSentencePause.Piece? =
        KokoroSentencePause.forPiece(ids, dur, sentencePauseMs, lastPiece, sampleRate)?.also {
            Log.d(PERF_TAG, "kokoro pause=${sentencePauseMs}ms splices=${it.spliceSamples} trailing=${it.trailing}")
        }

    /**
     * One planned piece: what the generator needs, copied out of the
     * planner's results so the session is free between slices. [x] is the
     * decoder front's `[channels, 2 × totalFrames]` output, row-major; [har]
     * the harmonic source, `totalFrames × FRAME_SAMPLES` samples; [gn] the
     * piece's generator norm table; [tailStart] the first frame of the
     * trailing pause ([KokoroStreamSlicing.tailStart]).
     */
    private class StreamPlan(
        val x: FloatArray,
        val channels: Int,
        val totalFrames: Int,
        val s: FloatArray,
        val har: FloatArray,
        val gn: FloatArray,
        val tailStart: Int,
        val encoder: String,
        /** The planned duration frames of the piece's wrapped token ids (#96's pause splice). */
        val dur: LongArray,
    )

    /**
     * The planner run for one piece of wrapped token [ids] in phonemization
     * language [lang]. In the v26 layout the piece's text encoder
     * ([textEncoderFor]) runs first and planner-rest takes its d_en. Caller
     * holds [synthLock].
     */
    private suspend fun planPiece(
        text: String,
        ids: IntArray,
        voiceName: String,
        lang: String,
        speed: Float,
        styleRow: Int,
    ): StreamPlan {
        val ort = env ?: error("engine not loaded")
        val session = plannerSession ?: error("planner session missing")
        // Before the run, so a failed read costs no plan.
        val stats = genStatsModel(voiceName)
        val encoderGraph = if (splitPlanner) textEncoderFor(engineDir, lang, text) else PLANNER_GRAPH
        val encoder = if (splitPlanner) textEncoderSession(ort, encoderGraph) else null
        val inputs = tokenInputs(ort, text, ids, voiceName, speed, styleRow)
        try {
            return abortableInference(engineName, { OrtRunAbort(shrinkArena) }) { abort ->
                val dEn = encoder?.run(mapOf(PLAN_TOKENS to inputs.getValue(PLAN_TOKENS)), abort.options)
                val planInputs = if (dEn == null) inputs else inputs + (PLAN_D_EN to (dEn.get(0) as OnnxTensor))
                dEn.use { session.run(planInputs, abort.options) }.use { results ->
                    val xTensor = results.get(PLAN_X).get() as OnnxTensor
                    val xLen = xTensor.info.shape[2].toInt()
                    val x = floatsOf(xTensor)
                    val har = floatsOf(results.get(PLAN_HAR).get() as OnnxTensor)
                    val dur = (results.get(PLAN_DURATION).get() as OnnxTensor).longBuffer
                        .let { b -> LongArray(b.remaining()).also { b.get(it) } }
                    val totalFrames = xLen / 2
                    check(totalFrames > 0 && dur.sum() == totalFrames.toLong() && har.size == totalFrames * FRAME_SAMPLES) {
                        "planner outputs disagree: Σdur=${dur.sum()} x=$xLen values, har=${har.size} samples"
                    }
                    val tail = KokoroStreamSlicing.tailStart(ids, dur)
                        ?: error("planner returned ${dur.size} durations for ${ids.size} tokens")
                    val pause = KokoroGenStats.pauseFrames(ids, dur).toDouble() / totalFrames
                    StreamPlan(
                        x = x,
                        channels = x.size / xLen,
                        totalFrames = totalFrames,
                        s = floatsOf(results.get(PLAN_S).get() as OnnxTensor),
                        har = har,
                        gn = KokoroGenStats.normTable(stats, pause, totalFrames),
                        tailStart = tail,
                        encoder = encoderGraph,
                        dur = dur,
                    )
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * The v26 text encoder [graph]'s session, opened on first use with the
     * streaming graphs' settings and kept until release. Caller holds
     * [synthLock].
     */
    private fun textEncoderSession(ort: OrtEnvironment, graph: String): OrtSession =
        textEncoders.getOrPut(graph) {
            val file = modelFile(engineDir, graph)
            val t0 = System.currentTimeMillis()
            streamSessionOptions().use { createSession(ort, it, file) }.also {
                Log.i(TAG, "text encoder ${file.name} opened in ${System.currentTimeMillis() - t0} ms")
            }
        }

    /**
     * One generator slice of [plan]: its kept PCM. The [last] slice holds the
     * whole trailing pause, so it alone gets the whole path's tail treatment
     * ([finishTail]). Caller holds [synthLock].
     */
    private suspend fun generateSlice(plan: StreamPlan, slice: KittenStreamSlicing.Slice, last: Boolean): ShortArray {
        val ort = env ?: error("engine not loaded")
        val session = genSession ?: error("generator session missing")
        val first = slice.render.first
        val frames = slice.render.count()
        val xLen = 2 * plan.totalFrames
        val xTensor = directFloatTensor(ort, longArrayOf(1, plan.channels.toLong(), 2L * frames)) { buf ->
            for (c in 0 until plan.channels) buf.put(plan.x, c * xLen + 2 * first, 2 * frames)
        }
        val harTensor = directFloatTensor(ort, longArrayOf(1, frames.toLong() * FRAME_SAMPLES, 1)) { buf ->
            buf.put(plan.har, first * FRAME_SAMPLES, frames * FRAME_SAMPLES)
        }
        val sTensor = directFloatTensor(ort, longArrayOf(1, plan.s.size.toLong())) { it.put(plan.s) }
        val gnTensor = directFloatTensor(ort, longArrayOf(2, plan.gn.size / 2L)) { it.put(plan.gn) }
        try {
            return abortableInference(engineName, { OrtRunAbort(shrinkArena) }) { abort ->
                session.run(
                    mapOf(GEN_X to xTensor, GEN_S to sTensor, GEN_HAR to harTensor, GEN_NORMS to gnTensor),
                    abort.options,
                ).use { results ->
                    val wav = extractWaveform(results[0].value)
                    check(wav.size >= slice.keepOffsetSamples + slice.keepSamples) {
                        "generator returned ${wav.size} samples for $frames frames"
                    }
                    if (last) {
                        finishTail(wav.copyOfRange(slice.keepOffsetSamples, slice.keepOffsetSamples + slice.keepSamples))
                    } else {
                        floatToPcm16(wav, slice.keepOffsetSamples, slice.keepSamples)
                    }
                }
            }
        } finally {
            xTensor.close()
            harTensor.close()
            sTensor.close()
            gnTensor.close()
        }
    }

    /**
     * [voiceKey]'s generator-stats model, float32 [[KokoroGenStats.MODEL_ROWS], S],
     * read on first use. A streaming bundle ships one for every voice, so a
     * missing file is a broken bundle, not a fallback. Caller holds [synthLock].
     */
    private fun genStatsModel(voiceKey: String): FloatArray {
        val key = genStatsKeyFor(voiceKey, genStatsVoiceKeys)
        return genStatsModels.getOrPut(key) {
            // The streaming layout check ([hasStreamingLayout]) found every one of [genStatsVoiceKeys].
            val bytes = File(engineDir, "$GENSTATS_DIR/$key.bin").readBytes()
            FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
    }

    private fun floatsOf(tensor: OnnxTensor): FloatArray =
        tensor.floatBuffer.let { buf -> FloatArray(buf.remaining()).also { buf.get(it) } }

    /**
     * The token ids [text] encodes to for voice [voiceKey] ([lang] overrides
     * the voice's language, as in [synthesizeStream]). For the debug bench's
     * token dump only (issue #13: phone-exact training text for the
     * per-voice generator statistics).
     */
    internal suspend fun benchTokens(text: String, voiceKey: String, lang: String?): IntArray {
        ensureLoadedSuspending()
        val effectiveLang = lang ?: espeakVoiceFor(voiceKey)
        return synthLock.withLock { encodeTextToTokens(text, effectiveLang) }
    }

    /**
     * One model call's worth of text: [text], its ≤ [MAX_PHONEMES_PER_CHUNK]
     * token ids, and its style row (see [kokoroStyleRow]).
     */
    private class TokenPiece(val text: String, val ids: IntArray, val styleRow: Int)

    /**
     * [text] encoded to token ids — one piece, or several when it overflows
     * the style table's position cap (the bound is on tokens, not IPA
     * length: the encoder interleaves lexicon tokens with espeak phonemes).
     * Truncating used to drop the chunk's tail silently: English run-ons,
     * comma-joined Chinese (≈3.4 tokens per Han char), 、-joined Japanese.
     * Re-split at clause marks → whitespace → hard cut instead; the stream
     * loop renders and emits the pieces one at a time. [known] is [text]'s
     * ids when the caller already has them.
     *
     * Every piece takes its style row from [rowTokens] — the whole sentence
     * [text] was cut from, when the planner cut it — else from [text] as a
     * whole, never from the piece itself (T6).
     */
    private fun tokenPieces(
        text: String,
        lang: String,
        known: IntArray? = null,
        rowTokens: Int? = null,
    ): List<TokenPiece> {
        val rawIds = known ?: encodeTextToTokens(text, lang)
        val styleRow = kokoroStyleRow(rawIds.size, rowTokens)
        if (rawIds.size <= MAX_PHONEMES_PER_CHUNK) return listOf(TokenPiece(text, rawIds, styleRow))
        val pieces = TextChunker.splitToFit(text) {
            encodeTextToTokens(it, lang).size <= MAX_PHONEMES_PER_CHUNK
        }
        Log.i(TAG, "token count ${rawIds.size} exceeds $MAX_PHONEMES_PER_CHUNK — re-split into ${pieces.size} pieces")
        return pieces.map { piece ->
            val ids = encodeTextToTokens(piece, lang)
            // splitToFit leaves a piece oversize only if it's one character.
            TokenPiece(piece, ids.copyOf(minOf(ids.size, MAX_PHONEMES_PER_CHUNK)), styleRow)
        }
    }

    /**
     * One ORT call for ≤ [MAX_PHONEMES_PER_CHUNK] tokens of [text], voiced
     * with style row [styleRow] (see [kokoroStyleRow]).
     */
    private suspend fun inferTokens(
        text: String,
        phonemeIds: IntArray,
        voiceName: String,
        speed: Float,
        styleRow: Int,
        /** Gets the model's duration output (frames per wrapped token), or null when it has none. */
        durations: ((LongArray?) -> Unit)? = null,
    ): ShortArray {
        if (phonemeIds.isEmpty()) return ShortArray(0)
        val ort = env ?: error("engine not loaded")
        val session = acousticSession ?: error("acoustic session missing")
        val inputs = tokenInputs(ort, text, wrapForKokoro(phonemeIds), voiceName, speed, styleRow)
        try {
            // Abortable: a cancelled stream (reader tap, Stop) stops this run
            // between graph nodes instead of finishing an unwanted chunk.
            return abortableInference(engineName, { OrtRunAbort(shrinkArena) }) { abort ->
                session.run(inputs, abort.options).use { results ->
                    durations?.invoke(
                        (results.takeIf { it.size() > 1 }?.get(1) as? OnnxTensor)?.longBuffer
                            ?.let { b -> LongArray(b.remaining()).also { b.get(it) } },
                    )
                    finishTail(extractWaveform(results[0].value))
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * The `tokens` / `style` / `speed` inputs the unsplit model and the
     * planner both take, for wrapped token ids [inputIds] voiced as
     * [voiceName] with style row [styleRow]. Style and speed fill the
     * reusable scratch buffers, so the caller holds [synthLock] until it has
     * closed the returned tensors.
     */
    private fun tokenInputs(
        ort: OrtEnvironment,
        text: String,
        inputIds: IntArray,
        voiceName: String,
        speed: Float,
        styleRow: Int,
    ): Map<String, OnnxTensor> {
        val sid = speakerIdFor(voiceName)
        if (sid < 0) {
            Log.w(TAG, "unknown voice '$voiceName' — falling back to speaker 0")
        }
        val resolvedSid = if (sid >= 0) sid else 0

        // Fill reusable scratch buffers directly — no intermediate FloatArray.
        val styleFloat = styleScratchFloat ?: error("style scratch missing")
        val speedFloat = speedScratchFloat ?: error("speed scratch missing")
        fillStyleScratch(resolvedSid, styleRow, styleFloat)
        speedFloat.clear()
        speedFloat.put(speed)
        speedFloat.rewind()

        Log.d(TAG, "input='$text'")
        Log.d(TAG, "voice='$voiceName' sid=$resolvedSid styleLen=$styleRow speed=$speed tokenCount=${inputIds.size - 2}")
        Log.d(TAG, "tokens=${inputIds.toList()}")

        // Tokens vary in size per chunk (1 to ~500 longs); still per-call
        // alloc. Future atom can cap+reuse with a max-sized scratch.
        val inputIdTensor = directLongTensor(ort, longArrayOf(1, inputIds.size.toLong())) { buf ->
            for (id in inputIds) buf.put(id.toLong())
        }
        return mapOf(
            "tokens" to inputIdTensor,
            "style" to OnnxTensor.createTensor(ort, styleFloat, longArrayOf(1, STYLE_DIM.toLong())),
            "speed" to OnnxTensor.createTensor(ort, speedFloat, longArrayOf(1)),
        )
    }

    /**
     * The end of a rendered chunk (the unsplit model's whole output, or the
     * streaming path's last slice, which holds the whole trailing pause) →
     * trimmed, compressed PCM16.
     */
    private fun finishTail(raw: FloatArray): ShortArray {
        // Amplitude-aware tail trim (CLI kokoro-daemon `_last_loud_end`
        // + TAIL_KEEP port, see TailTrim): walk back across the
        // decoder's ring-out and keep ~75 ms of it as the natural gap.
        // The blind 5000-sample chop this replaces cut real speech at
        // speeds ≥2.5x, where the rendered tail is shorter than the
        // chop (issue #8, measured 2026-09-12).
        val trimmed = TailTrim.trimTail(raw)
        // Safety net only: with a 75 ms kept tail the compressor's
        // 2400-sample gate makes it a no-op on normal chunks. It still
        // catches an unusually long model-chosen tail, which would
        // otherwise accumulate into draggy playback across many chunks.
        val pcm16 = floatToPcm16(trimmed)
        return SilenceCompressor.compress(pcm16)
    }

    /** The Mandarin lexicon, loaded on first use (see the field). Caller holds [synthLock]. */
    private fun mandarinLexicon(): LexiconZh? {
        if (!lexiconZhTried) {
            lexiconZhTried = true
            val lexFile = File(engineDir, LEXICON_ZH_FILE)
            if (lexFile.isFile) {
                val t1 = System.currentTimeMillis()
                lexiconZh = LexiconZh(lexFile).also {
                    Log.i(TAG, "loaded lexicon-zh (${it.size()} entries) in ${System.currentTimeMillis() - t1} ms")
                }
            } else {
                Log.w(TAG, "lexicon-zh.txt absent — Mandarin falls back to espeak (degraded)")
            }
        }
        return lexiconZh
    }

    /**
     * Encode a chunk of input text to Kokoro token IDs, routing CJK runs
     * through the Mandarin lexicon and everything else through espeak.
     *
     * Fast path (the common case — no Han characters, or no lexicon loaded):
     * the whole chunk goes through espeak exactly as before.
     *
     * Mixed/zh path: split on `[一-鿿]+` (sherpa's CJK regex). Han runs
     * resolve directly to token IDs via [LexiconZh.match]; the Latin/number/
     * punctuation fragments between them go through espeak (set to en-us for
     * zh voices in [synthesizeStream], so loanwords sound right). The two
     * token streams concatenate in source order.
     *
     * Why route per-run instead of sending the whole string to espeak:
     * espeak's Mandarin voice produces broken IPA for Han ideographs (no
     * word segmentation, "chinese letter" fallback). The model was trained
     * on misaki + pypinyin output, which is exactly what lexicon-zh.txt
     * bakes in. See [LexiconZh] for the full rationale.
     */
    private fun encodeTextToTokens(text: String, lang: String): IntArray {
        val phon = phonemizer ?: error("phonemizer missing")

        // Japanese: Open JTalk reading (kanji→kana, mora) → cutlet IPA. This is
        // the G2P Kokoro v1.0 was trained with (segmental, no pitch markers).
        // Falls back to espeak if the dict isn't installed (pre-v18 bundle).
        val ojt = openJtalk
        if (lang == "ja" && ojt != null) {
            val ipa = CutletJaG2P.convert(ojt.analyze(text))
            Log.d(TAG, "ja encode: '$text' → '$ipa'")
            return encodePhonemesKokoro(ipa)
        }

        val lex = if (LexiconZh.CJK_RUN_PATTERN.containsMatchIn(text)) mandarinLexicon() else null
        if (lex == null) return encodePhonemesKokoro(espeakPhonemes(phon, text, lang))

        Log.d(TAG, "zh-aware encode for '$text'")
        var out = IntArray(text.length * 8)
        var pos = 0
        var cursor = 0

        fun append(ids: IntArray) {
            if (pos + ids.size > out.size) out = out.copyOf(maxOf(out.size * 2, pos + ids.size))
            ids.copyInto(out, pos)
            pos += ids.size
        }

        for (m in LexiconZh.CJK_RUN_PATTERN.findAll(text)) {
            if (m.range.first > cursor) {
                append(encodePhonemesKokoro(espeakPhonemes(phon, text.substring(cursor, m.range.first), lang)))
            }
            append(lex.match(m.value))
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            append(encodePhonemesKokoro(espeakPhonemes(phon, text.substring(cursor), lang)))
        }
        return out.copyOf(pos)
    }

    /**
     * Espeak G2P with the language-appropriate post-processing. English
     * keeps the untied path EnPhonemeFixups was tuned against (upstream
     * English goes through the misaki lexicon anyway, so there is no
     * trained tied-token mapping to mirror); every other espeak language
     * mirrors misaki's EspeakG2P: ties on, multi-char phonemes rewritten
     * to the model's single trained tokens. See [KokoroEspeakG2P].
     *
     * German (the partial-support tier, Max 2026-09-20) runs the fuller
     * DEG2P-parity pipeline in [GermanG2P] — normalizer + override lexicon +
     * tied espeak-de + postprocess + the ʏ→y vocab repair — off the same
     * espeak instance ([GermanG2P.forEspeak] pins voice = "de" per span).
     * Every other non-English language additionally runs
     * [KokoroLangSubstitutions], which is a no-op except for the measured
     * out-of-vocab repairs (bg's ɫ→l); the es/fr/it/hi/pt paths stay
     * byte-identical.
     */
    private fun espeakPhonemes(phon: EspeakPhonemizer, text: String, lang: String): String =
        when {
            lang.startsWith("en") -> englishPhonemes(phon, text, lang)
            lang == "de" -> GermanG2P.forEspeak(phon).phonemes(text)
            else -> KokoroLangSubstitutions.apply(
                KokoroEspeakG2P.postprocess(phon.phonemize(text, lang, tie = true)),
                lang,
            )
        }

    /**
     * Phoneme→token-ID encode with Kokoro's sentence-end → `<punct> ` space
     * expansion. The shared [encodePhonemes] from KittenDirect's vocab does
     * the char-by-char mapping; we walk it here to insert an extra space token
     * after every sentence-final `.`/`!`/`?`. This mirrors the espeak JNI
     * shim's `trailingExtra` (espeak_jni.c) which appends an extra space after
     * `.!?` so Kitten/Kokoro render the long sentence-final pause prosody —
     * same treatment across all languages. Combined with the trailing space
     * cutlet/espeak already emit after a stop, sentence ends get `<punct>  `
     * (two spaces), the trained long-pause cue. (Previously only `.` got the
     * extra space, so `?`/`!`-ending sentences — common in Japanese ？ —
     * rushed into the next sentence.)
     *
     * Primitive `IntArray` with a write cursor — no boxing through
     * `ArrayList<Int>`. Worst case is every char being a sentence-ender
     * (each becomes 2 tokens), so the upper bound is `2 × baseIds.size`.
     */
    private fun encodePhonemesKokoro(phonemes: String): IntArray {
        val baseIds = encodePhonemes(phonemes)
        val out = IntArray(baseIds.size * 2)
        var pos = 0
        for (i in baseIds.indices) {
            out[pos++] = baseIds[i]
            val c = phonemes[i]
            if (c == '.' || c == '!' || c == '?') {
                out[pos++] = SPACE_TOKEN
            }
        }
        return if (pos == out.size) out else out.copyOf(pos)
    }

    /**
     * Kokoro wraps as `[0, ...ids..., 0]`. No Kitten-style end token
     * (Kitten's 10 = ellipsis marks end-of-phoneme; Kokoro uses 0/PAD
     * symmetrically as a "start/end of utterance" sentinel).
     */
    private fun wrapForKokoro(ids: IntArray): IntArray {
        val out = IntArray(ids.size + 2)
        out[0] = PAD_TOKEN
        ids.copyInto(out, destinationOffset = 1)
        out[ids.size + 1] = PAD_TOKEN
        return out
    }

    /**
     * Fill [scratch] with the 256 floats of the style row for [speakerId]
     * at phoneme-position [len]. Skips the intermediate `FloatArray`
     * allocation that the original return-value-based design forced
     * (matched the "allocate per call" pattern but wasted a 1 KB heap
     * row for every chunk).
     *
     * Layout of voices.bin: `[num_speakers, MAX_TOKEN_LEN, STYLE_DIM]`
     * row-major, so row `(s, l)` lives at offset
     * `s*MAX_TOKEN_LEN*STYLE_DIM + l*STYLE_DIM`. The bulk `put(array,
     * offset, length)` is JVM-intrinsified into a native memcpy.
     */
    private fun fillStyleScratch(speakerId: Int, len: Int, scratch: FloatBuffer) {
        scratch.clear()
        val view = voicesFloatView
        if (view == null) {
            // No voices mapped — emit zeros (caller already logged earlier).
            for (i in 0 until STYLE_DIM) scratch.put(0f)
            scratch.rewind()
            return
        }
        val clampedLen = len.coerceIn(0, MAX_TOKEN_LEN - 1)
        val start = speakerId * MAX_TOKEN_LEN * STYLE_DIM + clampedLen * STYLE_DIM
        if (start + STYLE_DIM > view.limit()) {
            Log.w(TAG, "style lookup out of range (sid=$speakerId len=$clampedLen view=${view.limit()}) — using zeros")
            for (i in 0 until STYLE_DIM) scratch.put(0f)
            scratch.rewind()
            return
        }
        // Independent cursor over the mapped buffer; no data copy.
        val src = view.duplicate()
        src.position(start)
        src.limit(start + STYLE_DIM)
        scratch.put(src)
        scratch.rewind()
    }

    /** Cast ORT's polymorphic output into a flat FloatArray. */
    private fun extractWaveform(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            @Suppress("UNCHECKED_CAST")
            (value as Array<FloatArray>).firstOrNull() ?: FloatArray(0)
        }
        else -> FloatArray(0)
    }

    private fun floatToPcm16(samples: FloatArray, offset: Int = 0, count: Int = samples.size): ShortArray {
        val out = ShortArray(count)
        for (i in 0 until count) {
            val clamped = samples[offset + i].coerceIn(-1.0f, 1.0f)
            out[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return out
    }

    /**
     * Memory-map [file] read-only and return a little-endian `FloatBuffer`
     * view. The underlying `MappedByteBuffer` keeps the file mapped for
     * the lifetime of the returned buffer (closing the RandomAccessFile
     * does not invalidate the mapping per the JDK contract). Total Java
     * heap cost is the small buffer wrapper — actual data lives in the
     * OS page cache.
     */
    private fun mmapVoicesAsFloatBuffer(file: File): FloatBuffer {
        val raf = RandomAccessFile(file, "r")
        val mbb = raf.use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
        mbb.order(ByteOrder.LITTLE_ENDIAN)
        return mbb.asFloatBuffer()
    }

    // -- ORT helpers ----------------------------------------------------------

    /**
     * On arm64 we load model files by path (ORT mmaps them — cheap, shared
     * across processes). On 32-bit ARM we read the bytes into a heap
     * ByteArray first because misaligned weight offsets in the mmap'd file
     * trip SIGBUS on ARMv7. Same workaround as PocketEngine + KittenDirect.
     */
    private fun createSession(
        ort: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        file: File,
    ): OrtSession {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return if (primary == "armeabi-v7a" || primary == "armeabi") {
            ort.createSession(file.readBytes(), opts)
        } else {
            ort.createSession(file.absolutePath, opts)
        }
    }

    private inline fun directFloatTensor(
        ort: OrtEnvironment,
        shape: LongArray,
        fill: (java.nio.FloatBuffer) -> Unit,
    ): OnnxTensor {
        val count = shape.fold(1L) { acc, d -> acc * d }.toInt().coerceAtLeast(1)
        val buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fill(buf)
        buf.rewind()
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    private inline fun directLongTensor(
        ort: OrtEnvironment,
        shape: LongArray,
        fill: (java.nio.LongBuffer) -> Unit,
    ): OnnxTensor {
        val count = shape.fold(1L) { acc, d -> acc * d }.toInt().coerceAtLeast(1)
        val buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
        fill(buf)
        buf.rewind()
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    override fun release() {
        // synthLock excludes an in-flight chunk inference / warmup (closing
        // a session mid-run() is a native SIGSEGV — synth holds it at most
        // one chunk at a time); loadLock excludes a concurrent load. This
        // is the only place both are held, so the order can't deadlock.
        kotlinx.coroutines.runBlocking {
            synthLock.withLock { loadLock.withLock { releaseInternal() } }
        }
    }

    private fun releaseInternal() {
        // Cancel any mid-flight warmup so it doesn't blow up trying to
        // touch sessions we're about to null out.
        warmupJob?.cancel()
        warmupJob = null
        try { acousticSession?.close() } catch (_: Throwable) {}
        try { plannerSession?.close() } catch (_: Throwable) {}
        try { genSession?.close() } catch (_: Throwable) {}
        textEncoders.values.forEach { try { it.close() } catch (_: Throwable) {} }
        try { phonemizer?.close() } catch (_: Throwable) {}
        try { openJtalk?.close() } catch (_: Throwable) {}
        acousticSession = null
        plannerSession = null
        genSession = null
        textEncoders.clear()
        splitPlanner = false
        genStatsModels.clear()
        phonemizer = null
        openJtalk = null
        lexiconZh = null
        lexiconZhTried = false
        voicesFloatView = null
        styleScratchBuf = null
        styleScratchFloat = null
        speedScratchBuf = null
        speedScratchFloat = null
        env = null  // process-scoped; do not close
    }

    /** How a load renders (see the file comment). */
    internal enum class RenderPath { STREAMING, WHOLE }

    companion object {
        const val ENGINE_NAME = "kokoro-direct-v1_0"

        /** Graph base names; [modelFile] adds the extension. */
        private const val MODEL_GRAPH = "model"
        private const val PLANNER_GRAPH = "planner"
        private const val GEN_GRAPH = "gen"

        /** The v26 planner (issue #25): the planner minus ALBERT, and its two text encoders. */
        internal const val PLANNER_REST_GRAPH = "planner-rest"
        internal const val TEXT_ENCODER_GRAPH = "textenc"
        internal const val TEXT_ENCODER_EN_GRAPH = "textenc-en"
        private const val GENSTATS_DIR = "genstats"
        private const val VOICES_FILE = "voices.bin"
        private const val TOKENS_FILE = "tokens.txt"
        private const val LEXICON_ZH_FILE = "lexicon-zh.txt"
        private const val OPENJTALK_DICT_DIR = "openjtalk_dic"

        // The cut's graph boundary (scripts in ~/coding/scratch/kokoro-stream-d1c/):
        // planner outputs, then generator inputs. GEN_NORMS is the norm table.
        private const val PLAN_TOKENS = "tokens"
        private const val PLAN_X = "x"
        private const val PLAN_S = "s"
        private const val PLAN_HAR = "har"
        private const val PLAN_DURATION = "duration"

        /** The text encoders' output and planner-rest's input: ALBERT + bert_encoder's tensor, [1, T, 512]. */
        private const val PLAN_D_EN = "/bert_encoder/Add_output_0"
        private const val GEN_X = "x"
        private const val GEN_S = "s"
        private const val GEN_HAR = "har"
        private const val GEN_NORMS = "gn"

        /** Token ID for ASCII space in the Kokoro vocab. */
        private const val SPACE_TOKEN = 16

        /**
         * The file for [graph] in [dir]: `<graph>.ort` when the bundle ships
         * one (ORT's own format, which skips graph parsing at load), else
         * `<graph>.onnx`.
         */
        internal fun modelFile(dir: File, graph: String): File =
            File(dir, "$graph.ort").takeIf { it.isFile } ?: File(dir, "$graph.onnx")

        /**
         * Raw keys of the multilingual catalog's offered voices (a retired
         * one's stats may stay in the bundle, unread): the default
         * [voiceKeys] of the layout helpers below. An engine with its own
         * voices passes its own keys ([genStatsVoiceKeys]).
         */
        internal val CATALOG_VOICE_KEYS: List<String> =
            KokoroDirectVoiceCatalog.voices.map { it.id.substringAfter(':') }

        /** Whether [dir] holds the streaming pair and the generator statistics of every one of [voiceKeys]. */
        internal fun hasStreamingLayout(dir: File, voiceKeys: List<String> = CATALOG_VOICE_KEYS): Boolean =
            (modelFile(dir, PLANNER_GRAPH).isFile || hasSplitPlanner(dir)) &&
                modelFile(dir, GEN_GRAPH).isFile && hasAllGenStats(dir, voiceKeys)

        /**
         * Whether [dir] holds the v26 planner: planner-rest and the text
         * encoder every language can use. Preferred over a whole planner.
         */
        internal fun hasSplitPlanner(dir: File): Boolean =
            modelFile(dir, PLANNER_REST_GRAPH).isFile && modelFile(dir, TEXT_ENCODER_GRAPH).isFile

        /**
         * Whether a streaming load uses the v26 planner: whenever [dir] has
         * it, unless the debug knob [plannerKnob] (`kokoro.planner`) is
         * `whole` and a whole planner is there to fall back on (an A/B).
         */
        internal fun usesSplitPlanner(dir: File, plannerKnob: String?): Boolean =
            hasSplitPlanner(dir) && !(plannerKnob == "whole" && modelFile(dir, PLANNER_GRAPH).isFile)

        /**
         * The v26 text encoder for a piece of [text] phonemized as [lang]:
         * the English student (`textenc-en`, when [dir] has it) for English
         * phonemes, else mixed-int8 ALBERT (`textenc`). The encoder reads
         * only token ids, so the voice doesn't matter, only what the ids
         * spell. English means an `en*` espeak language and no Han run:
         * a Han run reads through the Mandarin lexicon whatever the
         * language, and the student fails Chinese and Japanese (#16: 0.61
         * and 0.52 frames/token; es/fr/it/pt-BR pass, hi is borderline).
         */
        internal fun textEncoderFor(dir: File, lang: String, text: String): String =
            if (lang.startsWith("en") && !LexiconZh.CJK_RUN_PATTERN.containsMatchIn(text) &&
                modelFile(dir, TEXT_ENCODER_EN_GRAPH).isFile
            ) {
                TEXT_ENCODER_EN_GRAPH
            } else {
                TEXT_ENCODER_GRAPH
            }

        /**
         * Whether [dir]'s genstats/ has a non-empty file for every one of
         * [voiceKeys] (the engine's voices, speaker order). An empty or
         * partial directory is not a streaming layout: the bundle renders
         * whole if it has model.onnx, and is not installed if it doesn't.
         */
        internal fun hasAllGenStats(dir: File, voiceKeys: List<String> = CATALOG_VOICE_KEYS): Boolean =
            voiceKeys.all { key -> File(dir, "$GENSTATS_DIR/$key.bin").let { it.isFile && it.length() > 0 } }

        /**
         * The voice of [voiceKeys] whose generator statistics [voiceKey]
         * renders with: itself when it is one of them, else the first
         * (speaker 0, the voice the style lookup falls back to as well).
         * Never a raw key in a path.
         */
        internal fun genStatsKeyFor(voiceKey: String, voiceKeys: List<String> = CATALOG_VOICE_KEYS): String =
            if (voiceKey in voiceKeys) voiceKey else voiceKeys.first()

        /**
         * A bundle either path can render: voices and tokens, plus the
         * unsplit model or the streaming layout for [voiceKeys].
         */
        internal fun isInstalledIn(dir: File, voiceKeys: List<String> = CATALOG_VOICE_KEYS): Boolean =
            dir.isDirectory && File(dir, VOICES_FILE).isFile && File(dir, TOKENS_FILE).isFile &&
                (modelFile(dir, MODEL_GRAPH).isFile || hasStreamingLayout(dir, voiceKeys))

        /**
         * Streaming whenever [dir] has the streaming layout for [voiceKeys],
         * unless the debug knob [streamKnob] (`kokoro.stream`) is `false` and
         * the unsplit model is there to fall back on.
         */
        internal fun renderPathFor(
            dir: File,
            streamKnob: String?,
            voiceKeys: List<String> = CATALOG_VOICE_KEYS,
        ): RenderPath =
            if (hasStreamingLayout(dir, voiceKeys) && !(streamKnob == "false" && modelFile(dir, MODEL_GRAPH).isFile)) {
                RenderPath.STREAMING
            } else {
                RenderPath.WHOLE
            }

        /** Intra-op spinning for the streaming pair: on, unless the debug knob `kokoro.spin` says `false`. */
        internal fun streamSpinning(spinKnob: String?): Boolean = spinKnob?.toBooleanStrictOrNull() ?: true

        /**
         * Chunk sizes on the streaming path: the whole path's merge floor, but
         * sentences stay whole up to the 500-token cap (1.35 × 360 = 486).
         * Longer sentences are cut at clause marks into ~360-token pieces
         * ([TextChunker.planByTokens]). Streaming makes the first sound
         * independent of the sentence's length except for its plan, so only
         * a long first sentence is cut short ([kokoroStreamCutFirst]): into a
         * head of about 80 tokens (40–120; ~0.4 s of planning), then the rest
         * whole up to the target (growth 5).
         */
        internal val STREAM_TOKEN_BUDGET = TextChunker.TokenBudget(
            mergeFloor = 90,
            target = 360,
            firstPiece = 80,
            growth = 5.0,
            firstPieceMin = 40,
            firstPieceMax = 120,
        )

        /**
         * Chunk sizes in model tokens (see [TextChunker.planByTokens]).
         * Merge floor 90 ≈ the old 80-English-char floor (sherpa's
         * tiny-sentence merge); a merge stops at 200. Target 200: a sentence over 270
         * (1.35 × 200) is cut at clause marks — ~5 s cool / ~10 s hot per
         * piece on the Pixel 8a, where one 715-token Chinese sentence used
         * to take 31 s. A cut first sentence starts with a ~40-token piece
         * (≈ 1.4 s cool, 2.1 s hot) and grows 1.5× per piece — defaults
         * until a chunk has been measured; [kokoroStreamBudget] then adapts
         * both to the device's measured speed. A short first piece cut from
         * a normal-size sentence must hold 15–55 tokens (defaults of
         * [TextChunker.TokenBudget]).
         */
        private val TOKEN_BUDGET = TextChunker.TokenBudget(
            mergeFloor = 90,
            target = 200,
            firstPiece = 40,
            growth = 1.5,
        )
    }
}
