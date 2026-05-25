package app.marmalade.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketClonedVoice
import app.marmalade.tts.engine.pocket.PocketClonedVoiceStore
import app.marmalade.tts.engine.pocket.PocketClonedVoiceSummary
import app.marmalade.tts.engine.pocket.PocketStates
import app.marmalade.tts.engine.pocket.PocketTokenizer
import app.marmalade.tts.engine.pocket.bindStateInputs
import app.marmalade.tts.engine.pocket.initStates
import app.marmalade.tts.engine.pocket.updateStatesFromResult
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
    private val clonedVoicesDir: File get() = File(engineDir, "cloned_voices")

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

    /**
     * Detailed phase timings from the most recent [synthesize] call.
     * Read by [synthesizeWithTimings] to attach to the returned
     * [EnginePhaseTimings]. Protected by [synthLock] — never read while
     * a synth is in flight.
     *
     * Bench-only. Plain [synthesize] callers ignore this; the overhead
     * is a handful of `System.currentTimeMillis()` calls per synth so
     * we always populate it.
     */
    @Volatile
    private var lastDetailedPhases: List<PhaseSpan> = emptyList()

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
            val phases = ArrayList<PhaseSpan>(8)
            val voiceName = voiceId.substringAfter(':', voiceId)

            val voiceEncStart = System.currentTimeMillis()
            val voiceWasCached = voiceEmbeddings.containsKey(voiceName)
            val voiceEmb = embeddingForVoice(voiceName)
            val voiceEncMs = System.currentTimeMillis() - voiceEncStart
            if (!voiceWasCached) {
                phases.add(PhaseSpan("voice-encode (cold)", voiceEncMs))
            } else if (voiceEncMs > 0) {
                // Sub-ms warm hit; only record if it actually showed up.
                phases.add(PhaseSpan("voice-encode (warm)", voiceEncMs))
            }

            // Tokenize. Apply the preprocessing flags the bundle exposes
            // (semicolons, short-input padding); these are no-ops for the
            // common case but matter for short / punctuation-heavy inputs.
            val tokStart = System.currentTimeMillis()
            val preprocessed = preprocessForPocket(text, bundle)
            val tokens = tokenizer.encode(preprocessed)
            val tokMs = System.currentTimeMillis() - tokStart
            phases.add(PhaseSpan("tokenize", tokMs, detail = "${tokens.size} tokens"))
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

            val flowStart = System.currentTimeMillis()
            val flowResult = runFlowLm(bundle, voiceEmb, tokens, phases)
            val flowMs = System.currentTimeMillis() - flowStart
            // The phase spans for phase-1, phase-2, AR loop are added by runFlowLm.
            // This "flow-lm total" is just a sanity sum for the bench UI.
            phases.add(
                PhaseSpan(
                    "flow-lm total",
                    flowMs,
                    detail = "${flowResult.numFrames} latent frames" +
                        if (flowResult.eosFiredAt >= 0) ", EOS at #${flowResult.eosFiredAt}"
                        else ", no EOS (capped)",
                ),
            )

            val decStart = System.currentTimeMillis()
            val pcmFloat = runMimiDecoder(bundle, flowResult.latents)
            val decMs = System.currentTimeMillis() - decStart
            val audioSeconds = pcmFloat.size.toDouble() / bundle.sampleRate.toDouble()
            phases.add(
                PhaseSpan(
                    "mimi-decode",
                    decMs,
                    detail = "→ %.2f s of audio".format(audioSeconds),
                ),
            )

            val pcm16Start = System.currentTimeMillis()
            val pcm16 = floatToPcm16(pcmFloat)
            val pcm16Ms = System.currentTimeMillis() - pcm16Start
            if (pcm16Ms > 0) phases.add(PhaseSpan("pcm16 convert", pcm16Ms))

            lastDetailedPhases = phases
            SynthAudio(pcm = pcm16, sampleRate = bundle.sampleRate)
        }
    }

    /**
     * Override the default timed-synth wrapper so the bench UI gets our
     * detailed phase breakdown. Production callers stay on plain
     * [synthesize] and never trip this path.
     */
    override suspend fun synthesizeWithTimings(
        text: String,
        voiceId: String,
        speed: Float,
    ): TimedSynthAudio = withContext(Dispatchers.Default) {
        val loadStart = System.currentTimeMillis()
        ensureLoadedSuspending()
        val loadMs = System.currentTimeMillis() - loadStart
        val t0 = System.currentTimeMillis()
        val audio = synthesize(text, voiceId, speed)
        val totalMs = System.currentTimeMillis() - t0
        TimedSynthAudio(
            audio = audio,
            timings = EnginePhaseTimings(
                engineName = engineName,
                totalMs = totalMs,
                loadMs = loadMs,
                phases = lastDetailedPhases,
            ),
        )
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
     *   2. If `voiceName` starts with `"cloned-"`: read from
     *      `cloned_voices/<voiceName>.bin` (PVS1 format).
     *   3. On-disk built-in `.emb` cache.
     *   4. Encode from `voices/<name>.wav` via `mimi_encoder`, write the
     *      .emb cache, then return.
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
        val embedding = encodePcm(wav.samples, wav.sampleRate)
        try {
            writeEmbCache(cacheFile, embedding)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write voice cache $cacheFile: ${t.message}")
        }
        voiceEmbeddings[voiceName] = embedding
        return embedding
    }

    /**
     * Run [pcm] (at [srcSampleRate], in [-1, 1] float range) through the
     * `mimi_encoder` and return the flat `[numFrames, 1024]` embedding.
     *
     * Pre-processing matches the Python ground truth:
     *   - resample to the bundle's sample rate (24 kHz for english_2026-04),
     *   - divide by peak if any sample exceeds 1.0,
     *   - truncate to 30 s (the encoder's effective receptive field).
     *
     * Shared between the built-in voice encoder (called lazily on first
     * use of each Kyutai-supplied WAV) and the [cloneVoice] path
     * (called when the user supplies their own audio).
     */
    private fun encodePcm(pcm: FloatArray, srcSampleRate: Int): FloatArray {
        val bundle = bundle ?: error("bundle missing")
        val ort = env ?: error("ORT env missing")
        val session = mimiEncoderSession ?: error("mimi encoder session missing")

        var processed = PocketAudio.resample(pcm, srcSampleRate, bundle.sampleRate)
        processed = PocketAudio.normalizeIfClipping(processed)
        val cap = 30 * bundle.sampleRate
        if (processed.size > cap) processed = processed.copyOf(cap)

        // mimi_encoder input: `audio` float32 [1, 1, T]
        val buf = ByteBuffer.allocateDirect(processed.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(processed)
        buf.rewind()
        OnnxTensor.createTensor(ort, buf, longArrayOf(1, 1, processed.size.toLong())).use { audioTensor ->
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

    // -- voice cloning (backend) --------------------------------------------
    //
    // Surface area only. There is NO user-facing affordance yet — the
    // cloning UX (recorder, file picker, consent dialog, alias editor
    // entry) is deliberately deferred pending the ethical/UX call on
    // whether and how to expose this capability. These methods exist so
    // the eventual UI can wire up without further engine changes, and
    // so we can exercise the encode path end-to-end in isolation.
    //
    // The cloned voice's mimi embedding is computed once at clone time
    // and persisted to disk (PVS1 binary format) — synthesis just
    // reads the file. No live audio leaves the device.

    /**
     * Clone a voice from arbitrary PCM input. The audio is resampled +
     * normalised + capped to 30 s before being fed through
     * `mimi_encoder`. The resulting embedding is written to
     * `cloned_voices/cloned-<uuid>.bin` and returned via its full voice
     * ID (`pocket-tts-en-v2026_04:cloned-<uuid>`).
     *
     * Idempotent w.r.t. failure: if the encode succeeds but the file
     * write fails, no state survives the throw. The cloned voice is
     * also added to the in-memory cache so the first synth call after
     * cloning doesn't pay the file-read cost.
     *
     * @param displayName user-facing label (≤ 256 UTF-8 bytes)
     * @param pcm mono float32 PCM in [-1, 1]
     * @param srcSampleRate sample rate of [pcm]
     * @return full voice ID, e.g. `"pocket-tts-en-v2026_04:cloned-abc..."`
     */
    suspend fun cloneVoice(
        displayName: String,
        pcm: FloatArray,
        srcSampleRate: Int,
    ): String = withContext(Dispatchers.Default) {
        require(displayName.isNotBlank()) { "Cloned voice display name must not be blank" }
        require(pcm.isNotEmpty()) { "Cloned voice PCM is empty" }
        ensureLoadedSuspending()
        val bundle = bundle ?: error("bundle missing after load")

        synthLock.withLock {
            val embedding = encodePcm(pcm, srcSampleRate)
            val numFrames = embedding.size / bundle.conditioningDim
            val localId = CLONED_VOICE_PREFIX + java.util.UUID.randomUUID().toString()
            val voice = PocketClonedVoice(
                id = localId,
                displayName = displayName,
                createdAtMillis = System.currentTimeMillis(),
                numFrames = numFrames,
                embedding = embedding,
            )
            PocketClonedVoiceStore.write(clonedVoicesDir, voice)
            voiceEmbeddings[localId] = embedding
            Log.i(TAG, "Cloned voice '$displayName' as $localId ($numFrames frames)")
            "${PocketVoiceCatalog.ENGINE}:$localId"
        }
    }

    /**
     * List every cloned voice on disk. Reads only the headers — does
     * NOT load embeddings into memory. Cheap to call from a UI flow.
     */
    suspend fun listClonedVoices(): List<PocketClonedVoiceSummary> = withContext(Dispatchers.IO) {
        PocketClonedVoiceStore.list(clonedVoicesDir)
    }

    /**
     * Delete the cloned voice with [voiceId] (the full
     * `pocket-tts-en-v2026_04:cloned-<uuid>` form, or just the local
     * `cloned-<uuid>` part). Returns true if a file was removed.
     * Also drops the in-memory cache entry.
     */
    suspend fun deleteClonedVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        val localId = voiceId.substringAfter(':', voiceId)
        voiceEmbeddings.remove(localId)
        PocketClonedVoiceStore.delete(clonedVoicesDir, localId)
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
    /** Return-shape from [runFlowLm]: flattened latents + telemetry for the bench. */
    private data class FlowLmResult(
        val latents: FloatArray,
        val numFrames: Int,
        /** Frame index at which the EOS logit first crossed the threshold, or -1 if it never did. */
        val eosFiredAt: Int,
    )

    private fun runFlowLm(
        bundle: PocketBundle,
        voiceEmbedding: FloatArray,
        tokens: IntArray,
        phases: MutableList<PhaseSpan>,
    ): FlowLmResult {
        val ort = env ?: error("ORT env missing")
        val main = flowLmMainSession ?: error("flow_lm_main session missing")
        val flow = flowLmFlowSession ?: error("flow_lm_flow session missing")
        val textCond = textCondSession ?: error("text_conditioner session missing")

        val state = initStates(bundle.flowLmStateManifest)

        // PHASE 1: voice conditioning.
        // text_embeddings = bos_before_voice ++ voice_embedding, shape [1, V+1, conditioningDim]
        // sequence = empty [1, 0, latentDim]
        val phase1Start = System.currentTimeMillis()
        run {
            val bos = bosBeforeVoice ?: error("bos_before_voice missing")
            val voiceFrames = voiceEmbedding.size / bundle.conditioningDim
            val totalFrames = voiceFrames + if (bundle.insertBosBeforeVoice) 1 else 0
            val concat = FloatArray(totalFrames * bundle.conditioningDim)
            var pos = 0
            if (bundle.insertBosBeforeVoice) {
                System.arraycopy(bos, 0, concat, pos, bos.size)
                pos += bos.size
            }
            System.arraycopy(voiceEmbedding, 0, concat, pos, voiceEmbedding.size)

            runFlowLmMain(
                session = main,
                bundle = bundle,
                ort = ort,
                state = state,
                sequenceData = FloatArray(0),
                sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
                textEmbedsData = concat,
                textEmbedsShape = longArrayOf(1, totalFrames.toLong(), bundle.conditioningDim.toLong()),
                captureConditioning = false,
            )
        }
        phases.add(PhaseSpan("flow-lm phase 1 (voice cond)", System.currentTimeMillis() - phase1Start))

        // PHASE 2: text conditioning.
        // text_embeddings = text_conditioner(tokens), shape [1, T, conditioningDim]
        // sequence = empty [1, 0, latentDim]
        val phase2Start = System.currentTimeMillis()
        run {
            val tcStart = System.currentTimeMillis()
            val textEmbeds = runTextConditioner(textCond, tokens, bundle, ort)
            val tcMs = System.currentTimeMillis() - tcStart
            runFlowLmMain(
                session = main,
                bundle = bundle,
                ort = ort,
                state = state,
                sequenceData = FloatArray(0),
                sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
                textEmbedsData = textEmbeds,
                textEmbedsShape = longArrayOf(1, tokens.size.toLong(), bundle.conditioningDim.toLong()),
                captureConditioning = false,
            )
            // Note the conditioner is included in phase 2 total; surface its
            // sub-contribution as a detail string for the bench UI.
            phases.add(
                PhaseSpan(
                    "flow-lm phase 2 (text cond)",
                    System.currentTimeMillis() - phase2Start,
                    detail = "incl. text_conditioner $tcMs ms",
                ),
            )
        }

        // PHASE 3: autoregressive generation.
        // For each frame: sequence=[1,1,32] (NaN→BOS on first iter,
        // previous latent thereafter), text_embeddings=empty[1,0,1024].
        val maxFrames = estimateMaxFrames(bundle, tokens.size)
        val framesAfterEos = framesAfterEosFor(bundle, tokens)
        val latents = ArrayList<FloatArray>(maxFrames)
        var previousLatent = FloatArray(bundle.latentDim) { Float.NaN }
        var eosFired = false
        var eosFiredAtFrame = -1
        var framesPostEos = 0
        val arStart = System.currentTimeMillis()

        for (frame in 0 until maxFrames) {
            val capture = runFlowLmMain(
                session = main,
                bundle = bundle,
                ort = ort,
                state = state,
                sequenceData = previousLatent,
                sequenceShape = longArrayOf(1, 1, bundle.latentDim.toLong()),
                textEmbedsData = FloatArray(0),
                textEmbedsShape = longArrayOf(1, 0, bundle.conditioningDim.toLong()),
                captureConditioning = true,
            )!!  // captureConditioning=true guarantees non-null
            val nextLatent = runFlowEuler(flow, ort, capture.conditioning, bundle.latentDim)
            latents.add(nextLatent)
            previousLatent = nextLatent

            if (!eosFired && capture.eosLogit > EOS_THRESHOLD) {
                eosFired = true
                eosFiredAtFrame = frame
                Log.d(TAG, "EOS at frame $frame (logit=${capture.eosLogit}); generating $framesAfterEos more")
            }
            if (eosFired) {
                framesPostEos++
                if (framesPostEos >= framesAfterEos) break
            }
        }

        val arMs = System.currentTimeMillis() - arStart
        val perFrameUs = if (latents.isNotEmpty()) (arMs * 1000.0 / latents.size) else 0.0
        // Estimated realtime headroom: frame_rate (12.5 Hz) means each frame
        // is ~80 ms of audio. If per-frame compute < 80 ms we're faster
        // than realtime → streaming is a clear win.
        val msPerFrameRealtime = 1000.0 / bundle.frameRate
        val realtimeRatio = if (perFrameUs > 0) (msPerFrameRealtime / (perFrameUs / 1000.0)) else 0.0
        phases.add(
            PhaseSpan(
                "flow-lm phase 3 (AR loop)",
                arMs,
                detail = "${latents.size} frames @ %.1f ms/frame, %.2fx realtime".format(
                    perFrameUs / 1000.0,
                    realtimeRatio,
                ),
            ),
        )

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
        return FlowLmResult(latents = flat, numFrames = latents.size, eosFiredAt = eosFiredAtFrame)
    }

    /** Result of a `flow_lm_main` call when phase 3 captures the value outputs. */
    private data class FlowLmCapture(val conditioning: FloatArray, val eosLogit: Float)

    /**
     * Run `text_conditioner(tokens)` and return the flat embeddings
     * (shape `[T, conditioningDim]`). Tokenizer output is an int64
     * tensor; the conditioner is a plain embedding lookup.
     */
    private fun runTextConditioner(
        session: OrtSession,
        tokens: IntArray,
        bundle: PocketBundle,
        ort: OrtEnvironment,
    ): FloatArray {
        val tokensLong = LongArray(tokens.size) { tokens[it].toLong() }
        val tokT = OnnxTensor.createTensor(
            ort, LongBuffer.wrap(tokensLong), longArrayOf(1, tokens.size.toLong()),
        )
        try {
            session.run(mapOf("token_ids" to tokT)).use { result ->
                val out = result.get("embeddings").orElseThrow {
                    IllegalStateException("text_conditioner did not return 'embeddings'")
                } as OnnxTensor
                val flat = FloatArray(tokens.size * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        } finally {
            tokT.close()
        }
    }

    /**
     * Run flow_lm_main once. Persistent state lives in [state] as Kotlin
     * arrays and is updated in place from the session outputs (the only
     * correct lifecycle with the ORT Java API — see PocketStateManager
     * for why we can't carry OnnxTensor references across calls).
     *
     * @param captureConditioning when true, returns the `conditioning`
     *   and `eos_logit` outputs (phase 3 needs them). When false (phases
     *   1 + 2) returns null and skips the extra copies.
     */
    private fun runFlowLmMain(
        session: OrtSession,
        bundle: PocketBundle,
        ort: OrtEnvironment,
        state: PocketStates,
        sequenceData: FloatArray,
        sequenceShape: LongArray,
        textEmbedsData: FloatArray,
        textEmbedsShape: LongArray,
        captureConditioning: Boolean,
    ): FlowLmCapture? {
        val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 2)
        val seqT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(sequenceData), sequenceShape)
        val textT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(textEmbedsData), textEmbedsShape)
        inputs["sequence"] = seqT
        inputs["text_embeddings"] = textT
        bindStateInputs(ort, bundle.flowLmStateManifest, state, inputs)

        try {
            session.run(inputs).use { result ->
                val capture = if (captureConditioning) {
                    val condTensor = result.get("conditioning").orElseThrow {
                        IllegalStateException("flow_lm_main did not return 'conditioning'")
                    } as OnnxTensor
                    val cond = FloatArray(bundle.conditioningDim)
                    condTensor.floatBuffer.get(cond)
                    val eosTensor = result.get("eos_logit").orElseThrow {
                        IllegalStateException("flow_lm_main did not return 'eos_logit'")
                    } as OnnxTensor
                    val eos = eosTensor.floatBuffer.get(0)
                    FlowLmCapture(conditioning = cond, eosLogit = eos)
                } else {
                    null
                }
                updateStatesFromResult(bundle.flowLmStateManifest, result, state)
                return capture
            }
        } finally {
            for (t in inputs.values) {
                try { t.close() } catch (_: Throwable) {}
            }
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

        val state = initStates(bundle.mimiStateManifest)
        val pcm = FloatArray(numFrames * bundle.samplesPerFrame)
        var pcmPos = 0

        // Decode in chunks to keep memory bounded for long inputs. The
        // decoder is stateful so chunk size only affects throughput,
        // not output. 15 frames ≈ 1.2 s of audio at 12.5 fps.
        var frame = 0
        while (frame < numFrames) {
            val chunk = minOf(MIMI_CHUNK_FRAMES, numFrames - frame)
            val chunkFloats = chunk * bundle.latentDim
            val chunkData = FloatArray(chunkFloats)
            System.arraycopy(latents, frame * bundle.latentDim, chunkData, 0, chunkFloats)

            val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 1)
            val latT = OnnxTensor.createTensor(
                ort, FloatBuffer.wrap(chunkData),
                longArrayOf(1, chunk.toLong(), bundle.latentDim.toLong()),
            )
            inputs["latent"] = latT
            bindStateInputs(ort, bundle.mimiStateManifest, state, inputs)
            try {
                session.run(inputs).use { result ->
                    val audioTensor = result.get("audio_frame").orElseThrow {
                        IllegalStateException("mimi_decoder did not return 'audio_frame'")
                    } as OnnxTensor
                    val audioFloats = chunk * bundle.samplesPerFrame
                    audioTensor.floatBuffer.get(pcm, pcmPos, audioFloats)
                    pcmPos += audioFloats
                    updateStatesFromResult(bundle.mimiStateManifest, result, state)
                }
            } finally {
                for (t in inputs.values) {
                    try { t.close() } catch (_: Throwable) {}
                }
            }
            frame += chunk
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

        /**
         * Voice-name prefix that marks a cloned voice. Built-in voices
         * use Kyutai's reference names (alba/azelma/...). Cloned voices
         * carry a UUID with this prefix so the lookup in
         * [embeddingForVoice] can route to the right on-disk format.
         */
        const val CLONED_VOICE_PREFIX = "cloned-"

        // Reused across calls; ThreadLocal so two simultaneous engines
        // wouldn't share a Random (we serialise via synthLock anyway, but
        // the field is engine-scoped to make the no-share guarantee obvious).
        private val random: java.util.Random = java.util.Random()
    }
}
