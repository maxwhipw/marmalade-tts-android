package app.marmalade.tts.engine.pocket

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

// -----------------------------------------------------------------------------
// State lifecycle for Pocket TTS's two stateful ONNX models.
//
// `flow_lm_main` has 18 state slots (KV cache for 6 transformer layers).
// `mimi_decoder` has 56 state slots (streaming conv buffers + transformer KV).
//
// Storage model (v0.3.0-alpha.7+): every state slot owns a persistent
// direct ByteBuffer in native byte order. Bind reuses the same buffer
// across calls — no per-frame allocation, no JNI heap→direct copy.
// Per-frame work per slot: position/limit the buffer + createTensor +
// session.run + read output bytes into the buffer + close result.
//
// The prior implementation kept FloatArray/LongArray/ByteArray storage and
// called `FloatBuffer.wrap(heapArray)` at every bind. Per ORT issue #16937
// that path triggers a JNI-side heap→direct copy of every input on every
// call. For flow_lm at 6 layers × 8 MB cache slots + smaller offset/step
// tensors that was ~50 MB of memcpy traffic per AR frame; with the direct-
// buffer path the same data stays in native memory across frames.
//
// Why we still allocate a fresh OnnxTensor per call: `OrtSession.Result.close()`
// invalidates every tensor it owns, including inputs we passed in (some
// ORT versions; safer to assume always). Reusing input tensors across
// calls is a separate experiment for a later iteration.
//
// Lazy growth: cache slots have fixed shape from the manifest, but a few
// "current_end" / "previous" slots start empty ([0] or [..., 0]) and grow
// at runtime. Initial capacity tracks the manifest shape; on first
// observed growth we double until the new size fits and reallocate the
// direct buffer. Amortised O(1) per call.
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
 * A single state tensor backed by a persistent direct ByteBuffer in
 * native byte order. The buffer is allocated once at engine load and
 * reused across all subsequent calls — `bindAsTensor` only updates the
 * position/limit window before passing the buffer to ORT.
 *
 * Capacity grows lazily: if [updateFromOutput] sees a tensor larger
 * than the current capacity, the buffer is reallocated to 2× the
 * needed size (amortised constant cost). In practice the only growing
 * slots are bounded by the model's max sequence length, so growth
 * happens at most a handful of times per engine load.
 */
