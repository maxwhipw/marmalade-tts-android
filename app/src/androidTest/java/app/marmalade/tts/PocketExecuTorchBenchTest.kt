package app.marmalade.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * On-device speed probe for the ExecuTorch Pocket mimi_decoder.
 *
 * Prerequisite: push the exported graph to the app's external files dir:
 *   adb push tools/executorch-export/out/pocket-tts-en-v2026_04/mimi_decoder.pte \
 *     /sdcard/Android/data/app.marmalade.tts.debug/files/mimi_decoder.pte
 *
 * Run only this test:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=app.marmalade.tts.PocketExecuTorchBenchTest
 *
 * Reads the result from logcat (tag ETBench). Compares against the realtime
 * budget (80 ms/frame at 12.5 Hz) and the current ORT overlap-discard decode
 * (~19 ms/frame). The decoder is stateless single-shot: latent[1,T,32] -> audio[1,1,T*1920].
 */
@RunWith(AndroidJUnit4::class)
class PocketExecuTorchBenchTest {

    @Test
    fun benchMimiDecoder() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pte = File(ctx.getExternalFilesDir(null), "mimi_decoder.pte")
        Log.i(TAG, "pte=${pte.absolutePath} exists=${pte.exists()} size=${pte.length()}")
        assertTrue("push mimi_decoder.pte to ${pte.absolutePath} first", pte.exists())

        val module = Module.load(pte.absolutePath)

        val frames = 80
        val dim = 32
        val data = FloatArray(frames * dim) { (((it * 31) % 97) - 48) * 0.05f } // arbitrary non-zero latent
        val shape = longArrayOf(1, frames.toLong(), dim.toLong())

        // Warm up (first call JITs / allocates).
        repeat(2) { module.forward(EValue.from(Tensor.fromBlob(data, shape))) }

