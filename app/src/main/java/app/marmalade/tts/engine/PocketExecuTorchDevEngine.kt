package app.marmalade.tts.engine

import android.content.Context
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.PocketEtVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketClonedVoiceStore
import app.marmalade.tts.engine.pocket.PocketTokenizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   synthesize(text, voiceId, speed) ──► synthesizeStream(...).collect ──► merged PCM16
//                                              │
//   synthesizeStream(text, voiceId, speed):    ▼
//     ensureModelLoaded (loads 5–6 .pte via ExecuTorch Module.load)
//     embeddingForVoice(name)  ── voices/<name>.wav ─► resample 24k ─► norm ─►
//                                 pad/truncate to EXACTLY 30 s ─► mimi_encoder.pte
//                                 ─► [V, 1024] (cached .emb on disk + in memory)
//     TextChunker.chunk(...) ──► mini-chunks (≤ bundle.maxTokenPerChunk tokens)
//        for each mini-chunk:  synthesizeChunk(...)
//          PHASE 1 (voice cond):  flow_lm_cond.pte   seq=[1,0,32] text=BOS++voice
//          PHASE 2 (text cond):   text_conditioner.pte ─► [T,1024]
//                                  flow_lm_cond.pte   seq=[1,0,32] text=[1,T,1024]
//          PHASE 3 (AR loop):     flow_lm_main_int8.pte  seq=[1,1,32] text=[1,0,1024]
//                                  ─► conditioning[1,1,1024] + eos_logit
//                                  ─► flow_lm_flow.pte ×LSD_DECODE_STEPS (Euler) ─► latent[32]
//                                  KV caches (6 × [2,1,1024,16,64]) threaded 1→2→3,
//                                  zero-filled at chunk start, copied back each step.
//          DECODE:                mimi_decoder.pte (STATELESS, whole chunk one call)
//                                  ─► float PCM ─► PCM16
//          emit SynthAudio
//
// This is a developer-only A/B against the shipping ORT [PocketEngine]. The
// orchestration recipe (preprocess, chunking, EOS, noise sampling, voice
// loading) mirrors PocketEngine exactly; only the inference backend differs
// (org.pytorch.executorch instead of onnxruntime). The whole point is the
// "PocketET" timing logs — full-pipeline RTF read straight from logcat.
// -----------------------------------------------------------------------------

/**
 * Pocket TTS via ExecuTorch — a developer-only engine that runs the same
 * 5-graph LSD pipeline as [PocketEngine] but on `org.pytorch.executorch`
 * (the planned long-term inference stack) instead of onnxruntime.
 *
 * Installs as a separate engine ([engineName] = [PocketEtVoiceCatalog.ENGINE]).
 * The `.pte` graphs are side-loaded into `getExternalFilesDir(null)` (push via
 * adb); the bundle's non-ONNX files (tokenizer, bos_before_voice, bundle.json,
 * voices) come from production Pocket's install dir under `filesDir`.
 *
 * Graphs loaded:
 *  - text_conditioner.pte   tokens int64[1,T]            -> embeddings [1,T,1024]
 *  - mimi_encoder.pte (fp32) audio [1,1,720000] STATIC   -> latents [1,V,1024]
 *  - flow_lm_cond.pte (fp32) phases 1&2 (voice + text conditioning)
 *  - flow_lm_main_int8.pte   phase-3 AR step (the int8 KleidiAI target)
 *  - flow_lm_flow.pte (fp32) c[1,1024] s[1,1] t[1,1] x[1,32] -> flow_dir[1,32]
 *  - mimi_decoder.pte (fp32) latent [1,T,32] STATELESS  -> audio [1,1,T*1920]
 *
 * flow_lm_cond.pte is the conditioning-phases export (phases 1 & 2 use empty
 * `sequence` + length-T `text`). If it's missing at load (the export may still
 * be getting finalized), we log a clear warning and load everything else so
 * the class still constructs; the cond-phase calls then fail fast at synth time
 * with a descriptive error rather than producing silent garbage.
 *
 * Differences from [PocketEngine] that the blueprint mandates:
 *  - mimi_decoder is STATELESS single-shot: the whole chunk decodes in ONE
 *    forward, so there is NO P-AL overlap-discard / snapshot / restore.
 *  - flow_lm KV caches are 6 explicit FloatArray([2,1,1024,16,64]) passed as the
 *    last 6 forward args; the 6 output cache tensors are copied back each step.
 *
 * Production-faithful constants: LSD_DECODE_STEPS=4, TEMPERATURE=0.7, noise
 * clamp ±3.0, EOS_THRESHOLD=-4.0.
 *
 * Thread-safety: load behind [loadLock]; synth serialised by [synthLock] (the
 * KV cache isn't reentrant). The AR loop checks [ensureActive] each frame for
 * cooperative cancellation.
 */
@Singleton
class PocketExecuTorchDevEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : TtsEngine {

    override val engineName: String = PocketEtVoiceCatalog.ENGINE

    override val sampleRate: Int
        get() = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE

    /** Pocket self-chunks (see [TextChunker] usage) — advertise no outer cap. */
    override val maxInputChars: Int = Int.MAX_VALUE

