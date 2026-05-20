package app.marmalade.tts.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Marmalade TTS Room database.
 *
 * Version 1. Starting with the [VoiceMeta] schema placeholder so Room's
 * KSP processor has at least one entity to process. Feature work will add
 * entities (aliases, synthesis history) via proper migrations.
 */
@Database(
    entities = [VoiceMeta::class],
    version = 1,
    exportSchema = true,
)
abstract class MarmaladeDb : RoomDatabase()
