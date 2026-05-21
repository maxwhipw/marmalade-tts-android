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
 * - v3: adds `voice_alias` table for user-saved voice aliases / personas
 *       (mirrors the CLI's `aliases:` block — see README "Voice aliases /
 *       personas"). Existing installs have no alias rows yet (the feature
 *       is brand-new), so v2→v3 also rides the destructive-migration
 *       fallback — no user data is lost. The `voice_meta` table is
 *       reseeded by [AppModule]'s onCreate callback after the wipe, so
 *       the user's voice picker stays populated.
 *
 * Schemas are exported under `app/schemas/` so future versions can write
 * migrations against the v3 hash without guesswork.
 */
@Database(
    entities = [VoiceMeta::class, VoiceAlias::class],
    version = 3,
    exportSchema = true,
)
abstract class MarmaladeDb : RoomDatabase() {
    abstract fun voiceMetaDao(): VoiceMetaDao
    abstract fun voiceAliasDao(): VoiceAliasDao
}
