package app.marmalade.tts.devbench

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Pure helpers for [EngineBenchService]: sentence-file parsing, WAV encoding
 * and the logcat lines the desktop side parses. Kept free of Android so the
 * JVM tests pin the formats.
 */
internal object BenchSupport {

    /** Locale-independent number formatting: the desktop parser expects a '.' decimal point. */
    private fun fmt(pattern: String, value: Double): String = String.format(Locale.ROOT, pattern, value)

    /** Logcat tag of every bench line — `adb logcat -s B1Bench StreamPerf`. */
    const val TAG = "B1Bench"

    /** One sentence per non-blank line; `#` starts a comment line; a literal `\n` is a line break. */
    fun parseSentences(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.replace("\\n", "\n") }.toList()

    /** 16-bit mono PCM WAV (RIFF, 44-byte header). */
    fun wavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buf.putShort(1).putShort(1) // PCM, mono
        buf.putInt(sampleRate).putInt(sampleRate * 2)
        buf.putShort(2).putShort(16) // block align, bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    /** Median of [values]; NaN for an empty list. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** One rendered sentence. [ttfaMs] is request → first chunk out of the engine. */
    data class SentenceResult(
        val index: Int,
        val chars: Int,
        val ttfaMs: Long,
        val totalMs: Long,
        val audioMs: Long,
    ) {
        /** Whole-sentence render time ÷ audio length. */
        val rtf: Double get() = if (audioMs > 0) totalMs.toDouble() / audioMs else Double.NaN
    }

    fun sentenceLine(run: String, engine: String, pass: Int, r: SentenceResult): String =
        "sentence run=$run engine=$engine pass=$pass idx=${r.index} chars=${r.chars} " +
            "ttfaMs=${r.ttfaMs} totalMs=${r.totalMs} audioMs=${r.audioMs} rtf=${fmt("%.3f", r.rtf)}"

    fun passSummaryLine(run: String, engine: String, pass: Int, results: List<SentenceResult>): String =
        "pass run=$run engine=$engine pass=$pass n=${results.size} " +
            "medianTtfaMs=${fmt("%.0f", median(results.map { it.ttfaMs.toDouble() }))} " +
            "medianRtf=${fmt("%.3f", median(results.map { it.rtf }))} " +
            "totalAudioMs=${results.sumOf { it.audioMs }} totalRenderMs=${results.sumOf { it.totalMs }}"
}
