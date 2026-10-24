package app.marmalade.tts.engine.kitten

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineDevKnobs
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.OrtRunAbort
import app.marmalade.tts.engine.PlaybackHorizon
import app.marmalade.tts.engine.RenderMode
import app.marmalade.tts.engine.SentenceGaps
import app.marmalade.tts.engine.SliceCost
import app.marmalade.tts.engine.SliceEstimate
import app.marmalade.tts.engine.TimedPieceGate
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.abortableInference
import app.marmalade.tts.lang.LangDetector
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.EnPhonemeFixups
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// KittenDirectEngine — Kitten TTS without sherpa-onnx
// -----------------------------------------------------------------------------
//
// Runs the upstream KittenML acoustic model (15M parameters, fp32)
// directly on `com.microsoft.onnxruntime:onnxruntime-android`, paired with
// [EspeakPhonemizer] for text→IPA. espeak-ng (GPL-3.0-or-later) is compiled
// from source into the APK as libespeak-ng.so and dlopen'd by the MIT JNI
// shim — Play forbids downloading executable code, so the lib can't ride in
// the engine bundle the way it did through v14. The espeak-ng-data is the
// app-level shared full-language tree (SharedEspeakData). (An earlier design
// used a BSD-3 OpenPhonemizer ONNX to stay GPL-free; it was dropped because
// IPA-convention mismatches degraded quality.)
//
// Streaming (issue #10, from the #3/#9 prototype): the model is shipped cut
// in two (scripts/kitten-stream/split.py). Since #17 the planner's text model
// is the X2 student, a 4-layer encoder (3.4M weights) trained to give
// Kitten's duration and pitch predictors what its ALBERT did (5.8M, 12
// passes of one shared layer, issue #14); the rest is Kitten's own weights,
// nothing retrained. Each chunk is
// planned ONCE — the planner graph runs the text model, durations, pitch, the
// decoder's frame-rate front and the harmonic source with the sentence's one
// style row — and the generator graph (iSTFTNet, ~98% of the decoder's time)
// renders the planned frames in slices of 0.5 s, 1 s, then doubling to 4 s,
// each sent the moment it exists. The generator's InstanceNorms use fixed
// per-voice statistics (genstats/), which makes it purely convolutional, so
// slices rendered with 4 frames of context join exactly; the fixed statistics
// differ from the whole-sentence ones by about Kitten's own run-to-run
// variation (~0.5 dB mel). There is no non-streaming mode: one slice costs
// what the unsplit model did, each extra one ~120 ms on a Pixel 8a (#5).
//
// Bundle layout (`${filesDir}/engines/kitten-direct-v0_8/`):
//   planner.onnx, gen.onnx            — the two graphs (8.2M + 3.4M params)
//   genstats/<name>.bin               — float32 [2, 3456] generator norm table per voice
//   voices/<name>.bin                 — float32 [N, 256] style table per voice
//   phonemizer/                       — legacy (espeak data + ≤v14 .so);
//                                       ignored — the APK carries both the
//                                       lib and the shared data
// An older bundle (one kitten.onnx: the v22 download, seeds before 1.2) fails
// the layout check and is replaced from the APK's seed (EngineInstaller).
//
// Carries over the Pocket engine perf lessons (A-G atoms):
//   - direct ByteBuffers for ONNX inputs
//   - plain CPU EP for both graphs (XNNPACK cost the planner +280 MB, #13)
//   - per-device thread autodetect via CpuClusterDetector
//   - warmup synth after load
//   - manual thread override from Settings
// -----------------------------------------------------------------------------

private const val TAG = "KittenDirectEngine"

/** Logcat tag for the P-A streaming perf diagnostic — `adb logcat -s StreamPerf`. */
private const val PERF_TAG = "StreamPerf"

/** One generator frame's audio (25 ms at 24 kHz). */
private const val FRAME_MS = KittenTrim.FRAME_SAMPLES * 1000.0 / KittenDirectVoiceCatalog.SAMPLE_RATE

/** KittenML model's style-vector dimension; fixed by the trained model. */
private const val STYLE_DIM = 256

/**
 * Silence inserted between chunks, sized by boundary type (F rules,
 * Max's 2026-08-07 ear-lab). Trimmed chunk edges keep ~60 ms lead +
 * ~75 ms tail of rendered pause, so effective silence ≈ gap + 135 ms:
 *
 *  - clause boundary (`;` `:`, dialogue intro, newline after a comma):
 *    80 ms → ≈215 ms effective, between the model's own comma (~50 ms
 *    in-render) and period.
 *  - sentence boundary: 260 ms → ≈395 ms effective, matching the
 *    model's natural in-render period pause (390 ms measured). The old
 *    uniform 150 ms (≈285 ms effective) made chunked periods pause
 *    LESS than unchunked ones — heard as "barely any pause" on Psalm 23.
 */
private const val CLAUSE_GAP_MS = 80
private const val SENTENCE_GAP_MS = 260

/**
 * The silence after a chunk: its boundary's own gap, plus [pauseMs], the
 * alias's pause that follows it (#96; [SentenceGaps.Follower]).
 */
internal fun kittenGapMs(sentenceEnd: Boolean, pauseMs: Int): Int =
    (if (sentenceEnd) SENTENCE_GAP_MS else CLAUSE_GAP_MS) + pauseMs.coerceAtLeast(0)

