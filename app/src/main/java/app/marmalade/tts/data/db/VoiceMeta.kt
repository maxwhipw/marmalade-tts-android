package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for a locally available TTS voice.
 *
 * One row per (engine, voice) pair. Powered by Room and exposed via
 * [VoiceMetaDao] as a Flow so the UI can observe install state changes.
 *
 * `isInstalled` is the source of truth for "does the underlying model
 * exist on disk?" — voice picker filters on it, system-TTS voice
 * negotiation rejects voices where it is false.
 *
 * @property id           Stable identifier — convention `"<engine>:<displayName>"`,
 *                        e.g. `"kitten:Bella"`. Used as the `voiceName` token
 *                        in the Android TTS API.
 * @property engine       Engine key (`"kitten"`, later `"piper"`, `"kokoro"`, …).
 *                        Matches the catalog in `marmalade-tts` CLI.
 * @property displayName  User-facing name as shown in the voice picker.
 * @property languageCode IETF BCP-47 tag, e.g. `"en-US"`. Used by
 *                        `onIsLanguageAvailable` lookups.
 * @property sampleRate   Native PCM sample rate the model emits (Hz).
 *                        Kitten-nano = 24000.
 * @property gender       `"female"`, `"male"`, or null if unspecified.
 * @property isInstalled  True if the engine's model files are present on
 *                        disk and synthesis would succeed. Defaults false.
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
)
