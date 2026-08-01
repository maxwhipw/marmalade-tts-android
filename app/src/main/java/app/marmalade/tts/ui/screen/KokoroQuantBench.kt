package app.marmalade.tts.ui.screen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.marmalade.tts.engine.kitten.PAD_TOKEN
import app.marmalade.tts.engine.kitten.encodePhonemes
import app.marmalade.tts.perf.CpuClusterDetector
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Kokoro quantisation bench (debug-only diagnostic)
// -----------------------------------------------------------------------------
//
// Measures on-device inference speed of several Kokoro ONNX precision
// variants through the SAME ORT configuration KokoroDirectEngine uses
// (XNNPACK EP, perf-core intra-op threads, memory-pattern optimisation),
// so the numbers are comparable with what the shipping engine sees.
//
// Baseline (Q0) is the installed engine's own fp32 model. The quantised
// candidates are side-loaded by hand into `${filesDir}/kokoro-quant/`:
//
//   adb push model_fp16.onnx      → files/kokoro-quant/model_fp16.onnx
//   adb push model_uint8f16.onnx  → files/kokoro-quant/model_uint8f16.onnx
//   adb push model_q8f16.onnx     → files/kokoro-quant/model_q8f16.onnx
//   adb push model_quantized.onnx → files/kokoro-quant/model_quantized.onnx
//
// Any variant that isn't present is reported as "not side-loaded" and
// skipped; the baseline always runs.
//
// This file deliberately duplicates a little of KokoroDirectEngine's
// session/tensor plumbing rather than reaching into it — the engine's
// helpers are private, and a diagnostic must be free to vary the session
// options (see the q8f16 crash guard below) without touching ship code.
// -----------------------------------------------------------------------------

object KokoroQuantBench {

    private const val TAG = "KokoroQuantBench"

    private const val SAMPLE_RATE = 24_000
    private const val STYLE_DIM = 256
    private const val MAX_TOKEN_LEN = 510

    /** `af_heart` — speaker row 3 of voices.bin (see KokoroDirectVoiceCatalog). */
    private const val SPEAKER_AF_HEART = 3

    private const val WARMUP_RUNS = 1
    private const val TIMED_RUNS = 3

    private const val ENGINE_DIR = "engines/kokoro-direct-v1_0"
    private const val QUANT_DIR = "kokoro-quant"

    // -------------------------------------------------------------------------
    // Bench inputs
    // -------------------------------------------------------------------------
    // Phoneme strings produced by misaki G2P (2026-08-01) for the four
    // desktop bench texts, embedded verbatim so the desktop and device runs
    // are input-identical — the device has espeak, not misaki, so
    // re-phonemizing here would silently change the token sequence and make
    // the two sets of numbers incomparable.
    //
    //   plain      "The quarterly report shows steady growth…"
    //   expressive "Wait... you actually finished the whole thing?…"
    //   numbers    "The train departs at 6:45 AM from platform 12…"
    //   tongue     "She sells seashells by the seashore…"
    // -------------------------------------------------------------------------

    private const val PHONEMES_PLAIN =
        "ðə kwˈɔɹTəɹli ɹəpˈɔɹt ʃˈOz stˈɛdi ɡɹˈOθ əkɹˈɔs ˈɔl θɹˈi ɹˈiʤᵊnz, " +
            "wɪð pəɹtˈɪkjələɹli stɹˈɔŋ ɹəzˈʌlts ɪn ðə nˈɔɹðəɹn dˈɪstɹɪkt."

    private const val PHONEMES_EXPRESSIVE =
        "wˈAt... ju ˈækʧəwəli fˈɪnəʃt ðə hˈOl θˈɪŋ? ðˈæts ɪnkɹˈɛdəbᵊl! " +
            "ˌI ˈɑnəstli dˈɪdᵊnt θˈɪŋk ɪt kʊd bi dˈʌn."

