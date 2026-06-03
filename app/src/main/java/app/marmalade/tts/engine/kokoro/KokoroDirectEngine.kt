package app.marmalade.tts.engine.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.SilenceCompressor
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.kitten.PAD_TOKEN
import app.marmalade.tts.engine.kitten.encodePhonemes
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.CutletJaG2P
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.OpenJtalkPhonemizer
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
// Runs the upstream `kokoro-multi-lang-v1_0` ONNX (~325 MB fp32) directly
// on `com.microsoft.onnxruntime:onnxruntime-android`, sharing the same
// Pocket-style perf stack as KittenDirect (XNNPACK EP, direct ByteBuffers,
// thread autodetect). Espeak-ng is dlopen'd at runtime from the engine
// bundle — same JNI-shim pattern KittenDirect uses to keep the APK MIT-only.
//
// Bundle layout (`${filesDir}/engines/kokoro-direct-v1_0/`):
//   model.onnx                          — acoustic model (~325 MB)
//   voices.bin                          — float32 [53, 510, 256] style table
//   tokens.txt                          — phoneme→ID lookup (114 entries)
//   phonemizer/
//     espeak-ng-data/                   — espeak data dir (GPL-3.0)
//     arm64-v8a/libttsespeak.so         — espeak shared lib (GPL-3.0)
//
// Differences from KittenDirect (each verified against sherpa-onnx
// `csrc/offline-tts-kokoro-*` source — see [[compare-at-model-boundary]]):
//
//   1. NO speed priors. Sherpa's `OfflineTtsKokoroModelMetaData` does not
//      read a `speaker_speed_priors` field, and the v1.0 add_meta_data.py
//      script doesn't write one. User [speed] feeds the model directly.
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
//   6. Espeak voice is hardcoded "en-us" for all 53 voices — matches
//      [app.marmalade.tts.engine.KokoroV10Engine]'s behavior. The
//      non-English voices in voices.bin are speaker-style references,
//      not language switches; sherpa runs everything through en-us
//      phonemization and the speaker style does the rest. A future
//      atom can add per-voice espeak language switching.
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
 * Last N samples of every Kokoro synth are decoder ring-out — drop them.
 * Matches sherpa-onnx's silence trim and Kitten's 5000-sample default;
 * absence of a trim leaves an audible "buzz" tail at the end of each
 * chunk during streaming playback.
 */
private const val TRIM_SAMPLES = 5000

/** Default espeak phonemization voice — see file comment. */
private const val ESPEAK_VOICE = "en-us"

