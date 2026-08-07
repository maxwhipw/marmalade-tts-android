package app.marmalade.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketTokenizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * P-AD — clean-room Pocket TTS engine. Deliberately stripped of every
 * perf optimization in [PocketEngine] so it can serve as a diagnostic
 * reference when production Pocket produces audio artefacts.
 *
 * Installs as a separate engine ([engineDir] resolves to
 * `engines/pocket-tts-en-v2026_04-dev/`). Same archive payload as
 * production Pocket but parallel install — install/uninstall
 * independently, A/B them side-by-side via two aliases.
 *
 * Differences from production [PocketEngine] (all intentional):
 *  - No `setMemoryPatternOptimization(true)` — default ORT optimization only.
 *  - No `setExecutionMode` / `setOptimizationLevel` overrides.
 *  - No XNNPACK execution provider.
 *  - No pinned outputs / `session.run(inputs, requested, pinned)`.
 *  - No `setMemoryPatternOptimization`. Default ORT settings.
 *  - Single intra-op thread (cleanest baseline; eliminates thread-pool variability).
 *  - No persistent direct ByteBuffers. State, scratch — everything heap-backed via
 *    `FloatBuffer.wrap(...)`. Accepts the JNI memcpy hit each call.
 *  - No engine-level state cache. Fresh state map allocated per chunk.
 *  - No engine-level scratch (`mainSeqBuf`, etc.). Fresh per AR step.
 *  - No mimi state reuse across chunks (upstream re-init's per chunk; production also
 *    does after the P-B revert, but the engine-level field is still there). Strictly
 *    per-chunk fresh `init_states`.
 *  - No chunking sub-loop inside mimi (`MIMI_CHUNK_FRAMES`); whole chunk decoded in
 *    one `mimi_decoder.run`.
 *  - No mimi cold-start trim, no fade-in, no noise clamp. Upstream defaults.
 *  - No async warmup. Cold start eats the first synth's wall clock honestly.
 *  - No streaming pre-roll / pipelined decode. Strict sequential: AR fills the
 *    whole chunk → mimi decodes once → emit. (Streaming is per-text-chunk, not
 *    per-frame.)
 *
 * What's faithfully ported from upstream `pocket_tts/models/tts_model.py`:
 *  - `split_into_best_sentences` sentence-aware chunker.
 *  - `prepare_text_prompt` preprocessing (capitalize first, trailing dot, etc.).
 *  - Per-chunk AR with NaN backbone_input at frame 0 (model substitutes BOS).
 *  - LSD Euler with `x_0 ~ N(0, sqrt(temp))`, `LSD_DECODE_STEPS=1`, temp=0.7.
 *  - `frames_after_eos`: 3 if word-count ≤4, else 1, then +2 safety per Python.
 *  - Voice conditioning via bos_before_voice ++ mimi_encoder(voice.wav).
 */
@Singleton
class PocketDevEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : TtsEngine {

    override val engineName: String = PocketDevVoiceCatalog.ENGINE

    override val sampleRate: Int
        get() = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE

    /** Same as production Pocket — handle our own chunking via [splitIntoBestSentences]. */
    override val maxInputChars: Int = Int.MAX_VALUE

    /** Own install dir — parallel to production Pocket. */
    private val engineDir: File get() = File(ctx.filesDir, "engines/$engineName")
    private val voicesDir: File get() = File(engineDir, "voices")

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    private var env: OrtEnvironment? = null
    private var bundle: PocketBundle? = null
    private var tokenizer: PocketTokenizer? = null
    private var bosBeforeVoice: FloatArray? = null

    private var textCondSession: OrtSession? = null
    private var mimiEncoderSession: OrtSession? = null
    private var mimiDecoderSession: OrtSession? = null
    private var flowLmMainSession: OrtSession? = null
    private var flowLmFlowSession: OrtSession? = null

    /** Built-in voice embeddings, lazily encoded on first use. */
    private val voiceEmbeddings = ConcurrentHashMap<String, FloatArray>()

