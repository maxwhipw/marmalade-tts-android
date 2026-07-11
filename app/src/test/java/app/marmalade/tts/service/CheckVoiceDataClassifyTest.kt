package app.marmalade.tts.service

import app.marmalade.tts.data.db.VoiceMeta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CheckVoiceDataActivity.classifyVoices] — the pure
 * classification behind the CHECK_TTS_DATA report. Settings gates the
 * Play-example button and Language picker on the available list, so
 * getting this wrong greys both out (the v1.0.0-beta.1 bug: the report
 * keyed off the vestigial VoiceMeta.isInstalled flag and always came
 * back empty).
 */
class CheckVoiceDataClassifyTest {

    private fun voice(id: String, engine: String, lang: String) = VoiceMeta(
        id = id,
        engine = engine,
        displayName = id.substringAfter(':'),
        languageCode = lang,
        sampleRate = 24_000,
        gender = null,
    )

    @Test
    fun `voices of installed engines are available`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(
                voice("kitten:Bella", "kitten-direct-v0_8", "en-US"),
                voice("pocket:Lea", "pocket-tts-en-v2026_04", "en-US"),
            ),
            installedEngines = setOf("kitten-direct-v0_8"),
        )
        assertEquals(listOf("eng-USA"), available)
        assertEquals(emptyList<String>(), unavailable)
    }

    @Test
    fun `nothing installed reports english as unavailable baseline`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(voice("kitten:Bella", "kitten-direct-v0_8", "en-US")),
            installedEngines = emptySet(),
        )
        assertEquals(emptyList<String>(), available)
        assertEquals(listOf("eng-USA"), unavailable)
    }

    @Test
    fun `available wins even when an uninstalled engine reported the language first`() {
        // Kokoro (uninstalled) sorts before Kitten (installed) in the DAO's
        // engine-ordered read; eng-USA must still land in available only.
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(
                voice("kokoro:af_bella", "kokoro-direct-v1_0", "en-US"),
                voice("kokoro:ff_siwis", "kokoro-direct-v1_0", "fr-FR"),
                voice("kitten:Bella", "kitten-direct-v0_8", "en-US"),
            ),
            installedEngines = setOf("kitten-direct-v0_8"),
        )
        assertEquals(listOf("eng-USA"), available)
        assertEquals(listOf("fra-FRA"), unavailable)
    }

    @Test
    fun `unparseable language codes are dropped`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(voice("kitten:Bella", "kitten-direct-v0_8", "xx-YY")),
            installedEngines = setOf("kitten-direct-v0_8"),
        )
        assertEquals(emptyList<String>(), available)
        assertEquals(listOf("eng-USA"), unavailable)
    }
}
