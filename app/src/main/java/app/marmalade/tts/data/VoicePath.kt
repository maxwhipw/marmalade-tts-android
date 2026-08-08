package app.marmalade.tts.data

import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.install.EngineCatalog
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------------
// One hierarchy for every voice
// -----------------------------------------------------------------------------
// Voices come in two shapes. On-device engines are two-level — the engine IS
// the model, so Kitten Nano simply has voices. Cloud voices are three-level:
// a provider (Venice) fronts many models (Kokoro, ElevenLabs Turbo, …) whose
// voice sets are disjoint.
//
// Rather than branch the UI on which shape a voice is, everything is expressed
// as the same three-part path — source › model › voice — and the middle part
// is *collapsed for display* when it carries no information (an on-device
// engine has exactly one model, itself). That is what lets the alias editor
// show one Voice row for both kinds and the picker use one drill-down.
//
// Before this existed, AliasScreen rendered `alias.voiceId.substringAfter(':')`,
// which assumed a 2-part id and printed the raw
// `cloud-api-v1 · venice:tts-elevenlabs-turbo-v2-5:Aria` for every cloud alias.
// -----------------------------------------------------------------------------

/**
 * A voice's location in the source › model › voice hierarchy, resolved to
 * display strings.
 *
 * @property source     Where the audio comes from — "On this device" or the
 *                      provider's name. Doubles as the cloud/offline cue.
 * @property model      The model's friendly name. Equal to [source] for
 *                      on-device engines, which is how [collapsed] decides
 *                      there is nothing to show.
 * @property voice      The voice's own name.
 * @property isCloud    True when synthesis needs the network.
 */
data class VoicePath(
    val source: String,
    val model: String,
    val voice: String,
    val isCloud: Boolean,
) {
    /**
     * The path without the redundant middle step: "Kitten Nano" on device,
     * "Venice › ElevenLabs Turbo v2.5" for cloud. Shown under the alias
     * editor's Voice row and as the picker breadcrumb.
     */
    val collapsed: String
        get() = if (model == source) model else "$source › $model"

    /** One-line summary for an alias card: "ElevenLabs Turbo v2.5 · Aria". */
    val summary: String get() = "$model · $voice"
}

/**
 * Resolves a stored `voiceId` to its display path.
 *
 * Cloud model names come from the provider descriptor, so a model the
 * descriptor doesn't list falls back to its raw id rather than throwing —
 * an alias can outlive the model it points at (the user downgrades, or a
 * provider retires a model), and a slightly ugly label beats a crash.
 */
@Singleton
class VoicePathResolver @Inject constructor(
    private val providers: CloudProviderDirectory,
) {
    fun resolve(voiceId: String, engineName: String): VoicePath {
        CloudApiVoiceCatalog.parseVoiceId(voiceId)?.let { ref ->
            val provider = providers.providerById(ref.providerId)
            val model = provider?.models?.firstOrNull { it.id == ref.modelId }
            return VoicePath(
                source = provider?.displayName ?: ref.providerId,
                model = model?.displayName ?: ref.modelId,
                voice = ref.voice,
                isCloud = true,
            )
        }
        // On-device: the engine is the model, and the id is `engine:voice`.
        // The id's voice key is machinery-facing (`am_adam`, `marius`) —
        // show the human form the picker uses instead.
        val engineLabel = EngineCatalog.byName(engineName)?.displayName ?: engineName
        val voiceKey = voiceId.substringAfterLast(':', voiceId)
        val voiceLabel = when (engineName) {
            KokoroDirectVoiceCatalog.ENGINE -> KokoroDirectVoiceCatalog.bareName(voiceKey)
            else -> voiceKey.replaceFirstChar { it.uppercase() }
        }
        return VoicePath(
            source = engineLabel,
            model = engineLabel,
            voice = voiceLabel,
            isCloud = false,
        )
    }
}
