package app.marmalade.tts.phonemizer

import org.json.JSONObject

/**
 * Loads the golden vectors in `german/fixtures.json` (copied verbatim from
 * scratch/kokoro-german-lab/fixtures/fixtures.json). Parsing uses `org.json`,
 * so callers must run under Robolectric — the plain-JVM `org.json` stub
 * returns empty objects.
 */
internal object GermanFixtures {

    data class IoVec(val input: String, val expected: String)

    /** A g2p_de span: exactly one of espeak (text+tied), override, or passthrough. */
    data class Span(
        val text: String?,
        val tied: String?,
        val override: String?,
        val passthrough: String?,
    )

    data class G2pDe(
        val text: String,
        val normalized: String,
        val spans: List<Span>,
        val expected: String,
    )

    data class G2pBg(val text: String, val tied: String, val expected: String)

    private val root: JSONObject by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("german/fixtures.json")) {
            "missing test resource german/fixtures.json"
        }
        JSONObject(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    fun normalizer(): List<IoVec> = ioVecs("normalizer")

    fun lookup(): List<IoVec> = ioVecs("lookup")

    private fun ioVecs(key: String): List<IoVec> {
        val arr = root.getJSONArray(key)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            IoVec(o.getString("input"), o.getString("expected"))
        }
    }

    fun g2pDe(): List<G2pDe> {
        val arr = root.getJSONArray("g2p_de")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val spansArr = o.getJSONArray("spans")
            val spans = (0 until spansArr.length()).map { j ->
                val s = spansArr.getJSONObject(j)
                Span(
                    text = s.optStringOrNull("text"),
                    tied = s.optStringOrNull("tied"),
                    override = s.optStringOrNull("override"),
                    passthrough = s.optStringOrNull("passthrough"),
                )
            }
            G2pDe(o.getString("text"), o.getString("normalized"), spans, o.getString("expected"))
        }
    }

    fun g2pBg(): List<G2pBg> {
        val arr = root.getJSONArray("g2p_bg")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            G2pBg(o.getString("text"), o.getString("tied"), o.getString("expected"))
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key)) getString(key) else null
}
