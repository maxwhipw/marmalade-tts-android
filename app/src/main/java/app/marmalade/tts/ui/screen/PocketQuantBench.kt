package app.marmalade.tts.ui.screen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.marmalade.tts.R
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.bindStateInputs
import app.marmalade.tts.engine.pocket.initStates
import app.marmalade.tts.engine.pocket.updateStatesFromResult
import app.marmalade.tts.perf.CpuClusterDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Pocket flow_lm_main quantisation bench (debug-only diagnostic)
// -----------------------------------------------------------------------------
//
// Times ONE thing: the flow_lm_main AR step — the transformer forward that
// dominates Pocket's RTF — across precision variants, isolated from
// flow_lm_flow / mimi / audio plumbing. Values fed are synthetic (random
// conditioning, random latents); inference COST doesn't depend on values,
// and the state lifecycle (manifest init → bind → update-from-outputs per
// step) is exactly PocketEngine's, so per-step growth of the KV attention
// window is realistic.
//
// Side-load dir `${filesDir}/pocket-quant/`:
//
//   adb push bundle.json                → files/pocket-quant/bundle.json
//   adb push flow_lm_main_int8.onnx     → files/pocket-quant/  (optional if
//                                         the Pocket engine is installed)
//   adb push flow_lm_main_qdq_X1.onnx   → files/pocket-quant/
//   adb push flow_lm_main_fp32.onnx     → files/pocket-quant/  (optional)
//
// The int8 baseline and bundle.json fall back to the installed engine dir
// (engines/pocket-tts-en-v2026_04/). Missing variants are skipped.
//
// EP rule (measured on Kokoro, 2026-08-01, commit 2669852): registering the
// XNNPACK EP on a session whose graph contains QDQ nodes is a native SIGSEGV
// in ORT-Android 1.26 session setup — QDQ variants run plain CPU EP only.
// The dynamic-int8 and fp32 variants bench both with and without XNNPACK to
// separate the EP effect from the quantization-format effect.
// -----------------------------------------------------------------------------

object PocketQuantBench {

    private const val TAG = "PocketQuantBench"

    private const val QUANT_DIR = "pocket-quant"
    private const val ENGINE_DIR = "engines/pocket-tts-en-v2026_04"
    private const val RESULTS_FILE = "results.json"

    /** Voice-conditioning frames (phase 1) + text frames (phase 2). */
    private const val VOICE_FRAMES = 51
    private const val TEXT_FRAMES = 30

    /** AR steps per round; the first [WARMUP_STEPS] are excluded from stats. */
    private const val AR_STEPS = 80
    private const val WARMUP_STEPS = 5

    private data class Variant(
        val label: String,
        val fileName: String,
        val useXnnpack: Boolean,
    )

    private val VARIANTS = listOf(
        // Shipping configuration first: dynamic int8 with the XNNPACK EP
        // registered (its MatMulInteger ops fall back to the CPU EP, but the
        // session config matches PocketEngine).
        Variant("int8dyn-xnnpack", "flow_lm_main_int8.onnx", useXnnpack = true),
        Variant("int8dyn-cpu", "flow_lm_main_int8.onnx", useXnnpack = false),
        Variant("qdq-x1-cpu", "flow_lm_main_qdq_X1.onnx", useXnnpack = false),
        Variant("fp32-cpu", "flow_lm_main_fp32.onnx", useXnnpack = false),
        Variant("fp32-xnnpack", "flow_lm_main_fp32.onnx", useXnnpack = true),
    )

    data class VariantResult(
        val label: String,
        val fileName: String,
        val sizeMb: Double,
        /** "ok", "not side-loaded", or a failure message. */
        val status: String,
        val condMs: Long = 0,
        val arMedianMs: Double = 0.0,
        val arP90Ms: Double = 0.0,
        val arSteps: Int = 0,
    )