    /**
     * This engine's OWN install dir (self-contained, mirroring
     * [app.marmalade.tts.engine.PocketDevEngine]): the catalog archive extracts
     * the Pocket bundle (tokenizer, bos_before_voice, bundle.json, voice WAVs)
     * here. Only the `.pte` graphs differ — those are side-loaded into external
     * files via `adb push`, not part of the archive. (The bundled .onnx graphs
     * are unused baggage; we infer from .pte instead.)
     */
    private val bundleDir: File get() = File(ctx.filesDir, "engines/$engineName")
    private val voicesDir: File get() = File(bundleDir, "voices")
    private val voiceCacheDir: File get() = File(bundleDir, "voice_cache")
    private val clonedVoicesDir: File get() = File(bundleDir, "cloned_voices")

    /** Side-loaded `.pte` graphs live here (adb push target). */
    private val pteDir: File? get() = ctx.getExternalFilesDir(null)

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    // -- live state (null while not loaded) ----------------------------------

    @Volatile
    private var loaded: Boolean = false
    private var bundle: PocketBundle? = null
    private var tokenizer: PocketTokenizer? = null
    private var bosBeforeVoice: FloatArray? = null

    private var textCondModule: Module? = null
    private var mimiEncoderModule: Module? = null
    private var mimiDecoderModule: Module? = null
    private var flowLmMainModule: Module? = null
    private var flowLmFlowModule: Module? = null

    /** Conditioning-phases graph. May be null if its export isn't side-loaded yet. */
    private var flowLmCondModule: Module? = null

    /** Cached voice embeddings (built-in + cloned), keyed by local voice name. */
    private val voiceEmbeddings = ConcurrentHashMap<String, FloatArray>()

    private val random = java.util.Random()

    override fun isInstalled(): Boolean {
        // Bundle's non-ONNX files (shared with production Pocket).
        if (!bundleDir.isDirectory) return false
        for (name in REQUIRED_NON_ONNX_FILES) {
            if (!File(bundleDir, name).isFile) return false
        }
        // Parse-validate bundle.json (structural sanity); we don't keep the
        // parsed value here — doLoad re-parses and stashes it.
        runCatching { PocketBundle.load(File(bundleDir, "bundle.json")) }
            .getOrNull() ?: return false
        if (!voicesDir.isDirectory) return false
        for (voice in PocketVoiceCatalog.voices) {
            if (!File(voicesDir, "${voice.displayName}.wav").isFile) return false
        }
        // Side-loaded .pte graphs. flow_lm_cond is optional (export may be in
        // flight); the other five are required.
        val dir = pteDir ?: return false
        for (name in REQUIRED_PTE_FILES) {
            if (!File(dir, name).isFile) return false
        }
        return true
    }

    override fun ensureModelLoaded() {
        if (loaded) return
        kotlinx.coroutines.runBlocking { ensureLoadedSuspending() }
    }