    private const val PHONEMES_NUMBERS =
        "ðə tɹˈAn dəpˈɑɹts æt sˈɪks fˈɔɹTi fˈIv ˌAˈɛm fɹʌm plˈætfˌɔɹm twˈɛlv, " +
            "əɹˈIvɪŋ ɪn ɹˈʌfli tˈu ˈWəɹz ænd θˈɜɹTi mˈɪnəts, ˌɔn mˈɑɹʧ θˈɜɹd, " +
            "twˈɛnti twˈɛnti sˈɪks."

    private const val PHONEMES_TONGUE =
        "ʃˌi sˈɛlz sˈiʃˌɛlz bI ðə sˈiʃˌɔɹ, ænd ðə ʃˈɛlz ʃi sˈɛlz ɑɹ ʃˈʊɹli sˈiʃˌɛlz."

    private val TEXTS: List<Pair<String, String>> = listOf(
        "plain" to PHONEMES_PLAIN,
        "expressive" to PHONEMES_EXPRESSIVE,
        "numbers" to PHONEMES_NUMBERS,
        "tongue" to PHONEMES_TONGUE,
    )

    /** The text whose audio is written to disk for pull-and-listen checks. */
    private const val WAV_TEXT = "tongue"

    // -------------------------------------------------------------------------
    // Variant table
    // -------------------------------------------------------------------------
    //
    // ORDER MATTERS. On desktop x86 (ORT 1.28) the graph fusion of
    // model_q8f16.onnx at ALL_OPT is a native SIGSEGV — uncatchable from
    // Kotlin, it takes the whole process with it. ARM / ORT 1.26 behaviour is
    // unknown, so q8f16 is benched twice: once at BASIC_OPT (known-safe, gives
    // us a usable number) and once at ALL_OPT as the VERY LAST item, after
    // every other result has already been logged and pushed to the UI. If it
    // crashes there, nothing is lost.

    private data class Variant(
        val label: String,
        val fileName: String,
        /** Null → the installed engine's own model dir (the fp32 baseline). */
        val quantFile: Boolean,
        val optLevel: OrtSession.SessionOptions.OptLevel,
    )

    private val VARIANTS = listOf(
        Variant("q0-fp32-baseline", "model.onnx", quantFile = false, optLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT),
        // The installed baseline is a different fp32 export (310 MB respin) than
        // the onnx-community family (326 MB) — side-load their fp32 too for a
        // same-export-family comparison. Optional like the rest.
        Variant("fp32-oc", "model.onnx", quantFile = true, optLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT),
        Variant("fp16", "model_fp16.onnx", quantFile = true, optLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT),
        Variant("uint8f16", "model_uint8f16.onnx", quantFile = true, optLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT),
        Variant("quantized", "model_quantized.onnx", quantFile = true, optLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT),
        Variant("q8f16-basic", "model_q8f16.onnx", quantFile = true, optLevel = OrtSession.SessionOptions.OptLevel.BASIC_OPT),
        // Crash guard: ALL_OPT q8f16 runs dead last, on purpose.
        Variant("q8f16-all", "model_q8f16.onnx", quantFile = true, optLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT),
    )

    // -------------------------------------------------------------------------
    // Results
    // -------------------------------------------------------------------------

    data class TextTiming(
        val textName: String,
        val tokenCount: Int,
        val medianMs: Long,
        val audioSeconds: Double,
        /** compute / audio. Lower is faster; < 1.0 is faster than realtime. */
        val rtf: Double,
    )

    data class VariantResult(
        val label: String,
        val fileName: String,
        val sizeMb: Double,
        /** "ok", "not side-loaded", or a failure message. */
        val status: String,
        val timings: List<TextTiming> = emptyList(),
        val meanRtf: Double = 0.0,
    )

