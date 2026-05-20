package app.marmalade.tts.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Marmalade TTS Room database.
 *
 * Schema history:
 * - v1: placeholder `voices` table with only `id` (scaffold).
 * - v2: real `voice_meta` schema (id, engine, displayName, languageCode,
 *       sampleRate, gender, isInstalled). v1 carried no real data, so v1→v2
 *       uses Room's `fallbackToDestructiveMigration()` — see [AppModule].
 *
 * Schemas are exported under `app/schemas/` so future versions can write
 * migrations against the v2 hash without guesswork.
 */
@Database(
    entities = [VoiceMeta::class],
    version = 2,
    exportSchema = true,
)
abstract class MarmaladeDb : RoomDatabase() {
    abstract fun voiceMetaDao(): VoiceMetaDao
}
