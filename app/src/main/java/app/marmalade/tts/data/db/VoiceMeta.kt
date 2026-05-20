package app.marmalade.tts.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for a locally available TTS voice.
 *
 * Schema placeholder — fields will be expanded when the voice picker
 * feature is implemented (v0.1). The table exists now so Room can build
 * the database schema; migrations will add columns as needed.
 *
 * @param id  Stable voice identifier (e.g., "kitten/bella").
 */
@Entity(tableName = "voices")
data class VoiceMeta(
    @PrimaryKey val id: String,
)