/**
 * Per-voice speed multipliers. The voices differ in inherent pace, so a
 * single global prior can't work: at the old uniform 0.84, Bella read
 * as slow-motion while Kiki read as rushed.
 *
 * Values are Max's per-voice picks from the 2026-08-07 speed-sweep
 * ear-lab (all 8 voices × net speeds 0.72–1.00, one un-chunked render
 * each; Bella/Leo extrapolated above the sweep's 1.00 ceiling).
 * Upstream's only per-speaker data — `speed_priors` in KittenML's HF
 * config.json (0.8 everyone, 0.9 Hugo) — was checked 2026-08-07 and
 * offers nothing finer; these picks supersede it.
 *
 * The user's [speed] argument multiplies these — alias speed=1.2 on
 * Jasper becomes 1.2 × 0.84 = 1.008. The prior is the per-voice
 * baseline; the alias speed is the user's deliberate adjustment on top.
 */
private val SPEED_PRIORS = mapOf(
    "bella"  to 1.24f,  // expr-voice-2-f
    "jasper" to 0.84f,  // expr-voice-2-m
    "luna"   to 0.92f,  // expr-voice-3-f
    "bruno"  to 0.88f,  // expr-voice-3-m
    "rosie"  to 0.96f,  // expr-voice-4-f
    "hugo"   to 0.84f,  // expr-voice-4-m
    "kiki"   to 0.84f,  // expr-voice-5-f
    "leo"    to 1.25f,  // expr-voice-5-m
)

/**
 * The planner's text model has a 512-row position embedding (Kitten's
 * ALBERT did, and the X2 student that replaced it in #17 keeps the
 * limit) — at most 512 positions. With the
 * `[0, ...phonemes..., 10, 0]` wrapping that's 3 reserved positions,
 * leaving 509 for actual phonemes. We cap a few below that to be safe.
 */
private const val MAX_PHONEMES_PER_CHUNK = 500