class PocketStateSlot(
    val dtype: PocketBundle.StateDtype,
    initialShape: LongArray,
    private val initialFill: PocketBundle.StateFill,
) {
    /**
     * P-Y — preserved across [reset] so we can restore the slot to its
     * cold-start shape between synths without re-allocating the slot.
     * Independent copy of the constructor arg.
     */
    private val initialShapeCopy: LongArray = initialShape.copyOf()

    /** Current logical shape. Mutated by [updateFromOutput] when growth is observed. */
    var shape: LongArray = initialShape.copyOf()
        private set

    private val elementBytes: Int = bytesPerElement(dtype)
    private var capacityElements: Int = maxOf(elementCount(initialShape), 1)
    private var buffer: ByteBuffer = newNativeDirectBuffer(capacityElements * elementBytes)

    /**
     * P-V — secondary buffer for output-side pinning. Null unless
     * [enablePinning] is called (only happens for slots whose
     * output exists in the model's real outputs AND whose shape is
     * fully fixed, so the buffer never needs to grow). When non-null
     * the AR loop pins this buffer as the destination for the slot's
     * output tensor and swaps it with [buffer] after each call — so
     * the next call's input is what the previous call wrote, with no
     * memcpy in between.
     */
    private var outBuffer: ByteBuffer? = null

    init {
        // Fill the buffer's current-shape window with the init value.
        // Capacity may exceed shape (e.g. growing slots padded for headroom);
        // the unused tail is left at whatever ByteBuffer.allocateDirect
        // gives us (typically zero, but unspecified — bounded by limit/position
        // so unused bytes never reach ORT).
        fillRange(buffer, dtype, initialFill, elementCount(shape))
    }

    /**
     * P-Y — restore the slot to its cold-start condition WITHOUT
     * re-allocating buffers. Called at the start of each new synth to
     * replace the old "allocate fresh slot per chunk" pattern. Saves a
     * direct-buffer allocation per slot per synth (and the matching
     * native-memory backlog that accumulates while Cleaner runs are
     * gated by JVM GC).
     *
     * Preserves [buffer] and [outBuffer] (if present); restores [shape]
     * to [initialShapeCopy] and refills both buffers with the slot's
     * declared init value.
     */
    fun reset() {
        shape = initialShapeCopy.copyOf()
        // Refill `buffer` over the current-shape window. Capacity is
        // unchanged — we may have grown above initialShape during a
        // previous synth (via [updateFromOutput]); the larger buffer is
        // retained for headroom.
        fillRange(buffer, dtype, initialFill, elementCount(shape))
        outBuffer?.let { ob ->
            fillRange(ob, dtype, initialFill, elementCount(shape))
        }
    }

    /**
     * Allocate the output-side buffer + match it to the input's
     * init values so the first AR call doesn't see uninitialised bytes
     * if the model happens to read its own output before writing all
     * of it. Idempotent; calling twice keeps the first allocation.
     */
    fun enablePinning(initialFill: PocketBundle.StateFill) {
        if (outBuffer != null) return
        outBuffer = newNativeDirectBuffer(capacityElements * elementBytes).also {
            fillRange(it, dtype, initialFill, elementCount(shape))
        }
    }

    /**
     * Wrap [outBuffer] in a fresh OnnxTensor for use as a pinned output
     * in `session.run(inputs, requestedOutputs, pinnedOutputs)`. Caller
     * owns the tensor's lifecycle (close after run) — but since the
     * underlying buffer is persistent, the next call reuses the same
     * memory.
     *
     * After the run completes the caller MUST also call [swapBuffers]
     * so the just-written output becomes the next call's input.
     *
     * Throws if [enablePinning] hasn't been called.
     */
    fun bindAsPinnedOutput(env: OrtEnvironment): OnnxTensor {
        val out = outBuffer ?: error("bindAsPinnedOutput called on slot without enablePinning")
        val numElements = elementCount(shape)
        out.position(0)
        out.limit(numElements * elementBytes)
        return when (dtype) {
            PocketBundle.StateDtype.FLOAT32 ->
                OnnxTensor.createTensor(env, out.asFloatBuffer(), shape)
            PocketBundle.StateDtype.INT64 ->
                OnnxTensor.createTensor(env, out.asLongBuffer(), shape)
            PocketBundle.StateDtype.BOOL ->
                OnnxTensor.createTensor(env, out, shape, OnnxJavaType.BOOL)
        }
    }

    /**
     * Swap the input and output buffers. The just-written output
     * becomes the next call's input; the old input buffer becomes the
     * scratch destination for the next call's output. Zero-copy state
     * advance — replaces what would have been a `floatBuffer.get()`
     * + `floatBuffer.put()` round-trip in [updateFromOutput].
     */
    fun swapBuffers() {
        val out = outBuffer ?: error("swapBuffers called on slot without enablePinning")
        val tmp = buffer
        buffer = out
        outBuffer = tmp
    }

    /**
     * Position/limit the buffer to the current shape's byte range and
     * wrap it in an OnnxTensor. The tensor is created fresh per call
     * (see file-level comment); only the buffer's memory is reused.
     *
     * P-M experiment (caching this tensor across calls) crashed with
     * SIGSEGV on ORT-Android 1.26: `OrtSession.Result.close()` DOES
     * invalidate the input tensors' native handles, leaving the Java
     * wrapper as a stale pointer for the next `session.run`. The comment
     * at file head was right — keep the per-call alloc.
     */
    fun bindAsTensor(env: OrtEnvironment): OnnxTensor {
        val numElements = elementCount(shape)
        buffer.position(0)
        buffer.limit(numElements * elementBytes)
        return when (dtype) {
            PocketBundle.StateDtype.FLOAT32 ->
                OnnxTensor.createTensor(env, buffer.asFloatBuffer(), shape)
            PocketBundle.StateDtype.INT64 ->
                OnnxTensor.createTensor(env, buffer.asLongBuffer(), shape)
            PocketBundle.StateDtype.BOOL ->
                OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.BOOL)
        }
    }

    /**
     * Copy the output tensor's data into this slot's persistent buffer,
     * growing capacity if necessary. Updates [shape] to the output shape.
     *
     * MUST be called before [OrtSession.Result.close] — the output buffer
     * views from [out] become invalid after close.
     */
    fun updateFromOutput(out: OnnxTensor) {
        val outShape = out.info.shape
        val outElements = elementCount(outShape)
        if (outElements > capacityElements) {
            // Double until we fit. Reallocation drops the previous buffer
            // (collected by GC + native deallocator) and replaces with a
            // larger one — old contents are irrelevant since the output
            // tensor is the new source of truth.
            do {
                capacityElements = maxOf(capacityElements * 2, 1)
            } while (capacityElements < outElements)
            buffer = newNativeDirectBuffer(capacityElements * elementBytes)
        }
        buffer.position(0)
        buffer.limit(outElements * elementBytes)
        if (outElements > 0) {
            when (dtype) {
                PocketBundle.StateDtype.FLOAT32 -> {
                    val src = out.floatBuffer
                    src.position(0); src.limit(outElements)
                    buffer.asFloatBuffer().put(src)
                }
                PocketBundle.StateDtype.INT64 -> {
                    val src = out.longBuffer
                    src.position(0); src.limit(outElements)
                    buffer.asLongBuffer().put(src)
                }
                PocketBundle.StateDtype.BOOL -> {
                    val src = out.byteBuffer
                    src.position(0); src.limit(outElements)
                    buffer.put(src)
                    buffer.position(0)
                }
            }
        }
        shape = outShape
    }
}

