package app.marmalade.tts.engine.pocket

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

// -----------------------------------------------------------------------------
// State lifecycle for Pocket TTS's two stateful ONNX models.
//
// `flow_lm_main` has 18 state slots (KV cache for 6 transformer layers).
// `mimi_decoder` has 56 state slots (streaming conv buffers + transformer KV).
//
// Both follow the same pattern:
//   1. At the start of synthesis (or a fresh voice encode), allocate the
//      starting state per the manifest's `fill` policy.
//   2. Each session call reads `state_0..state_N` and writes
//      `out_state_0..out_state_N`.
//   3. After the call: close the old state tensors, and the OUT tensors
//      become the IN tensors for the next call. Repeat until done.
//   4. On engine teardown, close the live state.
//
// The ONNX outputs ARE the next inputs — we never copy buffers between
// calls. That keeps the AR loop allocation-free past warm-up.
//
// Why drive everything off the bundle's manifest instead of hard-coding:
// the manifest specifies per-slot dtype + fill (float32/NaN for KV
// caches, int64/zeros for step counters, bool/ones for `first` flags,
// float32/empty for zero-length sentinels). Mixing those up silently
// breaks the model — NekoSpeak's zero-fill-all approach happens to mostly
// work because attention masking covers for the NaN-vs-zero mismatch,
// but the bundle.json is the source of truth and we follow it.
// -----------------------------------------------------------------------------

/**
 * Allocate a fresh state-tensor bundle per [manifest], with values
 * dictated by each entry's [PocketBundle.StateFill] policy.
 *
 * The caller (PocketEngine) owns the returned tensors and is responsible
 * for closing them when they're no longer needed — typically by passing
 * them to [cycleStates] and then closing the previous generation.
 */
fun initStates(
    env: OrtEnvironment,
    manifest: List<PocketBundle.StateSpec>,
): Map<String, OnnxTensor> {
    val out = LinkedHashMap<String, OnnxTensor>(manifest.size)
    for (spec in manifest) {
        out[spec.inputName] = allocateStateTensor(env, spec)
    }
    return out
}

/**
 * Build the next-generation state map by reading [outputNames] (the
 * `out_state_*` slot names) from [outputs] and re-keying them under the
 * corresponding [inputNames] (`state_*`) so the next session call can
 * consume them.
 *
 * Closes the [previousInputs] tensors before returning — they're done.
 */
fun cycleStates(
    manifest: List<PocketBundle.StateSpec>,
    outputs: ai.onnxruntime.OrtSession.Result,
    previousInputs: Map<String, OnnxTensor>,
): Map<String, OnnxTensor> {
    // Take ownership of the OUT tensors so they survive the Result.close()
    // the caller will perform on the OrtSession.Result wrapper.
    val next = LinkedHashMap<String, OnnxTensor>(manifest.size)
    for (spec in manifest) {
        val outTensor = outputs.get(spec.outputName).orElseThrow {
            IllegalStateException("ONNX session did not return ${spec.outputName}")
        } as OnnxTensor
        next[spec.inputName] = outTensor
    }
    // Close the previous generation now that we've extracted what we need.
    for (t in previousInputs.values) {
        try {
            t.close()
        } catch (_: Throwable) {
            // best-effort
        }
    }
    return next
}

/** Close every tensor in a state map. Idempotent across already-closed tensors. */
fun closeStates(states: Map<String, OnnxTensor>) {
    for (t in states.values) {
        try {
            t.close()
        } catch (_: Throwable) {
            // best-effort
        }
    }
}

// -----------------------------------------------------------------------------
// Per-spec allocation
// -----------------------------------------------------------------------------

private fun allocateStateTensor(
    env: OrtEnvironment,
    spec: PocketBundle.StateSpec,
): OnnxTensor {
    val numElements = elementCount(spec.shape)
    return when (spec.dtype) {
        PocketBundle.StateDtype.FLOAT32 -> {
            // Empty tensors (any 0 dim) still need a real OnnxTensor with
            // a zero-capacity FloatBuffer — ORT distinguishes "absent" from
            // "present but empty" and we want "present but empty".
            val buf = ByteBuffer.allocateDirect(numElements * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            if (numElements > 0) {
                val fillValue = when (spec.fill) {
                    PocketBundle.StateFill.NAN -> Float.NaN
                    PocketBundle.StateFill.ZEROS -> 0f
                    PocketBundle.StateFill.ONES -> 1f
                    PocketBundle.StateFill.EMPTY -> 0f // unreachable: empty + non-empty shape
                }
                fillFloat(buf, fillValue, numElements)
            }
            OnnxTensor.createTensor(env, buf, spec.shape)
        }
        PocketBundle.StateDtype.INT64 -> {
            val buf = ByteBuffer.allocateDirect(numElements * 8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
            if (numElements > 0) {
                val fillValue: Long = when (spec.fill) {
                    PocketBundle.StateFill.ZEROS -> 0L
                    PocketBundle.StateFill.ONES -> 1L
                    PocketBundle.StateFill.NAN -> 0L  // NaN doesn't apply to int — should not occur
                    PocketBundle.StateFill.EMPTY -> 0L
                }
                fillLong(buf, fillValue, numElements)
            }
            OnnxTensor.createTensor(env, buf, spec.shape)
        }
        PocketBundle.StateDtype.BOOL -> {
            // ORT represents bool as 1 byte/element. The Java API exposes
            // bool tensors via ByteBuffer + OnnxJavaType.BOOL.
            val buf = ByteBuffer.allocateDirect(numElements)
                .order(ByteOrder.nativeOrder())
            if (numElements > 0) {
                val fillByte: Byte = when (spec.fill) {
                    PocketBundle.StateFill.ONES -> 1
                    PocketBundle.StateFill.ZEROS -> 0
                    PocketBundle.StateFill.NAN -> 0
                    PocketBundle.StateFill.EMPTY -> 0
                }
                for (i in 0 until numElements) buf.put(i, fillByte)
            }
            OnnxTensor.createTensor(
                env,
                buf,
                spec.shape,
                ai.onnxruntime.OnnxJavaType.BOOL,
            )
        }
    }
}

/** Total scalar count for a shape — zero if any dimension is zero (empty tensors). */
private fun elementCount(shape: LongArray): Int {
    var n = 1L
    for (d in shape) {
        if (d == 0L) return 0
        n *= d
    }
    require(n <= Int.MAX_VALUE.toLong()) { "State tensor too large: ${shape.toList()}" }
    return n.toInt()
}

private fun fillFloat(buf: FloatBuffer, value: Float, count: Int) {
    for (i in 0 until count) buf.put(i, value)
}

private fun fillLong(buf: LongBuffer, value: Long, count: Int) {
    for (i in 0 until count) buf.put(i, value)
}
