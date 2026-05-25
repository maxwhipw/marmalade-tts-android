package app.marmalade.tts.engine.pocket

import org.json.JSONObject
import java.io.File

/**
 * Parsed representation of `bundle.json` for a Pocket TTS install.
 *
 * The bundle contains static metadata (sample rate, frame rate, latent
 * dim, predefined voices) AND two state manifests describing the
 * shape/dtype/fill policy of every persistent state tensor for
 * `flow_lm_main` (18 entries, the transformer's KV cache) and
 * `mimi_decoder` (56 entries, the streaming Mimi codec's conv + attention
 * buffers). We drive state initialization off these manifests rather than
 * hard-coding them — both because the manifest is the source of truth
 * for fill policies (cache slots want NaN, offset trackers want zeros,
 * bool flags want ones, etc.) and because future bundles might add or
 * reshape state entries.
 *
 * Why a plain JSONObject parse instead of kotlinx.serialization or Moshi:
 * the file is parsed exactly once per engine load and the project hasn't
 * yet pulled in a JSON library beyond the framework's `org.json`. Adding
 * one for ~150 lines of read-once metadata isn't worth the dependency.
 */
data class PocketBundle(
    /** PCM sample rate emitted by `mimi_decoder`. 24 kHz for english_2026-04. */
    val sampleRate: Int,
    /** Latent frame rate (Hz). 12.5 for english_2026-04. */
    val frameRate: Double,
    /** Samples per latent frame. 1920 for english_2026-04 (= sampleRate / frameRate). */
    val samplesPerFrame: Int,
    /** Latent dim (sequence channel). 32. */
    val latentDim: Int,
    /** Conditioning dim (transformer model dim). 1024. */
    val conditioningDim: Int,
    /**
     * Maximum tokens per chunk before the upstream chunker splits the input.
     * 50 for english_2026-04. v0.3.0-alpha.2 doesn't ship a chunker; inputs
     * over this size emit a warning and run anyway.
     */
    val maxTokenPerChunk: Int,
    /**
     * When true, prepend the learned `bos_before_voice` 1024-d embedding to
     * the voice embedding along the time axis before voice conditioning.
     * `english_2026-04` sets this to true. Skipping the prepend is a real
     * quality regression (NekoSpeak's bug #1 per ADR research).
     */
    val insertBosBeforeVoice: Boolean,
    /** When true, replace `;` with `,` before tokenizing. */
    val removeSemicolons: Boolean,
    /** When true, prepend 8 spaces to inputs with <5 words. */
    val padWithSpacesForShortInputs: Boolean,
    /**
     * Optional model recommendation for frames-to-generate after the EOS
     * logit threshold first fires. When null (english_2026-04's case),
     * fall back to 3 frames (or 5 for ≤4-word inputs per Python's heuristic).
     */
    val modelRecommendedFramesAfterEos: Int?,
    /** Display names of voices bundled in the `voices/` subdir as `<name>.wav`. */
    val predefinedVoices: List<String>,
    /**
     * Per-tensor state spec for the 18 `flow_lm_main` state slots
     * (`state_0`..`state_17` / `out_state_0`..`out_state_17`).
     */
    val flowLmStateManifest: List<StateSpec>,
    /**
     * Per-tensor state spec for the 56 `mimi_decoder` state slots
     * (`state_0`..`state_55` / `out_state_0`..`out_state_55`).
     */
    val mimiStateManifest: List<StateSpec>,
) {
    /**
     * A single state tensor's shape/dtype/fill spec. Drives both the
     * initial value (`fill` → see [StateFill]) and the wire shape passed
     * to ONNX Runtime when calling the session.
     */
    data class StateSpec(
        val index: Int,
        val inputName: String,
        val outputName: String,
        val dtype: StateDtype,
        val fill: StateFill,
        /**
         * Static shape from the manifest. Dimensions of `0` are intentional —
         * they denote empty/length-tracker tensors (e.g. `current_end`
         * starts at shape `[0]` and the model uses it as a position counter).
         */
        val shape: LongArray,
    )

    enum class StateDtype { FLOAT32, INT64, BOOL }

    /**
     * Fill policy from `bundle.json`. Each kind maps to a different
     * initialization at engine reset time:
     *  - `NAN`: float NaN (used for KV caches; the model masks reads to
     *    the valid prefix so NaN never reaches softmax).
     *  - `ZEROS`: numeric zero (offset trackers, conv stream buffers).
     *  - `ONES`: one (the bool "first" flags in streaming convolutions —
     *    stored as `0x01` bytes for the BOOL dtype).
     *  - `EMPTY`: a literally zero-element tensor; the model uses these
     *    as length sentinels (`current_end[0]`, `previous[1,128,0]`).
     */
    enum class StateFill { NAN, ZEROS, ONES, EMPTY }

    companion object {

        /**
         * Parse a bundle.json file from disk. Throws [IllegalStateException]
         * with the bundle-relative path on any structural failure — the
         * caller (PocketEngine.ensureModelLoaded) surfaces that as an
         * engine-init failure so the UI can flag a corrupt install.
         */
        fun load(file: File): PocketBundle {
            val text = file.readText(Charsets.UTF_8)
            val json = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw IllegalStateException("bundle.json is not valid JSON at $file", t)
            }
            return PocketBundle(
                sampleRate = json.getInt("sample_rate"),
                frameRate = json.getDouble("frame_rate"),
                samplesPerFrame = json.getInt("samples_per_frame"),
                latentDim = json.getInt("latent_dim"),
                conditioningDim = json.getInt("conditioning_dim"),
                maxTokenPerChunk = json.getInt("max_token_per_chunk"),
                insertBosBeforeVoice = json.optBoolean("insert_bos_before_voice", false),
                removeSemicolons = json.optBoolean("remove_semicolons", false),
                padWithSpacesForShortInputs = json.optBoolean("pad_with_spaces_for_short_inputs", false),
                modelRecommendedFramesAfterEos = if (json.isNull("model_recommended_frames_after_eos")) {
                    null
                } else {
                    json.getInt("model_recommended_frames_after_eos")
                },
                predefinedVoices = json.getJSONArray("predefined_voices").let { arr ->
                    List(arr.length()) { i -> arr.getString(i) }
                },
                flowLmStateManifest = parseManifest(json.getJSONArray("flow_lm_state_manifest")),
                mimiStateManifest = parseManifest(json.getJSONArray("mimi_state_manifest")),
            )
        }

        private fun parseManifest(arr: org.json.JSONArray): List<StateSpec> {
            return List(arr.length()) { i ->
                val entry = arr.getJSONObject(i)
                val shapeArr = entry.getJSONArray("shape")
                val shape = LongArray(shapeArr.length()) { j -> shapeArr.getLong(j) }
                StateSpec(
                    index = entry.getInt("index"),
                    inputName = entry.getString("input_name"),
                    outputName = entry.getString("output_name"),
                    dtype = when (val s = entry.getString("dtype")) {
                        "float32" -> StateDtype.FLOAT32
                        "int64" -> StateDtype.INT64
                        "bool" -> StateDtype.BOOL
                        else -> throw IllegalStateException("Unknown manifest dtype '$s' at index $i")
                    },
                    fill = when (val s = entry.getString("fill")) {
                        "nan" -> StateFill.NAN
                        "zeros" -> StateFill.ZEROS
                        "ones" -> StateFill.ONES
                        "empty" -> StateFill.EMPTY
                        else -> throw IllegalStateException("Unknown manifest fill '$s' at index $i")
                    },
                    shape = shape,
                )
            }
        }
    }
}
