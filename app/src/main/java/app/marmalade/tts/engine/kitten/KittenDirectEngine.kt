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
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
// [EspeakPhonemizer] for text→IPA. Unlike the sherpa-onnx Kitten engines
// (which statically link espeak inside libsherpa-onnx-jni.so), here espeak
// ships as a downloaded `libttsespeak.so` that the MIT JNI shim dlopen's at
// runtime — the APK code stays MIT, but the assembled engine bundle is
// GPL-3.0-or-later because of espeak-ng. (An earlier design used a BSD-3
// OpenPhonemizer ONNX to stay GPL-free; it was dropped because IPA-
// convention mismatches degraded quality, so espeak-in-bundle won.)
//
// Bundle layout (`${filesDir}/engines/kitten-direct-v0_8/`):
//   kitten.onnx                       — acoustic model (15M params)
//   voices/<name>.bin                 — float32 [N, 256] style table per voice
//   phonemizer/<abi>/libttsespeak.so  — espeak-ng shared lib (GPL-3.0)
//   phonemizer/espeak-ng-data         — espeak dictionaries
//
// Carries over the Pocket engine perf lessons (A-G atoms):
//   - direct ByteBuffers for ONNX inputs
//   - XNNPACK execution provider, intra-op spinning disabled
//   - per-device thread autodetect via CpuClusterDetector
//   - warmup synth after load
//   - manual thread override from Settings
//
// What's still TODO (split into later atoms so each is verifiable on its
// own — see [[one-change-then-verify]]):
//   - Sentence-boundary chunking + per-chunk streaming (#63 / atom Q)
//   - Bundle creation + EngineCatalog entry           (#62 / atom R)
//   - Device validation against sherpa-Kitten         (#64 / atom S)
// -----------------------------------------------------------------------------

private const val TAG = "KittenDirectEngine"

/** Logcat tag for the P-A streaming perf diagnostic — `adb logcat -s StreamPerf`. */
private const val PERF_TAG = "StreamPerf"

/** KittenML model's style-vector dimension; fixed by the trained model. */
private const val STYLE_DIM = 256

/**
 * Last N samples of every Kitten synth are decoder ring-out — drop them.
 * Upstream KittenML and Maise both hardcode 5000; we match.
 */
private const val TRIM_SAMPLES = 5000

/**
 * Per-voice speed multipliers derived from Sherpa-onnx's
 * `speaker_speed_priors` ONNX metadata, lightly tuned from device A/B.
 * The raw KittenML model produces audio that's ~20% too fast at
 * speed=1.0; without applying these priors the output sounds rushed
 * and words run together.
 *
 * Sherpa's priors are 0.8 for everyone except Hugo at 0.9. Local
 * listening tests found 0.8 a touch too slow, so the 0.8 voices land
 * at 0.84 here. Hugo stays at 0.9.
 *
 * The user's [speed] argument multiplies these — alias speed=1.2 on
 * Bella becomes 1.2 × 0.84 = 1.008. The prior is the per-voice
 * baseline; the alias speed is the user's deliberate adjustment on top.
 *
 * Source: `speaker_speed_priors` metadata field in sherpa's repackaged
 * `kitten-nano-en-v0_8-fp32` ONNX, extracted via
 * `onnx.load(...).metadata_props` and tuned per device A/B.
 */
private val SPEED_PRIORS = mapOf(
    "bella"  to 0.84f,  // expr-voice-2-f
    "jasper" to 0.84f,  // expr-voice-2-m
    "luna"   to 0.84f,  // expr-voice-3-f
    "bruno"  to 0.84f,  // expr-voice-3-m
    "rosie"  to 0.84f,  // expr-voice-4-f
    "hugo"   to 0.9f,   // expr-voice-4-m — sherpa outlier, kept as-is
    "kiki"   to 0.84f,  // expr-voice-5-f
    "leo"    to 0.84f,  // expr-voice-5-m
)

/**
 * Kitten's internal BERT submodule has `position_embeddings.weight`
 * shape `[512, 128]` — at most 512 positions. With the
 * `[0, ...phonemes..., 10, 0]` wrapping that's 3 reserved positions,
 * leaving 509 for actual phonemes. We cap a few below that to be safe.
 */
private const val MAX_PHONEMES_PER_CHUNK = 500