/** Mutable state map. Owned by the engine, updated in place each call. */
typealias PocketStates = MutableMap<String, PocketStateSlot>

/**
 * Enable output-pinning on each slot listed in [pinnableSpecs]. Idempotent.
 * Called once per AR-session after [initStates] when the engine has
 * decided this state map will be used with pinned outputs.
 */
fun enableStatePinning(
    states: PocketStates,
    pinnableSpecs: List<PocketBundle.StateSpec>,
) {
    for (spec in pinnableSpecs) {
        val slot = states[spec.inputName]
            ?: error("State missing for ${spec.inputName} — initStates not called?")
        slot.enablePinning(spec.fill)
    }
}

/**
 * P-Y — reset every slot to its cold-start shape + init value in place.
 * Replaces the previous "allocate a fresh state map per chunk/stream"
 * pattern. Per-synth direct-buffer allocations drop from ~30 (flow_lm)
 * + 56 (mimi) to zero.
 *
 * Why this matters: direct ByteBuffers don't pressure the Java heap,
 * so JVM GC stays lazy, so [java.lang.ref.Cleaner] runs are delayed,
 * so the previous synth's buffers' native memory accumulates run-over-
 * run even though they're JVM-unreachable. Reusing the same buffers
 * across synths eliminates the backlog.
 */
fun resetStatesToInit(
    states: PocketStates,
    manifest: List<PocketBundle.StateSpec>,
) {
    for (spec in manifest) {
        val slot = states[spec.inputName]
            ?: error("State missing for ${spec.inputName} — initStates not called?")
        slot.reset()
    }
}

/**
 * Initialise a fresh state map per [manifest]. Each entry gets its
 * fill-policy starting value backed by a persistent direct ByteBuffer.
 * The map's iteration order matches the manifest order, which simplifies
 * binding.
 */
