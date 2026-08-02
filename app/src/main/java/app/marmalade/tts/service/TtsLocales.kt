package app.marmalade.tts.service

import android.speech.tts.TextToSpeech
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import java.util.Locale

/**
 * The one place that knows how Marmalade's BCP-47 voice languages
 * (`VoiceMeta.languageCode`, e.g. `"pt-BR"`) relate to the codes the
 * Android TTS framework speaks.
 *
 * The framework is inconsistent by design: `CHECK_TTS_DATA` wants
 * ISO-639-3 + ISO-3166-alpha-3 tags (`"por-BRA"`), while
 * `onIsLanguageAvailable` / `SynthesisRequest.getLanguage` arrive as
 * either the alpha-2 or the alpha-3 form depending on the caller. Both
 * shapes are handled here so [CheckVoiceDataActivity] and
 * [MarmaladeTtsService] cannot drift apart — which is exactly what they
 * did through v1.0.0-beta.1, the check activity advertising eight
 * non-English locales that the service then rejected.
 *
 * Compact lookup tables for the codes Marmalade actually ships, rather
 * than `Locale.getISO3Language()`, which depends on the device's ICU
 * tables and has been known to disagree across OEMs.
 */
internal object TtsLocales {

    /**
     * English is available whatever is installed. Every engine we ship
     * speaks it, and the framework enumerates the engine through this
     * answer before the user has installed anything — saying "not
     * supported" pre-install would hide Marmalade from the picker.
     */
    const val BASELINE_LANGUAGE = "en"

    /**
     * Availability of [lang]/[country] given the languages of the voices
     * that are actually installed ([installedLanguages] as BCP-47 codes,
     * i.e. `VoiceMeta.languageCode`).
     *
     * Returns the framework's `TextToSpeech.LANG_*` contract:
     * `LANG_COUNTRY_AVAILABLE` when language *and* country match an
     * installed voice, `LANG_AVAILABLE` for a language-only match, and
     * `LANG_NOT_SUPPORTED` otherwise.
     */
    fun availability(
        lang: String?,
        country: String?,
        installedLanguages: Collection<String>,
    ): Int {
        val language = toIso2Language(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val region = toIso2Region(country)
        if (language == BASELINE_LANGUAGE) {
            // Pre-existing behaviour, kept verbatim: en-US is the country
            // match, every other English region is language-level.
            return if (region == "US") {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        val installed = installedLanguages.mapNotNull { parse(it) }
        val languageMatch = installed.filter { it.first == language }
        return when {
            languageMatch.isEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
            region != null && languageMatch.any { it.second == region } ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    /**
     * Pick the voice to speak a [lang]/[country] request with, out of
     * [voices] (already filtered to what's installed).
     *
     * Kokoro wins ties: it is the only multilingual on-device engine, so
     * for anything but English it is usually the only real answer, and
     * for English it is the catalog's own default engine. Within an
     * engine the first catalog row for the language wins, which keeps
     * the choice stable across calls.
     */
    fun defaultVoiceFor(
        lang: String?,
        country: String?,
        voices: List<VoiceMeta>,
    ): VoiceMeta? {
        val language = toIso2Language(lang) ?: return null
        val region = toIso2Region(country)
        val candidates = voices.filter { parse(it.languageCode)?.first == language }
        if (candidates.isEmpty()) return null
        val kokoroFirst = compareByDescending<VoiceMeta> {
            it.engine == KokoroDirectVoiceCatalog.ENGINE
        }
        // Exact region first (en-GB request → bf_alice, not af_alloy),
        // then any voice of the language.
        return candidates
            .filter { region != null && parse(it.languageCode)?.second == region }
            .minWithOrNull(kokoroFirst)
            ?: candidates.minWithOrNull(kokoroFirst)
    }

    /** `Locale` to hand the framework for a voice's `languageCode`. */
    fun localeFor(bcp47: String): Locale = Locale.forLanguageTag(bcp47.replace('_', '-'))

    /**
     * Convert a BCP-47 tag like `"en-US"` to the TTS engine's ISO-639-3 +
     * ISO-3166-1-alpha-3 form (`"eng-USA"`). Variant (third component) is
     * empty for everything we ship.
     *
     * Returns null on inputs we can't parse — those voices are silently
     * dropped from the CHECK_TTS_DATA report rather than crashing the
     * check.
     */
    fun bcp47ToTtsTag(bcp47: String): String? {
        val (lang3, region3) = iso3TripleFor(bcp47) ?: return null
        return if (region3.isNotEmpty()) "$lang3-$region3" else lang3
    }

    /**
     * A voice's language as the `[language, country, variant]` triple
     * `TextToSpeechService.onGetLanguage` must return — ISO-639-3 plus
     * ISO-3166-alpha-3, variant always empty. Null when the language
     * isn't one we map.
     */
    fun iso3TripleFor(bcp47: String): Array<String>? {
        val (lang2, region2) = parse(bcp47) ?: return null
        val lang3 = LANG_2_TO_3[lang2] ?: return null
        return arrayOf(lang3, region2?.let { REGION_2_TO_3[it] ?: it } ?: "", "")
    }

    /** Normalise `"ja"`, `"jpn"`, `"JA"` → `"ja"`. Null when unknown. */
    fun toIso2Language(code: String?): String? {
        val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (c in LANG_2_TO_3) return c
        return LANG_3_TO_2[c]
    }

    /**
     * Normalise `"JP"`, `"JPN"`, `"jp"` → `"JP"`. Null only when the code
     * is absent; a region we don't have a mapping for is passed through
     * uppercased rather than dropped.
     */
    fun toIso2Region(code: String?): String? {
        val c = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (c in REGION_2_TO_3) return c
        return REGION_3_TO_2[c] ?: c
    }

    /** Split a BCP-47 tag into (lowercase alpha-2 lang, uppercase region?). */
    private fun parse(bcp47: String): Pair<String, String?>? {
        val parts = bcp47.split('-', '_')
        val lang = toIso2Language(parts.getOrNull(0)) ?: return null
        return lang to toIso2Region(parts.getOrNull(1))
    }

    // Every code pair below is exercised by at least one shipped Kokoro
    // voice (see KokoroDirectVoiceCatalog.languageFor). Grow them as new
    // engines/voices land — keep the maps small and obviously correct.
    private val LANG_2_TO_3: Map<String, String> = mapOf(
        "en" to "eng",
        "es" to "spa",
        "fr" to "fra",
        "hi" to "hin",
        "it" to "ita",
        "ja" to "jpn",
        "pt" to "por",
        "zh" to "zho",
    )

    private val REGION_2_TO_3: Map<String, String> = mapOf(
        "US" to "USA",
        "GB" to "GBR",
        "ES" to "ESP",
        "FR" to "FRA",
        "IN" to "IND",
        "IT" to "ITA",
        "JP" to "JPN",
        "BR" to "BRA",
        "CN" to "CHN",
    )

    private val LANG_3_TO_2: Map<String, String> =
        LANG_2_TO_3.entries.associate { (k, v) -> v to k }

    private val REGION_3_TO_2: Map<String, String> =
        REGION_2_TO_3.entries.associate { (k, v) -> v to k }
}
