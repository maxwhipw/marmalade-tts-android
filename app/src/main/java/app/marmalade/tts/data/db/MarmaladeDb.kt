package app.marmalade.tts.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Marmalade TTS Room database.
 *
 * Version 1 — no entities yet. Entities for voices, aliases, and synthesis
 * history will be added in v0.1 feature work. Use a migration when adding
 * the first entity rather than destructive recreation.
 */
@Database(
    entities = [],
    version = 1,
    exportSchema = true,
)
abstract class MarmaladeDb : RoomDatabase()
