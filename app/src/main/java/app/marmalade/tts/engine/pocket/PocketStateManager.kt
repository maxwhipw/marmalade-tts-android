package app.marmalade.tts.engine.pocket

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

// -----------------------------------------------------------------------------
// State lifecycle for Pocket TTS's two stateful ONNX models.
//
// `flow_lm_main` has 18 state slots (KV cache for 6 transformer layers).
// `mimi_decoder` has 56 state slots (streaming conv buffers + transformer KV).
//
// Storage model: the persistent state lives in plain Kotlin arrays
// (`FloatArray`/`LongArray`/`ByteArray` for `bool` tensors) keyed by the
// `state_N` input name. ON EVERY CALL we materialise these as fresh
// `OnnxTensor` inputs, run the session, copy each `out_state_N` back
// into the matching array, then close the session result. This is the
// only correct pattern with the ORT Java API — `OrtSession.Result.close()`
// invalidates every output tensor it owns, so any reference we save
// across the close() becomes a dangling pointer (manifests as
// `SIGSEGV` with fault addresses like `0x80808080...` inside ORT).
//
// Init policies (per `bundle.json`'s `*_state_manifest`):
//   - `nan`   → Float.NaN (KV caches; the model masks reads to the valid
//                prefix so NaN never reaches softmax).
//   - `zeros` → 0 (numeric offset trackers, conv stream buffers).
//   - `ones`  → 1 (the bool `first` flags in streaming convolutions —
//                stored as `0x01` bytes for the BOOL dtype).
//   - `empty` → a literally zero-element array; the model uses these as
//                length sentinels (`current_end[0]`).
// -----------------------------------------------------------------------------

/**
 * A single state tensor's data + shape, stored as a Kotlin array that
 * survives across ORT calls (the `OnnxTensor`s wrapping these arrays
 * are created fresh per call and closed afterwards).
 */
sealed class PocketStateValue {
    abstract val shape: LongArray
    class Floats(val data: FloatArray, override val shape: LongArray) : PocketStateValue()
    class Longs(val data: LongArray, override val shape: LongArray) : PocketStateValue()
    /** ORT represents `bool` tensors as one byte per element. */
    class Bytes(val data: ByteArray, override val shape: LongArray) : PocketStateValue()
}

/** Mutable state map. Owned by the engine, updated in place each call. */
typealias PocketStates = MutableMap<String, PocketStateValue>

/**
 * Initialise a fresh state map per [manifest]. Each entry gets its
 * fill-policy starting value. The map's iteration order matches the
 * manifest order, which simplifies binding.
 */
fun initStates(manifest: List<PocketBundle.StateSpec>): PocketStates {
    val out = LinkedHashMap<String, PocketStateValue>(manifest.size)
    for (spec in manifest) {
        val numElements = elementCount(spec.shape)
        out[spec.inputName] = when (spec.dtype) {
            PocketBundle.StateDtype.FLOAT32 -> {
                val fillValue = when (spec.fill) {
                    PocketBundle.StateFill.NAN -> Float.NaN
                    PocketBundle.StateFill.ZEROS -> 0f
                    PocketBundle.StateFill.ONES -> 1f
                    PocketBundle.StateFill.EMPTY -> 0f
                }
                PocketStateValue.Floats(FloatArray(numElements) { fillValue }, spec.shape)
            }
            PocketBundle.StateDtype.INT64 -> {
                val fillValue: Long = when (spec.fill) {
                    PocketBundle.StateFill.ZEROS -> 0L
                    PocketBundle.StateFill.ONES -> 1L
                    PocketBundle.StateFill.NAN -> 0L // doesn't apply to int
                    PocketBundle.StateFill.EMPTY -> 0L
                }
                PocketStateValue.Longs(LongArray(numElements) { fillValue }, spec.shape)
            }
            PocketBundle.StateDtype.BOOL -> {
                val fillByte: Byte = when (spec.fill) {
                    PocketBundle.StateFill.ONES -> 1
                    PocketBundle.StateFill.ZEROS -> 0
                    PocketBundle.StateFill.NAN -> 0
                    PocketBundle.StateFill.EMPTY -> 0
                }
                PocketStateValue.Bytes(ByteArray(numElements) { fillByte }, spec.shape)
            }
        }
    }
    return out
}

/**
 * Add fresh `OnnxTensor`s for every state slot to [inputs]. Returns the
 * created tensors so the caller can close them after the session run.
 *
 * The created tensors wrap the Kotlin arrays directly via
 * `FloatBuffer.wrap` / `LongBuffer.wrap` / `ByteBuffer.wrap` — these are
 * heap buffers that the JNI layer copies to native memory at run time.
 * That copy is the price we pay for the Java API's tensor lifecycle.
 */
fun bindStateInputs(
    env: OrtEnvironment,
    manifest: List<PocketBundle.StateSpec>,
    states: PocketStates,
    inputs: MutableMap<String, OnnxTensor>,
): List<OnnxTensor> {
    val created = ArrayList<OnnxTensor>(manifest.size)
    for (spec in manifest) {
        val value = states[spec.inputName]
            ?: error("State missing for ${spec.inputName} — initStates not called?")
        val tensor: OnnxTensor = when (value) {
            is PocketStateValue.Floats -> OnnxTensor.createTensor(
                env, FloatBuffer.wrap(value.data), value.shape,
            )
            is PocketStateValue.Longs -> OnnxTensor.createTensor(
                env, LongBuffer.wrap(value.data), value.shape,
            )
            is PocketStateValue.Bytes -> OnnxTensor.createTensor(
                env, ByteBuffer.wrap(value.data), value.shape, OnnxJavaType.BOOL,
            )
        }
        inputs[spec.inputName] = tensor
        created.add(tensor)
    }
    return created
}

/**
 * Copy every `out_state_N` from [result] into the corresponding `state_N`
 * slot in [states]. Must run BEFORE [result] is closed — once the Result
 * is closed the output tensors are invalid.
 *
 * The shape from the output is used as the new shape (it may have grown
 * — `current_end` widens by 1 after each call, KV caches stay fixed).
 */
fun updateStatesFromResult(
    manifest: List<PocketBundle.StateSpec>,
    result: OrtSession.Result,
    states: PocketStates,
) {
    for (spec in manifest) {
        val out = result.get(spec.outputName).orElseThrow {
            IllegalStateException("Session did not return ${spec.outputName}")
        } as OnnxTensor
        val outShape = out.info.shape
        val numElements = elementCount(outShape)
        val newValue = when (spec.dtype) {
            PocketBundle.StateDtype.FLOAT32 -> {
                val buf = out.floatBuffer
                val arr = FloatArray(numElements)
                if (numElements > 0) buf.get(arr)
                PocketStateValue.Floats(arr, outShape)
            }
            PocketBundle.StateDtype.INT64 -> {
                val buf = out.longBuffer
                val arr = LongArray(numElements)
                if (numElements > 0) buf.get(arr)
                PocketStateValue.Longs(arr, outShape)
            }
            PocketBundle.StateDtype.BOOL -> {
                val buf = out.byteBuffer
                val arr = ByteArray(numElements)
                if (numElements > 0) buf.get(arr)
                PocketStateValue.Bytes(arr, outShape)
            }
        }
        states[spec.inputName] = newValue
    }
}

/** Total scalar count for a shape — zero if any dimension is zero (empty tensors). */
private fun elementCount(shape: LongArray): Int {
    var n = 1L
    for (d in shape) {
        if (d <= 0L) return 0
        n *= d
    }
    require(n <= Int.MAX_VALUE.toLong()) { "State tensor too large: ${shape.toList()}" }
    return n.toInt()
}