@Singleton
open class KittenDirectEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME
    override val sampleRate: Int = KittenDirectVoiceCatalog.SAMPLE_RATE

    /**
     * Voices this engine serves. Open so the Mini variant
     * ([KittenDirectMiniEngine]) can substitute its own catalog — the names
     * and ordering match nano, but it's a distinct engine string.
     */
    protected open val voiceMetas: List<app.marmalade.tts.data.db.VoiceMeta> =
        KittenDirectVoiceCatalog.voices

    /**
     * Per-voice speed prior. Nano runs ~25% fast at speed=1.0 so it needs
     * 0.84 (0.9 for Hugo); Mini's sherpa metadata reports all-1.0 priors
     * (correctly paced), so [KittenDirectMiniEngine] overrides this to 1.0.
     */
    protected open fun speedPriorFor(voiceName: String): Float =
        SPEED_PRIORS[voiceName.lowercase()] ?: 0.8f

    /**
     * Soft cap for per-chunk char count. KittenDirect chunks at sentence
     * boundaries (`.?!:` + newlines only — not commas or em-dashes)
     * and never word-splits, so this is a target for the bin-packer
     * rather than a hard limit. An over-long sentence with no internal
     * sentence-end will exceed this and be emitted whole; the
     * [MAX_PHONEMES_PER_CHUNK] guard in [runInference] catches the
     * pathological case where the IPA would blow past Kitten's BERT
     * 512-position limit.
     *
     * 255 chars empirically lands ~2-4 sentences per chunk for normal
     * prose. Longer chunks improve prosody pacing (voice style index
     * is `min(len(text), 399)` per upstream — shorter chunks index a
     * lower row which seeds the model with "short-utterance prosody"
     * and produces faster-sounding speech).
     */
    override val maxInputChars: Int = 255

    private val engineDir: File get() = File(ctx.filesDir, "engines/$engineName")
    private val acousticModelFile: File get() = File(engineDir, MODEL_FILE)
    private val phonemizerDir: File get() = File(engineDir, PHONEMIZER_DIR)
    private val voicesDir: File get() = File(engineDir, VOICES_DIR)

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    // -- live state (null while not loaded) -----------------------------------

    private var env: OrtEnvironment? = null
    private var acousticSession: OrtSession? = null
    private var phonemizer: EspeakPhonemizer? = null

    /** Voice style tables (`float32[N, 256]`) cached per voice. */
    private val voiceTables = HashMap<String, FloatArray>()

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

    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        if (!acousticModelFile.isFile) return false
        if (!espeakLibFile().isFile) return false
        if (!File(phonemizerDir, ESPEAK_DATA_DIR).isDirectory) return false
        if (!voicesDir.isDirectory) return false
        for (voice in voiceMetas) {
            if (!File(voicesDir, "${voice.displayName.lowercase()}.bin").isFile) return false
        }
        return true
    }

    /**
     * Bundle ships `libttsespeak.so` under one of two ABI subdirs.
     * Picks the one matching this device's primary ABI; falls through
     * to arm64 as the modern default if Build.SUPPORTED_ABIS is empty
     * (impossible on real devices but harmless to guard).
     */
    private fun espeakLibFile(): File {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val abiDir = if (primary == "armeabi-v7a" || primary == "armeabi") "armeabi-v7a" else "arm64-v8a"
        return File(phonemizerDir, "$abiDir/libttsespeak.so")
    }

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
            val threadCount = manualThreads ?: CpuClusterDetector.detectPerfCoreCount()
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
        val ort = OrtEnvironment.getEnvironment().also { env = it }
        val opts = buildSessionOptions(intraOpThreads)

        acousticSession = createSession(ort, opts, acousticModelFile)

        // Espeak ships in the engine bundle (GPL-3.0). Our JNI shim
        // dlopens it from the per-ABI bundle path; espeak code never
        // enters the APK at build time. See phonemizer/EspeakPhonemizer.kt
        // and cpp/espeak_jni.c for the GPL-isolation architecture.
        val espeak = EspeakPhonemizer(
            libPath = espeakLibFile().absolutePath,
            dataPath = File(phonemizerDir, ESPEAK_DATA_DIR).absolutePath,
            voice = "en-us",
        )
        val rate = espeak.open()
        if (rate < 0) {
            throw IllegalStateException("espeak failed to open (status=$rate)")
        }
        phonemizer = espeak
        Log.i(TAG, "espeak version=${espeak.version()}")

        // P-D: warmup runs async so doLoad returns as soon as ORT + espeak
        // are usable. Warmup acquires synthLock to serialise against any
        // user synth that lands during the gap. ensureModelLoaded() (and
        // by extension SpeechPlayer.preload) considers the engine "ready"
        // the moment doLoad returns — a Speak tap arriving before warmup
        // completes pays a brief synthLock wait instead of blocking the
        // entire load.
        warmupJob = warmupScope.launch {
            synthLock.withLock { warmupSynth() }
        }
    }

    /**
     * Same options stack as [app.marmalade.tts.engine.PocketEngine.doLoad],
     * factored here to make any future tuning land in both engines via a
     * single edit. See PocketEngine's load() for the calibration history.
     */
    private fun buildSessionOptions(intraOpThreads: Int): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            try {
                addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
                addConfigEntry("session.intra_op.allow_spinning", "0")
                Log.i(TAG, "XNNPACK EP enabled (intraOpThreads=$intraOpThreads)")
            } catch (t: Throwable) {
                Log.w(TAG, "XNNPACK EP unavailable; CPU EP only", t)
            }
        }
    }

    /**
     * Touch each session with a 1-character synth so ORT's first-call
     * kernel compilation costs are folded into engine load. Mirrors
     * PocketEngine's warmup strategy.
     */
    private fun warmupSynth() {
        val t0 = System.currentTimeMillis()
        try {
            val firstVoice = voiceMetas.first().displayName
            runInference(text = "Hi.", voiceName = firstVoice, speed = 1.0f)
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
     * sentence chunking automatically without having to know KittenTTS's
     * 512-position BERT limit.
     */
    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        val parts = ArrayList<ShortArray>()
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect { parts.add(it.pcm) }
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
     * Sentence-chunked streaming. Splits the input via [TextChunker] at
     * [maxInputChars], synthesises one chunk at a time inside
     * [synthLock] (so multiple concurrent callers don't trample each
     * other's ORT session), and emits each chunk's PCM the moment it's
     * ready. This is what gives KittenDirect a usable TTFA on long
     * inputs — without it, a paragraph would block on the full synth
     * before any audio plays.
     *
     * The chunker's `maxChars` cap is conservative relative to the
     * 512-position BERT limit; a per-chunk runtime guard inside
     * [runInference] log-warns and truncates if a single sentence
     * still phonemizes past [MAX_PHONEMES_PER_CHUNK].
     */
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic: split "load wait" from "first chunk inference".
        // If loadMs > 0 on the first run after a cold start, the user is paying
        // load + warmup as part of TTFA — async warmup (P-D) might shave it
        // off if we also wire eager pre-load somewhere.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        // Kitten ships English-only — `phonemizationLanguage` here is
        // effectively a user override (e.g. `"en-gb"` for British accent).
        // Always call setVoice with an explicit effective value so a
        // null-after-non-null sequence doesn't leave espeak stuck on
        // the previous override (cached internally, so this is cheap
        // when the language hasn't changed).
        phonemizer?.setVoice(phonemizationLanguage ?: KITTEN_DEFAULT_ESPEAK_VOICE)
        val voiceName = voiceId.substringAfter(':', voiceId)
        // KittenDirect chunking rules (per device-testing feedback):
        //  - never split mid-word — even at maxChars boundary
        //  - split only on .?!: + newlines (commas and em-dashes do NOT
        //    end a chunk — they're internal prosody, not pauses big
        //    enough to be a chunk boundary)
        //  - pack short sentences together up to [maxInputChars] so
        //    voice-row indexing (text length) seeds the model with
        //    natural pacing instead of "short utterance, fast speech"
        val chunks = TextChunker.chunk(
            text = text,
            maxChars = maxInputChars,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = MIN_CHARS_PER_CHUNK,
        )
        if (chunks.isEmpty()) return@channelFlow

        // P-A diagnostic: track inter-chunk producer rhythm. The "gap" line
        // measures wall-clock between two consecutive chunks LEAVING the
        // producer — i.e. how long downstream had to wait between handoffs.
        // RTF (real-time factor) = inferMs / audioMs; <1 = producer faster
        // than playback (no audible gap possible from inference alone).
        var prevSendNs = 0L
        for ((idx, chunk) in chunks.withIndex()) {
            val inferStartNs = System.nanoTime()
            // Single ORT session is non-reentrant, so we serialise per chunk.
            // The send() outside the lock is fine because PCM is already a
            // ShortArray; no further session access happens during emit.
            val pcm = synthLock.withLock { runInference(chunk, voiceName, speed) }
            val inferMs = (System.nanoTime() - inferStartNs) / 1_000_000
            if (pcm.isNotEmpty()) {
                val audioMs = pcm.size * 1000L / sampleRate
                val gapMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
                val rtf = if (audioMs > 0) inferMs.toDouble() / audioMs else Double.NaN
                Log.d(PERF_TAG, "kitten chunk=$idx/${chunks.size} infer=${inferMs}ms audio=${audioMs}ms rtf=${"%.2f".format(rtf)} gap=${gapMs}ms textLen=${chunk.length}")
                if (idx == 0) {
                    val ttfaMs = (System.nanoTime() - streamStartNs) / 1_000_000
                    Log.d(PERF_TAG, "kitten TTFA=${ttfaMs}ms (loadWait=${loadWaitMs}ms + firstInfer=${inferMs}ms + overhead=${ttfaMs - loadWaitMs - inferMs}ms)")
                }
                send(SynthAudio(pcm = pcm, sampleRate = sampleRate))
                prevSendNs = System.nanoTime()
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Full text→PCM16 pipeline for one synth call. Lives outside
     * [synthesize] so [warmupSynth] can call it without re-acquiring
     * [synthLock] (warmup runs on the load thread, before the engine is
     * advertised as ready).
     */
    private fun runInference(text: String, voiceName: String, speed: Float): ShortArray {
        val ort = env ?: error("engine not loaded")
        val session = acousticSession ?: error("acoustic session missing")
        val phon = phonemizer ?: error("phonemizer missing")

        val rawIpa = phon.phonemize(text)
        if (rawIpa.isEmpty()) return ShortArray(0)

        // BERT position-embedding cap: any phoneme tail past this would
        // trip an ORT_INVALID_ARGUMENT on `/bert/Expand`. The outer
        // chunker already targets char-count, but Kitten's IPA expansion
        // varies per phrase (stress marks, length marks), so we still
        // need this guard on the phoneme side. Truncating drops the
        // sentence's tail rather than the engine crashing on the chunk.
        val ipa = if (rawIpa.length > MAX_PHONEMES_PER_CHUNK) {
            Log.w(TAG, "phoneme count ${rawIpa.length} exceeds $MAX_PHONEMES_PER_CHUNK — truncating tail")
            rawIpa.substring(0, MAX_PHONEMES_PER_CHUNK)
        } else {
            rawIpa
        }

        val phonemeIds = encodePhonemes(ipa)
        val inputIds = wrapForKitten(phonemeIds)
        // Upstream KittenML indexes voice style by the ORIGINAL TEXT
        // length, not the phoneme-string length
        // (onnx_model.py:108 `ref_id = min(len(text), voices[voice].shape[0] - 1)`).
        // Text and phoneme length diverge non-trivially for tech jargon
        // ("kubectl" = 7 chars but ~10 IPA chars), so this index choice
        // affects which prosody seed Kitten uses.
        val style = lookupVoiceStyle(voiceName, text.length)

        // Apply sherpa's per-voice speed prior. The raw model runs ~25%
        // too fast at speed=1.0; sherpa compensates by silently
        // multiplying the caller's speed by 0.8 (or 0.9 for Hugo).
        // Without this we sound rushed and words mash together because
        // the inter-word space tokens get squeezed.
        val voicePrior = speedPriorFor(voiceName)
        val effectiveSpeed = speed * voicePrior

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
            val results = session.run(
                mapOf(
                    "input_ids" to inputIdTensor,
                    "style"     to styleTensor,
                    "speed"     to speedTensor,
                ),
            )
            try {
                val raw = extractWaveform(results[0].value)
                val trimmed = if (raw.size > TRIM_SAMPLES) raw.copyOf(raw.size - TRIM_SAMPLES) else raw
                return floatToPcm16(trimmed)
            } finally {
                results.close()
            }
        } finally {
            inputIdTensor.close()
            styleTensor.close()
            speedTensor.close()
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
        val bytes = file.readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
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

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
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
        kotlinx.coroutines.runBlocking {
            loadLock.withLock { releaseInternal() }
        }
    }

    private fun releaseInternal() {
        // Cancel any mid-flight warmup so it doesn't blow up trying to
        // touch sessions we're about to null out.
        warmupJob?.cancel()
        warmupJob = null
        try { acousticSession?.close() } catch (_: Throwable) {}
        try { phonemizer?.close() } catch (_: Throwable) {}
        acousticSession = null
        phonemizer = null
        voiceTables.clear()
        env = null  // process-scoped; do not close
    }

    companion object {
        const val ENGINE_NAME = "kitten-direct-v0_8"
        private const val MODEL_FILE = "kitten.onnx"
        private const val PHONEMIZER_DIR = "phonemizer"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
        private const val VOICES_DIR = "voices"

        /**
         * Soft floor for chunker's `minChars` merge pass — runs of
         * tiny sentences are merged until the accumulator reaches this
         * size, but every chunk still ends at a sentence boundary.
         * Matches sherpa's 50-token tiny-sentence merge threshold.
         * Speed-prior compensation makes the prosody-by-style-row
         * concern moot at this chunk size.
         */
        private const val MIN_CHARS_PER_CHUNK = 80

        /**
         * Default espeak voice for the load-time `EspeakPhonemizer`
         * constructor + the fallback when callers pass null
         * `phonemizationLanguage`. Kitten is English-only at the model
         * level; non-English overrides will produce out-of-vocab IPA.
         */
        private const val KITTEN_DEFAULT_ESPEAK_VOICE = "en-us"
    }
}