// `open` solely so the JVM unit tests can subclass it with a fake that
// returns fixture PCM instead of standing up an ORT session — see
// FakeKittenDirectEngine in MarmaladeTtsServiceTest. Nothing in production
// extends it (the Kitten Mini subclass was retired in 1.0.0-beta.1).
@Singleton
open class KittenDirectEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val sharedEspeak: SharedEspeakData,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME
    override val sampleRate: Int = KittenDirectVoiceCatalog.SAMPLE_RATE

    /** Voices this engine serves. */
    private val voiceMetas: List<app.marmalade.tts.data.db.VoiceMeta> =
        KittenDirectVoiceCatalog.voices

    /**
     * The user's rate change is a time-stretch on the rendered audio, not
     * a model parameter — Kitten's `speed` tensor saturates hard
     * (measured 2026-09-12: requests of 2.5x and 3.0x render
     * BYTE-IDENTICAL audio at about 1.85x) and mangles articulation on
     * the way there. The services hand this engine speed = 1.0 and
     * prepend `EffectBlock.Tempo(userSpeed)` instead
     * (`applySpeedFallback`), which hits the requested factor exactly —
     * same policy the CLI adopted for every engine.
     *
     * This does NOT retire the per-voice priors in [SPEED_PRIORS]. Those
     * are not user speed: they are part of each voice's blessed sound,
     * Max's picks from the 2026-08-07 ear-lab, and the raw model runs
     * ~25% too fast without them. So [plan] keeps applying
     * `prior × incoming speed` to the tensor natively — with the
     * incoming speed now pinned at 1.0, that is just the prior. Only the
     * user-requested rate moved to the effect chain.
     */
    override val supportsNativeSpeed: Boolean = false

    /**
     * Soft cap for per-chunk char count. Under the F rules chunks are
     * clause fragments (never word-split), so this cap only matters for
     * a single over-long fragment, which is emitted whole; [ipaPieces]
     * re-splits one whose IPA would blow past the planner's 512-position
     * limit ([MAX_PHONEMES_PER_CHUNK]).
     *
     * The per-sentence style row (text-length lookup on the PRE-SPLIT
     * sentence, [TextChunker.ClauseChunk.rowText]) is deliberate: short
     * sentences get the model's brisk short-utterance register, long
     * ones the flowing long-form one, and clause fragments inherit
     * their sentence's register instead of re-registering mid-sentence.
     */
    override val maxInputChars: Int = 255

    private val engineDir: File get() = File(ctx.filesDir, "engines/$engineName")
    private val plannerFile: File get() = File(engineDir, PLANNER_FILE)
    private val genFile: File get() = File(engineDir, GEN_FILE)
    private val voicesDir: File get() = File(engineDir, VOICES_DIR)
    private val genStatsDir: File get() = File(engineDir, GENSTATS_DIR)

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    /** Shrink the CPU arena after each generator run ([OrtRunAbort]); debug A/B knob `shrink=false`. */
    @Volatile private var shrinkArena = true

    // -- live state (null while not loaded) -----------------------------------

    @Volatile private var env: OrtEnvironment? = null
    private var plannerSession: OrtSession? = null
    private var genSession: OrtSession? = null
    private var phonemizer: EspeakPhonemizer? = null

    /** Voice style tables (`float32[N, 256]`) cached per voice. */
    private val voiceTables = HashMap<String, FloatArray>()

    /** Generator norm tables (`float32[2, S]`) cached per voice. */
    private val normTables = HashMap<String, FloatArray>()

    /**
     * P-D: warmup runs async on this scope after [doLoad] returns, so the
     * engine reports "loaded" as soon as sessions are ready. The Speak
     * screen calls [TtsEngine.ensureModelLoaded] eagerly via
     * [app.marmalade.tts.audio.SpeechPlayer.preload]; pairing that with
     * async warmup lets the ~335 ms warmup happen during the gap
     * between screen-mount and Speak tap. Job is tracked so
     * [releaseInternal] can cancel a mid-flight warmup.
     */
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var warmupJob: kotlinx.coroutines.Job? = null

    /**
     * Chunk mode (#54, [RenderMode]): how much emitted audio is still ahead
     * of the listener and the generator's cost per slice.
     */
    private val horizon = PlaybackHorizon()
    private val sliceCost = SliceCost()

    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        if (!plannerFile.isFile || !genFile.isFile) return false
        for (voice in voiceMetas) {
            val name = "${voice.displayName.lowercase()}.bin"
            if (!File(voicesDir, name).isFile || !File(genStatsDir, name).isFile) return false
        }
        return true
    }

    /**
     * [env] is `doLoad`'s publish-last marker — see
     * [app.marmalade.tts.engine.kokoro.KokoroDirectEngine.isLoaded].
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
                throw IllegalStateException("KittenDirect load failed: ${t.message}", t)
            }
        }
    }

    private fun doLoad(intraOpThreads: Int) {
        // Build everything against a local [ort]; only publish to the
        // [env] field at the END, after both sessions and [phonemizer]
        // are both set. ensureLoadedSuspending uses an unlocked `if (env
        // != null) return` fast path — assigning env mid-load lets a
        // concurrent caller skip the lock and reach inference with
        // a session still null ("planner session missing").
        val ort = OrtEnvironment.getEnvironment()
        // ORT copies the options into the native session; close them after
        // so keepalive evict/reload cycles don't leak native memory.
        shrinkArena = EngineDevKnobs.get(ctx.filesDir, "shrink") != "false"
        plannerSession = buildSessionOptions(intraOpThreads).use { createSession(ort, it, plannerFile) }
        genSession = buildSessionOptions(intraOpThreads, generator = true).use { createSession(ort, it, genFile) }
        // split.py writes what the planner holds into its doc_string (#17).
        val textModel = if (runCatching { plannerSession?.metadata?.description }.getOrNull()
                ?.contains("X2 student") == true
        ) "X2 student" else "ALBERT"
        Log.i(
            TAG,
            "streaming render: planner (CPU EP, text model $textModel) + generator (CPU EP, spinning), threads $intraOpThreads, slices ${KittenStreamSlicing.FIRST_SLICE_FRAMES}, " +
                "${KittenStreamSlicing.SLICE_FRAMES}, doubling to ${KittenStreamSlicing.MAX_SLICE_FRAMES} frames, " +
                "context ${KittenStreamSlicing.CONTEXT_FRAMES}",
        )

        // libespeak-ng.so is compiled from source into the APK, and the data
        // is the app-level shared full-language tree — bundle-shipped espeak
        // data is ignored legacy. See phonemizer/SharedEspeakData.kt.
        val espeak = EspeakPhonemizer(
            libPath = EspeakPhonemizer.APK_LIB_NAME,
            dataPath = sharedEspeak.ensure().absolutePath,
            voice = "en-us",
            fixupModel = EnPhonemeFixups.Model.KITTEN,
        )
        val rate = espeak.open()
        if (rate < 0) {
            throw IllegalStateException("espeak failed to open (status=$rate)")
        }
        phonemizer = espeak
        Log.i(TAG, "espeak version=${espeak.version()}")

        // Publish only after every field a sibling caller's inference
        // touches is non-null.
        env = ort

        // P-D: warmup runs async so doLoad returns as soon as ORT + espeak
        // are usable. Warmup takes synthLock per ORT call, like any synth,
        // so a user synth landing during the gap interleaves with it. ensureModelLoaded() (and
        // by extension SpeechPlayer.preload) considers the engine "ready"
        // the moment doLoad returns — a Speak tap arriving before warmup
        // completes pays a brief synthLock wait instead of blocking the
        // entire load.
        warmupJob = warmupScope.launch { warmupSynth() }
    }

    /**
     * Both graphs run on ORT's plain CPU EP (issue #13 "A"; the planner had
     * XNNPACK until then). XNNPACK keeps its own packed copy of a weight for
     * every use of a shared layer, and the planner's text model was then
     * ALBERT, one shared layer at every depth: +280 MB of resident memory
     * against +35 MB on the CPU EP (#12 "X1", Pixel 8a), and not faster.
     *
     * The [generator] graph also spins its intra-op threads (issue #10,
     * Pixel 8a, Bella, B1 set): streamed RTF 0.49 → 0.43 (CPU EP only) →
     * 0.35–0.36 (plus spinning); median first sound 1013 → 900 → 831–890 ms.
     * The planner doesn't spin.
     *
     * Debug builds can put XNNPACK back on the planner for an A/B
     * (`kitten.plannerXnnpack=true`, [EngineDevKnobs]).
     */
    private fun buildSessionOptions(intraOpThreads: Int, generator: Boolean = false): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            if (generator) {
                addConfigEntry("session.intra_op.allow_spinning", "1")
                return@apply
            }
            addConfigEntry("session.intra_op.allow_spinning", "0")
            // x86_64 only exists in debug builds, for the emulator, whose
            // XNNPACK EP segfaults in createSession: never there.
            if (EngineDevKnobs.get(ctx.filesDir, "kitten.plannerXnnpack") == "true" &&
                Build.SUPPORTED_ABIS.firstOrNull() != "x86_64"
            ) {
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                    Log.i(TAG, "planner: XNNPACK EP (dev knob)")
                } catch (t: Throwable) {
                    Log.w(TAG, "planner: XNNPACK EP unavailable; CPU EP only", t)
                }
            }
        }
    }

    /**
     * Touch both sessions with a 1-word synth so ORT's first-call kernel
     * compilation costs are folded into engine load. Mirrors PocketEngine's
     * warmup strategy.
     */
    private suspend fun warmupSynth() {
        val t0 = System.currentTimeMillis()
        try {
            val firstVoice = voiceMetas.first().displayName
            val pieces = synthLock.withLock { ipaPieces("Hi.", KITTEN_DEFAULT_ESPEAK_VOICE) }
            for (piece in pieces) {
                renderIpa(piece.text, piece.ipa, firstVoice, speed = 1.0f, rowText = piece.text, warmup = true) { _, _, _, _ -> }
            }
            Log.i(TAG, "warmup synth done in ${System.currentTimeMillis() - t0} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "warmup failed (non-fatal): ${t.message}")
        }
    }

    // -- inference ------------------------------------------------------------

    /**
     * Synthesize the full input as one merged PCM buffer. Delegates to
     * [synthesizeStream] + concat so the chunking logic lives in one
     * place — every caller (TTS services, benchmark batched mode) gets
     * sentence chunking automatically without having to know the planner's
     * 512-position limit.
     */
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

    /**
     * Clause-chunked, slice-streamed synthesis. Splits the input via
     * [TextChunker.clauseChunks], plans one chunk at a time and emits its
     * audio slice by slice (0.5 s, 1 s, 2 s, then 4 s — see [renderIpa]),
     * so the first sound waits for one planner call and one 0.5 s slice,
     * not the whole first chunk. Every ORT call runs inside [synthLock]
     * (one session pair, non-reentrant).
     *
     * Chunks are never word-split, so a run-on sentence can phonemize
     * past the planner's 512-position limit; [ipaPieces] then re-splits it
     * into pieces under [MAX_PHONEMES_PER_CHUNK] rather than truncating.
     */
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        // No pre-roll: streamed Kitten renders at RTF ~0.13 on a 6a, so
        // playback rarely outruns it. A phone where it does plays whole
        // pieces (chunk mode, #54).
        playbackRate: Float,
        expressiveness: Float?,
        sentencePauseMs: Int,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic: split "load wait" from the first render.
        // If loadMs > 0 on the first run after a cold start, the user is paying
        // load + warmup as part of TTFA — async warmup (P-D) might shave it
        // off if we also wire eager pre-load somewhere.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        // `phonemizationLanguage` is honoured, English by default: the
        // model reads en-us IPA, but a detected non-English utterance
        // with no installed Kokoro voice to hand it to is better served
        // by accented Kitten than by English letter rules (Max,
        // 2026-08-08). See [espeakVoiceFor].
        //
        // setVoice is only a warm-up (pays the ~50 ms language load before
        // chunk 1); per-chunk phonemize(text, lang) re-asserts the voice
        // atomically, since espeak's active voice is process-global and a
        // concurrent synth on the other engine can flip it between chunks.
        val espeakVoice = espeakVoiceFor(phonemizationLanguage)
        phonemizer?.setVoice(espeakVoice)
        val voiceName = voiceId.substringAfter(':', voiceId)
        // KittenDirect chunking = the F rules (Max's 2026-08-07 ear-lab
        // pick; see TextChunker.clauseChunks): quote-aware sentence ends,
        // newline = sentence boundary, clause cuts at `;` `:` + dialogue
        // intro, no merging, never word-split. Fragments keep their
        // PRE-SPLIT sentence's style row; the gap after each chunk is
        // sized by boundary type. The clause cuts are prosody, not latency
        // (the model gives a mid-render `;` `:` only ~50 ms of pause), so
        // they stayed when streaming made the first chunk's length cheap.
        val chunks = TextChunker.clauseChunks(text)
        if (chunks.isEmpty()) return@channelFlow
        // The alias's pause between sentences (#96), read off the text after each chunk.
        val pauses = SentenceGaps.Follower(text, sentencePauseMs)

        val pace = RenderMode.paceFor(engineName, settings, ctx.filesDir, playbackRate)
        horizon.startRequest(pace.rate())
        val gate = TimedPieceGate<SynthAudio>(
            scope = this,
            send = { out ->
                for (audio in out) {
                    send(audio)
                    horizon.onEmitted((audio.pcm.size * 1000L / sampleRate / pace.rate()).toLong())
                }
            },
            rate = pace::rate,
            onRelease = { heldMs, slices, rest ->
                Log.d(PERF_TAG, "kitten chunk-mode release held=${heldMs}ms slices=$slices rest=$rest")
            },
        )
        var firstSent = false
        try {
            for ((idx, chunk) in chunks.withIndex()) {
                // A cancelled stream (reader tap, Stop, skip) must stop HERE:
                // inference blocks and the uncontended lock/buffered send never
                // suspend, so without this check a cancelled producer rendered
                // every remaining chunk while holding the service's synth mutex —
                // the next request's TTFA paid for all of them (~11 s on the 8a).
                ensureActive()
                val pieces = synthLock.withLock { ipaPieces(chunk.text, espeakVoice) }
                val pauseMs = pauses.pauseAfter(chunk.text)
                // An over-cap chunk's pieces go out one at a time, each as soon
                // as it is rendered, instead of all joined after the last one.
                for ((pi, piece) in pieces.withIndex()) {
                    // Per-piece check: a stop lands between pieces rather than
                    // after the whole chunk.
                    ensureActive()
                    // Boundary-typed gap rides on the chunk's last slice (see
                    // CLAUSE_GAP_MS/SENTENCE_GAP_MS). Not after the last chunk.
                    // The alias's pause (#96) adds to it; Kitten already renders a
                    // chunk per clause, so nothing else about the audio changes.
                    val lastSlice = idx < chunks.size - 1 && pi == pieces.size - 1
                    val gapSamples = if (lastSlice) sampleRate * kittenGapMs(chunk.sentenceEnd, pauseMs) / 1000 else 0
                    val pauseSamples = if (lastSlice) SentenceGaps.samples(pauseMs, sampleRate) else 0
                    // Chunk mode (#54), decided per piece at the speed it will be heard at.
                    val mode = pace.pieceMode()
                    horizon.follow(mode.playbackRate)
                    gate.startPiece(mode.chunk)
                    // Every piece keeps the sentence's [rowText] register.
                    renderIpa(piece.text, piece.ipa, voiceName, speed, rowText = chunk.rowText, chunkMode = mode.chunk) { pcm, first, rest, extraMs ->
                        if (!firstSent) {
                            firstSent = true
                            val ttfaMs = (System.nanoTime() - streamStartNs) / 1_000_000
                            Log.d(PERF_TAG, "kitten TTFA=${ttfaMs}ms (loadWait=${loadWaitMs}ms)")
                        }
                        val out = if (rest.isEmpty() && gapSamples > 0) pcm.copyOf(pcm.size + gapSamples) else pcm
                        // A piece starts at a clause (or, over the cap, a word):
                        // the Narrator may resume there, never mid-piece.
                        val audio = SynthAudio(
                            pcm = out, sampleRate = sampleRate, resumePoint = first, extraRenderMs = extraMs,
                            addedSilence = if (rest.isEmpty()) pauseSamples else 0,
                        )
                        gate.offer(audio, out.size * 1000.0 / sampleRate, aheadMs = { horizon.requestRemainingMs().toDouble() }) {
                            rest.map { SliceEstimate(sliceCost.cautiousMs(it.render.count()), it.keep.count() * FRAME_MS) }
                        }
                    }
                    gate.endPiece()
                }
            }
        } catch (t: Throwable) {
            // Cancelled or failed: what this stream fed has been cut off.
            horizon.clear()
            throw t
        }
    }.flowOn(Dispatchers.Default)

    /** One planner call's worth of text: [text] and its ≤ [MAX_PHONEMES_PER_CHUNK] IPA. */
    private class IpaPiece(val text: String, val ipa: String)

    /**
     * [text] phonemized — one piece, or several when its IPA would pass the
     * text model's position-embedding cap (a tail past it trips an
     * ORT_INVALID_ARGUMENT on `x2//pos/Gather`; `/bert/Expand` before #17).
     * The chunker never word-splits
     * a sentence, and Kitten's IPA expansion varies per phrase, so a run-on
     * can still overflow. Truncating used to drop the tail silently;
     * re-split at clause marks → whitespace → hard cut instead. The stream
     * loop renders and emits the pieces one at a time.
     */
    private fun ipaPieces(text: String, espeakVoice: String): List<IpaPiece> {
        val phon = phonemizer ?: error("phonemizer missing")
        val rawIpa = phon.phonemize(text, espeakVoice)
        if (rawIpa.length <= MAX_PHONEMES_PER_CHUNK) return listOf(IpaPiece(text, rawIpa))
        val pieces = TextChunker.splitToFit(text) {
            phon.phonemize(it, espeakVoice).length <= MAX_PHONEMES_PER_CHUNK
        }
        Log.i(TAG, "phoneme count ${rawIpa.length} exceeds $MAX_PHONEMES_PER_CHUNK — re-split into ${pieces.size} pieces")
        // splitToFit leaves a piece oversize only if it's one character.
        return pieces.map { IpaPiece(it, phon.phonemize(it, espeakVoice).take(MAX_PHONEMES_PER_CHUNK)) }
    }

    /**
     * One planned piece: everything the generator needs, copied out of the
     * planner's results so the session is free between slices. [x] is the
     * decoder front's `[channels, 2 × totalFrames]` output, row-major; [har]
     * the harmonic source, `totalFrames × FRAME_SAMPLES` samples; [gn] the
     * voice's generator norm table; [speech] the frames kept after the
     * lead/tail trim.
     */
    private class Plan(
        val x: FloatArray,
        val channels: Int,
        val totalFrames: Int,
        val s128: FloatArray,
        val har: FloatArray,
        val gn: FloatArray,
        val speech: IntRange,
    )

    /**
     * Render [ipa] (≤ [MAX_PHONEMES_PER_CHUNK] characters of [text]): plan it
     * once, then generate its speech frames slice by slice, handing each
     * slice's PCM to [emit] (flagged first / last) as soon as it exists.
     * [synthLock] is held per ORT call only, never across [emit]. Both kinds
     * of run are abortable: a cancelled stream (reader tap, Stop) stops inside
     * the planner or slice in flight instead of finishing it. In [chunkMode]
     * the slices are shorter; the generator calls that adds are reported
     * with the first slice (`extraMs`, [SynthAudio.extraRenderMs]), so the
     * app's measured RTF doesn't depend on the mode (#54).
     */
    private suspend fun renderIpa(
        text: String,
        ipa: String,
        voiceName: String,
        speed: Float,
        rowText: String,
        warmup: Boolean = false,
        chunkMode: Boolean = false,
        emit: suspend (pcm: ShortArray, first: Boolean, rest: List<KittenStreamSlicing.Slice>, extraMs: Long) -> Unit,
    ) {
        if (ipa.isEmpty()) return
        val inputIds = wrapForKitten(encodePhonemes(ipa))
        val t0 = System.nanoTime()
        val plan = synthLock.withLock { plan(text, ipa, inputIds, voiceName, speed, rowText) }
        val planMs = (System.nanoTime() - t0) / 1_000_000
        val slices = KittenStreamSlicing.slices(
            plan.speech, plan.totalFrames,
            max = if (chunkMode) KittenStreamSlicing.CHUNK_MODE_MAX_SLICE_FRAMES else KittenStreamSlicing.MAX_SLICE_FRAMES,
        )
        val extraCalls = if (chunkMode) {
            slices.size - KittenStreamSlicing.slices(plan.speech, plan.totalFrames, max = KittenStreamSlicing.MAX_SLICE_FRAMES).size
        } else {
            0
        }
        var genMs = 0L
        for ((i, slice) in slices.withIndex()) {
            val tGen = System.nanoTime()
            val pcm = synthLock.withLock { generate(plan, slice) }
            val sliceMs = (System.nanoTime() - tGen) / 1_000_000
            genMs += sliceMs
            if (!warmup) sliceCost.add(slice.render.count(), sliceMs.toDouble())
            Log.v(PERF_TAG, "kitten slice=$i/${slices.size} frames=${slice.render.count()} gen=${sliceMs}ms")
            val extraMs = if (i == 0) (extraCalls.coerceAtLeast(0) * sliceCost.fixedMs()).toLong() else 0L
            emit(pcm, i == 0, slices.subList(i + 1, slices.size), extraMs)
        }
        val audioMs = plan.speech.count() * KittenTrim.FRAME_SAMPLES * 1000L / sampleRate
        val rtf = if (audioMs > 0) (planMs + genMs).toDouble() / audioMs else Double.NaN
        Log.d(PERF_TAG, "kitten piece textLen=${text.length} plan=${planMs}ms gen=${genMs}ms slices=${slices.size} audio=${audioMs}ms rtf=${"%.2f".format(rtf)}")
    }

    /** The planner run for one piece. Caller holds [synthLock]. */
    private suspend fun plan(
        text: String,
        ipa: String,
        inputIds: IntArray,
        voiceName: String,
        speed: Float,
        rowText: String,
    ): Plan {
        val ort = env ?: error("engine not loaded")
        val session = plannerSession ?: error("planner session missing")

        // Upstream KittenML indexes voice style by the ORIGINAL TEXT
        // length, not the phoneme-string length
        // (onnx_model.py:108 `ref_id = min(len(text), voices[voice].shape[0] - 1)`).
        // Text and phoneme length diverge non-trivially for tech jargon
        // ("kubectl" = 7 chars but ~10 IPA chars), so this index choice
        // affects which prosody seed Kitten uses. [rowText] is the
        // PRE-SPLIT sentence when [text] is a clause fragment — the
        // fragment must keep its sentence's register (F rules).
        val style = lookupVoiceStyle(voiceName, rowText.length)
        val gn = normTable(voiceName)

        // The per-voice prior stays native. The raw model runs ~25% too
        // fast at speed=1.0 — without the prior we sound rushed and words
        // mash together because the inter-word space tokens get squeezed —
        // and the prior is part of the voice's blessed sound, not a rate
        // the user chose. [speed] is 1.0 on every service-driven call now
        // (see [supportsNativeSpeed]); the user's rate is a Tempo block on
        // the output. Direct callers keep the multiply.
        val voicePrior = speedPriorFor(voiceName)
        val effectiveSpeed = effectiveModelSpeed(voiceName, speed)

        Log.d(TAG, "input='$text'")
        Log.d(TAG, "ipa='$ipa' (textLen=${text.length} ipaLen=${ipa.length})")
        Log.d(TAG, "speed=$speed * prior=$voicePrior = $effectiveSpeed")
        Log.d(TAG, "tokens=${inputIds.toList()}")

        val inputIdTensor = directLongTensor(ort, longArrayOf(1, inputIds.size.toLong())) { buf ->
            for (id in inputIds) buf.put(id.toLong())
        }
        val styleTensor = directFloatTensor(ort, longArrayOf(1, STYLE_DIM.toLong())) { buf ->
            buf.put(style)
        }
        val speedTensor = directFloatTensor(ort, longArrayOf(1)) { buf ->
            buf.put(effectiveSpeed)
        }
        try {
            return abortableInference(engineName, ::OrtRunAbort) { abort ->
                session.run(
                    mapOf(
                        "input_ids" to inputIdTensor,
                        "style"     to styleTensor,
                        "speed"     to speedTensor,
                    ),
                    abort.options,
                ).use { results ->
                    val xTensor = results.get(GEN_X).get() as OnnxTensor
                    val xLen = xTensor.info.shape[2].toInt()
                    val x = floatsOf(xTensor)
                    val har = floatsOf(results.get(GEN_HAR).get() as OnnxTensor)
                    val totalFrames = xLen / 2
                    check(har.size == totalFrames * KittenTrim.FRAME_SAMPLES) {
                        "planner outputs disagree: x=$xLen values, har=${har.size} samples"
                    }
                    val dur = extractDurations(results.get(DURATION).get().value)
                    Plan(
                        x = x,
                        channels = x.size / xLen,
                        totalFrames = totalFrames,
                        s128 = floatsOf(results.get(GEN_S).get() as OnnxTensor),
                        har = har,
                        gn = gn,
                        speech = speechFrames(inputIds, dur, totalFrames),
                    )
                }
            }
        } finally {
            inputIdTensor.close()
            styleTensor.close()
            speedTensor.close()
        }
    }

    /**
     * The frames to keep: duration-exact lead/tail trim (CLI `_trim_run`
     * port, see [KittenTrim]) — the BOS lead pad and the trailing pause
     * group are never rendered. When the durations don't add up to the
     * planned frames (never seen since issue #8's rounding fix) everything is
     * kept rather than cutting speech.
     */
    private fun speechFrames(ids: IntArray, dur: LongArray?, totalFrames: Int): IntRange {
        if (dur != null && dur.sum() == totalFrames.toLong()) {
            KittenStreamSlicing.speechFrames(ids, dur)?.let { return it }
        }
        Log.w(
            TAG,
            "duration contract broken: frames=$totalFrames vs Σdur=${dur?.sum()} " +
                "(ids=${ids.size} durs=${dur?.size}) — rendering untrimmed",
        )
        return 0 until totalFrames
    }

    /** One generator slice of [plan]: its kept PCM. Caller holds [synthLock]. */
    private suspend fun generate(plan: Plan, slice: KittenStreamSlicing.Slice): ShortArray {
        val ort = env ?: error("engine not loaded")
        val session = genSession ?: error("generator session missing")
        val first = slice.render.first
        val frames = slice.render.count()
        val xLen = 2 * plan.totalFrames
        val xTensor = directFloatTensor(ort, longArrayOf(1, plan.channels.toLong(), 2L * frames)) { buf ->
            for (c in 0 until plan.channels) buf.put(plan.x, c * xLen + 2 * first, 2 * frames)
        }
        val harTensor = directFloatTensor(ort, longArrayOf(1, frames.toLong() * KittenTrim.FRAME_SAMPLES, 1)) { buf ->
            buf.put(plan.har, first * KittenTrim.FRAME_SAMPLES, frames * KittenTrim.FRAME_SAMPLES)
        }
        val sTensor = directFloatTensor(ort, longArrayOf(1, plan.s128.size.toLong())) { it.put(plan.s128) }
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
                    floatToPcm16(wav, slice.keepOffsetSamples, slice.keepSamples)
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
     * Kitten expects `[0, ...phoneme_tokens..., 10, 0]` — the `10`
     * (ellipsis) before the final pad is the model's end-of-phoneme
     * marker. Kokoro uses the same vocab but skips the `10` (its
     * wrapping is just `[0, ...ids..., 0]`).
     */
    private fun wrapForKitten(ids: IntArray): IntArray {
        val out = IntArray(ids.size + 3)
        out[0] = PAD_TOKEN
        ids.copyInto(out, destinationOffset = 1)
        out[ids.size + 1] = KITTEN_END_TOKEN
        out[ids.size + 2] = PAD_TOKEN
        return out
    }

    /**
     * Voice tables are laid out as `[N, 256]` rows. Upstream KittenML
     * indexes by the original TEXT length (not phoneme length) per
     * `onnx_model.py:108`. Different rows seed the model with slightly
     * different prosody for short vs long utterances; matching upstream's
     * indexing keeps the prosody envelope consistent with what the
     * weights were trained against.
     */
    private fun lookupVoiceStyle(voiceName: String, refIndex: Int): FloatArray {
        val table = voiceTables.getOrPut(voiceName) { loadVoiceTable(voiceName) }
        if (table.isEmpty()) return FloatArray(STYLE_DIM)
        val rowCount = table.size / STYLE_DIM
        val row = refIndex.coerceIn(0, rowCount - 1)
        val start = row * STYLE_DIM
        return table.copyOfRange(start, start + STYLE_DIM)
    }

    private fun loadVoiceTable(voiceName: String): FloatArray {
        val file = File(voicesDir, "${voiceName.lowercase()}.bin")
        if (!file.isFile) {
            Log.w(TAG, "voice file missing for $voiceName")
            return FloatArray(0)
        }
        return readFloats(file)
    }

    /** float32 `[2, S]`: per-channel multiply (row 0) and add (row 1) for the generator's norms. */
    private fun normTable(voiceName: String): FloatArray =
        normTables.getOrPut(voiceName) { readFloats(File(genStatsDir, "${voiceName.lowercase()}.bin")) }

    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun floatsOf(tensor: OnnxTensor): FloatArray =
        tensor.floatBuffer.let { buf -> FloatArray(buf.remaining()).also { buf.get(it) } }

    /** Cast ORT's polymorphic output into a flat FloatArray. */
    private fun extractWaveform(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            @Suppress("UNCHECKED_CAST")
            (value as Array<FloatArray>).firstOrNull() ?: FloatArray(0)
        }
        else -> FloatArray(0)
    }

    private fun floatToPcm16(samples: FloatArray, offset: Int, count: Int): ShortArray {
        val out = ShortArray(count)
        for (i in 0 until count) {
            val clamped = samples[offset + i].coerceIn(-1.0f, 1.0f)
            out[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return out
    }

    // -- ORT helpers ----------------------------------------------------------

    /**
     * On arm64 we load model files by path (ORT mmaps them — cheap and
     * shared across processes). On 32-bit ARM we read the bytes into a
     * heap ByteArray first because misaligned int8 weight offsets in
     * the mmap'd file trip SIGBUS on ARMv7. Same workaround PocketEngine
     * documents in its createSession comment block.
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
        try { plannerSession?.close() } catch (_: Throwable) {}
        try { genSession?.close() } catch (_: Throwable) {}
        try { phonemizer?.close() } catch (_: Throwable) {}
        plannerSession = null
        genSession = null
        phonemizer = null
        voiceTables.clear()
        normTables.clear()
        env = null  // process-scoped; do not close
    }

    companion object {
        const val ENGINE_NAME = "kitten-direct-v0_8"
        private const val PLANNER_FILE = "planner.onnx"
        private const val GEN_FILE = "gen.onnx"
        private const val VOICES_DIR = "voices"
        private const val GENSTATS_DIR = "genstats"

        // Graph-boundary tensors (scripts/kitten-stream/split.py): planner
        // outputs, generator inputs. GEN_NORMS is the generator's norm table.
        private const val GEN_X = "/decoder/decode.3/Mul_output_0"
        private const val GEN_S = "/Unsqueeze_12_output_0"
        private const val GEN_HAR = "/decoder/generator/m_source/l_tanh/Tanh_output_0"
        private const val GEN_NORMS = "gn"
        private const val DURATION = "duration"

        /**
         * Per-token durations from the model's second output, as frame
         * counts. The export's dtype has been seen as both int64 and
         * float32 across kitten bundles, so accept either; null
         * (→ amplitude-trim fallback) on anything else.
         *
         * Float durations are ROUNDED, not truncated. The vocoder renders
         * `600 × round(dur)` samples per token, so truncation broke the
         * `wav.size == 600 × Σdur` contract [KittenTrim.trim] checks and
         * dropped the whole render onto the fallback trim (issue #8).
         *
         * In the companion (and `internal`) so the dtype handling is
         * testable without standing up an ORT session.
         */
        /**
         * Per-voice speed prior — see [SPEED_PRIORS] for provenance.
         * Unknown voices fall back to upstream's uniform 0.8.
         */
        internal fun speedPriorFor(voiceName: String): Float =
            SPEED_PRIORS[voiceName.lowercase()] ?: 0.8f

        /**
         * What actually goes into the model's `speed` tensor: the voice's
         * prior times whatever the caller asked for.
         *
         * Since 2026-09-12 the services always ask for 1.0 (the user's
         * rate is a time-stretch — see [supportsNativeSpeed]), so in
         * practice this returns the bare prior. It stays a multiply so a
         * direct caller can still nudge the model, and so the two
         * concepts stay visibly separate: prior = the voice's blessed
         * pace, argument = a deliberate model-level adjustment.
         *
         * In the companion (and `internal`) so it's testable without an
         * ORT session — same reason as [extractDurations].
         */
        internal fun effectiveModelSpeed(voiceName: String, speed: Float): Float =
            speed * speedPriorFor(voiceName)

        internal fun extractDurations(value: Any?): LongArray? {
            val flat: Any? = if (value is Array<*>) value.firstOrNull() else value
            return when (flat) {
                is LongArray -> flat
                is FloatArray -> LongArray(flat.size) { flat[it].roundToLong() }
                is IntArray -> LongArray(flat.size) { flat[it].toLong() }
                else -> null
            }
        }

        /**
         * The espeak voice this engine phonemizes with when the caller
         * names none — at load time for the `EspeakPhonemizer`
         * constructor and for every phonemize call that arrives without a
         * `phonemizationLanguage`.
         */
        private const val KITTEN_DEFAULT_ESPEAK_VOICE = KittenDirectVoiceCatalog.ESPEAK_VOICE

        /**
         * Caller's `phonemizationLanguage` → the espeak voice to use.
         *
         * Kitten's model is trained on en-us IPA, so a non-English
         * language gives accented, imperfect speech — Max's 2026-08-08
         * call is that this beats the alternative, which was reading
         * Spanish through English letter rules. Reached when an alias
         * asks for auto-detect and no installed Kokoro voice covers the
         * detected language; the full espeak data tree ships in the APK,
         * so every language we detect has its dictionary.
         *
         * A leftover [LangDetector.AUTO] sentinel means a resolution step
         * was skipped upstream, never "phonemize as auto" — it degrades
         * to English rather than reaching espeak_SetVoiceByName.
         */
        internal fun espeakVoiceFor(phonemizationLanguage: String?): String =
            phonemizationLanguage?.takeIf { it.isNotBlank() && it != LangDetector.AUTO }
                ?: KITTEN_DEFAULT_ESPEAK_VOICE
    }
}
