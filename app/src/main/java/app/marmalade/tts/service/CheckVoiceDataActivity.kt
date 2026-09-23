package app.marmalade.tts.service

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.engine.kokoro.KokoroGermanEngine
import app.marmalade.tts.engine.api.CloudApiEngine
import app.marmalade.tts.engine.vits.VitsDirectEngine
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
 * [onCreate]. We report each catalog voice's language in the engine's
 * `lang-COUNTRY-VARIANT` ISO-639-3 form (Android's
 * `Locale.getISO3Language()` shape, the same convention every published
 * TTS engine uses). A voice is available exactly when
 * [MarmaladeTtsService.advertisableVoices] says so — the filter the
 * service's own negotiation callbacks use, so this report and
 * `onGetVoices` can't disagree (they did: VITS was missing here, so a
 * device whose only engine was a VITS pack reported nothing available).
 * Settings gates its Play-example button
 * and Language picker on the default locale appearing in
 * `EXTRA_AVAILABLE_VOICES`, so an empty available list greys both out.
 * When no engine is installed we still return `CHECK_VOICE_DATA_PASS`
 * with an empty available list — that's enough for the framework to
 * enumerate us; the user can then install engines through Marmalade.
 */
@AndroidEntryPoint
class CheckVoiceDataActivity : ComponentActivity() {

    @Inject lateinit var voiceDao: VoiceMetaDao

    @Inject lateinit var kittenDirect: KittenDirectEngine
    @Inject lateinit var kokoroDirect: KokoroDirectEngine
    @Inject lateinit var kokoroGerman: KokoroGermanEngine
    @Inject lateinit var pocket: PocketEngine
    @Inject lateinit var vits: VitsDirectEngine
    @Inject lateinit var cloudApi: CloudApiEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Runs blocking on the main thread because the contract requires
        // a synchronous setResult+finish. The DAO read is a single
        // indexed query against a tiny table (≤ ~80 rows) and each
        // install probe is a handful of stat calls, so the cost is well
        // under the ANR threshold.
        // Single snapshot of the Flow is sufficient — we're firing once
        // per CHECK_TTS_DATA dispatch, not subscribing.
        val voices = runBlocking { voiceDao.getAll().first() }
        val (available, unavailable) =
            classifyVoices(voices, ::isEngineInstalled) { vits.installedPackIds() }

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

    /**
     * On-disk install state per engine. VITS is absent on purpose: its
     * voices are judged per pack by [MarmaladeTtsService.advertisableVoices].
     * Developer-only rows (Pocket dev) and unknown engines are never
     * advertised.
     */
    private fun isEngineInstalled(engineName: String): Boolean = when (engineName) {
        KokoroDirectVoiceCatalog.ENGINE -> kokoroDirect.isInstalled()
        KokoroGermanVoiceCatalog.ENGINE -> kokoroGerman.isInstalled()
        KittenDirectVoiceCatalog.ENGINE -> kittenDirect.isInstalled()
        PocketVoiceCatalog.ENGINE -> pocket.isInstalled()
        CloudApiVoiceCatalog.ENGINE -> cloudApi.isInstalled()
        else -> false
    }

    companion object {
        private const val TAG = "MarmaladeTts.CheckData"

        /**
         * Split the catalog into available/unavailable language tags by
         * whether [MarmaladeTtsService.advertisableVoices] lets each voice
         * through; everything else (engine or pack not installed, an
         * unreleased pack, developer-only rows) counts as unavailable. Pure
         * so the classification is unit-testable without Robolectric.
         */
        internal fun classifyVoices(
            voices: List<VoiceMeta>,
            isEngineInstalled: (String) -> Boolean,
            installedVitsPacks: () -> Collection<String>,
        ): Pair<ArrayList<String>, ArrayList<String>> {
            val advertised = MarmaladeTtsService
                .advertisableVoices(voices, isEngineInstalled, installedVitsPacks)
                .mapTo(HashSet()) { it.id }
            val available = ArrayList<String>()
            val unavailable = ArrayList<String>()
            for (v in voices) {
                val tag = TtsLocales.bcp47ToTtsTag(v.languageCode) ?: continue
                if (v.id in advertised) {
                    if (!available.contains(tag)) available.add(tag)
                } else {
                    if (!unavailable.contains(tag) && !available.contains(tag)) {
                        unavailable.add(tag)
                    }
                }
            }
            // A language can be reported by both an installed and an
            // uninstalled engine — available wins, drop the duplicate.
            unavailable.removeAll(available)

            // English is the engine's baseline language even when nothing
            // is installed — declare it so the picker can list us
            // pre-install.
            if (available.isEmpty() && !unavailable.contains("eng-USA")) {
                unavailable.add("eng-USA")
            }
            return available to unavailable
        }
    }
}