    /** Cross-instance run guard — same rationale as KokoroQuantBench. */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun run(
        ctx: Context,
        onProgress: (String) -> Unit,
        onVariant: (VariantResult) -> Unit,
    ): String? = withContext(Dispatchers.Default) {
        if (!running.compareAndSet(false, true)) {
            return@withContext ctx.getString(R.string.bench_quant_already_running)
        }
        try {
            runLocked(ctx, onProgress, onVariant)
        } finally {
            running.set(false)
        }
    }

    private fun runLocked(
        ctx: Context,
        onProgress: (String) -> Unit,
        onVariant: (VariantResult) -> Unit,
    ): String? {
        val quantDir = File(ctx.filesDir, QUANT_DIR).apply { mkdirs() }
        val engineDir = File(ctx.filesDir, ENGINE_DIR)

        val bundleFile = File(quantDir, "bundle.json").takeIf { it.isFile }
            ?: File(engineDir, "bundle.json").takeIf { it.isFile }
            ?: return ctx.getString(R.string.pocket_bench_no_bundle)
        val bundle = PocketBundle.load(bundleFile)

        val completed = ArrayList<VariantResult>()
        val emit: (VariantResult) -> Unit = { r ->
            completed.add(r)
            runCatching { persist(File(quantDir, RESULTS_FILE), completed) }
            onVariant(r)
        }

        val threads = CpuClusterDetector.detectPerfCoreCount()
        val ort = OrtEnvironment.getEnvironment()
        Log.i(TAG, "bench start — intraOpThreads=$threads, bundle=${bundleFile.absolutePath}")

        for (variant in VARIANTS) {
            val modelFile = File(quantDir, variant.fileName).takeIf { it.isFile }
                ?: File(engineDir, variant.fileName)
            if (!modelFile.isFile) {
                Log.w(TAG, "${variant.label}: ${variant.fileName} absent — skipped")
                emit(VariantResult(variant.label, variant.fileName, 0.0, "not side-loaded"))
                continue
            }
            val sizeMb = modelFile.length() / 1_048_576.0
            onProgress("${variant.label} (${"%.0f".format(sizeMb)} MB)")
            Log.i(TAG, "=== ${variant.label} — ${modelFile.name}, ${"%.1f".format(sizeMb)} MB ===")

            var session: OrtSession? = null
            try {
                session = ort.createSession(
                    modelFile.absolutePath,
                    buildOptions(threads, variant.useXnnpack),
                )
                val r = benchOne(ort, session, bundle, variant, sizeMb)
                Log.i(
                    TAG,
                    "${variant.label}: cond=${r.condMs}ms arMedian=${"%.1f".format(r.arMedianMs)}ms " +
                        "arP90=${"%.1f".format(r.arP90Ms)}ms steps=${r.arSteps}",
                )
                emit(r)
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.java.simpleName
                Log.e(TAG, "${variant.label} FAILED: $msg", t)
                emit(VariantResult(variant.label, variant.fileName, sizeMb, "FAILED: $msg"))
            } finally {
                runCatching { session?.close() }
            }
        }
        Log.i(TAG, "bench done")
        return null
    }

    /**
     * One variant: conditioning prefill (voice + text shapes) then
     * [AR_STEPS] single-frame steps with the manifest-driven state loop.
     */
    private fun benchOne(
        ort: OrtEnvironment,
        session: OrtSession,
        bundle: PocketBundle,
        variant: Variant,
        sizeMb: Double,
    ): VariantResult {
        val rng = Random(42)
        val state = initStates(bundle.flowLmStateManifest)

        val condStart = System.nanoTime()
        runOnce(
            ort, session, bundle, state,
            seq = FloatArray(0),
            seqShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
            text = randomFloats(rng, VOICE_FRAMES * bundle.conditioningDim),
            textShape = longArrayOf(1, VOICE_FRAMES.toLong(), bundle.conditioningDim.toLong()),
        )
        runOnce(
            ort, session, bundle, state,
            seq = FloatArray(0),
            seqShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
            text = randomFloats(rng, TEXT_FRAMES * bundle.conditioningDim),
            textShape = longArrayOf(1, TEXT_FRAMES.toLong(), bundle.conditioningDim.toLong()),
        )
        val condMs = (System.nanoTime() - condStart) / 1_000_000

        // First AR step feeds NaN (the engine's BOS latent), then random.
        var latent = FloatArray(bundle.latentDim) { Float.NaN }
        val stepMs = ArrayList<Double>(AR_STEPS)
        repeat(AR_STEPS) {
            val t0 = System.nanoTime()
            runOnce(
                ort, session, bundle, state,
                seq = latent,
                seqShape = longArrayOf(1, 1, bundle.latentDim.toLong()),
                text = FloatArray(0),
                textShape = longArrayOf(1, 0, bundle.conditioningDim.toLong()),
            )
            stepMs.add((System.nanoTime() - t0) / 1e6)
            latent = randomFloats(rng, bundle.latentDim)
        }
        val timed = stepMs.drop(WARMUP_STEPS).sorted()
        return VariantResult(
            label = variant.label,
            fileName = variant.fileName,
            sizeMb = sizeMb,
            status = "ok",
            condMs = condMs,
            arMedianMs = timed[timed.size / 2],
            arP90Ms = timed[(timed.size * 9) / 10],
            arSteps = timed.size,
        )
    }