    /**
     * Per-process RNG for LSD initial noise. java.util.Random is fine here —
     * upstream uses torch's default RNG which is similarly process-shared.
     */
    private val random = Random()

    override fun isInstalled(): Boolean {
        // Mirror production Pocket's install check, since we share its bundle.
        if (!engineDir.isDirectory) return false
        if (!File(engineDir, "bundle.json").isFile) return false
        if (!File(engineDir, "tokenizer.model").isFile) return false
        if (!File(engineDir, "bos_before_voice.npy").isFile) return false
        val spec = runCatching { PocketBundle.load(File(engineDir, "bundle.json")) }
            .getOrNull() ?: return false
        val o = spec.onnxFiles
        for (n in listOf(o.textConditioner, o.mimiEncoder, o.mimiDecoder, o.flowLmMain, o.flowLmFlow)) {
            if (!File(engineDir, n).isFile) return false
        }
        if (!voicesDir.isDirectory) return false
        for (v in PocketVoiceCatalog.voices) {
            if (!File(voicesDir, "${v.displayName}.wav").isFile) return false
        }
        return true
    }

    /** Same [env] marker [ensureModelLoaded]'s unlocked fast path reads. */
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
            Log.i(TAG, "Loading PocketDev (clean reference) from $engineDir …")
            val t0 = System.currentTimeMillis()
            try {
                doLoad()
                Log.i(TAG, "PocketDev loaded in ${System.currentTimeMillis() - t0} ms")
            } catch (t: Throwable) {
                releaseInternal()
                throw IllegalStateException("Failed to load PocketDev: ${t.message}", t)
            }
        }
    }

    private fun doLoad() {
        val ort = OrtEnvironment.getEnvironment().also { env = it }
        bundle = PocketBundle.load(File(engineDir, "bundle.json"))
        tokenizer = PocketTokenizer.load(File(engineDir, "tokenizer.model"))

        val npy = NpyReader.readFloat32(File(engineDir, "bos_before_voice.npy"))
        check(npy.data.size == bundle!!.conditioningDim)
        bosBeforeVoice = npy.data

        // Minimal session options — NO setMemoryPatternOptimization, NO
        // execution-mode tweaks, NO XNNPACK. Just defaults + single thread.
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }

        val files = bundle!!.onnxFiles
        textCondSession = createSession(ort, opts, files.textConditioner)
        mimiEncoderSession = createSession(ort, opts, files.mimiEncoder)
        mimiDecoderSession = createSession(ort, opts, files.mimiDecoder)
        flowLmMainSession = createSession(ort, opts, files.flowLmMain)
        flowLmFlowSession = createSession(ort, opts, files.flowLmFlow)
    }

    private fun createSession(ort: OrtEnvironment, opts: OrtSession.SessionOptions, name: String): OrtSession {
        val file = File(engineDir, name)
        require(file.isFile) { "Missing ONNX file: $name" }
        return ort.createSession(file.absolutePath, opts)
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        val chunks = mutableListOf<ShortArray>()
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect { audio ->
            chunks.add(audio.pcm)
        }
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size); pos += c.size
        }
        SynthAudio(pcm = out, sampleRate = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE)
    }

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): Flow<SynthAudio> = flow {
        ensureLoadedSuspending()
        synthLock.withLock {
            val bundle = bundle!!
            val tokenizer = tokenizer!!
            val voiceName = voiceNameOf(voiceId)
            val voiceEmb = encodeVoiceIfNeeded(voiceName)

            val chunks = splitIntoBestSentences(tokenizer, text, bundle.maxTokenPerChunk, bundle)
            Log.i(TAG, "PocketDev: ${chunks.size} chunk(s) for input of ${text.length} chars")

            for ((i, chunk) in chunks.withIndex()) {
                val pcm = synthesizeChunk(chunk, voiceEmb, chunkIndex = i)
                Log.d(TAG, "PocketDev: chunk $i (${chunk.length} chars) → ${pcm.size} samples")
                emit(SynthAudio(pcm = floatToPcm16(pcm), sampleRate = bundle.sampleRate))
            }
        }
    }.flowOn(Dispatchers.Default)

    // -- chunk synthesis: phase 1 + 2 + AR + decode (all fresh per chunk) ----

    private fun synthesizeChunk(rawText: String, voiceEmb: FloatArray, chunkIndex: Int): FloatArray {
        val bundle = bundle!!
        val tokenizer = tokenizer!!
        val ort = env!!
        val main = flowLmMainSession!!
        val flow = flowLmFlowSession!!
        val textCond = textCondSession!!
        val mimi = mimiDecoderSession!!

        val preprocessed = preparePrompt(rawText, bundle)
        val tokens = tokenizer.encode(preprocessed)
        if (tokens.isEmpty()) return FloatArray(0)

        // Fresh state per chunk — upstream-faithful (the decode worker also
        // init_states's a fresh mimi per chunk, see tts_model.py:_decode_audio_worker).
        val state = initState(bundle.flowLmStateManifest)

        // PHASE 1 — voice conditioning. text_embeddings = bos ++ voice_emb.
        val bos = bosBeforeVoice!!
        val voiceFrames = voiceEmb.size / bundle.conditioningDim
        val totalFrames = voiceFrames + if (bundle.insertBosBeforeVoice) 1 else 0
        val phase1 = FloatArray(totalFrames * bundle.conditioningDim).also {
            var pos = 0
            if (bundle.insertBosBeforeVoice) {
                System.arraycopy(bos, 0, it, pos, bos.size); pos += bos.size
            }
            System.arraycopy(voiceEmb, 0, it, pos, voiceEmb.size)
        }
        runFlowLmMain(
            main, ort, bundle, state,
            seqData = FloatArray(0),
            seqShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
            textData = phase1,
            textShape = longArrayOf(1, totalFrames.toLong(), bundle.conditioningDim.toLong()),
            captureConditioning = false,
        )

        // PHASE 2 — text conditioning.
        val textEmbeds = runTextConditioner(textCond, ort, tokens, bundle)
        runFlowLmMain(
            main, ort, bundle, state,
            seqData = FloatArray(0),
            seqShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
            textData = textEmbeds,
            textShape = longArrayOf(1, tokens.size.toLong(), bundle.conditioningDim.toLong()),
            captureConditioning = false,
        )

        // PHASE 3 — AR loop. backbone_input starts as NaN (model substitutes BOS).
        val latents = ArrayList<FloatArray>(64)
        var previousLatent = FloatArray(bundle.latentDim) { Float.NaN }
        val maxFrames = estimateMaxFrames(bundle, tokens.size)
        val framesAfterEos = framesAfterEosFor(bundle, rawText)
        var eosFired = false
        var eosFrame = -1
        var framesPostEos = 0
        // P-AD diagnostics: track the EOS-logit trajectory + latent magnitude
        // so a glitchy chunk's logcat tells us WHY it went bad. Adversarial
        // reviewers' two leading theories make distinct predictions here:
        //   (1) EOS-failure → over-generation: eosFired=false, frames==maxFrames.
        //   (2) bad noise draw → AR divergence: peak latent magnitude blows up.
        var maxEosLogit = Float.NEGATIVE_INFINITY
        var peakLatentAbs = 0f
        var sawNonFinite = false
        for (frame in 0 until maxFrames) {
            val capture = runFlowLmMain(
                main, ort, bundle, state,
                seqData = previousLatent,
                seqShape = longArrayOf(1, 1, bundle.latentDim.toLong()),
                textData = FloatArray(0),
                textShape = longArrayOf(1, 0, bundle.conditioningDim.toLong()),
                captureConditioning = true,
            )!!
            if (capture.eosLogit > maxEosLogit) maxEosLogit = capture.eosLogit
            val nextLatent = runFlowEuler(flow, ort, capture.conditioning, bundle.latentDim)
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

        // Per-chunk summary. WARN when EOS never fired (the over-generation
        // failure mode the reviewers flagged) or when a non-finite latent
        // appeared (AR divergence). INFO otherwise.
        val summary = "PocketDev chunk $chunkIndex: frames=${latents.size}/$maxFrames " +
            "eosFired=$eosFired@$eosFrame " +
            "maxEosLogit=${"%.2f".format(maxEosLogit)} " +
            "peakLatentAbs=${"%.2f".format(peakLatentAbs)} nonFinite=$sawNonFinite " +
            "text=\"${rawText.take(48)}\""
        if (!eosFired || sawNonFinite) {
            Log.w(TAG, "SUSPECT $summary")
        } else {
            Log.i(TAG, summary)
        }

        if (latents.isEmpty()) return FloatArray(0)

        // MIMI DECODE — fresh state per chunk, whole chunk in one call.
        val mimiState = initState(bundle.mimiStateManifest)
        val flat = FloatArray(latents.size * bundle.latentDim).also { arr ->
            var pos = 0
            for (l in latents) {
                System.arraycopy(l, 0, arr, pos, bundle.latentDim); pos += bundle.latentDim
            }
        }
        return runMimiDecoder(mimi, ort, bundle, mimiState, flat, latents.size)
    }

    // -- ONNX invocations ----------------------------------------------------

    private data class FlowLmCapture(val conditioning: FloatArray, val eosLogit: Float)

    /**
     * flow_lm_main one call. Plain `session.run(inputs)` — no pinned
     * outputs, no 3-arg variant. Heap arrays wrapped via FloatBuffer.wrap.
     */
    private fun runFlowLmMain(
        session: OrtSession,
        ort: OrtEnvironment,
        bundle: PocketBundle,
        state: MutableMap<String, StateEntry>,
        seqData: FloatArray,
        seqShape: LongArray,
        textData: FloatArray,
        textShape: LongArray,
        captureConditioning: Boolean,
    ): FlowLmCapture? {
        val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 2)
        val created = ArrayList<OnnxTensor>(state.size + 2)
        inputs["sequence"] = floatTensor(ort, seqData, seqShape).also { created.add(it) }
        inputs["text_embeddings"] = floatTensor(ort, textData, textShape).also { created.add(it) }
        bindStateInputs(ort, state, bundle.flowLmStateManifest, inputs, created)

        try {
            session.run(inputs).use { result ->
                val capture = if (captureConditioning) {
                    val condT = result.get("conditioning").orElseThrow {
                        IllegalStateException("flow_lm_main missing 'conditioning'")
                    } as OnnxTensor
                    val cond = FloatArray(bundle.conditioningDim)
                    condT.floatBuffer.get(cond)
                    val eosT = result.get("eos_logit").orElseThrow {
                        IllegalStateException("flow_lm_main missing 'eos_logit'")
                    } as OnnxTensor
                    FlowLmCapture(conditioning = cond, eosLogit = eosT.floatBuffer.get(0))
                } else null
                updateStateFromResult(bundle.flowLmStateManifest, result, state)
                return capture
            }
        } finally {
            for (t in created) try { t.close() } catch (_: Throwable) {}
        }
    }

    private fun runTextConditioner(
        session: OrtSession,
        ort: OrtEnvironment,
        tokens: IntArray,
        bundle: PocketBundle,
    ): FloatArray {
        val longs = LongArray(tokens.size) { tokens[it].toLong() }
        val t = OnnxTensor.createTensor(ort, LongBuffer.wrap(longs), longArrayOf(1, tokens.size.toLong()))
        try {
            session.run(mapOf("token_ids" to t)).use { result ->
                val out = result.get("embeddings").orElseThrow {
                    IllegalStateException("text_conditioner missing 'embeddings'")
                } as OnnxTensor
                val flat = FloatArray(tokens.size * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        } finally {
            try { t.close() } catch (_: Throwable) {}
        }
    }

    /**
     * LSD Euler. Upstream `flow_lm.py:101-110`:
     *   std = temp**0.5
     *   x = normal(mean=0, std=std)
     *   for i in 0..num_steps: x += flow_net(c, s, t, x) / num_steps
     *
     * Defaults from `default_parameters.py`: LSD_DECODE_STEPS=1, TEMPERATURE=0.7.
     * noise_clamp default None — we don't clamp either.
     */
    private fun runFlowEuler(
        session: OrtSession,
        ort: OrtEnvironment,
        conditioning: FloatArray,
        latentDim: Int,
    ): FloatArray {
        val noiseStd = sqrt(TEMPERATURE).toFloat()
        val current = FloatArray(latentDim) { random.nextGaussian().toFloat() * noiseStd }
        val dt = 1f / LSD_DECODE_STEPS

        val cT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(conditioning),
            longArrayOf(1, conditioning.size.toLong()))
        try {
            for (j in 0 until LSD_DECODE_STEPS) {
                val s = j.toFloat() / LSD_DECODE_STEPS
                val t = (j + 1).toFloat() / LSD_DECODE_STEPS
                val sT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(floatArrayOf(s)), longArrayOf(1, 1))
                val tT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1, 1))
                val xT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(current.copyOf()),
                    longArrayOf(1, latentDim.toLong()))
                try {
                    session.run(mapOf("c" to cT, "s" to sT, "t" to tT, "x" to xT)).use { result ->
                        val flowOut = result.get("flow_dir").orElseThrow {
                            IllegalStateException("flow_lm_flow missing 'flow_dir'")
                        } as OnnxTensor
                        val flow = FloatArray(latentDim)
                        flowOut.floatBuffer.get(flow)
                        for (k in 0 until latentDim) current[k] += flow[k] * dt
                    }
                } finally {
                    try { sT.close() } catch (_: Throwable) {}
                    try { tT.close() } catch (_: Throwable) {}
                    try { xT.close() } catch (_: Throwable) {}
                }
            }
        } finally {
            try { cT.close() } catch (_: Throwable) {}
        }
        return current
    }

    /**
     * mimi_decoder: whole chunk in one call. No sub-chunking, no
     * pipelining. Upstream `_decode_audio_worker` decodes per-latent inside
     * its worker; our exported graph batches all frames in a single call.
     */
    private fun runMimiDecoder(
        session: OrtSession,
        ort: OrtEnvironment,
        bundle: PocketBundle,
        state: MutableMap<String, StateEntry>,
        flatLatents: FloatArray,
        numFrames: Int,
    ): FloatArray {
        val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 1)
        val created = ArrayList<OnnxTensor>(state.size + 1)
        // Graph input is `latent` (singular) and output is `audio_frame`
        // — matches the production PocketEngine.decodeMimiChunk names.
        inputs["latent"] = floatTensor(ort, flatLatents,
            longArrayOf(1, numFrames.toLong(), bundle.latentDim.toLong())).also { created.add(it) }
        bindStateInputs(ort, state, bundle.mimiStateManifest, inputs, created)

        try {
            session.run(inputs).use { result ->
                val out = result.get("audio_frame").orElseThrow {
                    IllegalStateException("mimi_decoder missing 'audio_frame'")
                } as OnnxTensor
                val pcm = FloatArray(numFrames * bundle.samplesPerFrame)
                out.floatBuffer.get(pcm)
                updateStateFromResult(bundle.mimiStateManifest, result, state)
                return pcm
            }
        } finally {
            for (t in created) try { t.close() } catch (_: Throwable) {}
        }
    }

    // -- voice encoding ------------------------------------------------------

    private fun voiceNameOf(voiceId: String): String {
        val sep = voiceId.indexOf(':')
        return if (sep > 0) voiceId.substring(sep + 1) else voiceId
    }

    private fun encodeVoiceIfNeeded(name: String): FloatArray {
        voiceEmbeddings[name]?.let { return it }
        val bundle = bundle!!
        val ort = env!!
        val session = mimiEncoderSession!!
        val file = File(voicesDir, "$name.wav")
        require(file.isFile) { "Missing voice WAV: $name" }
        val wav = PocketAudio.readWav(file)
        var pcm = PocketAudio.resample(wav.samples, wav.sampleRate, bundle.sampleRate)
        pcm = PocketAudio.normalizeIfClipping(pcm)
        val cap = 30 * bundle.sampleRate
        if (pcm.size > cap) pcm = pcm.copyOf(cap)

        val t = OnnxTensor.createTensor(ort, FloatBuffer.wrap(pcm), longArrayOf(1, 1, pcm.size.toLong()))
        try {
            session.run(mapOf("audio" to t)).use { result ->
                val out = result.get("latents").orElseThrow {
                    IllegalStateException("mimi_encoder missing 'latents'")
                } as OnnxTensor
                val shape = out.info.shape
                check(shape.size == 3 && shape[0] == 1L && shape[2].toInt() == bundle.conditioningDim)
                val flat = FloatArray(shape[1].toInt() * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                voiceEmbeddings[name] = flat
                return flat
            }
        } finally {
            try { t.close() } catch (_: Throwable) {}
        }
    }

    // -- state management (heap-backed, fresh per call) ---------------------

    /**
     * State entry. Heap-backed — `data` is FloatArray | LongArray | ByteArray
     * matching the slot's dtype. Shape mutates when an out_state tensor
     * comes back larger (length-tracker slots grow as frames generate).
     */
    private class StateEntry(
        val dtype: PocketBundle.StateDtype,
        var shape: LongArray,
        var data: Any,
    )

    private fun initState(manifest: List<PocketBundle.StateSpec>): MutableMap<String, StateEntry> {
        val out = LinkedHashMap<String, StateEntry>(manifest.size)
        for (spec in manifest) {
            val n = elementCount(spec.shape)
            val data: Any = when (spec.dtype) {
                PocketBundle.StateDtype.FLOAT32 -> FloatArray(maxOf(n, 0)).also { arr ->
                    when (spec.fill) {
                        PocketBundle.StateFill.NAN -> arr.fill(Float.NaN)
                        PocketBundle.StateFill.ZEROS, PocketBundle.StateFill.EMPTY -> {}
                        PocketBundle.StateFill.ONES -> arr.fill(1f)
                    }
                }
                PocketBundle.StateDtype.INT64 -> LongArray(maxOf(n, 0)).also { arr ->
                    if (spec.fill == PocketBundle.StateFill.ONES) arr.fill(1L)
                }
                PocketBundle.StateDtype.BOOL -> ByteArray(maxOf(n, 0)).also { arr ->
                    if (spec.fill == PocketBundle.StateFill.ONES) arr.fill(1)
                }
            }
            out[spec.inputName] = StateEntry(spec.dtype, spec.shape.copyOf(), data)
        }
        return out
    }

    private fun bindStateInputs(
        ort: OrtEnvironment,
        state: MutableMap<String, StateEntry>,
        manifest: List<PocketBundle.StateSpec>,
        inputs: MutableMap<String, OnnxTensor>,
        created: MutableList<OnnxTensor>,
    ) {
        for (spec in manifest) {
            val entry = state[spec.inputName]
                ?: error("State missing for ${spec.inputName}")
            val t = when (spec.dtype) {
                PocketBundle.StateDtype.FLOAT32 -> {
                    val arr = entry.data as FloatArray
                    // ORT requires a backing buffer even for empty shapes;
                    // provide a 1-element dummy if the array is empty.
                    val buf = if (arr.isNotEmpty()) FloatBuffer.wrap(arr) else FloatBuffer.allocate(1)
                    OnnxTensor.createTensor(ort, buf, entry.shape)
                }
                PocketBundle.StateDtype.INT64 -> {
                    val arr = entry.data as LongArray
                    val buf = if (arr.isNotEmpty()) LongBuffer.wrap(arr) else LongBuffer.allocate(1)
                    OnnxTensor.createTensor(ort, buf, entry.shape)
                }
                PocketBundle.StateDtype.BOOL -> {
                    val arr = entry.data as ByteArray
                    val src = if (arr.isNotEmpty()) arr else ByteArray(1)
                    val bb = ByteBuffer.wrap(src).order(ByteOrder.nativeOrder())
                    OnnxTensor.createTensor(ort, bb, entry.shape, ai.onnxruntime.OnnxJavaType.BOOL)
                }
            }
            inputs[spec.inputName] = t
            created.add(t)
        }
    }

    private fun updateStateFromResult(
        manifest: List<PocketBundle.StateSpec>,
        result: OrtSession.Result,
        state: MutableMap<String, StateEntry>,
    ) {
        for (spec in manifest) {
            val out = result.get(spec.outputName).orElseThrow {
                IllegalStateException("Missing output ${spec.outputName}")
            } as OnnxTensor
            val entry = state[spec.inputName]!!
            val outShape = out.info.shape
            val outElements = elementCount(outShape)
            when (spec.dtype) {
                PocketBundle.StateDtype.FLOAT32 -> {
                    var arr = entry.data as FloatArray
                    if (outElements > arr.size) arr = FloatArray(outElements)
                    if (outElements > 0) out.floatBuffer.get(arr, 0, outElements)
                    entry.data = arr
                }
                PocketBundle.StateDtype.INT64 -> {
                    var arr = entry.data as LongArray
                    if (outElements > arr.size) arr = LongArray(outElements)
                    if (outElements > 0) out.longBuffer.get(arr, 0, outElements)
                    entry.data = arr
                }
                PocketBundle.StateDtype.BOOL -> {
                    var arr = entry.data as ByteArray
                    if (outElements > arr.size) arr = ByteArray(outElements)
                    if (outElements > 0) {
                        val src = out.byteBuffer
                        src.position(0); src.limit(outElements)
                        src.get(arr, 0, outElements)
                    }
                    entry.data = arr
                }
            }
            entry.shape = outShape.copyOf()
        }
    }

    private fun elementCount(shape: LongArray): Int {
        var n = 1L
        for (d in shape) {
            if (d <= 0L) return 0
            n *= d
            if (n > Int.MAX_VALUE) error("State tensor exceeds Int.MAX_VALUE elements")
        }
        return n.toInt()
    }

    // -- text preprocessing + chunking (faithful upstream port) -------------

    /**
     * Mirrors `prepare_text_prompt` (tts_model.py:750-768).
     */
    private fun preparePrompt(raw: String, bundle: PocketBundle): String {
        // P-AG — fold smart punctuation (curly quotes/apostrophes, dashes,
        // ellipsis) to ASCII before tokenizing. The vocab has only a
        // straight `'` piece; a curly apostrophe (mobile autocorrect) would
        // byte-fall-back and render as a stumble/pause in contractions.
        var s = normalizeSmartPunctuation(raw)
            .replace('\n', ' ').replace('\r', ' ').replace("  ", " ").trim()
        if (s.isEmpty()) return s
        if (bundle.removeSemicolons) s = s.replace(';', ',')
        if (!s[0].isUpperCase()) s = s[0].uppercaseChar() + s.substring(1)
        if (s.last().isLetterOrDigit()) s += "."
        if (bundle.padWithSpacesForShortInputs && s.split(' ').count { it.isNotEmpty() } < 5) {
            s = "        $s"
        }
        return s
    }

    /** Fold smart punctuation to ASCII — see PocketEngine for rationale. */
    private fun normalizeSmartPunctuation(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    '‘', '’', '‚', '‛', '′', 'ʼ' -> '\''
                    '“', '”', '„', '‟', '″' -> '"'
                    '–', '—', '‒', '―' -> '-'
                    ' ', ' ', ' ' -> ' '
                    '…' -> '.'
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    /**
     * String-level analogue of upstream `split_into_best_sentences`
     * (tts_model.py:793-859). Upstream splits on tokenized punctuation,
     * we split directly on the punctuation characters — equivalent for
     * the bundles we ship since their tokenizer keeps `.!?,;:` as
     * standalone single-char pieces. Token count is measured per
     * candidate via [PocketTokenizer.encode] so the bin-pack uses the
     * same metric as upstream.
     */
    private fun splitIntoBestSentences(
        tokenizer: PocketTokenizer,
        text: String,
        maxTokens: Int,
        bundle: PocketBundle,
    ): List<String> {
        val prepared = preparePrompt(text, bundle).trim()
        if (prepared.isEmpty()) return emptyList()

        // Tier 1: split on sentence-ending punctuation.
        val sentences = SENTENCE_SPLIT_REGEX.split(prepared).filter { it.isNotBlank() }
        // Tier 2: each sentence's token count; if too long, sub-split on `,;:`.
        val refined = ArrayList<Pair<Int, String>>()
        for (s in sentences) {
            val sTrim = s.trim()
            val nb = tokenizer.encode(sTrim).size
            if (nb <= maxTokens) {
                refined.add(nb to sTrim)
            } else {
                val subs = CLAUSE_SPLIT_REGEX.split(sTrim).filter { it.isNotBlank() }
                if (subs.size > 1) {
                    for (sub in subs) {
                        val st = sub.trim()
                        refined.add(tokenizer.encode(st).size to st)
                    }
                } else {
                    refined.add(nb to sTrim) // can't split further — let it through
                }
            }
        }

        // Bin-pack: greedy concat as long as the running token total stays ≤ maxTokens.
        val chunks = ArrayList<String>()
        var cur = StringBuilder()
        var curTokens = 0
        for ((nb, seg) in refined) {
            if (cur.isEmpty()) {
                cur.append(seg); curTokens = nb; continue
            }
            if (curTokens + nb > maxTokens) {
                chunks.add(cur.toString().trim()); cur = StringBuilder(seg); curTokens = nb
            } else {
                cur.append(' ').append(seg); curTokens += nb
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString().trim())
        return chunks
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

    private fun floatTensor(ort: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        val buf = if (data.isEmpty()) FloatBuffer.allocate(1) else FloatBuffer.wrap(data)
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return out
    }

    override fun release() {
        kotlinx.coroutines.runBlocking {
            loadLock.withLock { synthLock.withLock { releaseInternal() } }
        }
    }

    private fun releaseInternal() {
        for (s in listOf(textCondSession, mimiEncoderSession, mimiDecoderSession,
                flowLmMainSession, flowLmFlowSession)) {
            try { s?.close() } catch (_: Throwable) {}
        }
        textCondSession = null
        mimiEncoderSession = null
        mimiDecoderSession = null
        flowLmMainSession = null
        flowLmFlowSession = null
        voiceEmbeddings.clear()
        bundle = null
        tokenizer = null
        bosBeforeVoice = null
        env = null
    }

    companion object {
        private const val TAG = "PocketDev"

        /** Upstream `pocket_tts/default_parameters.py:DEFAULT_LSD_DECODE_STEPS`. */
        private const val LSD_DECODE_STEPS = 1

        /** Upstream `pocket_tts/default_parameters.py:DEFAULT_TEMPERATURE`. */
        private const val TEMPERATURE = 0.7

        /** From the export wrapper — raw eos_logit threshold. */
        private const val EOS_THRESHOLD = -4.0f

        /** Belt-and-braces — should never trip under normal use. */
        private const val MAX_FRAMES_HARD_CAP = 500

        /** Sentence-ending punctuation followed by whitespace. Keeps the punctuation with the sentence. */
        private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?])\s+""")
        /** Clause separators (comma, semicolon, colon) followed by whitespace. */
        private val CLAUSE_SPLIT_REGEX = Regex("""(?<=[,;:])\s+""")
    }
}