    private suspend fun ensureLoadedSuspending() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            if (!isInstalled()) throw EngineNotInstalledException(engineName)
            Log.i(TAG, "Loading PocketExecuTorch (dev A/B) — bundle=$bundleDir pte=$pteDir …")
            val t0 = System.currentTimeMillis()
            try {
                doLoad()
                Log.i(TAG, "PocketExecuTorch loaded in ${System.currentTimeMillis() - t0} ms")
                loaded = true
            } catch (t: Throwable) {
                releaseInternal()
                throw IllegalStateException("Failed to load PocketExecuTorch: ${t.message}", t)
            }
        }
    }

    private fun doLoad() {
        val dir = pteDir ?: error("getExternalFilesDir(null) is null — external storage unavailable")
        bundle = PocketBundle.load(File(bundleDir, "bundle.json"))
        tokenizer = PocketTokenizer.load(File(bundleDir, "tokenizer.model"))

        val npy = NpyReader.readFloat32(File(bundleDir, "bos_before_voice.npy"))
        check(npy.data.size == bundle!!.conditioningDim) {
            "bos_before_voice.npy has ${npy.data.size} floats; expected ${bundle!!.conditioningDim}"
        }
        bosBeforeVoice = npy.data

        textCondModule = loadModule(dir, PTE_TEXT_CONDITIONER)
        // NOTE: mimi_encoder is NOT loaded here. Its ExecuTorch runtime arena is
        // the largest of the 6 graphs and it only runs ONCE per voice — keeping
        // it resident through the AR loop + decode pushes peak memory past the
        // device's free RAM (lmkd kills the process). It's lazy-loaded and
        // released inside encodePcm. (Module.load is a cheap mmap; the big arena
        // only materializes on forward() and lives until destroy().)
        mimiDecoderModule = loadModule(dir, PTE_MIMI_DECODER)
        flowLmMainModule = loadModule(dir, PTE_FLOW_LM_MAIN)
        flowLmFlowModule = loadModule(dir, PTE_FLOW_LM_FLOW)

        // Conditioning-phases graph is optional while its export is finalized.
        val condFile = File(dir, PTE_FLOW_LM_COND)
        flowLmCondModule = if (condFile.isFile) {
            loadModule(dir, PTE_FLOW_LM_COND)
        } else {
            Log.w(
                TAG,
                "$PTE_FLOW_LM_COND not present at ${condFile.absolutePath} — loaded the other " +
                    "graphs, but phases 1 & 2 will fail until you push it. Synthesis is " +
                    "blocked until then.",
            )
            null
        }
    }

    private fun loadModule(dir: File, name: String): Module {
        val file = File(dir, name)
        require(file.isFile) { "Missing ExecuTorch graph: ${file.absolutePath}" }
        val t0 = System.currentTimeMillis()
        val module = Module.load(file.absolutePath)
        Log.i(TAG, "load $name: ${System.currentTimeMillis() - t0} ms (${file.length() / 1_048_576} MB)")
        return module
    }

    // -- synthesis -----------------------------------------------------------

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        val parts = ArrayList<ShortArray>()
        var sr = 0
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect { chunk ->
            parts.add(chunk.pcm)
            sr = chunk.sampleRate
        }
        if (parts.isEmpty()) {
            return@withContext SynthAudio(
                pcm = ShortArray(0),
                sampleRate = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE,
            )
        }
        val total = parts.sumOf { it.size }
        val merged = ShortArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, merged, pos, p.size)
            pos += p.size
        }
        SynthAudio(pcm = merged, sampleRate = sr)
    }

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = flow {
        ensureLoadedSuspending()
        val bundle = bundle ?: error("bundle missing after load")
        val tokenizer = tokenizer ?: error("tokenizer missing after load")
        if (speed != 1.0f) {
            Log.d(TAG, "PocketExecuTorch ignores speed=$speed (not exposed natively)")
        }

        synthLock.withLock {
            val voiceName = voiceId.substringAfter(':', voiceId)
            val voiceEmb = embeddingForVoice(voiceName)

            // Mirror PocketEngine's chunking: sentence-only, char-bound,
            // minChars-merge, then enforce the model's hard token cap.
            val preprocessed = preprocessForPocket(text.trim(), bundle)
            val outerChunks = TextChunker.chunk(
                text = preprocessed,
                maxChars = POCKET_MAX_CHARS_PER_CHUNK,
                packSentences = false,
                sentenceOnly = true,
                allowWordSplits = false,
                minChars = POCKET_MIN_CHARS_PER_CHUNK,
            )
            val miniChunks = ArrayList<String>(outerChunks.size)
            for (chunk in outerChunks) {
                val tokenCount = tokenizer.encode(chunk).size
                if (tokenCount <= bundle.maxTokenPerChunk) {
                    miniChunks.add(chunk)
                } else {
                    Log.d(TAG, "outer chunk has $tokenCount tokens > ${bundle.maxTokenPerChunk}; sub-splitting")
                    miniChunks.addAll(chunkPocketByTokens(chunk, tokenizer, bundle, bundle.maxTokenPerChunk))
                }
            }
            if (miniChunks.isEmpty()) return@withLock
            Log.i(TAG, "PocketET: ${miniChunks.size} mini-chunk(s) for input of ${text.length} chars")

            val synthStartNs = System.nanoTime()
            var totalFrames = 0
            var totalAudioSamples = 0
            for ((i, miniChunk) in miniChunks.withIndex()) {
                val pcm = synthesizeChunk(bundle, voiceEmb, miniChunk, chunkIndex = i)
                totalFrames += pcm.size / bundle.samplesPerFrame
                totalAudioSamples += pcm.size
                emit(SynthAudio(pcm = floatToPcm16(pcm), sampleRate = bundle.sampleRate))
            }
            val synthMs = (System.nanoTime() - synthStartNs) / 1_000_000.0
            val audioMs = totalAudioSamples * 1000.0 / bundle.sampleRate
            val rtf = if (audioMs > 0) synthMs / audioMs else 0.0
            Log.i(
                TAG,
                "RTF total: synthMs=%.0f audioMs=%.0f frames=%d RTF=%.3f (%.2fx realtime)"
                    .format(synthMs, audioMs, totalFrames, rtf, if (rtf > 0) 1.0 / rtf else 0.0),
            )
        }
    }.flowOn(Dispatchers.Default)

    // -- per-chunk synthesis: phase 1 + 2 + AR + decode ----------------------

    private suspend fun synthesizeChunk(
        bundle: PocketBundle,
        voiceEmb: FloatArray,
        rawText: String,
        chunkIndex: Int,
    ): FloatArray {
        val tokenizer = tokenizer!!
        val preprocessed = preprocessForPocket(rawText, bundle)
        val tokens = tokenizer.encode(preprocessed)
        if (tokens.isEmpty()) return FloatArray(0)

        // Fresh KV caches per chunk — 6 layers, zero-filled. Threaded
        // phase 1 → phase 2 → phase 3.
        val caches = Array(N_LAYERS) { FloatArray(CACHE_ELEMENTS) }

        // PHASE 1 — voice conditioning. text = bos ++ voice_emb; seq empty.
        val bos = bosBeforeVoice!!
        val voiceFrames = voiceEmb.size / bundle.conditioningDim
        val totalCondFrames = voiceFrames + if (bundle.insertBosBeforeVoice) 1 else 0
        val phase1Text = FloatArray(totalCondFrames * bundle.conditioningDim).also { arr ->
            var pos = 0
            if (bundle.insertBosBeforeVoice) {
                System.arraycopy(bos, 0, arr, pos, bos.size); pos += bos.size
            }
            System.arraycopy(voiceEmb, 0, arr, pos, voiceEmb.size)
        }
        var offset = 0L
        val p1Ms = measureMs {
            runFlowLmCond(bundle, caches, phase1Text, totalCondFrames, offset)
        }
        offset += totalCondFrames.toLong()

        // PHASE 2 — text conditioning.
        val tc0 = System.nanoTime()
        val textEmbeds = runTextConditioner(tokens, bundle)
        val textCondMs = (System.nanoTime() - tc0) / 1_000_000
        val p2Ms = measureMs {
            runFlowLmCond(bundle, caches, textEmbeds, tokens.size, offset)
        }
        offset += tokens.size.toLong()
        logMem("chunk $chunkIndex after cond, before AR")

        // PHASE 3 — AR loop.
        val latents = ArrayList<FloatArray>(64)
        var previousLatent = FloatArray(bundle.latentDim) { Float.NaN }
        val maxFrames = estimateMaxFrames(bundle, tokens.size)
        val framesAfterEos = framesAfterEosFor(bundle, rawText)
        var eosFired = false
        var eosFrame = -1
        var framesPostEos = 0
        var maxEosLogit = Float.NEGATIVE_INFINITY
        var peakLatentAbs = 0f
        var sawNonFinite = false

        var mainMs = 0L
        var flowMs = 0L
        val arStartNs = System.nanoTime()
        for (frame in 0 until maxFrames) {
            coroutineContext.ensureActive()

            val mainStart = System.nanoTime()
            val capture = runFlowLmMain(bundle, caches, previousLatent, offset)
            mainMs += (System.nanoTime() - mainStart) / 1_000_000
            offset += 1L
            if (capture.eosLogit > maxEosLogit) maxEosLogit = capture.eosLogit

            val flowStart = System.nanoTime()
            val nextLatent = runFlowEuler(capture.conditioning, bundle.latentDim)
            flowMs += (System.nanoTime() - flowStart) / 1_000_000

            for (v in nextLatent) {
                if (!v.isFinite()) { sawNonFinite = true; continue }
                val a = if (v < 0f) -v else v
                if (a > peakLatentAbs) peakLatentAbs = a
            }
            latents.add(nextLatent)
            previousLatent = nextLatent

            if (!eosFired && capture.eosLogit > EOS_THRESHOLD) {
                eosFired = true
                eosFrame = frame
            }
            if (eosFired) {
                framesPostEos++
                if (framesPostEos >= framesAfterEos) break
            }
        }
        val arMs = (System.nanoTime() - arStartNs) / 1_000_000.0
        val nFrames = latents.size
        val perFrameMs = if (nFrames > 0) arMs / nFrames else 0.0
        val mainPerFrame = if (nFrames > 0) mainMs.toDouble() / nFrames else 0.0
        val flowPerFrame = if (nFrames > 0) flowMs.toDouble() / nFrames else 0.0

        val summary = "PocketET chunk $chunkIndex: frames=$nFrames/$maxFrames " +
            "eosFired=$eosFired@$eosFrame maxEosLogit=${"%.2f".format(maxEosLogit)} " +
            "peakLatentAbs=${"%.2f".format(peakLatentAbs)} nonFinite=$sawNonFinite " +
            "text=\"${rawText.take(48)}\""
        if (!eosFired || sawNonFinite) Log.w(TAG, "SUSPECT $summary") else Log.i(TAG, summary)

        if (latents.isEmpty()) return FloatArray(0)

        // DECODE — stateless single-shot, whole chunk in one forward.
        val flat = FloatArray(nFrames * bundle.latentDim).also { arr ->
            var pos = 0
            for (l in latents) {
                System.arraycopy(l, 0, arr, pos, bundle.latentDim); pos += bundle.latentDim
            }
        }
        logMem("chunk $chunkIndex before decode (nFrames=$nFrames)")
        val decodeStart = System.nanoTime()
        val pcm = runMimiDecoder(bundle, flat, nFrames)
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
        logMem("chunk $chunkIndex after decode")

        Log.i(
            TAG,
            ("chunk %d timings: cond1=%dms textCond=%dms cond2=%dms AR=%.0fms " +
                "(%.1f ms/frame: main=%.1f flow=%.1f) decode=%dms")
                .format(
                    chunkIndex, p1Ms, textCondMs, p2Ms, arMs,
                    perFrameMs, mainPerFrame, flowPerFrame, decodeMs,
                ),
        )
        return pcm
    }

    // -- ExecuTorch graph invocations ----------------------------------------

    private data class FlowLmCapture(val conditioning: FloatArray, val eosLogit: Float)

    /**
     * text_conditioner.pte: token_ids int64[1,T] -> embeddings f32[1,T,1024].
     */
    private fun runTextConditioner(tokens: IntArray, bundle: PocketBundle): FloatArray {
        val module = textCondModule ?: error("text_conditioner module missing")
        val longs = LongArray(tokens.size) { tokens[it].toLong() }
        val tokT = Tensor.fromBlob(longs, longArrayOf(1, tokens.size.toLong()))
        val out = module.forward(EValue.from(tokT))
        val flat = out[0].toTensor().dataAsFloatArray
        check(flat.size == tokens.size * bundle.conditioningDim) {
            "text_conditioner returned ${flat.size} floats; expected ${tokens.size * bundle.conditioningDim}"
        }
        return flat
    }

    /**
     * flow_lm_cond.pte (phases 1 & 2): sequence f32[1,0,32], text f32[1,T,1024],
     * offset i64[], 6 caches f32[2,1,1024,16,64]. Outputs conditioning/eos at
     * length T (discarded — we only need the updated caches) and the 6 updated
     * caches, copied back into [caches] in place.
     */
    private fun runFlowLmCond(
        bundle: PocketBundle,
        caches: Array<FloatArray>,
        textData: FloatArray,
        textFrames: Int,
        offset: Long,
    ) {
        val module = flowLmCondModule ?: error(
            "$PTE_FLOW_LM_COND not loaded — push it to ${pteDir?.absolutePath} " +
                "(conditioning phases 1 & 2 require it).",
        )
        val seqT = Tensor.fromBlob(EMPTY_FLOATS, longArrayOf(1, 0, bundle.latentDim.toLong()))
        val textT = Tensor.fromBlob(textData, longArrayOf(1, textFrames.toLong(), bundle.conditioningDim.toLong()))
        val offsetT = Tensor.fromBlob(longArrayOf(offset), LongArray(0))
        val inputs = buildFlowLmInputs(seqT, textT, offsetT, caches)
        val out = module.forward(*inputs)
        // out[0] = conditioning, out[1] = eos_logit, out[2..7] = caches_after.
        copyCachesBack(out, caches)
    }

    /**
     * flow_lm_main_int8.pte (phase 3 AR step): sequence f32[1,1,32]
     * (NaN@frame0 = BOS), text f32[1,0,1024] (EMPTY), offset i64[], 6 caches
     * f32[2,1,1024,16,64]. Outputs conditioning f32[1,1,1024], eos_logit
     * f32[1,1,1], and 6 updated caches (copied back in place).
     */
    private fun runFlowLmMain(
        bundle: PocketBundle,
        caches: Array<FloatArray>,
        previousLatent: FloatArray,
        offset: Long,
    ): FlowLmCapture {
        val module = flowLmMainModule ?: error("flow_lm_main module missing")
        val seqT = Tensor.fromBlob(previousLatent, longArrayOf(1, 1, bundle.latentDim.toLong()))
        val textT = Tensor.fromBlob(EMPTY_FLOATS, longArrayOf(1, 0, bundle.conditioningDim.toLong()))
        val offsetT = Tensor.fromBlob(longArrayOf(offset), LongArray(0))
        val inputs = buildFlowLmInputs(seqT, textT, offsetT, caches)
        val out = module.forward(*inputs)
        val cond = out[0].toTensor().dataAsFloatArray
        check(cond.size == bundle.conditioningDim) {
            "flow_lm_main conditioning had ${cond.size} floats; expected ${bundle.conditioningDim}"
        }
        val eos = out[1].toTensor().dataAsFloatArray[0]
        copyCachesBack(out, caches)
        return FlowLmCapture(conditioning = cond, eosLogit = eos)
    }

    /** Build [sequence, text, offset, cache0..cache5] EValue args. */
    private fun buildFlowLmInputs(
        seqT: Tensor,
        textT: Tensor,
        offsetT: Tensor,
        caches: Array<FloatArray>,
    ): Array<EValue> = arrayOf(
        EValue.from(seqT),
        EValue.from(textT),
        EValue.from(offsetT),
        *Array(N_LAYERS) { EValue.from(Tensor.fromBlob(caches[it], CACHE_SHAPE)) },
    )

    /** out[2..7] are the updated caches; copy them back into [caches] in place. */
    private fun copyCachesBack(out: Array<EValue>, caches: Array<FloatArray>) {
        for (i in 0 until N_LAYERS) {
            val updated = out[2 + i].toTensor().dataAsFloatArray
            check(updated.size == CACHE_ELEMENTS) {
                "cache $i had ${updated.size} floats; expected $CACHE_ELEMENTS"
            }
            System.arraycopy(updated, 0, caches[i], 0, CACHE_ELEMENTS)
        }
    }

    /**
     * LSD Euler integration via flow_lm_flow.pte. Matches [PocketEngine]:
     * x_0 ~ trunc_normal(0, sqrt(temp), clamp=±NOISE_CLAMP_ABS); for each of
     * LSD_DECODE_STEPS steps, x += flow_lm_flow(c, [[s]], [[t]], x) * dt.
     *
     * flow_lm_flow: c f32[1,1024], s f32[1,1], t f32[1,1], x f32[1,32] -> [1,32].
     */
    private fun runFlowEuler(conditioning: FloatArray, latentDim: Int): FloatArray {
        val module = flowLmFlowModule ?: error("flow_lm_flow module missing")
        val noiseStd = sqrt(TEMPERATURE).toFloat()
        val clamp = NOISE_CLAMP_ABS
        val current = FloatArray(latentDim) {
            var x = random.nextGaussian().toFloat() * noiseStd
            while (x > clamp || x < -clamp) x = random.nextGaussian().toFloat() * noiseStd
            x
        }
        val steps = LSD_DECODE_STEPS
        val dt = 1f / steps

        val cT = Tensor.fromBlob(conditioning, longArrayOf(1, conditioning.size.toLong()))
        val cEv = EValue.from(cT)
        for (j in 0 until steps) {
            val s = j.toFloat() / steps
            val t = (j + 1).toFloat() / steps
            val out = module.forward(
                cEv,
                EValue.from(Tensor.fromBlob(floatArrayOf(s), longArrayOf(1, 1))),
                EValue.from(Tensor.fromBlob(floatArrayOf(t), longArrayOf(1, 1))),
                EValue.from(Tensor.fromBlob(current.copyOf(), longArrayOf(1, latentDim.toLong()))),
            )
            val flowDir = out[0].toTensor().dataAsFloatArray
            for (k in 0 until latentDim) current[k] += flowDir[k] * dt
        }
        return current
    }

    /**
     * mimi_decoder.pte — STATELESS single-shot. latent f32[1,T,32] (all chunk
     * frames in ONE forward) -> audio f32[1,1,T*1920]. No state, no overlap.
     */
    private fun runMimiDecoder(bundle: PocketBundle, flatLatents: FloatArray, numFrames: Int): FloatArray {
        val module = mimiDecoderModule ?: error("mimi_decoder module missing")
        val latT = Tensor.fromBlob(
            flatLatents,
            longArrayOf(1, numFrames.toLong(), bundle.latentDim.toLong()),
        )
        val out = module.forward(EValue.from(latT))
        val pcm = out[0].toTensor().dataAsFloatArray
        val expected = numFrames * bundle.samplesPerFrame
        check(pcm.size == expected) {
            "mimi_decoder returned ${pcm.size} samples; expected $expected ($numFrames frames)"
        }
        return pcm
    }

    // -- voice encoding ------------------------------------------------------

    /**
     * Resolve [voiceName]'s embedding ([V, 1024] flat). Mirrors PocketEngine:
     *   1. in-memory cache
     *   2. cloned_voices/<name>.bin (cloned- prefix, PVS1 format)
     *   3. voice_cache/<name>.emb (int nFrames LE + nFrames*1024 LE f32)
     *   4. encode voices/<name>.wav via mimi_encoder, write .emb cache.
     */
    private fun embeddingForVoice(voiceName: String): FloatArray {
        voiceEmbeddings[voiceName]?.let { return it }

        if (voiceName.startsWith(CLONED_VOICE_PREFIX)) {
            val cacheFile = File(clonedVoicesDir, "$voiceName.bin")
            check(cacheFile.isFile) { "Cloned voice missing: $cacheFile" }
            val cloned = PocketClonedVoiceStore.read(cacheFile)
            voiceEmbeddings[voiceName] = cloned.embedding
            return cloned.embedding
        }

        if (!voiceCacheDir.exists()) voiceCacheDir.mkdirs()
        val cacheFile = File(voiceCacheDir, "$voiceName.emb")
        if (cacheFile.isFile) {
            try {
                val cached = readEmbCache(cacheFile)
                voiceEmbeddings[voiceName] = cached
                return cached
            } catch (t: Throwable) {
                Log.w(TAG, "Voice cache $cacheFile unreadable, re-encoding: ${t.message}")
                cacheFile.delete()
            }
        }

        val wavFile = File(voicesDir, "$voiceName.wav")
        check(wavFile.isFile) { "Voice WAV missing: $wavFile" }
        val wav = PocketAudio.readWav(wavFile)
        val encStart = System.nanoTime()
        val embedding = encodePcm(wav.samples, wav.sampleRate)
        Log.i(TAG, "voice-encode '$voiceName' (cold): ${(System.nanoTime() - encStart) / 1_000_000}ms")
        try {
            writeEmbCache(cacheFile, embedding)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write voice cache $cacheFile: ${t.message}")
        }
        voiceEmbeddings[voiceName] = embedding
        return embedding
    }

    /**
     * mimi_encoder.pte: audio f32[1,1,720000] (STATIC 30 s — the ET encoder
     * graph is exported at a fixed length) -> latents f32[1,V,1024].
     *
     * The PCM is resampled to the bundle rate, peak-normalised if clipping,
     * then padded OR truncated to EXACTLY 30 s before the call.
     */
    private fun encodePcm(pcm: FloatArray, srcSampleRate: Int): FloatArray {
        val bundle = bundle ?: error("bundle missing")
        // Lazy-load the encoder for this one call, release its arena immediately
        // after (see doLoad note) so it never coexists with the AR loop + decode.
        val dir = pteDir ?: error("getExternalFilesDir(null) null")
        val module = loadModule(dir, PTE_MIMI_ENCODER)
        try {
            return encodeWith(module, bundle, pcm, srcSampleRate)
        } finally {
            try { module.destroy() } catch (_: Throwable) {}
            logMem("after voice-encode (encoder released)")
        }
    }

    private fun encodeWith(
        module: Module,
        bundle: PocketBundle,
        pcm: FloatArray,
        srcSampleRate: Int,
    ): FloatArray {
        var processed = PocketAudio.resample(pcm, srcSampleRate, bundle.sampleRate)
        processed = PocketAudio.normalizeIfClipping(processed)
        // The ET mimi_encoder is exported at a STATIC 30 s input (the ORT graph
        // was dynamic and just took the real length). A short clip must be
        // zero-padded to fill the fixed shape — BUT those trailing-silence
        // frames must NOT prime the flow_lm KV cache. Priming with ~26 s of
        // silence frames pushes the model out-of-distribution: EOS never fires
        // and it babbles to the frame cap (confirmed: padded 30 s → eos −9.1,
        // real length → eos +5.9). The encoder is CAUSAL, so the leading
        // `realFrames` of the padded output are exactly the real-audio
        // embedding — truncate the OUTPUT back to that, matching the ORT engines
        // which feed (and thus keep) only the real length. samplesPerFrame=1920.
        val target = 30 * bundle.sampleRate
        val realSamples = processed.size.coerceAtMost(target)
        val realFrames = (realSamples / bundle.samplesPerFrame).coerceAtLeast(1)
        if (processed.size != target) processed = processed.copyOf(target)

        val audioT = Tensor.fromBlob(processed, longArrayOf(1, 1, target.toLong()))
        val out = module.forward(EValue.from(audioT))
        val latT = out[0].toTensor()
        val shape = latT.shape()
        check(shape.size == 3 && shape[0] == 1L && shape[2].toInt() == bundle.conditioningDim) {
            "mimi_encoder unexpected output shape: ${shape.toList()}"
        }
        val full = latT.dataAsFloatArray
        val totalFrames = shape[1].toInt()
        if (realFrames >= totalFrames) return full
        Log.i(TAG, "voice-encode: keeping $realFrames/$totalFrames frames (${realSamples} real samples)")
        return full.copyOf(realFrames * bundle.conditioningDim)
    }

    private fun readEmbCache(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 4) { "voice cache too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numFrames = buf.int
        val condDim = bundle!!.conditioningDim
        val expectedFloats = numFrames * condDim
        require(bytes.size == 4 + expectedFloats * 4) {
            "voice cache size mismatch: header says $numFrames frames but file holds ${(bytes.size - 4) / 4} floats"
        }
        val out = FloatArray(expectedFloats)
        buf.asFloatBuffer().get(out)
        return out
    }

    private fun writeEmbCache(file: File, embedding: FloatArray) {
        val condDim = bundle!!.conditioningDim
        require(embedding.size % condDim == 0) { "embedding length not divisible by conditioningDim" }
        val numFrames = embedding.size / condDim
        val out = ByteBuffer.allocate(4 + embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(numFrames)
        out.asFloatBuffer().put(embedding)
        file.writeBytes(out.array())
    }

    // -- text preprocessing + chunking (faithful PocketEngine port) ----------

    private fun preprocessForPocket(raw: String, bundle: PocketBundle): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        s = normalizeSmartPunctuation(s)
        s = Regex("([.!?]?)[ \\t]*\\n[\\s]*").replace(s) { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + " " else ". "
        }
        s = s.replace(Regex("\\s+"), " ")
        if (bundle.removeSemicolons) s = s.replace(';', ',')
        s = s[0].uppercaseChar() + s.substring(1)
        if (s.last().isLetterOrDigit()) s += "."
        if (bundle.padWithSpacesForShortInputs) {
            val wordCount = s.split(' ').count { it.isNotEmpty() }
            if (wordCount < 5) s = "        $s"
        }
        return s
    }

    private fun normalizeSmartPunctuation(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    '‘', '’', '‚', '‛', '′', 'ʼ' -> '\''
                    '“', '”', '„', '‟', '″' -> '"'
                    '–', '—', '‒', '―' -> '-'
                    ' ', ' ', ' ' -> ' '
                    else -> ch
                },
            )
        }
        return sb.toString().replace('…', '.')
    }

    /**
     * Token-aware sub-splitter for the rare oversized single sentence.
     * Mirrors [PocketEngine.chunkPocketByTokens]: split on sentence ends,
     * sub-split on `,;:`, then greedy word packing, then greedy bin-pack.
     */
    private fun chunkPocketByTokens(
        text: String,
        tokenizer: PocketTokenizer,
        bundle: PocketBundle,
        maxTokens: Int,
    ): List<String> {
        val preprocessed = preprocessForPocket(text.trim(), bundle)
        if (preprocessed.isEmpty()) return emptyList()
        val sentences = SENTENCE_SPLIT_REGEX.split(preprocessed)
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return emptyList()

        val segments = ArrayList<String>(sentences.size * 2)
        for (s in sentences) {
            if (tokenizer.encode(s).size <= maxTokens) { segments.add(s); continue }
            val parts = COMMA_SPLIT_REGEX.split(s).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                for (p in parts) {
                    if (tokenizer.encode(p).size <= maxTokens) segments.add(p)
                    else segments.addAll(splitByWordsToTokenLimit(p, tokenizer, maxTokens))
                }
            } else {
                segments.addAll(splitByWordsToTokenLimit(s, tokenizer, maxTokens))
            }
        }

        val chunks = ArrayList<String>(segments.size)
        var cur = StringBuilder()
        var curTokens = 0
        for (seg in segments) {
            val segTokens = tokenizer.encode(seg).size
            if (cur.isEmpty()) { cur.append(seg); curTokens = segTokens; continue }
            if (curTokens + segTokens <= maxTokens) {
                cur.append(' ').append(seg); curTokens += segTokens
            } else {
                chunks.add(cur.toString()); cur = StringBuilder(seg); curTokens = segTokens
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    private fun splitByWordsToTokenLimit(
        text: String,
        tokenizer: PocketTokenizer,
        maxTokens: Int,
    ): List<String> {
        val words = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>(words.size / 5 + 1)
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (tokenizer.encode(candidate).size <= maxTokens) {
                cur = StringBuilder(candidate)
            } else if (cur.isNotEmpty()) {
                out.add(cur.toString()); cur = StringBuilder(w)
            } else {
                out.add(w); cur = StringBuilder()
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    // -- helpers -------------------------------------------------------------

    private fun estimateMaxFrames(bundle: PocketBundle, numTokens: Int): Int {
        val seconds = numTokens / 3.0 + 2.0
        return Math.ceil(seconds * bundle.frameRate).toInt().coerceAtMost(MAX_FRAMES_HARD_CAP)
    }

    private fun framesAfterEosFor(bundle: PocketBundle, originalText: String): Int {
        bundle.modelRecommendedFramesAfterEos?.let { return it + 2 }
        val wordCount = originalText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val base = if (wordCount <= 4) 3 else 1
        return base + 2
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }

    /** Log device-available memory at a labelled point (BUG-2 OOM diagnosis). */
    private fun logMem(label: String) {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        Log.i(TAG, "mem[$label] availMB=${mi.availMem / 1_048_576} lowMemory=${mi.lowMemory}")
    }

    /** Run [block], return its elapsed wall-clock in ms. */
    private inline fun measureMs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    override fun release() {
        try {
            kotlinx.coroutines.runBlocking {
                loadLock.withLock { synthLock.withLock { releaseInternal() } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release() ignored failure: ${t.message}")
        }
    }

    private fun releaseInternal() {
        for (m in listOf(
            textCondModule, mimiEncoderModule, mimiDecoderModule,
            flowLmMainModule, flowLmFlowModule, flowLmCondModule,
        )) {
            try { m?.destroy() } catch (_: Throwable) {}
        }
        textCondModule = null
        mimiEncoderModule = null
        mimiDecoderModule = null
        flowLmMainModule = null
        flowLmFlowModule = null
        flowLmCondModule = null
        voiceEmbeddings.clear()
        bundle = null
        tokenizer = null
        bosBeforeVoice = null
        loaded = false
    }

    companion object {
        private const val TAG = "PocketET"

        /** flow_lm KV layers (6 attention modules → 6 explicit caches). */
        private const val N_LAYERS = 6

        /** KV cache shape per layer: [2, 1, 1024, 16, 64] (cap 1024, heads 16, head-dim 64). */
        private val CACHE_SHAPE = longArrayOf(2, 1, 1024, 16, 64)
        private const val CACHE_ELEMENTS = 2 * 1 * 1024 * 16 * 64 // 2_097_152

        private val EMPTY_FLOATS = FloatArray(0)

        /** Production-faithful constants (see PocketEngine companion). */
        private const val LSD_DECODE_STEPS = 4
        private const val TEMPERATURE = 0.7
        private const val NOISE_CLAMP_ABS = 3.0f
        private const val EOS_THRESHOLD = -4.0f
        private const val MAX_FRAMES_HARD_CAP = 500

        private const val CLONED_VOICE_PREFIX = "cloned-"

        private const val POCKET_MAX_CHARS_PER_CHUNK = 255
        private const val POCKET_MIN_CHARS_PER_CHUNK = 40

        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
        private val COMMA_SPLIT_REGEX = Regex("(?<=[,;:])\\s+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        /** Non-ONNX bundle files shared with production Pocket. */
        private val REQUIRED_NON_ONNX_FILES = listOf(
            "tokenizer.model",
            "bos_before_voice.npy",
            "bundle.json",
        )

        // Side-loaded ExecuTorch graph filenames (adb push into getExternalFilesDir).
        private const val PTE_TEXT_CONDITIONER = "text_conditioner.pte"
        private const val PTE_MIMI_ENCODER = "mimi_encoder.pte"
        private const val PTE_MIMI_DECODER = "mimi_decoder.pte"
        private const val PTE_FLOW_LM_MAIN = "flow_lm_main_int8.pte"
        private const val PTE_FLOW_LM_FLOW = "flow_lm_flow.pte"
        private const val PTE_FLOW_LM_COND = "flow_lm_cond.pte"

        /** .pte that MUST be present for [isInstalled] (flow_lm_cond is optional). */
        private val REQUIRED_PTE_FILES = listOf(
            PTE_TEXT_CONDITIONER,
            PTE_MIMI_ENCODER,
            PTE_MIMI_DECODER,
            PTE_FLOW_LM_MAIN,
            PTE_FLOW_LM_FLOW,
        )
    }
}
