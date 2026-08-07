package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for a locally available TTS voice.
 *
 * One row per (engine, voice) pair. Powered by Room and exposed via
 * [VoiceMetaDao] as a Flow so the UI can observe install state changes.
 *
 * `isInstalled` is vestigial: nothing in production ever flips it to
 * true (the engine installer doesn't write back to this table), so
 * consumers that need real install state derive it from disk via
 * `TtsEngine.isInstalled()` / `EngineInstaller.verify()` instead.
 *
 * @property id           Stable identifier — convention `"<engine>:<voiceKey>"`,
 *                        e.g. `"kitten:Bella"`, `"kokoro-direct-v1_0:af_bella"`.
 *                        Used as the `voiceName` token in the Android TTS API.
 *                        The key after `:` is the engine's internal voice
 *                        name; it may differ from [displayName] (Kokoro shows
 *                        `"🇺🇸 Bella"` while its key stays `af_bella`).
 * @property engine       Engine key (`"kitten"`, later `"piper"`, `"kokoro"`, …).
 *                        Matches the catalog in `marmalade-tts` CLI.
 * @property displayName  User-facing name as shown in the voice picker.
 * @property languageCode IETF BCP-47 tag, e.g. `"en-US"`. Used by
 *                        `onIsLanguageAvailable` lookups.
 * @property sampleRate   Native PCM sample rate the model emits (Hz).
 *                        Kitten-nano = 24000.
 * @property gender       `"female"`, `"male"`, or null if unspecified.
 * @property isInstalled  Vestigial — always false in production; see the
 *                        class kdoc. Defaults false.
 * @property sortOrder    Position within its engine's voice list (Max's
 *                        curated best-first order for Kitten; speaker-id
 *                        order for Kokoro). Rows the catalogs don't rank
 *                        (cloud voices, Pocket clones) keep the 999 default
 *                        and fall back to displayName ordering.
 */
@Entity(tableName = "voice_meta")
data class VoiceMeta(
    @PrimaryKey val id: String,
    val engine: String,
    val displayName: String,
    val languageCode: String,
    val sampleRate: Int,
    val gender: String?,
    val isInstalled: Boolean = false,
    val sortOrder: Int = 999,
) {
    /**
     * [displayName] with any leading flag emoji / decoration stripped —
     * espeak reads `🇺🇸` aloud as "United States", so anything that EMBEDS
     * the name in spoken text (the picker's preview phrase) must use this.
     */
    val spokenName: String
        get() = displayName.trimStart { !it.isLetterOrDigit() }
}
