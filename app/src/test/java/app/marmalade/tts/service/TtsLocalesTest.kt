package app.marmalade.tts.service

import android.speech.tts.TextToSpeech
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TtsLocales] — the shared BCP-47 ↔ framework mapping
 * behind CHECK_TTS_DATA and the service's language negotiation. These two
 * used to keep their own copies and drifted apart: the check activity
 * advertised eight non-English locales that the service then rejected.
 */
class TtsLocalesTest {

    private fun voice(id: String, engine: String, lang: String) = VoiceMeta(
        id = id,
        engine = engine,
        displayName = id.substringAfter(':'),
        languageCode = lang,
        sampleRate = 24_000,
        gender = null,
    )

    private val kokoro = KokoroDirectVoiceCatalog.ENGINE
    private val kitten = "kitten-direct-v0_8"

    // -- code normalisation ------------------------------------------------

    @Test
    fun `language codes normalise from both ISO2 and ISO3`() {
        assertEquals("ja", TtsLocales.toIso2Language("ja"))
        assertEquals("ja", TtsLocales.toIso2Language("jpn"))
        assertEquals("es", TtsLocales.toIso2Language("SPA"))
        assertEquals("en", TtsLocales.toIso2Language("eng"))
        assertNull(TtsLocales.toIso2Language("deu"))
        assertNull(TtsLocales.toIso2Language(""))
        assertNull(TtsLocales.toIso2Language(null))
    }

    @Test
    fun `region codes normalise from both ISO2 and ISO3`() {
        assertEquals("JP", TtsLocales.toIso2Region("JPN"))
        assertEquals("US", TtsLocales.toIso2Region("us"))
        // Regions we don't map pass through rather than vanishing.
        assertEquals("ZZ", TtsLocales.toIso2Region("zz"))
        assertNull(TtsLocales.toIso2Region(""))
    }

    @Test
    fun `bcp47 converts to the CHECK_TTS_DATA tag shape`() {
        assertEquals("eng-USA", TtsLocales.bcp47ToTtsTag("en-US"))
        assertEquals("por-BRA", TtsLocales.bcp47ToTtsTag("pt-BR"))
        assertNull(TtsLocales.bcp47ToTtsTag("xx-YY"))
    }

    // -- availability ------------------------------------------------------

    @Test
    fun `english is available whatever is installed`() {
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TtsLocales.availability("eng", "USA", emptyList()),
        )
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            TtsLocales.availability("en", "GB", emptyList()),
        )
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            TtsLocales.availability("en", null, emptyList()),
        )
    }

    @Test
    fun `installed non-english voices make their locale available`() {
        val installed = listOf("en-US", "ja-JP", "fr-FR")
        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TtsLocales.availability("jpn", "JPN", installed),
        )
        // Language matches, country doesn't — language-level answer.
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            TtsLocales.availability("fra", "CAN", installed),
        )
        assertEquals(
            TextToSpeech.LANG_AVAILABLE,
            TtsLocales.availability("ja", null, installed),
        )
    }

    @Test
    fun `languages with nothing installed are not supported`() {
        assertEquals(
            TextToSpeech.LANG_NOT_SUPPORTED,
            TtsLocales.availability("jpn", "JPN", listOf("en-US")),
        )
        // Never in the catalog at all.
        assertEquals(
            TextToSpeech.LANG_NOT_SUPPORTED,
            TtsLocales.availability("deu", "DEU", listOf("en-US", "ja-JP")),
        )
    }

    // -- default voice per locale ------------------------------------------

    @Test
    fun `default voice prefers kokoro and an exact region match`() {
        val voices = listOf(
            voice("$kitten:Bella", kitten, "en-US"),
            voice("$kokoro:af_alloy", kokoro, "en-US"),
            voice("$kokoro:bf_alice", kokoro, "en-GB"),
            voice("$kokoro:jf_alpha", kokoro, "ja-JP"),
        )
        assertEquals(
            "$kokoro:af_alloy",
            TtsLocales.defaultVoiceFor("eng", "USA", voices)?.id,
        )
        assertEquals(
            "$kokoro:bf_alice",
            TtsLocales.defaultVoiceFor("en", "GB", voices)?.id,
        )
        assertEquals(
            "$kokoro:jf_alpha",
            TtsLocales.defaultVoiceFor("jpn", "JPN", voices)?.id,
        )
        // No region match → first voice of the language, kokoro first.
        assertEquals(
            "$kokoro:af_alloy",
            TtsLocales.defaultVoiceFor("en", "AU", voices)?.id,
        )
        assertNull(TtsLocales.defaultVoiceFor("deu", "DEU", voices))
    }

    @Test
    fun `default voice falls back to a non-kokoro engine when it is all there is`() {
        val voices = listOf(voice("$kitten:Bella", kitten, "en-US"))
        assertEquals("$kitten:Bella", TtsLocales.defaultVoiceFor("eng", "USA", voices)?.id)
    }

    @Test
    fun `iso3 triple is the onGetLanguage shape`() {
        assertEquals(
            listOf("jpn", "JPN", ""),
            TtsLocales.iso3TripleFor("ja-JP")?.toList(),
        )
        assertNull(TtsLocales.iso3TripleFor("xx-YY"))
    }
}
