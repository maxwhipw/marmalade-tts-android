package app.marmalade.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release builds strip only `Log.d`/`Log.v` (proguard-rules.pro), so info,
 * warn and error lines reach logcat and every bug-report zip. None of them may
 * carry the text being spoken (#117, SEC-2; SECURITY.md "Log hygiene"). A
 * source scan, narrow on purpose: it catches the shapes that leaked before,
 * a `text="…"` field in a log summary and a log call interpolating a variable
 * that holds user text. Lengths and counts are fine.
 */
class ReleaseLogHygieneTest {

    private val sources: List<File> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun noLogSummaryCarriesATextField() {
        val hits = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (TEXT_FIELD.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("user text in a log summary:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun noReleaseLevelLogInterpolatesUserText() {
        val hits = sources.flatMap { file -> userTextLogCalls(file.readText()).map { "${file.name}: $it" } }
        assertTrue("user text at info/warn/error:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun scanSeesMultiLineCallsAndAllowsLengths() {
        // Guards the scanner itself, so a passing scan means something.
        assertEquals(1, userTextLogCalls("Log.w(\n    TAG,\n    \"bad input: ${'$'}text\",\n)").size)
        assertEquals(1, userTextLogCalls("Log.e(TAG, \"chunk ${'$'}{chunk.trim()} failed\", t)").size)
        assertEquals(0, userTextLogCalls("Log.i(TAG, \"${'$'}{text.length} chars, ${'$'}{chunks.size} chunks\")").size)
        assertEquals(0, userTextLogCalls("Log.d(TAG, \"${'$'}text\")").size)
        assertTrue(sources.size > 100)
    }

    private companion object {
        val TEXT_FIELD = Regex("""text=\\"\$""")
        val LOG_CALL = Regex("""Log\.[iwe]\(""")
        val USER_TEXT = Regex(
            """\$\{?(text|rawText|miniChunk|chunk|input|phonemes|ipa|rawIpa|normalized|line|sentence|utterance|snippet)\b""" +
                """(?!\.length|\.size)""",
        )

        /** Each `Log.i/w/e(…)` call in [source], up to its closing paren, that interpolates user text. */
        fun userTextLogCalls(source: String): List<String> =
            LOG_CALL.findAll(source).mapNotNull { match ->
                var depth = 0
                var end = match.range.last
                while (end < source.length) {
                    when (source[end]) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) break
                    }
                    end++
                }
                source.substring(match.range.first, minOf(end + 1, source.length))
                    .takeIf { USER_TEXT.containsMatchIn(it) }
                    ?.replace(Regex("""\s+"""), " ")
            }.toList()
    }
}