    /** One flow_lm_main call via the same state plumbing PocketEngine uses. */
    private fun runOnce(
        ort: OrtEnvironment,
        session: OrtSession,
        bundle: PocketBundle,
        state: app.marmalade.tts.engine.pocket.PocketStates,
        seq: FloatArray,
        seqShape: LongArray,
        text: FloatArray,
        textShape: LongArray,
    ) {
        val inputs = LinkedHashMap<String, OnnxTensor>(state.size + 2)
        inputs["sequence"] = directFloatTensor(ort, seq, seqShape)
        inputs["text_embeddings"] = directFloatTensor(ort, text, textShape)
        bindStateInputs(ort, bundle.flowLmStateManifest, state, inputs)
        try {
            session.run(inputs).use { result ->
                updateStatesFromResult(bundle.flowLmStateManifest, result, state)
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun randomFloats(rng: Random, n: Int): FloatArray =
        FloatArray(n) { rng.nextGaussian().toFloat() }

    private fun directFloatTensor(
        ort: OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor {
        val buf = ByteBuffer.allocateDirect(maxOf(data.size, 1) * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data)
        buf.rewind()
        buf.limit(data.size)
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    /** Mirrors PocketEngine's session options, EP parameterised. */
    private fun buildOptions(
        threads: Int,
        useXnnpack: Boolean,
    ): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setMemoryPatternOptimization(true)
        if (useXnnpack) try {
            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            addConfigEntry("session.intra_op.allow_spinning", "0")
        } catch (t: Throwable) {
            Log.w(TAG, "XNNPACK EP unavailable; CPU EP only", t)
        }
    }

    /** Results of the previous run, if any — shown when the screen (re)opens. */
    fun loadPersisted(ctx: Context): List<VariantResult> = runCatching {
        val f = File(File(ctx.filesDir, QUANT_DIR), RESULTS_FILE)
        if (!f.isFile) return emptyList()
        val arr = org.json.JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VariantResult(
                label = o.getString("label"),
                fileName = o.getString("fileName"),
                sizeMb = o.getDouble("sizeMb"),
                status = o.getString("status"),
                condMs = o.getLong("condMs"),
                arMedianMs = o.getDouble("arMedianMs"),
                arP90Ms = o.getDouble("arP90Ms"),
                arSteps = o.getInt("arSteps"),
            )
        }
    }.getOrElse { emptyList() }

    private fun persist(file: File, results: List<VariantResult>) {
        val arr = org.json.JSONArray()
        for (r in results) {
            arr.put(
                org.json.JSONObject()
                    .put("label", r.label)
                    .put("fileName", r.fileName)
                    .put("sizeMb", r.sizeMb)
                    .put("status", r.status)
                    .put("condMs", r.condMs)
                    .put("arMedianMs", r.arMedianMs)
                    .put("arP90Ms", r.arP90Ms)
                    .put("arSteps", r.arSteps),
            )
        }
        file.writeText(arr.toString())
    }
}
