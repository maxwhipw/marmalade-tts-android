package app.marmalade.tts.phonemizer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.JsonReader
import android.util.Log
import java.io.File
import java.io.InputStreamReader
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

// -----------------------------------------------------------------------------
// OpenPhonemizer — text → IPA for the Kokoro & Kitten TTS engines
// -----------------------------------------------------------------------------
//
// Two-tier lookup:
//   1. Dictionary cache — a precomputed word→IPA map of common English
//      words. O(1) hash lookup, no model run. Optional: the engine
//      passes the dictionary path if it has one. The first bundle ships
//      without a dictionary; a later bundle adds one generated from
//      CMUDict.
//   2. Neural fallback — DeepPhonemizer seq2seq ONNX. Runs per OOV word
//      and the result is memoised in-process so repeats are free.
//
// Inputs:
//   - `modelPath`        — open-phonemizer.onnx on local disk
//   - `dictionaryPath`   — optional dictionary.json on local disk
//   - `env`              — shared OrtEnvironment (engine owns it)
//
// Outputs:
//   - IPA string, words separated by spaces, punctuation preserved
//     adjacent to the preceding token (Kokoro/Kitten interpret commas
//     and full stops as prosody cues, so they must survive).
//
// Threading:
//   - phonemize() is safe to call from one thread at a time. The OOV
//     cache is concurrent but the ORT session itself is not — callers
//     coming from multiple threads must serialise externally.
//
// Provenance:
//   - This file is original Kotlin written from a reading of the
//     DeepPhonemizer algorithm and the BSD-3 OpenPhonemizer Python
//     wrapper (https://github.com/NeuralVox/OpenPhonemizer). The ONNX
//     weights are downloaded from openphonemizer/ckpt on HuggingFace
//     and are BSD-3-Clause Clear licensed; see LICENSES/.
// -----------------------------------------------------------------------------

private const val TAG = "OpenPhonemizer"

/** Splits a sentence into runs of word characters / apostrophes / punctuation. */
private val TOKEN_REGEX = Regex("""[\p{L}\p{N}']+|[^\p{L}\p{N}\s]""")

/** Punctuation that hugs the *previous* token: "Hi," not "Hi ,". */
private val TRAILING_PUNCT = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '”')

/** Punctuation that hugs the *next* token: "(hi" not "( hi". */
private val LEADING_PUNCT = setOf('(', '[', '{', '«', '“')

class OpenPhonemizer(
    modelPath: String,
    dictionaryPath: String?,
    private val env: OrtEnvironment,
) {

    private val session: OrtSession
    private val dictionary: Map<String, String>
    private val oovCache = ConcurrentHashMap<String, String>()

    init {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelPath, opts)
        dictionary = dictionaryPath?.let(::loadDictionary) ?: emptyMap()
        Log.i(TAG, "loaded model=$modelPath dictionary=${dictionary.size} words")
    }

    /**
     * Convert a sentence to a single IPA string.
     *
     * Words are looked up in the dictionary first; misses fall through to
     * the neural model and are cached for subsequent calls. Punctuation
     * is preserved with the spacing convention Kokoro/Kitten expect.
     */
    fun phonemize(text: String): String {
        val sb = StringBuilder()
        for (match in TOKEN_REGEX.findAll(text)) {
            val token = match.value
            val firstChar = token[0]
            when {
                firstChar.isLetterOrDigit() || firstChar == '\'' -> appendWord(sb, token)
                firstChar in TRAILING_PUNCT -> {
                    // glue to previous token: kill any trailing space we added after the last word
                    if (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
                    sb.append(token)
                }
                firstChar in LEADING_PUNCT -> sb.append(token)
                else -> sb.append(token)
            }
        }
        return sb.toString().trim()
    }

    private fun appendWord(sb: StringBuilder, token: String) {
        if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() !in LEADING_PUNCT) {
            sb.append(' ')
        }
        val key = token.lowercase()
        val cached = oovCache[key]
        val ipa = dictionary[key] ?: cached ?: phonemizeOov(key).also { oovCache[key] = it }
        if (cached == null && !dictionary.containsKey(key)) {
            Log.d(TAG, "word='$key' -> ipa='$ipa'")
        }
        sb.append(ipa)
    }

    /** Run the seq2seq model on a single word and CTC-decode the output. */
    private fun phonemizeOov(word: String): String {
        val ids = encodeWord(word)
        if (ids.isEmpty()) return ""

        val input = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(ids),
            longArrayOf(1, ids.size.toLong()),
        )
        try {
            val results = session.run(mapOf("text" to input))
            try {
                // Output shape [1, seq_len, 64]; pick batch 0.
                @Suppress("UNCHECKED_CAST")
                val logits = (results[0].value as Array<Array<FloatArray>>)[0]
                return ctcDecode(logits)
            } finally {
                results.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "neural phonemize failed for \"$word\": ${e.message}")
            return ""
        } finally {
            input.close()
        }
    }

    fun close() {
        session.close()
    }

    // -- helpers ---------------------------------------------------------------

    /**
     * Stream-parse the en_us section of dictionary.json into a HashMap.
     *
     * The file is a single JSON object keyed by language code; we only
     * want one language but the whole file is ~10 MB, so we use a
     * streaming reader to avoid the 4-5× peak memory inflation a full
     * Gson/Moshi parse would cause.
     */
    private fun loadDictionary(path: String): Map<String, String> {
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "dictionary not found at $path — neural fallback only")
            return emptyMap()
        }
        return try {
            val out = HashMap<String, String>(140_000)
            file.inputStream().use { stream ->
                JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "en_us") {
                            r.beginObject()
                            while (r.hasNext()) out[r.nextName()] = r.nextString()
                            r.endObject()
                        } else {
                            r.skipValue()
                        }
                    }
                    r.endObject()
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "dictionary parse failed", e)
            emptyMap()
        }
    }
}