    /**
     * Run the whole bench. Emits each [VariantResult] through [onVariant] as
     * soon as that variant finishes (results are never batched to the end —
     * see the q8f16 crash guard), and short status strings through
     * [onProgress] for the "currently running" line.
     *
     * Returns a fatal error string when the bench can't start at all
     * (Kokoro Direct not installed), else null.
     */
    suspend fun run(
        ctx: Context,
        onProgress: (String) -> Unit,
        onVariant: (VariantResult) -> Unit,
    ): String? = withContext(Dispatchers.Default) {
        val engineDir = File(ctx.filesDir, ENGINE_DIR)
        val voicesFile = File(engineDir, "voices.bin")
        if (!voicesFile.isFile) {
            val msg = "Kokoro Direct not installed (${voicesFile.absolutePath} missing)"
            Log.w(TAG, msg)
            return@withContext msg
        }
        val quantDir = File(ctx.filesDir, QUANT_DIR).apply { mkdirs() }

        val threads = CpuClusterDetector.detectPerfCoreCount()
        val ort = OrtEnvironment.getEnvironment()
        val styleView = mmapFloats(voicesFile)
        Log.i(TAG, "bench start — intraOpThreads=$threads, quantDir=${quantDir.absolutePath}")

        // Encode every text once; the token sequence is variant-independent.
        val encoded = TEXTS.map { (name, phonemes) -> name to encodePhonemes(phonemes) }

        for (variant in VARIANTS) {
            val modelFile = if (variant.quantFile) {
                File(quantDir, variant.fileName)
            } else {
                File(engineDir, variant.fileName)
            }
            if (!modelFile.isFile) {
                val r = VariantResult(variant.label, variant.fileName, 0.0, "not side-loaded")
                Log.w(TAG, "${variant.label}: ${modelFile.absolutePath} absent — skipped")
                onVariant(r)
                continue
            }
            val sizeMb = modelFile.length() / 1_048_576.0
            onProgress("${variant.label} (${"%.0f".format(sizeMb)} MB)")
            Log.i(TAG, "=== ${variant.label} — ${modelFile.name}, ${"%.1f".format(sizeMb)} MB, opt=${variant.optLevel} ===")

            var session: OrtSession? = null
            try {
                session = ort.createSession(modelFile.absolutePath, buildOptions(threads, variant.optLevel))
                val timings = ArrayList<TextTiming>(encoded.size)
                for ((name, ids) in encoded) {
                    onProgress("${variant.label} · $name")
                    repeat(WARMUP_RUNS) { runOnce(ort, session, ids, styleView) }
                    val samples = ArrayList<Long>(TIMED_RUNS)
                    var lastAudio = FloatArray(0)
                    repeat(TIMED_RUNS) {
                        val t0 = System.nanoTime()
                        lastAudio = runOnce(ort, session, ids, styleView)
                        samples.add((System.nanoTime() - t0) / 1_000_000)
                    }
                    samples.sort()
                    val medianMs = samples[samples.size / 2]
                    val audioSeconds = lastAudio.size.toDouble() / SAMPLE_RATE
                    val rtf = if (audioSeconds > 0.0) (medianMs / 1000.0) / audioSeconds else 0.0
                    timings.add(TextTiming(name, ids.size, medianMs, audioSeconds, rtf))
                    Log.i(
                        TAG,
                        "${variant.label} · $name: tokens=${ids.size} median=${medianMs}ms " +
                            "audio=${"%.2f".format(audioSeconds)}s rtf=${"%.3f".format(rtf)} runs=$samples",
                    )
                    if (name == WAV_TEXT && lastAudio.isNotEmpty()) {
                        val wav = File(quantDir, "out_${variant.label}_$WAV_TEXT.wav")
                        writeWav(wav, lastAudio)
                        Log.i(TAG, "wrote ${wav.absolutePath}")
                    }
                }
                val meanRtf = timings.map { it.rtf }.average()
                Log.i(TAG, "${variant.label}: mean RTF ${"%.3f".format(meanRtf)}")
                onVariant(VariantResult(variant.label, variant.fileName, sizeMb, "ok", timings, meanRtf))
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.java.simpleName
                Log.e(TAG, "${variant.label} FAILED: $msg", t)
                onVariant(VariantResult(variant.label, variant.fileName, sizeMb, "FAILED: $msg"))
            } finally {
                // fp32 alone is ~310 MB resident — never hold two at once.
                runCatching { session?.close() }
            }
        }
        Log.i(TAG, "bench done")
        null
    }