@Singleton
class KokoroDirectEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME
    override val sampleRate: Int = KokoroDirectVoiceCatalog.SAMPLE_RATE

    /**
     * Ceiling for a single chunk. Most chunks land well below this:
     * the chunker splits at every `.!?;:` + newline, then merges runs
     * of tiny adjacent sentences up to [MIN_CHARS_PER_CHUNK]. maxChars
     * only kicks in for pathological single-sentence inputs that
     * exceed both thresholds, in which case we emit oversize and let
     * the per-chunk phoneme cap inside [runInference] truncate.
     */
    override val maxInputChars: Int = 255

    /**
     * Soft floor used by the chunker's `minChars` merge pass. Sentences
     * are grouped until the accumulator reaches this size; each chunk
     * always ends on a sentence boundary regardless. Matches sherpa's
     * 50-token tiny-sentence merge threshold (≈ 80 source-text chars
     * for typical English).
     */
    private val minCharsPerChunk: Int = MIN_CHARS_PER_CHUNK

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")
    private val acousticModelFile: File get() = File(engineDir, MODEL_FILE)
    private val voicesFile: File get() = File(engineDir, VOICES_FILE)
    private val tokensFile: File get() = File(engineDir, TOKENS_FILE)
    private val phonemizerDir: File get() = File(engineDir, PHONEMIZER_DIR)

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    // -- live state (null while not loaded) -----------------------------------

    private var env: OrtEnvironment? = null
    private var acousticSession: OrtSession? = null
    private var phonemizer: EspeakPhonemizer? = null

    /**
     * Mandarin lexicon, loaded from the bundle's `lexicon-zh.txt`. Null when
     * the installed bundle predates zh support (graceful degradation — CJK
     * then falls back to the espeak path, which is broken for Mandarin but
     * doesn't crash). Bundles from v17 onward ship it.
     */
    private var lexiconZh: LexiconZh? = null

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

    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        if (!acousticModelFile.isFile) return false
        if (!voicesFile.isFile) return false
        if (!tokensFile.isFile) return false
        if (!espeakLibFile().isFile) return false
        if (!File(phonemizerDir, ESPEAK_DATA_DIR).isDirectory) return false
        return true
    }

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
            if (!isInstalled()) throw EngineNotInstalledException(ENGINE_NAME)
            val manualThreads = settings.intraOpThreads.firstOrNull()
            val threadCount = manualThreads ?: CpuClusterDetector.detectPerfCoreCount()
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
        val ort = OrtEnvironment.getEnvironment().also { env = it }
        val opts = buildSessionOptions(intraOpThreads)

        acousticSession = createSession(ort, opts, acousticModelFile)
        voicesFloatView = mmapVoicesAsFloatBuffer(voicesFile)
        Log.i(TAG, "mmap'd voices.bin (${voicesFloatView?.limit() ?: 0} floats)")

        // Pre-allocate fixed-shape input tensor scratch — reused across
        // every chunk. See the field doc on [styleScratchBuf] for why.
        styleScratchBuf = ByteBuffer.allocateDirect(STYLE_DIM * 4).order(ByteOrder.nativeOrder())
        styleScratchFloat = styleScratchBuf!!.asFloatBuffer()
        speedScratchBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        speedScratchFloat = speedScratchBuf!!.asFloatBuffer()

        // Espeak — see [KittenDirectEngine.doLoad] for the GPL-isolation
        // architecture (JNI shim dlopens GPL libttsespeak.so from bundle).
        val espeak = EspeakPhonemizer(
            libPath = espeakLibFile().absolutePath,
            dataPath = File(phonemizerDir, ESPEAK_DATA_DIR).absolutePath,
            voice = ESPEAK_VOICE,
        )
        val rate = espeak.open()
        if (rate < 0) {
            throw IllegalStateException("espeak failed to open (status=$rate)")
        }
        phonemizer = espeak
        Log.i(TAG, "espeak version=${espeak.version()}")

        // Mandarin lexicon (sherpa-style Han→IPA token lookup). Optional —
        // older bundles don't ship it, in which case zh degrades to espeak.
        val lexFile = File(engineDir, LEXICON_ZH_FILE)
        if (lexFile.isFile) {
            val t1 = System.currentTimeMillis()
            val lex = LexiconZh(lexFile)
            lexiconZh = lex
            Log.i(TAG, "loaded lexicon-zh (${lex.size()} entries) in ${System.currentTimeMillis() - t1} ms")
        } else {
            Log.w(TAG, "lexicon-zh.txt absent — Mandarin falls back to espeak (degraded)")
        }

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

        // P-D: warmup runs async — see KittenDirectEngine for details.
        warmupJob = warmupScope.launch {
            synthLock.withLock { warmupSynth() }
        }
    }

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
     * Touch the session with a 1-character synth so ORT's first-call
     * kernel compilation costs fold into engine load. Mirrors the Pocket
     * and KittenDirect warmup strategy.
     */
    private fun warmupSynth() {
        val t0 = System.currentTimeMillis()
        try {
            val firstVoice = KokoroDirectVoiceCatalog.voices.first().displayName
            runInference(text = "Hi.", voiceName = firstVoice, speed = 1.0f, lang = "en-us")
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

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic — see KittenDirectEngine for the rationale.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        val voiceName = voiceId.substringAfter(':', voiceId)
        // Resolve effective phonemization language: explicit override > voice's
        // natural language. espeakVoiceFor maps each voice prefix (af_*→en-us,
        // jf_*→ja, zf_*→en-us-since-zh-goes-through-lexicon). The value also
        // selects the phonemizer in encodeTextToTokens: "ja" → Open JTalk +
        // cutlet, else espeak (+ zh lexicon for CJK runs). setVoice is cached,
        // so back-to-back same-language synths skip the ~50ms espeak reload.
        val effectiveLang = phonemizationLanguage
            ?: KokoroDirectVoiceCatalog.espeakVoiceFor(voiceName)
        phonemizer?.setVoice(effectiveLang)
        // Same chunking discipline as KittenDirect — never word-split, split
        // only on sentence-end punctuation + newlines, pack up to maxInputChars.
        val chunks = TextChunker.chunk(
            text = text,
            maxChars = maxInputChars,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = minCharsPerChunk,
        )
        if (chunks.isEmpty()) return@channelFlow

        // P-A diagnostic: per-chunk infer time + real-time factor + producer
        // inter-send gap. See KittenDirectEngine for the rationale.
        var prevSendNs = 0L
        for ((idx, chunk) in chunks.withIndex()) {
            val inferStartNs = System.nanoTime()
            val pcm = synthLock.withLock { runInference(chunk, voiceName, speed, effectiveLang) }
            val inferMs = (System.nanoTime() - inferStartNs) / 1_000_000
            if (pcm.isNotEmpty()) {
                val audioMs = pcm.size * 1000L / sampleRate
                val gapMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
                val rtf = if (audioMs > 0) inferMs.toDouble() / audioMs else Double.NaN
                Log.d(PERF_TAG, "kokoro chunk=$idx/${chunks.size} infer=${inferMs}ms audio=${audioMs}ms rtf=${"%.2f".format(rtf)} gap=${gapMs}ms textLen=${chunk.length}")
                if (idx == 0) {
                    val ttfaMs = (System.nanoTime() - streamStartNs) / 1_000_000
                    Log.d(PERF_TAG, "kokoro TTFA=${ttfaMs}ms (loadWait=${loadWaitMs}ms + firstInfer=${inferMs}ms + overhead=${ttfaMs - loadWaitMs - inferMs}ms)")
                }
                send(SynthAudio(pcm = pcm, sampleRate = sampleRate))
                prevSendNs = System.nanoTime()
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun runInference(text: String, voiceName: String, speed: Float, lang: String): ShortArray {
        val ort = env ?: error("engine not loaded")
        val session = acousticSession ?: error("acoustic session missing")

        val rawIds = encodeTextToTokens(text, lang)
        if (rawIds.isEmpty()) return ShortArray(0)

        // Cap token count so the wrapped length stays under MAX_TOKEN_LEN.
        // Bound is on tokens directly now (the encoder may interleave lexicon
        // tokens with espeak phonemes, so an IPA-string length cap no longer
        // maps cleanly).
        val phonemeIds = if (rawIds.size > MAX_PHONEMES_PER_CHUNK) {
            Log.w(TAG, "token count ${rawIds.size} exceeds $MAX_PHONEMES_PER_CHUNK — truncating tail")
            rawIds.copyOf(MAX_PHONEMES_PER_CHUNK)
        } else {
            rawIds
        }
        val inputIds = wrapForKokoro(phonemeIds)

        val sid = KokoroDirectVoiceCatalog.speakerIdFor(voiceName)
        if (sid < 0) {
            Log.w(TAG, "unknown voice '$voiceName' — falling back to speaker 0")
        }
        val resolvedSid = if (sid >= 0) sid else 0

        // Voice indexing by phoneme-token length (sherpa convention,
        // confirmed in offline-tts-kokoro-model.cc:Run): len = total - 2.
        val styleLen = inputIds.size - 2

        // Fill reusable scratch buffers directly — no intermediate FloatArray.
        val styleFloat = styleScratchFloat ?: error("style scratch missing")
        val speedFloat = speedScratchFloat ?: error("speed scratch missing")
        fillStyleScratch(resolvedSid, styleLen, styleFloat)
        speedFloat.clear()
        speedFloat.put(speed)
        speedFloat.rewind()

        Log.d(TAG, "input='$text'")
        Log.d(TAG, "voice='$voiceName' sid=$resolvedSid styleLen=$styleLen speed=$speed tokenCount=${phonemeIds.size}")
        Log.d(TAG, "tokens=${inputIds.toList()}")

        // Tokens vary in size per chunk (1 to ~500 longs); still per-call
        // alloc. Future atom can cap+reuse with a max-sized scratch.
        val inputIdTensor = directLongTensor(ort, longArrayOf(1, inputIds.size.toLong())) { buf ->
            for (id in inputIds) buf.put(id.toLong())
        }
        val styleTensor = OnnxTensor.createTensor(ort, styleFloat, longArrayOf(1, STYLE_DIM.toLong()))
        val speedTensor = OnnxTensor.createTensor(ort, speedFloat, longArrayOf(1))

        try {
            val results = session.run(
                mapOf(
                    "tokens" to inputIdTensor,
                    "style"  to styleTensor,
                    "speed"  to speedTensor,
                ),
            )
            try {
                val raw = extractWaveform(results[0].value)
                val trimmed = if (raw.size > TRIM_SAMPLES) raw.copyOf(raw.size - TRIM_SAMPLES) else raw
                // Sherpa-style silence compression: scales runs of inter-sentence
                // silence to 20% of their natural length. Without this, the
                // decoder's residual tail past TRIM_SAMPLES accumulates ~0.3s
                // per chunk and the playback runs ~20% long across many chunks.
                val pcm16 = floatToPcm16(trimmed)
                return SilenceCompressor.compress(pcm16)
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

        val lex = lexiconZh
        if (lex == null || !LexiconZh.CJK_RUN_PATTERN.containsMatchIn(text)) {
            return encodePhonemesKokoro(phon.phonemize(text))
        }

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
                append(encodePhonemesKokoro(phon.phonemize(text.substring(cursor, m.range.first))))
            }
            append(lex.match(m.value))
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            append(encodePhonemesKokoro(phon.phonemize(text.substring(cursor))))
        }
        return out.copyOf(pos)
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

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
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
        try { openJtalk?.close() } catch (_: Throwable) {}
        acousticSession = null
        phonemizer = null
        openJtalk = null
        lexiconZh = null
        voicesFloatView = null
        styleScratchBuf = null
        styleScratchFloat = null
        speedScratchBuf = null
        speedScratchFloat = null
        env = null  // process-scoped; do not close
    }

    companion object {
        const val ENGINE_NAME = "kokoro-direct-v1_0"
        private const val MODEL_FILE = "model.onnx"
        private const val VOICES_FILE = "voices.bin"
        private const val TOKENS_FILE = "tokens.txt"
        private const val LEXICON_ZH_FILE = "lexicon-zh.txt"
        private const val OPENJTALK_DICT_DIR = "openjtalk_dic"
        private const val PHONEMIZER_DIR = "phonemizer"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"

        /** Token ID for ASCII space in the Kokoro vocab. */
        private const val SPACE_TOKEN = 16

        /** See [minCharsPerChunk] — sherpa's 50-token threshold in chars. */
        private const val MIN_CHARS_PER_CHUNK = 80
    }
}
