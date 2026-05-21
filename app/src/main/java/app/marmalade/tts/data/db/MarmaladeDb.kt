package app.marmalade.tts.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 *       personas"). v2→v3 is destructive: on upgrade, `voice_meta` is
 *       rebuilt from `KittenVoiceCatalog` at catalog defaults via the
 *       `onCreate` reseed, so any `isInstalled = true` flags the user
 *       flipped revert to `false`. Acceptable in v0.1 because the install
 *       state is re-derived from engine-directory existence the next time
 *       a synth attempt runs (KittenEngine.ensureModelLoaded surfaces
 *       missing files as ModelMissing, prompting reinstall). The
 *       [MIGRATION_2_3] skeleton below is a ready-to-wire non-destructive
 *       alternative — switch AppModule to use `.addMigrations(MIGRATION_2_3)`
 *       instead of `fallbackToDestructiveMigration()` once preserving
 *       user-toggled flags becomes worth it.
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

/**
 * v2 → v3 non-destructive migration. Adds the `voice_alias` table without
 * touching `voice_meta`, so user-toggled `isInstalled` flags survive the
 * upgrade. Currently unused — [AppModule] takes the destructive fallback
 * path (acceptable for v0.1, see the schema-history block above). Wire
 * this in by replacing `.fallbackToDestructiveMigration()` with
 * `.addMigrations(MIGRATION_2_3)` (and keeping fallback as a belt-and-
 * braces option for future hash drift).
 *
 * The CREATE TABLE statement is kept literally in sync with the exported
 * schema at `app/schemas/.../3.json` — any change to [VoiceAlias] must
 * update both.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `voice_alias` (" +
                "`name` TEXT NOT NULL, " +
                "`engine` TEXT NOT NULL, " +
                "`voiceId` TEXT NOT NULL, " +
                "`speed` REAL NOT NULL, " +
                "`effectPreset` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`))"
        )
    }
}
