package app.marmalade.tts.lang

import android.util.Log
import app.marmalade.tts.data.InstalledVoiceAssets
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.data.pickableVoices
import app.marmalade.tts.data.probeInstalledVoiceAssets
import app.marmalade.tts.install.EngineInstaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * Gathers what [VoiceForLanguage.choose] needs — the text's language, the
 * aliases and the pickable voices — and logs the one line per decision.
 * Shared by the reader (once per article) and the share route (once per
 * shared text) so the two can't disagree; see VoiceForLanguage.kt for the rule.
 */
@Singleton
class LanguageVoiceSelector internal constructor(
    private val detector: LangDetector,
    private val settings: SettingsRepository,
    private val aliasDao: VoiceAliasDao,
    private val voiceDao: VoiceMetaDao,
    /** What is on disk; injected so the choice is testable without an installer. */
    private val probeAssets: suspend () -> InstalledVoiceAssets,
) {

    @Inject
    constructor(
        detector: LangDetector,
        settings: SettingsRepository,
        aliasDao: VoiceAliasDao,
        voiceDao: VoiceMetaDao,
        installer: EngineInstaller,
    ) : this(
        detector = detector,
        settings = settings,
        aliasDao = aliasDao,
        voiceDao = voiceDao,
        probeAssets = {
            probeInstalledVoiceAssets(
                installer = installer,
                anyCloudKeySet = settings.anyCloudApiKeySet.firstOrNull() == true,
            )
        },
    )

    /**
     * The voice to speak [text] in. [subject] and [logTag] only shape the log
     * line (`D/ReaderVoice: article lang=…`, `D/ShareVoice: share lang=…`).
     */
    suspend fun select(text: String, subject: String, logTag: String): VoiceChoice {
        val language = detector.detect(text)
        val primary = settings.primaryAliasId.first()?.let { aliasDao.findById(it) }
        // With no primary alias the service speaks its own default voice.
        val primaryVoiceId = primary?.voiceId ?: KittenDirectVoiceCatalog.DEFAULT_VOICE_ID
        val decision = VoiceForLanguage.choose(
            language = language,
            primary = primary,
            primaryVoice = voiceDao.findById(primaryVoiceId),
            aliases = aliasDao.getAll().first(),
            pickable = voiceDao.getAll().first().pickableVoices(
                assets = probeAssets(),
                showDeveloper = settings.showDeveloperEngines.firstOrNull() == true,
            ),
        )
        Log.d(logTag, VoiceForLanguage.describe(subject, decision, primaryVoiceId))
        return decision.voice
    }
}