        val runs = 8
        val times = LongArray(runs)
        for (i in 0 until runs) {
            val t0 = System.nanoTime()
            val out = module.forward(EValue.from(Tensor.fromBlob(data, shape)))
            val ms = (System.nanoTime() - t0) / 1_000_000
            times[i] = ms
            if (i == 0) {
                val outT = out[0].toTensor()
                Log.i(TAG, "output shape=${outT.shape().toList()} numel=${outT.numel()}")
            }
        }
        val sorted = times.sorted()
        val median = sorted[runs / 2]
        val perFrame = median.toDouble() / frames
        Log.i(
            TAG,
            "RESULT ExecuTorch mimi_decoder: frames=$frames times(ms)=$sorted median=${median}ms " +
                "perFrame=${"%.2f".format(perFrame)}ms  (budget 80ms/frame; ORT overlap-discard ~19ms/frame)",
        )
    }

    /**
     * The decisive RTF probe: flow_lm_main is the AR backbone (~80% of per-chunk
     * time in ORT). Phase-3 AR-step inputs: sequence[1,1,32], text[1,0,1024],
     * offset int64 scalar, + six KV caches [2,1,1024,16,64] (flattened to args).
     * One forward == one AR step's main-transformer cost (the Euler flow net is
     * a separate, smaller graph run 4x/frame).
     */
    @Test
    fun benchFlowLmMain() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // -Pandroid.testInstrumentationRunnerArguments.pte=flow_lm_main_int8.pte to A/B a variant.
        val name = InstrumentationRegistry.getArguments().getString("pte") ?: "flow_lm_main.pte"
        val pte = File(ctx.getExternalFilesDir(null), name)
        Log.i(TAG, "pte=${pte.absolutePath} exists=${pte.exists()} size=${pte.length()}")
        assertTrue("push flow_lm_main.pte first", pte.exists())
        val module = Module.load(pte.absolutePath)

        val nLayers = 6; val cap = 1024L; val h = 16L; val d = 64L
        val seq = Tensor.fromBlob(FloatArray(32) { 0.01f }, longArrayOf(1, 1, 32))
        val text = Tensor.fromBlob(FloatArray(0), longArrayOf(1, 0, 1024))
        val offset = Tensor.fromBlob(longArrayOf(0L), longArrayOf())
        val cacheLen = (2 * 1 * cap * h * d).toInt()
        val caches = Array(nLayers) {
            Tensor.fromBlob(FloatArray(cacheLen), longArrayOf(2, 1, cap, h, d))
        }
        val inputs: Array<EValue> = arrayOf(
            EValue.from(seq), EValue.from(text), EValue.from(offset),
            *Array(nLayers) { EValue.from(caches[it]) },
        )

        // Memory-pressure context: a noisy run (lmkd thrashing while the 76MB+
        // .pte and 48MB of zero KV caches are resident) inflates latency and the
        // median lies. Log avail mem + lowMemory flag so a confounded run is visible.
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        Log.i(TAG, "mem availMB=${mi.availMem / 1_048_576} lowMemory=${mi.lowMemory} thresholdMB=${mi.threshold / 1_048_576}")

        repeat(5) { module.forward(*inputs) }
        val runs = 30
        val times = LongArray(runs)
        for (i in 0 until runs) {
            val t0 = System.nanoTime()
            val out = module.forward(*inputs)
            times[i] = (System.nanoTime() - t0) / 1_000_000
            if (i == 0) Log.i(TAG, "flow_lm_main outputs=${out.size}")
        }
        val sorted = times.sorted()
        val median = sorted[runs / 2]
        val p90 = sorted[(runs * 9) / 10]
        am.getMemoryInfo(mi)
        Log.i(
            TAG,
            "RESULT ExecuTorch flow_lm_main AR-step: min=${sorted.first()}ms median=${median}ms " +
                "p90=${p90}ms max=${sorted.last()}ms runs=$runs lowMemAfter=${mi.lowMemory} times=$sorted",
        )
        module.destroy()
    }

    /**
     * The other per-frame cost: flow_lm_flow is the Euler flow-matching net, run
     * LSD_DECODE_STEPS (=4) times PER FRAME. Signature (export_pocket.py FlowNetWrapper):
     * c[1,1024], s[1,1], t[1,1], x[1,32] -> flow_dir[1,32]. All M=1, stateless.
     * Full per-frame AR cost ≈ flow_lm_main(1x) + 4 * flow_lm_flow + mimi_decode_share;
     * 80ms/frame is the 12.5Hz realtime budget.
     */
    @Test
    fun benchFlowLmFlow() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = InstrumentationRegistry.getArguments().getString("pte") ?: "flow_lm_flow.pte"
        val pte = File(ctx.getExternalFilesDir(null), name)
        Log.i(TAG, "pte=${pte.absolutePath} exists=${pte.exists()} size=${pte.length()}")
        assertTrue("push $name first", pte.exists())
        val module = Module.load(pte.absolutePath)

        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        Log.i(TAG, "mem availMB=${mi.availMem / 1_048_576} lowMemory=${mi.lowMemory}")

        val c = FloatArray(1024) { 0.01f }
        val x = FloatArray(32) { 0.01f }
        fun mkInputs(): Array<EValue> = arrayOf(
            EValue.from(Tensor.fromBlob(c, longArrayOf(1, 1024))),
            EValue.from(Tensor.fromBlob(floatArrayOf(0f), longArrayOf(1, 1))),
            EValue.from(Tensor.fromBlob(floatArrayOf(0.25f), longArrayOf(1, 1))),
            EValue.from(Tensor.fromBlob(x, longArrayOf(1, 32))),
        )

        repeat(5) { module.forward(*mkInputs()) }
        val runs = 30
        val times = LongArray(runs)
        for (i in 0 until runs) {
            val t0 = System.nanoTime()
            module.forward(*mkInputs())
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        val sorted = times.sorted()
        val median = sorted[runs / 2]
        am.getMemoryInfo(mi)
        Log.i(
            TAG,
            "RESULT ExecuTorch flow_lm_flow Euler-step: min=${sorted.first()}ms median=${median}ms " +
                "p90=${sorted[(runs * 9) / 10]}ms max=${sorted.last()}ms 4x(perFrame)=${median * 4}ms " +
                "lowMem=${mi.lowMemory} times=$sorted",
        )
        module.destroy()
    }

    // -- Memory footprint probe ---------------------------------------------
    private fun rssMB(): Long = try {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1
    } catch (e: Exception) { -1 }

    private fun nativeHeapMB(): Long = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576

    private fun memSnap(label: String) {
        System.gc(); Thread.sleep(120)
        Log.i(TAG, "MEM[$label] rss=${rssMB()}MB nativeHeap=${nativeHeapMB()}MB")
    }

    /**
     * Quantify where the ET engine's RAM goes: per-graph load + forward + destroy
     * deltas (isolated arena cost), then all 5 synth-resident graphs loaded
     * together (the real synth footprint). RSS is the source of truth (ExecuTorch
     * arenas may be mmap, not malloc). Read with: adb logcat -s ETBench | grep MEM
     */
    @Test
    fun benchMemoryFootprint() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null)!!
        fun p(n: String) = File(dir, n).absolutePath
        val nL = 6; val cap = 1024L; val h = 16L; val d = 64L
        val caches = Array(nL) { FloatArray((2 * cap * h * d).toInt()) }
        fun flowInputs(seqLen: Int, textLen: Int): Array<EValue> = arrayOf(
            EValue.from(Tensor.fromBlob(FloatArray(seqLen * 32) { 0.01f }, longArrayOf(1, seqLen.toLong(), 32))),
            EValue.from(Tensor.fromBlob(FloatArray(textLen * 1024) { 0.01f }, longArrayOf(1, textLen.toLong(), 1024))),
            EValue.from(Tensor.fromBlob(longArrayOf(0L), LongArray(0))),
            *Array(nL) { EValue.from(Tensor.fromBlob(caches[it], longArrayOf(2, 1, cap, h, d))) },
        )
        // (loaderName, build+forward closure that returns the loaded Module)
        data class G(val name: String, val file: String, val fwd: (Module) -> Unit)
        val graphs = listOf(
            G("text_conditioner", "text_conditioner.pte") {
                it.forward(EValue.from(Tensor.fromBlob(LongArray(20) { 1L }, longArrayOf(1, 20))))
            },
            G("flow_lm_cond", "flow_lm_cond.pte") { it.forward(*flowInputs(0, 32)) },
            G("flow_lm_main_int8", "flow_lm_main_int8.pte") { it.forward(*flowInputs(1, 0)) },
            G("flow_lm_flow", "flow_lm_flow.pte") {
                it.forward(
                    EValue.from(Tensor.fromBlob(FloatArray(1024) { 0.01f }, longArrayOf(1, 1024))),
                    EValue.from(Tensor.fromBlob(floatArrayOf(0f), longArrayOf(1, 1))),
                    EValue.from(Tensor.fromBlob(floatArrayOf(0.25f), longArrayOf(1, 1))),
                    EValue.from(Tensor.fromBlob(FloatArray(32) { 0.01f }, longArrayOf(1, 32))),
                )
            },
            G("mimi_decoder@57", "mimi_decoder.pte") {
                it.forward(EValue.from(Tensor.fromBlob(FloatArray(57 * 32) { 0.01f }, longArrayOf(1, 57, 32))))
            },
            G("mimi_encoder@30s", "mimi_encoder.pte") {
                it.forward(EValue.from(Tensor.fromBlob(FloatArray(30 * 24000) { 0.0f }, longArrayOf(1, 1, 30L * 24000))))
            },
        )

        memSnap("baseline (+50MB KV caches)")
        // (1) Isolated per-graph cost: load -> forward -> destroy.
        for (g in graphs) {
            val before = rssMB()
            val m = Module.load(p(g.file))
            val afterLoad = rssMB()
            g.fwd(m)
            val afterFwd = rssMB()
            m.destroy()
            System.gc(); Thread.sleep(120)
            val afterDestroy = rssMB()
            Log.i(
                TAG,
                "MEM ISOLATED ${g.name}: load +${afterLoad - before}MB  forward +${afterFwd - afterLoad}MB  " +
                    "(peak rss=${afterFwd}MB)  destroyed→${afterDestroy}MB (reclaimed ${afterFwd - afterDestroy}MB)",
            )
        }

        // (2) Cumulative synth-resident footprint: the 5 graphs alive at once
        // (encoder excluded — it's released after voice-encode in the engine).
        memSnap("cumulative: before")
        val resident = graphs.filter { it.name != "mimi_encoder@30s" }.map { g ->
            val m = Module.load(p(g.file)); g.fwd(m)
            Log.i(TAG, "MEM CUMULATIVE after ${g.name}: rss=${rssMB()}MB"); m
        }
        memSnap("cumulative: ALL 5 synth graphs loaded+forwarded (REAL footprint)")
        resident.forEach { it.destroy() }
    }

    companion object {
        private const val TAG = "ETBench"
    }
}
