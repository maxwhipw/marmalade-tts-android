package app.marmalade.tts.data

import app.marmalade.tts.data.cloud.CloudModel
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.db.VoiceMeta

/**
 * Identity + id scheme for the Cloud API engine — hosted synthesis over an
 * OpenAI-compatible `/audio/speech` endpoint. Nothing runs on-device, so
 * there is no bundle, no download and no entry in
 * [app.marmalade.tts.install.EngineCatalog]; the engine is "installed"
 * when any provider has an API key configured.
 *
 * Unlike the local engines there is no static voice list here: the rows
 * come from provider descriptors + live discovery
 * ([app.marmalade.tts.data.cloud.CloudProviderStore]) and are written to
 * Room at sync time, so a provider adding a model or voices never needs
 * an app release.
 *
 * Voice id scheme: `cloud-api-v1:<provider>:<model>:<voice>` — the
 * provider + model ride inside the id so aliases, per-app routing and the
 * system-TTS voiceName token all keep working with zero schema changes.
 */
object CloudApiVoiceCatalog {

    const val ENGINE = "cloud-api-v1"
    const val SAMPLE_RATE = 24000

    /** User-facing engine label (shown as the picker section header etc.). */
    const val DISPLAY_NAME = "Cloud voices"

    /** A cloud voice id, decomposed. */
    data class CloudVoiceRef(
        val providerId: String,
        val modelId: String,
        val voice: String,
    )

    fun voiceId(providerId: String, modelId: String, voice: String): String =
        "$ENGINE:$providerId:$modelId:$voice"

    /**
     * Decompose a cloud voice id. Ids from the pre-provider era
     * (`cloud-api-v1:af_heart`, seeded by CATALOG_VERSION 25) resolve to
     * the provider+model that era was hardcoded to, so aliases created
     * against them keep speaking. Returns null for ids of other engines.
     */
    fun parseVoiceId(voiceId: String): CloudVoiceRef? {
        val parts = voiceId.split(':')
        if (parts.firstOrNull() != ENGINE) return null
        return when (parts.size) {
            2 -> CloudVoiceRef("venice", "tts-kokoro", parts[1])
            4 -> CloudVoiceRef(parts[1], parts[2], parts[3])
            else -> null
        }
    }

    /**
     * Build the Room row for one (provider, model, voice). Kokoro-style
     * voice keys (`af_heart`) carry language + gender in their
     * `<lang><gender>_` prefix — reuse the KokoroDirect helpers for
     * those. The shape check matters: blindly deriving from the first
     * letters would tag OpenAI's `ballad` en-GB and `echo` es-ES.
     */
    fun voiceMeta(provider: CloudProvider, model: CloudModel, voice: String): VoiceMeta {
        val kokoroStyle = voice.length > 2 && voice[2] == '_' &&
            (voice[1] == 'f' || voice[1] == 'm')
        return VoiceMeta(
            id = voiceId(provider.id, model.id, voice),
            engine = ENGINE,
            displayName = voice,
            languageCode = (if (kokoroStyle) KokoroDirectVoiceCatalog.languageFor(voice) else null)
                ?: "en-US",
            sampleRate = SAMPLE_RATE,
            gender = if (kokoroStyle) KokoroDirectVoiceCatalog.genderFor(voice) else null,
            isInstalled = false,
        )
    }

    /**
     * Short provenance line for a cloud voice row ("Venice · Kokoro"),
     * shown in the picker so a voice that exists under two providers or
     * models stays distinguishable. Falls back to raw ids for a voice
     * whose provider/model isn't in [providers] (e.g. removed remotely).
     */
    fun provenance(voiceId: String, providers: List<CloudProvider>): String? {
        val ref = parseVoiceId(voiceId) ?: return null
        val provider = providers.firstOrNull { it.id == ref.providerId }
        val model = provider?.models?.firstOrNull { it.id == ref.modelId }
        return "${provider?.displayName ?: ref.providerId} · ${model?.displayName ?: ref.modelId}"
    }
}
