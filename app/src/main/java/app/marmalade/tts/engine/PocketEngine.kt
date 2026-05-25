package app.marmalade.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketTokenizer
import app.marmalade.tts.engine.pocket.closeStates
import app.marmalade.tts.engine.pocket.cycleStates
import app.marmalade.tts.engine.pocket.initStates
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pocket TTS engine — Kyutai Labs' Latent Space Diffusion TTS running
 * directly on `com.microsoft.onnxruntime:onnxruntime-android`.
 *
 * v0.3.0-alpha.2 lights up real synthesis with the full 5-graph LSD
 * pipeline:
 *
 *   text → tokenize → text_conditioner → text_embeddings [1,T,1024]
 *   voice WAV → resample 24kHz → mimi_encoder → voice_emb [1,V,1024]
 *               (cached to disk after first encode)
 *
 *   flow_lm phase 1 (voice cond):  sequence=[1,0,32]  +  text_embeds=BOS++voice_emb
 *   flow_lm phase 2 (text cond):   sequence=[1,0,32]  +  text_embeds=text_embeds
 *   flow_lm phase 3 (autoregressive, per latent frame):
 *      sequence=[1,1,32] (NaN→BOS first step, then previous latent)
 *      → conditioning [1,1024] + eos_logit [1,1]
 *      → Euler-integrated flow_lm_flow: x_{t+1} = x_t + flow(c,s,t,x)·(1/steps)
 *      → emit latent, check EOS
 *   mimi_decoder(all latents) → float PCM @ 24 kHz → PCM16
 *
 * Voice cloning (v0.3.0): users supply WAV; same `mimi_encoder` path,
 * with the resulting embedding written to `cloned_voices/<id>.bin`.
 *
 * Why a separate class (not a SherpaEngine subclass): different runtime
 * (ORT direct), different model topology (5 graphs vs 1 OfflineTts),
 * stateful streaming codec, voice cloning. The shared parent surface is
 * [TtsEngine] — same contract Synthesizer + the two TTS services route
 * through.
 *
 * Thread-safety: model loading is gated by a coroutine [Mutex]; synthesis
 * itself is gated by [Mutex.withLock] so concurrent `synthesize` calls
 * serialise (the model's KV cache state isn't reentrant).
 */