fun initStates(manifest: List<PocketBundle.StateSpec>): PocketStates {
    val out = LinkedHashMap<String, PocketStateSlot>(manifest.size)
    for (spec in manifest) {
        out[spec.inputName] = PocketStateSlot(
            dtype = spec.dtype,
            initialShape = spec.shape,
            initialFill = spec.fill,
        )
    }
    return out
}

/**
 * Add fresh `OnnxTensor`s for every state slot to [inputs]. Returns the
 * created tensors so the caller can close them after the session run.
 *
 * Each tensor wraps the slot's persistent direct ByteBuffer (no heap-to-
 * native copy) — see file-level comment for the rationale.
 */
fun bindStateInputs(
    env: OrtEnvironment,
    manifest: List<PocketBundle.StateSpec>,
    states: PocketStates,
    inputs: MutableMap<String, OnnxTensor>,
): List<OnnxTensor> {
    val created = ArrayList<OnnxTensor>(manifest.size)
    for (spec in manifest) {
        val slot = states[spec.inputName]
            ?: error("State missing for ${spec.inputName} — initStates not called?")
        val tensor = slot.bindAsTensor(env)
        inputs[spec.inputName] = tensor
        created.add(tensor)
    }
    return created
}

/**
 * Copy every `out_state_N` from [result] into the corresponding `state_N`
 * slot's persistent buffer in [states]. Must run BEFORE [result] is
 * closed — once the Result is closed the output tensors are invalid.
 *
 * Shapes can grow at runtime ([PocketStateSlot.updateFromOutput] handles
 * capacity growth via doubling).
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
        val slot = states[spec.inputName]
            ?: error("State missing for ${spec.inputName} — initStates not called?")
        slot.updateFromOutput(out)
    }
}


// -- internals ----------------------------------------------------------------

private fun newNativeDirectBuffer(byteCount: Int): ByteBuffer =
    ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())

private fun bytesPerElement(dtype: PocketBundle.StateDtype): Int = when (dtype) {
    PocketBundle.StateDtype.FLOAT32 -> 4
    PocketBundle.StateDtype.INT64 -> 8
    PocketBundle.StateDtype.BOOL -> 1
}

private fun fillRange(
    buffer: ByteBuffer,
    dtype: PocketBundle.StateDtype,
    fill: PocketBundle.StateFill,
    elementCount: Int,
) {
    if (elementCount == 0) return
    when (dtype) {
        PocketBundle.StateDtype.FLOAT32 -> {
            val v = when (fill) {
                PocketBundle.StateFill.NAN -> Float.NaN
                PocketBundle.StateFill.ZEROS -> 0f
                PocketBundle.StateFill.ONES -> 1f
                PocketBundle.StateFill.EMPTY -> 0f
            }
            val fb = buffer.asFloatBuffer()
            fb.position(0); fb.limit(elementCount)
            // Avoid allocating a temp array — write the constant directly.
            for (i in 0 until elementCount) fb.put(v)
        }
        PocketBundle.StateDtype.INT64 -> {
            val v: Long = when (fill) {
                PocketBundle.StateFill.ZEROS -> 0L
                PocketBundle.StateFill.ONES -> 1L
                PocketBundle.StateFill.NAN -> 0L
                PocketBundle.StateFill.EMPTY -> 0L
            }
            val lb = buffer.asLongBuffer()
            lb.position(0); lb.limit(elementCount)
            for (i in 0 until elementCount) lb.put(v)
        }
        PocketBundle.StateDtype.BOOL -> {
            val v: Byte = when (fill) {
                PocketBundle.StateFill.ONES -> 1
                PocketBundle.StateFill.ZEROS -> 0
                PocketBundle.StateFill.NAN -> 0
                PocketBundle.StateFill.EMPTY -> 0
            }
            for (i in 0 until elementCount) buffer.put(i, v)
        }
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