    /**
     * Mirrors `KokoroDirectEngine.buildSessionOptions`, with the optimisation
     * level parameterised for the q8f16 crash guard. Kept as a local copy on
     * purpose: the engine's version is private, and this one must be free to
     * vary without touching ship code.
     */
    private fun buildOptions(
        threads: Int,
        level: OrtSession.SessionOptions.OptLevel,
    ): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(level)
        setMemoryPatternOptimization(true)
        try {
            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            addConfigEntry("session.intra_op.allow_spinning", "0")
        } catch (t: Throwable) {
            Log.w(TAG, "XNNPACK EP unavailable; CPU EP only", t)
        }
    }

    /**
     * One `session.run`. Input tensors are allocated fresh per call and closed
     * after the result: ORT-Android 1.26 invalidates input tensors when the
     * Result is closed, so a cached/reused OnnxTensor is a SIGSEGV waiting to
     * happen (documented at length in PocketEngine.kt).
     */
    private fun runOnce(
        ort: OrtEnvironment,
        session: OrtSession,
        ids: IntArray,
        styleView: FloatBuffer,
    ): FloatArray {
        val tokenBuf = ByteBuffer.allocateDirect((ids.size + 2) * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        tokenBuf.put(PAD_TOKEN.toLong())
        for (id in ids) tokenBuf.put(id.toLong())
        tokenBuf.put(PAD_TOKEN.toLong())
        tokenBuf.rewind()

        val styleBuf = ByteBuffer.allocateDirect(STYLE_DIM * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fillStyleRow(styleView, ids.size, styleBuf)

        val speedBuf = ByteBuffer.allocateDirect(4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        speedBuf.put(1.0f)
        speedBuf.rewind()

        val tokens = OnnxTensor.createTensor(ort, tokenBuf, longArrayOf(1, (ids.size + 2).toLong()))
        val style = OnnxTensor.createTensor(ort, styleBuf, longArrayOf(1, STYLE_DIM.toLong()))
        val speed = OnnxTensor.createTensor(ort, speedBuf, longArrayOf(1))
        try {
            // The installed engine export names the token input "tokens"; the
            // onnx-community exports name it "input_ids". Same graph family,
            // different export — resolve per session.
            val tokenInput = if ("input_ids" in session.inputNames) "input_ids" else "tokens"
            val results = session.run(mapOf(tokenInput to tokens, "style" to style, "speed" to speed))
            try {
                return extractWaveform(results[0].value)
            } finally {
                results.close()
            }
        } finally {
            tokens.close()
            style.close()
            speed.close()
        }
    }

    /**
     * voices.bin is float32 `[53, 510, 256]` row-major, indexed by
     * (speaker, phoneme-token count). [tokenCount] excludes the two wrapping
     * pads, matching sherpa's `len = total - 2` convention.
     */
    private fun fillStyleRow(view: FloatBuffer, tokenCount: Int, out: FloatBuffer) {
        val len = tokenCount.coerceIn(0, MAX_TOKEN_LEN - 1)
        val start = SPEAKER_AF_HEART * MAX_TOKEN_LEN * STYLE_DIM + len * STYLE_DIM
        val src = view.duplicate()
        src.position(start)
        src.limit(start + STYLE_DIM)
        out.clear()
        out.put(src)
        out.rewind()
    }

    private fun extractWaveform(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            @Suppress("UNCHECKED_CAST")
            (value as Array<FloatArray>).firstOrNull() ?: FloatArray(0)
        }
        else -> FloatArray(0)
    }

    private fun mmapFloats(file: File): FloatBuffer {
        val raf = RandomAccessFile(file, "r")
        val mbb = raf.use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
        mbb.order(ByteOrder.LITTLE_ENDIAN)
        return mbb.asFloatBuffer()
    }

    /** 16-bit PCM mono WAV at [SAMPLE_RATE], for `adb pull` + listening. */
    private fun writeWav(file: File, samples: FloatArray) {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)               // PCM
        buf.putShort(1)               // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)   // byte rate
        buf.putShort(2)               // block align
        buf.putShort(16)              // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataBytes)
        for (s in samples) {
            buf.putShort((s.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort())
        }
        file.writeBytes(buf.array())
    }
}