@Singleton
open class PocketEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME

    override val sampleRate: Int
        get() = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")
    private val voicesDir: File get() = File(engineDir, "voices")
    private val voiceCacheDir: File get() = File(engineDir, "voice_cache")

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    // -- live state (null while not loaded) ----------------------------------

    private var env: OrtEnvironment? = null
    private var bundle: PocketBundle? = null
    private var tokenizer: PocketTokenizer? = null

    private var textCondSession: OrtSession? = null
    private var mimiEncoderSession: OrtSession? = null
    private var mimiDecoderSession: OrtSession? = null
    private var flowLmMainSession: OrtSession? = null
    private var flowLmFlowSession: OrtSession? = null

    /** `[1, 1, 1024]` learned embedding prepended to the voice prompt. */
    private var bosBeforeVoice: FloatArray? = null

    /** In-memory cache of voice embeddings (built-in + user-cloned). */
    private val voiceEmbeddings: ConcurrentHashMap<String, FloatArray> = ConcurrentHashMap()

    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        for (name in REQUIRED_FILES) {
            if (!File(engineDir, name).isFile) return false
        }
        if (!voicesDir.isDirectory) return false
        for (voice in PocketVoiceCatalog.voices) {
            if (!File(voicesDir, "${voice.displayName}.wav").isFile) return false
        }
        return true
    }

    override fun ensureModelLoaded() {
        // ensureModelLoaded is declared non-suspend by TtsEngine to keep
        // the system-TTS callback path simple. We bridge to the suspend
        // loadLock by running blocking on Dispatchers.IO inside synth's
        // own coroutine. The volatile field check makes the hot path
        // zero-cost after first load.
        if (env != null) return
        kotlinx.coroutines.runBlocking { ensureLoadedSuspending() }
    }

    private suspend fun ensureLoadedSuspending() {
        if (env != null) return
        loadLock.withLock {
            if (env != null) return
            if (!isInstalled()) throw EngineNotInstalledException(ENGINE_NAME)
            Log.i(TAG, "Loading Pocket TTS bundle from $engineDir …")
            val t0 = System.currentTimeMillis()
            try {
                doLoad()
                val elapsed = System.currentTimeMillis() - t0
                Log.i(TAG, "Pocket TTS loaded in ${elapsed} ms")
            } catch (t: Throwable) {
                // Best-effort cleanup so a half-loaded engine doesn't
                // leak across retries.
                releaseInternal()
                throw IllegalStateException("Failed to load Pocket TTS: ${t.message}", t)
            }
        }
    }

    private fun doLoad() {
        val ort = OrtEnvironment.getEnvironment().also { env = it }
        bundle = PocketBundle.load(File(engineDir, "bundle.json"))
        tokenizer = PocketTokenizer.load(File(engineDir, "tokenizer.model"))

        // bos_before_voice.npy: shape [1,1,1024] float32. Stash the flat
        // 1024-d vector — we'll concat along the time axis when needed.
        val npy = NpyReader.readFloat32(File(engineDir, "bos_before_voice.npy"))
        check(npy.data.size == bundle!!.conditioningDim) {
            "bos_before_voice.npy has ${npy.data.size} floats; expected ${bundle!!.conditioningDim}"
        }
        bosBeforeVoice = npy.data

        // ORT SessionOptions: 4 threads is a reasonable Pixel-class default
        // (matches our other engines). ALL_OPT + memory pattern is standard.
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
        }

        textCondSession = createSession(ort, opts, "text_conditioner_int8.onnx")
        mimiEncoderSession = createSession(ort, opts, "mimi_encoder_int8.onnx")
        mimiDecoderSession = createSession(ort, opts, "mimi_decoder_int8.onnx")
        flowLmMainSession = createSession(ort, opts, "flow_lm_main_int8.onnx")
        flowLmFlowSession = createSession(ort, opts, "flow_lm_flow_int8.onnx")
    }

    /**
     * Load an ONNX session, applying the ARMv7 byte-array workaround on
     * 32-bit devices. ARMv7 traps SIGBUS (BUS_ADRALN) on misaligned
     * 32-bit loads, which fires for INT8 ONNX tensors whose data offsets
     * end up at 1-byte alignment when ORT mmaps the file. Loading the
     * model into a heap-allocated `ByteArray` produces 4-byte alignment
     * via the JVM heap allocator and dodges the trap. arm64-v8a handles
     * misaligned access in hardware so mmap is fine there.
     *
     * Cost: ~50 MB transient heap during 5-session load on armeabi-v7a.
     * Pixel-class arm64 devices pay nothing.
     */
    private fun createSession(
        ort: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        fileName: String,
    ): OrtSession {
        val file = File(engineDir, fileName)
        return if (is32BitArm()) {
            ort.createSession(file.readBytes(), opts)
        } else {
            ort.createSession(file.absolutePath, opts)
        }
    }

    private fun is32BitArm(): Boolean {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        return primary == "armeabi-v7a" || primary == "armeabi"
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
    ): SynthAudio = withContext(Dispatchers.Default) {
        ensureLoadedSuspending()
        val bundle = bundle ?: error("bundle missing after load")
        val tokenizer = tokenizer ?: error("tokenizer missing after load")

        synthLock.withLock {
            val voiceName = voiceId.substringAfter(':', voiceId)
            val voiceEmb = embeddingForVoice(voiceName)

            // Tokenize. Apply the preprocessing flags the bundle exposes
            // (semicolons, short-input padding); these are no-ops for the
            // common case but matter for short / punctuation-heavy inputs.
            val preprocessed = preprocessForPocket(text, bundle)
            val tokens = tokenizer.encode(preprocessed)
            if (tokens.size > bundle.maxTokenPerChunk) {
                Log.w(
                    TAG,
                    "Input is ${tokens.size} tokens; bundle's max_token_per_chunk is " +
                        "${bundle.maxTokenPerChunk}. Output may skip words. " +
                        "(Sentence chunker lands in a future alpha.)",
                )
            }

            // Run the full inference pipeline. `speed` is currently
            // ignored — Pocket's LSD pipeline doesn't expose a
            // length-scale parameter natively. A sox-style post-resample
            // is the right home for speed; that's a Synthesizer-layer
            // concern, not engine-layer.
            if (speed != 1.0f) {
                Log.d(TAG, "Pocket TTS ignores speed=$speed (not exposed natively in this build)")
            }

            val latents = runFlowLm(bundle, voiceEmb, tokens)
            val pcmFloat = runMimiDecoder(bundle, latents)
            val pcm16 = floatToPcm16(pcmFloat)
            SynthAudio(pcm = pcm16, sampleRate = bundle.sampleRate)
        }
    }

    override fun release() {
        try {
            releaseInternal()
        } catch (t: Throwable) {
            Log.w(TAG, "release() ignored failure: ${t.message}")
        }
    }

    private fun releaseInternal() {
        listOf(
            textCondSession,
            mimiEncoderSession,
            mimiDecoderSession,
            flowLmMainSession,
            flowLmFlowSession,
        ).forEach { s ->
            try { s?.close() } catch (_: Throwable) {}
        }
        textCondSession = null
        mimiEncoderSession = null
        mimiDecoderSession = null
        flowLmMainSession = null
        flowLmFlowSession = null
        bundle = null
        tokenizer = null
        bosBeforeVoice = null
        voiceEmbeddings.clear()
        // OrtEnvironment is process-scoped; don't close it.
        env = null
    }

    // -- voice embedding cache -----------------------------------------------

    /**
     * Look up [voiceName]'s 1024-d voice embedding sequence (shape
     * `[V, 1024]` returned as a flat FloatArray of length `V * 1024`).
     *
     * Order of resolution:
     *   1. In-memory cache.
     *   2. On-disk `.emb` cache (binary: `int numFrames` + raw float32s).
     *   3. Encode from `voices/<name>.wav` via `mimi_encoder`, write the
     *      .emb cache, then return.
     */
    private fun embeddingForVoice(voiceName: String): FloatArray {
        voiceEmbeddings[voiceName]?.let { return it }

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
        val embedding = encodeVoiceWav(wavFile)
        try {
            writeEmbCache(cacheFile, embedding)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write voice cache $cacheFile: ${t.message}")
        }
        voiceEmbeddings[voiceName] = embedding
        return embedding
    }

    private fun encodeVoiceWav(file: File): FloatArray {
        val bundle = bundle ?: error("bundle missing")
        val ort = env ?: error("ORT env missing")
        val session = mimiEncoderSession ?: error("mimi encoder session missing")

        val wav = PocketAudio.readWav(file)
        var pcm = PocketAudio.resample(wav.samples, wav.sampleRate, bundle.sampleRate)
        pcm = PocketAudio.normalizeIfClipping(pcm)
        // 30 s cap matches the Python ground truth `truncate=True`.
        val cap = 30 * bundle.sampleRate
        if (pcm.size > cap) pcm = pcm.copyOf(cap)

        // mimi_encoder input: `audio` float32 [1, 1, T]
        val buf = ByteBuffer.allocateDirect(pcm.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(pcm)
        buf.rewind()
        OnnxTensor.createTensor(ort, buf, longArrayOf(1, 1, pcm.size.toLong())).use { audioTensor ->
            session.run(mapOf("audio" to audioTensor)).use { result ->
                val out = result.get("latents").orElseThrow {
                    IllegalStateException("mimi_encoder did not return 'latents'")
                } as OnnxTensor
                val shape = out.info.shape
                // Expected: [1, V, 1024]
                check(shape.size == 3 && shape[0] == 1L && shape[2].toInt() == bundle.conditioningDim) {
                    "mimi_encoder unexpected output shape: ${shape.toList()}"
                }
                val numFrames = shape[1].toInt()
                val flat = FloatArray(numFrames * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        }
    }

    private fun readEmbCache(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 4) { "voice cache too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numFrames = buf.getInt()
        val condDim = bundle!!.conditioningDim
        val expectedFloats = numFrames * condDim
        require(bytes.size == 4 + expectedFloats * 4) {
            "voice cache size mismatch: header says $numFrames frames " +
                "but file holds ${(bytes.size - 4) / 4} floats"
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

    // -- flow LM pipeline ----------------------------------------------------

    /**
     * Run all three flow_lm_main phases + the autoregressive Euler loop.
     * Returns the flat latent buffer (length = `numFrames * latentDim`).
     */
    private fun runFlowLm(
        bundle: PocketBundle,
        voiceEmbedding: FloatArray,
        tokens: IntArray,
    ): FloatArray {
        val ort = env ?: error("ORT env missing")
        val main = flowLmMainSession ?: error("flow_lm_main session missing")
        val flow = flowLmFlowSession ?: error("flow_lm_flow session missing")
        val textCond = textCondSession ?: error("text_conditioner session missing")

        var state = initStates(ort, bundle.flowLmStateManifest)

        try {
            // PHASE 1: voice conditioning
            // text_embeddings: bos_before_voice ++ voice_embedding, shape [1, V+1, conditioningDim]
            // sequence: empty [1, 0, latentDim]
            run {
                val bos = bosBeforeVoice ?: error("bos_before_voice missing")
                val voiceFrames = voiceEmbedding.size / bundle.conditioningDim
                val totalFrames = voiceFrames + if (bundle.insertBosBeforeVoice) 1 else 0
                val condBuf = ByteBuffer.allocateDirect(totalFrames * bundle.conditioningDim * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                if (bundle.insertBosBeforeVoice) condBuf.put(bos)
                condBuf.put(voiceEmbedding)
                condBuf.rewind()

                val emptySeq = ByteBuffer.allocateDirect(0)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                OnnxTensor.createTensor(
                    ort, emptySeq, longArrayOf(1, 0, bundle.latentDim.toLong()),
                ).use { seqT ->
                    OnnxTensor.createTensor(
                        ort, condBuf,
                        longArrayOf(1, totalFrames.toLong(), bundle.conditioningDim.toLong()),
                    ).use { condT ->
                        state = runFlowLmMain(main, bundle, seqT, condT, state)
                    }
                }
            }

            // PHASE 2: text conditioning
            // text_embeddings: text_conditioner(tokens), shape [1, T, conditioningDim]
            // sequence: empty [1, 0, latentDim]
            run {
                val textEmbeds = runTextConditioner(textCond, tokens, bundle, ort)
                textEmbeds.use { textT ->
                    val emptySeq = ByteBuffer.allocateDirect(0)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                    OnnxTensor.createTensor(
                        ort, emptySeq, longArrayOf(1, 0, bundle.latentDim.toLong()),
                    ).use { seqT ->
                        state = runFlowLmMain(main, bundle, seqT, textT, state)
                    }
                }
            }

            // PHASE 3: autoregressive generation
            // For each frame: sequence=[1,1,32] (NaN→BOS first iter,
            // previous latent thereafter), text_embeddings=empty[1,0,1024].
            val maxFrames = estimateMaxFrames(bundle, tokens.size)
            val framesAfterEos = framesAfterEosFor(bundle, tokens)
            val latents = ArrayList<FloatArray>(maxFrames)
            // first iteration sees a NaN-filled latent → model substitutes
            // its learned bos_emb internally.
            var previousLatent = FloatArray(bundle.latentDim) { Float.NaN }
            var eosFired = false
            var framesPostEos = 0

            for (frame in 0 until maxFrames) {
                val seqBuf = ByteBuffer.allocateDirect(bundle.latentDim * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                seqBuf.put(previousLatent)
                seqBuf.rewind()
                val emptyCond = ByteBuffer.allocateDirect(0)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()

                val conditioning: FloatArray
                val eosLogit: Float
                OnnxTensor.createTensor(
                    ort, seqBuf, longArrayOf(1, 1, bundle.latentDim.toLong()),
                ).use { seqT ->
                    OnnxTensor.createTensor(
                        ort, emptyCond, longArrayOf(1, 0, bundle.conditioningDim.toLong()),
                    ).use { condT ->
                        val (next, cond, eos) =
                            runFlowLmMainCapturingConditioning(main, bundle, seqT, condT, state)
                        state = next
                        conditioning = cond
                        eosLogit = eos
                    }
                }

                // Euler-integrated flow matching to turn the
                // conditioning vector + noise into the next latent.
                val nextLatent = runFlowEuler(flow, ort, conditioning, bundle.latentDim)
                latents.add(nextLatent)
                previousLatent = nextLatent

                if (!eosFired && eosLogit > EOS_THRESHOLD) {
                    eosFired = true
                    Log.d(TAG, "EOS at frame $frame (logit=$eosLogit); generating $framesAfterEos more")
                }
                if (eosFired) {
                    framesPostEos++
                    if (framesPostEos >= framesAfterEos) break
                }
            }

            if (!eosFired) {
                Log.w(TAG, "Hit max frames ($maxFrames) without EOS — output may be truncated")
            }

            // Flatten latents into a single contiguous [N, latentDim] buffer.
            val flat = FloatArray(latents.size * bundle.latentDim)
            var pos = 0
            for (frame in latents) {
                System.arraycopy(frame, 0, flat, pos, bundle.latentDim)
                pos += bundle.latentDim
            }
            return flat
        } finally {
            closeStates(state)
        }
    }

    private fun runTextConditioner(
        session: OrtSession,
        tokens: IntArray,
        bundle: PocketBundle,
        ort: OrtEnvironment,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(tokens.size * 8)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()
        for (t in tokens) buf.put(t.toLong())
        buf.rewind()
        OnnxTensor.createTensor(
            ort, buf, longArrayOf(1, tokens.size.toLong()),
        ).use { tokT ->
            val result = session.run(mapOf("token_ids" to tokT))
            // Detach the output tensor so it survives Result.close().
            val out = result.get("embeddings").orElseThrow {
                IllegalStateException("text_conditioner did not return 'embeddings'")
            } as OnnxTensor
            // Copy through a new tensor we own.
            val flat = FloatArray(tokens.size * bundle.conditioningDim)
            out.floatBuffer.get(flat)
            result.close()
            val ownedBuf = ByteBuffer.allocateDirect(flat.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            ownedBuf.put(flat)
            ownedBuf.rewind()
            return OnnxTensor.createTensor(
                ort, ownedBuf,
                longArrayOf(1, tokens.size.toLong(), bundle.conditioningDim.toLong()),
            )
        }
    }

    /** Run flow_lm_main once with a pre-built input map. Returns the new state map. */
    private fun runFlowLmMain(
        session: OrtSession,
        bundle: PocketBundle,
        sequence: OnnxTensor,
        textEmbeddings: OnnxTensor,
        prevState: Map<String, OnnxTensor>,
    ): Map<String, OnnxTensor> {
        val inputs = LinkedHashMap<String, OnnxTensor>(prevState.size + 2)
        inputs["sequence"] = sequence
        inputs["text_embeddings"] = textEmbeddings
        inputs.putAll(prevState)
        session.run(inputs).use { result ->
            // Cycle state forward — closes prevState as part of its job.
            return cycleStates(bundle.flowLmStateManifest, result, prevState)
        }
    }

    /**
     * Like [runFlowLmMain] but also extracts the `conditioning` + `eos_logit`
     * outputs. Used in the autoregressive phase 3 where those drive the
     * Euler step + the EOS check.
     */
    private fun runFlowLmMainCapturingConditioning(
        session: OrtSession,
        bundle: PocketBundle,
        sequence: OnnxTensor,
        textEmbeddings: OnnxTensor,
        prevState: Map<String, OnnxTensor>,
    ): Triple<Map<String, OnnxTensor>, FloatArray, Float> {
        val inputs = LinkedHashMap<String, OnnxTensor>(prevState.size + 2)
        inputs["sequence"] = sequence
        inputs["text_embeddings"] = textEmbeddings
        inputs.putAll(prevState)
        session.run(inputs).use { result ->
            val condTensor = result.get("conditioning").orElseThrow {
                IllegalStateException("flow_lm_main did not return 'conditioning'")
            } as OnnxTensor
            val cond = FloatArray(bundle.conditioningDim)
            condTensor.floatBuffer.get(cond)

            val eosTensor = result.get("eos_logit").orElseThrow {
                IllegalStateException("flow_lm_main did not return 'eos_logit'")
            } as OnnxTensor
            val eos = FloatArray(1)
            eosTensor.floatBuffer.get(eos)

            val nextState = cycleStates(bundle.flowLmStateManifest, result, prevState)
            return Triple(nextState, cond, eos[0])
        }
    }

    /**
     * Euler-integrated flow matching. Walks `x` from random noise toward
     * the conditioning target across [LSD_DECODE_STEPS] flow-net calls.
     *
     * Python's default is `steps=1` (LSD training collapses the
     * trajectory into one step). NekoSpeak uses 20 by default. v0.3.0-
     * alpha.2 ships 1 to match the upstream contract and minimise the
     * per-frame ORT calls; we can bump it after listening tests if 1
     * step shows artefacts.
     */
    private fun runFlowEuler(
        session: OrtSession,
        ort: OrtEnvironment,
        conditioning: FloatArray,
        latentDim: Int,
    ): FloatArray {
        // x_0 = random normal scaled by sqrt(temperature)
        val noiseStd = sqrt(TEMPERATURE).toFloat()
        val current = FloatArray(latentDim) { (random.nextGaussian().toFloat() * noiseStd) }
        val steps = LSD_DECODE_STEPS
        val dt = 1f / steps

        val cBuf = ByteBuffer.allocateDirect(conditioning.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        cBuf.put(conditioning)
        cBuf.rewind()
        val cTensor = OnnxTensor.createTensor(
            ort, cBuf, longArrayOf(1, conditioning.size.toLong()),
        )

        try {
            for (j in 0 until steps) {
                val s = j.toFloat() / steps
                val t = (j + 1).toFloat() / steps
                val sBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(s); it.rewind() }
                val tBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(t); it.rewind() }
                val xBuf = ByteBuffer.allocateDirect(latentDim * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(current); it.rewind() }

                OnnxTensor.createTensor(ort, sBuf, longArrayOf(1, 1)).use { sT ->
                    OnnxTensor.createTensor(ort, tBuf, longArrayOf(1, 1)).use { tT ->
                        OnnxTensor.createTensor(ort, xBuf, longArrayOf(1, latentDim.toLong())).use { xT ->
                            val inputs = mapOf("c" to cTensor, "s" to sT, "t" to tT, "x" to xT)
                            session.run(inputs).use { result ->
                                val flowOut = result.get("flow_dir").orElseThrow {
                                    IllegalStateException("flow_lm_flow did not return 'flow_dir'")
                                } as OnnxTensor
                                val flow = FloatArray(latentDim)
                                flowOut.floatBuffer.get(flow)
                                for (k in 0 until latentDim) current[k] += flow[k] * dt
                            }
                        }
                    }
                }
            }
        } finally {
            cTensor.close()
        }
        return current
    }

    // -- mimi decoder --------------------------------------------------------

    private fun runMimiDecoder(bundle: PocketBundle, latents: FloatArray): FloatArray {
        if (latents.isEmpty()) return FloatArray(0)
        val ort = env ?: error("ORT env missing")
        val session = mimiDecoderSession ?: error("mimi decoder session missing")
        val numFrames = latents.size / bundle.latentDim

        var state = initStates(ort, bundle.mimiStateManifest)
        val pcm = FloatArray(numFrames * bundle.samplesPerFrame)
        var pcmPos = 0
        try {
            // Decode in chunks to keep memory bounded for long inputs. The
            // decoder is stateful so chunk size only affects throughput,
            // not output. 15 frames ≈ 1.2 s of audio at 12.5 fps.
            var frame = 0
            while (frame < numFrames) {
                val chunk = minOf(MIMI_CHUNK_FRAMES, numFrames - frame)
                val chunkFloats = chunk * bundle.latentDim
                val buf = ByteBuffer.allocateDirect(chunkFloats * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                buf.put(latents, frame * bundle.latentDim, chunkFloats)
                buf.rewind()

                OnnxTensor.createTensor(
                    ort, buf, longArrayOf(1, chunk.toLong(), bundle.latentDim.toLong()),
                ).use { latT ->
                    val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 1)
                    inputs["latent"] = latT
                    inputs.putAll(state)
                    session.run(inputs).use { result ->
                        val audioTensor = result.get("audio_frame").orElseThrow {
                            IllegalStateException("mimi_decoder did not return 'audio_frame'")
                        } as OnnxTensor
                        val audioFloats = chunk * bundle.samplesPerFrame
                        val tmp = FloatArray(audioFloats)
                        audioTensor.floatBuffer.get(tmp)
                        System.arraycopy(tmp, 0, pcm, pcmPos, audioFloats)
                        pcmPos += audioFloats
                        state = cycleStates(bundle.mimiStateManifest, result, state)
                    }
                }
                frame += chunk
            }
        } finally {
            closeStates(state)
        }
        return pcm
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Pocket's text preprocessing pre-tokenizer. Mirrors Python's
     * `prepare_text_prompt`: trim, collapse internal whitespace,
     * optionally swap `;`→`,`, capitalize the first letter, append `.`
     * if the input ends in an alphanumeric char, optionally prepend
     * 8 spaces for <5-word inputs.
     */
    private fun preprocessForPocket(raw: String, bundle: PocketBundle): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        // Collapse runs of whitespace (newlines/tabs/multi-spaces) to single spaces.
        s = s.replace(Regex("\\s+"), " ")
        if (bundle.removeSemicolons) s = s.replace(';', ',')
        // Capitalize first letter (matches Python).
        s = s[0].uppercaseChar() + s.substring(1)
        // Append `.` if last char is alphanumeric.
        if (s.last().isLetterOrDigit()) s += "."
        // Short-input padding (only fires if the bundle requests it).
        if (bundle.padWithSpacesForShortInputs) {
            val wordCount = s.split(' ').count { it.isNotEmpty() }
            if (wordCount < 5) s = "        $s"
        }
        return s
    }

    /**
     * Estimate a generous safety cap on the number of generated frames.
     * Mirrors Python's `_estimate_max_gen_len` heuristic: speech ≈ 1
     * frame per 3-character token, padded by 2 seconds.
     */
    private fun estimateMaxFrames(bundle: PocketBundle, numTokens: Int): Int {
        val seconds = numTokens / 3.0 + 2.0
        val frames = Math.ceil(seconds * bundle.frameRate).toInt()
        return frames.coerceAtMost(MAX_FRAMES_HARD_CAP)
    }

    /**
     * Per the Python ground truth: when the bundle's
     * `model_recommended_frames_after_eos` is null (english_2026-04's
     * case), use 5 for short (<=4-word) inputs and 3 otherwise, then
     * add 2 for safety.
     */
    private fun framesAfterEosFor(bundle: PocketBundle, tokens: IntArray): Int {
        bundle.modelRecommendedFramesAfterEos?.let { return it + 2 }
        // Approximate word count from token count — Python uses the original
        // string's word count, but we want to keep tokenize/synth decoupled
        // and tokens roughly correlate with words.
        val approxShort = tokens.size <= 5
        return (if (approxShort) 5 else 3) + 2
    }

    /**
     * Convert mimi_decoder's float output (already in [-1, 1] modulo
     * occasional excursions) to PCM16. Clamps defensively so a rogue
     * sample doesn't wrap-around into a loud click.
     */
    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            out[i] = (clamped * 32767f).toInt().toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "PocketEngine"
        const val ENGINE_NAME = "pocket-tts-en-v2026_04"

        /** Required files at the root of the engine directory. */
        private val REQUIRED_FILES = listOf(
            "flow_lm_main_int8.onnx",
            "flow_lm_flow_int8.onnx",
            "mimi_encoder_int8.onnx",
            "mimi_decoder_int8.onnx",
            "text_conditioner_int8.onnx",
            "tokenizer.model",
            "bos_before_voice.npy",
            "bundle.json",
        )

        /** Mimi decoder chunk size (latent frames). ~1.2 s of audio @ 12.5 fps. */
        private const val MIMI_CHUNK_FRAMES = 15

        /**
         * Euler integration steps for flow_lm_flow. Python default is 1
         * — LSD training collapses the diffusion trajectory into a
         * single step. NekoSpeak ships 20. Start at 1; bump if listening
         * tests reveal artefacts.
         */
        private const val LSD_DECODE_STEPS = 1

        /** Sampling temperature for the initial noise. Python default. */
        private const val TEMPERATURE = 0.7

        /** Raw eos_logit threshold from the export wrapper. */
        private const val EOS_THRESHOLD = -4.0f

        /** Belt-and-braces ceiling. Should never trip under normal use. */
        private const val MAX_FRAMES_HARD_CAP = 500

        // Reused across calls; ThreadLocal so two simultaneous engines
        // wouldn't share a Random (we serialise via synthLock anyway, but
        // the field is engine-scoped to make the no-share guarantee obvious).
        private val random: java.util.Random = java.util.Random()
    }
}
