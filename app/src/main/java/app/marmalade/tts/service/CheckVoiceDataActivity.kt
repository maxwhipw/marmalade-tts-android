package app.marmalade.tts.service

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Android invokes this activity (via the `CHECK_TTS_DATA` intent) to
 * confirm voice data is present before listing Marmalade in the system
 * TTS picker. Without it, the framework logs
 * `voice data integrity check failed` and hides the engine.
 *
 * No UI — themed `@android:style/Theme.NoDisplay` and finished from
 * [onCreate]. We query [VoiceMetaDao] for installed voices and report
 * them in the engine's `lang-COUNTRY-VARIANT` ISO-639-3 form (Android's
 * `Locale.getISO3Language()` shape, the same convention every published
 * TTS engine uses). When no voices are installed we still return
 * `RESULT_OK` with an empty `EXTRA_AVAILABLE_VOICES` list — that's
 * enough for the framework to enumerate us; the user can then install
 * voices through Marmalade's in-app installer.
 */
@AndroidEntryPoint
class CheckVoiceDataActivity : ComponentActivity() {

    @Inject lateinit var voiceDao: VoiceMetaDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Runs blocking on the main thread because the contract requires
        // a synchronous setResult+finish. The DAO read is a single
        // indexed query against a tiny table (≤ ~80 rows), so the cost
        // is well under the ANR threshold.
        // Single snapshot of the Flow is sufficient — we're firing once
        // per CHECK_TTS_DATA dispatch, not subscribing.
        val installedVoices = runBlocking { voiceDao.getAll().first() }

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()
        for (v in installedVoices) {
            val tag = bcp47ToTtsTag(v.languageCode) ?: continue
            if (v.isInstalled) {
                if (!available.contains(tag)) available.add(tag)
            } else {
                if (!unavailable.contains(tag) && !available.contains(tag)) {
                    unavailable.add(tag)
                }
            }
        }

        // English is the engine's baseline language even when nothing is
        // installed — declare it so the picker can list us pre-install.
        if (available.isEmpty() && !unavailable.contains("eng-USA")) {
            unavailable.add("eng-USA")
        }

        val data = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                available,
            )
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                unavailable,
            )
        }

        Log.d(
            TAG,
            "CHECK_TTS_DATA: available=$available unavailable=$unavailable",
        )

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data)
        finish()
    }

    companion object {
        private const val TAG = "MarmaladeTts.CheckData"

        /**
         * Convert a BCP-47 tag like `"en-US"` to the TTS engine's
         * ISO-639-3 + ISO-3166-1-alpha-3 form (`"eng-USA"`). Variant
         * (third component) is empty for everything we ship.
         *
         * Returns null on inputs we can't parse — those voices are
         * silently dropped from the report rather than crashing the
         * check.
         */
        internal fun bcp47ToTtsTag(bcp47: String): String? {
            val parts = bcp47.split('-', '_')
            val lang2 = parts.getOrNull(0)?.lowercase() ?: return null
            val region2 = parts.getOrNull(1)?.uppercase()
            val lang3 = LANG_2_TO_3[lang2] ?: return null
            val region3 = region2?.let { REGION_2_TO_3[it] ?: it }
            return if (region3 != null) "$lang3-$region3" else lang3
        }

        // Compact lookup tables for the codes Marmalade actually ships
        // today. We can grow these as new engines/voices land — keep the
        // map small + obviously correct rather than pulling in
        // `Locale.getISO3Language()` which depends on the device's ICU
        // tables and has been known to disagree across OEMs.
        //
        // v0.1.19: expanded for the multi-lang Kokoro upgrade. Every code
        // pair below is exercised by at least one shipped Kokoro voice
        // (see KokoroVoiceCatalog.languageFor).
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
    }
}
