package app.marmalade.tts.reader

import android.util.Log
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.data.pickableVoices
import app.marmalade.tts.data.probeInstalledVoiceAssets
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.lang.LangDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * The production [ReaderVoicePicker]: gathers the article's language, the
 * aliases and the pickable voices, and hands them to [ReaderVoiceChooser].
 * See that file's header for the rule.
 */
@Singleton
class LanguageAwareReaderVoicePicker @Inject constructor(
    private val detector: LangDetector,
    private val settings: SettingsRepository,
    private val aliasDao: VoiceAliasDao,
    private val voiceDao: VoiceMetaDao,
    private val installer: EngineInstaller,
) : ReaderVoicePicker {

    override suspend fun voiceFor(article: ReaderArticle): ReaderVoice {
        val language = detector.detect(ReaderVoiceChooser.detectionSample(article))
        val primary = settings.primaryAliasId.first()?.let { aliasDao.findById(it) }
        // With no primary alias the service speaks its own default voice.
        val primaryVoiceId = primary?.voiceId ?: KittenDirectVoiceCatalog.DEFAULT_VOICE_ID

        val decision = ReaderVoiceChooser.choose(
            language = language,
            primary = primary,
            primaryVoice = voiceDao.findById(primaryVoiceId),
            aliases = aliasDao.getAll().first(),
            pickable = pickableVoices(),
        )
        Log.d(TAG, ReaderVoiceChooser.describe(decision, primaryVoiceId))
        return decision.voice
    }

    /** Same filter as the voice picker and the alias editor. */
    private suspend fun pickableVoices() = voiceDao.getAll().first().pickableVoices(
        assets = probeInstalledVoiceAssets(
            installer = installer,
            anyCloudKeySet = settings.anyCloudApiKeySet.firstOrNull() == true,
        ),
        showDeveloper = settings.showDeveloperEngines.firstOrNull() == true,
    )

    private companion object {
        const val TAG = "ReaderVoice"
    }
}
