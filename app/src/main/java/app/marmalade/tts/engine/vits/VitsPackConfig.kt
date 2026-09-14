package app.marmalade.tts.engine.vits

import java.io.File
import org.json.JSONObject

/**
 * Parsed `model.onnx.json` for one VITS voice pack.
 *
 * Everything the runtime needs about a voice comes from this file — sample
 * rate, espeak voice, inference scales, phoneme→id table, speaker count — so
 * a new language is a download, not a code change.
 *
 * Only the fields we actually use are modelled. Upstream configs carry more
 * (`phoneme_map`, `speaker_id_map`, `piper_version`, training metadata); they
 * are ignored rather than rejected so a config from a newer exporter still
 * loads.
 *
 * Why a plain `org.json` parse: same reasoning as
 * [app.marmalade.tts.engine.pocket.PocketBundle] — read once per pack load,
 * and the project deliberately carries no JSON library beyond the framework's.
 * Note `org.json` is stubbed-to-throw on a plain JVM, so unit tests covering
 * this parser run under Robolectric.
 */
data class VitsPackConfig(
    /** PCM sample rate the model's vocoder emits, from `audio.sample_rate`. */
    val sampleRate: Int,
    /**
     * espeak voice/language the checkpoint was trained against, from
     * `espeak.voice` (e.g. `"uk"`). Used verbatim as the phonemization
     * language unless the caller overrides it.
     */
    val espeakVoice: String,
    /** `inference.noise_scale` — the `scales[0]` input. */
    val noiseScale: Float,
    /**
     * `inference.length_scale` — the `scales[1]` input. Left at the config's
     * value always: user speed is a downstream time-stretch, not a model
     * parameter (see [app.marmalade.tts.engine.TtsEngine.supportsNativeSpeed]).
     */
    val lengthScale: Float,
    /** `inference.noise_w` — the `scales[2]` input. */
    val noiseW: Float,
    /**
     * Speaker count from `num_speakers`. 1 for every pack shipped so far;
     * when > 1 the graph also takes a `sid` input.
     */
    val numSpeakers: Int,
    /**
     * `phoneme_id_map`: one entry per phoneme the model knows, keyed by a
     * single-codepoint string, valued as the id list to emit for it. Almost
     * always one id per phoneme, but the list form is upstream's and is
     * honoured — a multi-id entry emits all of them, each pad-separated.
     */
    val phonemeIdMap: Map<String, List<Int>>,
    /** `language.code`, in upstream's underscore form (e.g. `"uk_UA"`). */
    val languageCode: String,
    /** `dataset` — the training corpus name, for logs and diagnostics. */
    val dataset: String,
) {
    init {
        require(sampleRate > 0) { "pack config has non-positive sample_rate ($sampleRate)" }
        require(numSpeakers >= 1) { "pack config has num_speakers < 1 ($numSpeakers)" }
        for (marker in REQUIRED_MARKERS) {
            require(phonemeIdMap.containsKey(marker)) {
                "pack config phoneme_id_map is missing the '$marker' marker"
            }
        }
    }

    /** True when the graph expects a `sid` speaker-index input. */
    val isMultiSpeaker: Boolean get() = numSpeakers > 1

    companion object {
        /** BOS marker id list. */
        const val BOS = "^"

        /** Pad marker, interspersed between every phoneme. */
        const val PAD = "_"

        /** EOS marker id list. */
        const val EOS = "$"

        private val REQUIRED_MARKERS = listOf(BOS, PAD, EOS)

        /**
         * Parse a pack's `model.onnx.json`.
         *
         * @throws IllegalStateException with the offending path when the file
         *   is not valid JSON or is missing a field the runtime needs. The
         *   caller (engine load) surfaces that as an engine-init failure so
         *   the UI can flag a corrupt pack.
         */
        fun load(file: File): VitsPackConfig =
            parse(file.readText(Charsets.UTF_8), origin = file.path)

        /** [load]'s string form — the seam unit tests drive. */
        fun parse(text: String, origin: String = "<memory>"): VitsPackConfig {
            val json = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw IllegalStateException("pack config is not valid JSON at $origin", t)
            }
            return try {
                val audio = json.getJSONObject("audio")
                val inference = json.getJSONObject("inference")
                val idMapJson = json.getJSONObject("phoneme_id_map")
                val idMap = HashMap<String, List<Int>>(idMapJson.length() * 2)
                for (key in idMapJson.keys()) {
                    val arr = idMapJson.getJSONArray(key)
                    idMap[key] = List(arr.length()) { i -> arr.getInt(i) }
                }
                VitsPackConfig(
                    sampleRate = audio.getInt("sample_rate"),
                    espeakVoice = json.getJSONObject("espeak").getString("voice"),
                    noiseScale = inference.getDouble("noise_scale").toFloat(),
                    lengthScale = inference.getDouble("length_scale").toFloat(),
                    noiseW = inference.getDouble("noise_w").toFloat(),
                    numSpeakers = json.optInt("num_speakers", 1),
                    phonemeIdMap = idMap,
                    languageCode = json.optJSONObject("language")
                        ?.optString("code")
                        ?.takeIf { it.isNotEmpty() }
                        ?: "",
                    dataset = json.optString("dataset", ""),
                )
            } catch (t: IllegalStateException) {
                throw t
            } catch (t: Throwable) {
                throw IllegalStateException("pack config at $origin is unusable: ${t.message}", t)
            }
        }
    }
}
