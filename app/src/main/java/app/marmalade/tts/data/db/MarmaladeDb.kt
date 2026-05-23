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
 *       personas"). v2→v3 prefers [MIGRATION_2_3], which adds the alias
 *       table without touching `voice_meta` so any `isInstalled = true`
 *       flags the user flipped survive the upgrade. [AppModule] also keeps
 *       `fallbackToDestructiveMigration()` wired in as a belt-and-braces
 *       option for any future hash drift; in practice it should never
 *       fire on the v2→v3 path. If a destructive fallback ever does run,
 *       install state is re-derived from engine-directory existence the
 *       next time a synth attempt happens (KittenEngine.ensureModelLoaded
 *       surfaces missing files as ModelMissing, prompting reinstall).
 * - v4: adds `app_alias_mapping` table for per-app alias routing — when
 *       an external app calls the system TTS service without specifying
 *       a voice, [app.marmalade.tts.service.TtsRouter] looks up the
 *       caller's package here to decide which voice alias to use.
 *       v3→v4 uses [MIGRATION_3_4], a clean CREATE TABLE that leaves
 *       `voice_meta` and `voice_alias` untouched.
 *
 * Schemas are exported under `app/schemas/` so future versions can write
 * migrations against the v4 hash without guesswork.
 */
@Database(
    entities = [VoiceMeta::class, VoiceAlias::class, AppAliasMapping::class],
    version = 4,
    exportSchema = true,
)
abstract class MarmaladeDb : RoomDatabase() {
    abstract fun voiceMetaDao(): VoiceMetaDao
    abstract fun voiceAliasDao(): VoiceAliasDao
    abstract fun appAliasMappingDao(): AppAliasMappingDao
}

/**
 * v2 → v3 non-destructive migration. Adds the `voice_alias` table without
 * touching `voice_meta`, so user-toggled `isInstalled` flags survive the
 * upgrade. Wired in via `.addMigrations(MIGRATION_2_3)` in [AppModule];
 * `fallbackToDestructiveMigration()` is kept alongside as a belt-and-
 * braces option for any future hash drift but should not fire on v2→v3.
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

/**
 * v3 → v4 non-destructive migration. Adds the `app_alias_mapping` table
 * for per-app voice routing without touching `voice_meta` or `voice_alias`,
 * so user data on both surviving tables is preserved. Wired in via
 * `.addMigrations(MIGRATION_2_3, MIGRATION_3_4)` in [AppModule].
 *
 * The CREATE TABLE statement is kept literally in sync with the exported
 * schema at `app/schemas/.../4.json` — any change to [AppAliasMapping] must
 * update both.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_alias_mapping` (" +
                "`packageName` TEXT NOT NULL, " +
                "`aliasName` TEXT NOT NULL, " +
                "`displayName` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`packageName`))"
        )
    }
}
