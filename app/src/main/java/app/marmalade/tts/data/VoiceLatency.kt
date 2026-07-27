package app.marmalade.tts.data

import app.marmalade.tts.data.cloud.CloudProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------------
// How long before you hear anything
// -----------------------------------------------------------------------------
//   cloud-providers.json `latency` per model ──┐
//                                              ├─► VoiceLatencyTracker.buckets
//   SettingsRepository.latencySamples ─────────┘         │
//   (rolling window of measured ms, written by           ▼
//    Synthesizer on every real synthesis)          the voice picker
//
// The seed exists because the badge is most useful in the picker, BEFORE a
// voice has ever been used — which is exactly when there is nothing to
// measure. It lives in the descriptor rather than in Kotlin because the
// descriptor is remotely fetchable and version-gated (see CloudProviders),
// so correcting a provider that got faster is a JSON publish, not a release.
//
// Measurement then wins on a per-device basis: the seed is somebody else's
// network, and the number that matters is the one this phone actually sees.
// -----------------------------------------------------------------------------

/**
 * How long a model makes you wait before the first audio arrives.
 *
 * Three buckets rather than a number because the underlying measurement is
 * noisy — a median over a handful of runs on a mobile network moves by
 * hundreds of milliseconds between sessions, and a figure that jitters
 * reads as broken. The buckets are wide enough to sit still.
 */
enum class LatencyBucket(val label: String) {
    INSTANT("Instant"),
    QUICK("Quick"),
    SLOW("Slow"),
    ;

    companion object {
        /**
         * Cut points, in milliseconds to first audio. Calibrated against
         * Max's ranking on a Pixel 8a (2026-07-25): on-device Kokoro
         * "basically instant"; MiniMax, ElevenLabs and xAI "slow but not
         * much more than local Kokoro"; Inworld and Gemini 3.1 Flash
         * "painfully slow". So the interesting line is not cloud-vs-device
         * — it runs between the merely-networked and the genuinely slow.
         *
         * The Instant cut moved 600 → 1000 ms on 2026-07-26: Gradium reads as
         * instant to Max but his device kept measuring it into Quick, and 600
         * was anchored on on-device Kokoro's ~50 ms, which no networked model
         * can meet. A second is the long-standing boundary for a response
         * still feeling immediate, and it lets a fast streaming cloud model
         * qualify without reaching the models Max actually calls slow.
         */
        private const val INSTANT_BELOW_MS = 1_000
        private const val QUICK_BELOW_MS = 1_800

        fun ofMillis(millis: Int): LatencyBucket = when {
            millis < INSTANT_BELOW_MS -> INSTANT
            millis < QUICK_BELOW_MS -> QUICK
            else -> SLOW
        }

        /** Parse a descriptor `latency` seed; unknown or absent → null. */
        fun parse(raw: String?): LatencyBucket? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * The grain latency is tracked at: the *model*, not the voice.
 *
 * Every Aria-vs-Bella under ElevenLabs Turbo goes through one endpoint and
 * one server-side pipeline, so per-voice samples would just be the same
 * distribution split eight ways and each arm would take eight times as long
 * to become trustworthy. On-device engines have no model level (the engine
 * is the model), so the engine name is the key.
 */
fun latencyKeyFor(voiceId: String, engineName: String): String {
    val ref = CloudApiVoiceCatalog.parseVoiceId(voiceId)
    return if (ref != null) "${ref.providerId}:${ref.modelId}" else engineName
}

/**
 * The read side of latency, as the UI needs it. A `fun interface` so a
 * ViewModel test can hand in a fixed map without standing up a
 * DataStore and a provider store — same shape as [VoicePathResolver]'s
 * [app.marmalade.tts.data.cloud.CloudProviderDirectory].
 */
fun interface VoiceLatencySource {
    fun buckets(): Flow<Map<String, LatencyBucket>>
}

/**
 * Merges descriptor seeds with this device's own measurements into one
 * `key → bucket` map for the picker.
 *
 * Recording is fire-and-forget from the synthesis path — a latency sample is
 * never worth failing or delaying a synthesis for, so [record] swallows its
 * own errors and callers don't await it.
 */
@Singleton
class VoiceLatencyTracker @Inject constructor(
    private val settings: SettingsRepository,
    private val providers: CloudProviderStore,
) : VoiceLatencySource {
    /**
     * Measured medians layered over descriptor seeds, keyed by
     * [latencyKeyFor]. A key with too few samples keeps its seed; a key with
     * neither is absent, and the picker shows no badge rather than a guess.
     */
    override fun buckets(): Flow<Map<String, LatencyBucket>> = merged

    private val merged: Flow<Map<String, LatencyBucket>> =
        settings.latencySamples.map { samples ->
            val merged = mutableMapOf<String, LatencyBucket>()
            for (provider in providers.providers()) {
                for (model in provider.models) {
                    model.latency?.let { merged["${provider.id}:${model.id}"] = it }
                }
            }
            for ((key, values) in samples) {
                if (values.size >= MIN_SAMPLES) {
                    merged[key] = LatencyBucket.ofMillis(median(values))
                }
            }
            merged
        }.flowOn(Dispatchers.IO)

    /**
     * Record that [voiceId] took [millis] to produce its first audio.
     *
     * Only short utterances count. A provider that buffers the whole
     * response before sending it — every MP3 model, see
     * `CloudApiEngine.synthesizeStream` — takes longer on longer input, so
     * mixing a two-word confirmation with a pasted article would rank models
     * by what the user happened to speak through them rather than by how
     * fast they are. The band is centred on the picker's preview phrase
     * ("Hello, I'm <name>.") and the notification-sized text this app mostly
     * speaks, which is both the comparable case and the common one.
     */
    suspend fun record(voiceId: String, engineName: String, millis: Long, charCount: Int) {
        if (charCount < MIN_CHARS || charCount > MAX_CHARS) return
        if (millis <= 0 || millis > IMPLAUSIBLE_MS) return
        settings.recordLatencySample(
            key = latencyKeyFor(voiceId, engineName),
            millis = millis.toInt(),
            keep = WINDOW,
        )
    }

    /**
     * [record], but at most [PER_WEEK] samples per model per week.
     *
     * For the system-TTS path, where synthesis happens because some other
     * app asked for it. Everything measured is speech the user already
     * triggered and already paid for — this never sends a request of its
     * own — but a heavy reader would still churn the ten-sample window
     * several times a day, so the badge would track this afternoon's
     * signal rather than the model. Three a week stretches the window
     * across roughly three weeks instead.
     */
    suspend fun recordMetered(voiceId: String, engineName: String, millis: Long, charCount: Int) {
        val key = latencyKeyFor(voiceId, engineName)
        if (!settings.claimLatencyQuota(key, week = now() / WEEK_MS, perWeek = PER_WEEK)) return
        record(voiceId, engineName, millis, charCount)
    }

    /**
     * Clock indirection for tests. Kept out of the `@Inject` constructor
     * so Hilt doesn't need a binding for `() -> Long` — same shape as
     * [app.marmalade.tts.ui.screen.AliasViewModel.now].
     */
    internal var now: () -> Long = { System.currentTimeMillis() }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        /** Below this, one slow run on a bad connection would set the badge. */
        const val MIN_SAMPLES = 3

        /** Rolling window; old samples fall off so a since-fixed provider recovers. */
        const val WINDOW = 10

        const val MIN_CHARS = 10
        const val MAX_CHARS = 120

        /** Longer than this and something hung — a timeout isn't a latency. */
        const val IMPLAUSIBLE_MS = 60_000

        /** Metered sampling budget, per model per week. */
        const val PER_WEEK = 3

        const val WEEK_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
