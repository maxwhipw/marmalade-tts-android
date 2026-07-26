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
 * - v5–v7: see [MIGRATION_4_5] / [MIGRATION_5_6] / [MIGRATION_6_7]
 *       (phonemizationLanguage column, the `effect` table, and the
 *       `effectId` column + back-fill, respectively).
 * - v8: [MIGRATION_7_8] deletes the orphaned sherpa `voice_meta` rows
 *       after sherpa-onnx was removed (no schema change).
 *
 * Schemas are exported under `app/schemas/` so future versions can write
 * migrations against the v4 hash without guesswork.
 */
@Database(
    entities = [VoiceMeta::class, VoiceAlias::class, AppAliasMapping::class, Effect::class],
    version = 9,
    exportSchema = true,
)
abstract class MarmaladeDb : RoomDatabase() {
    abstract fun voiceMetaDao(): VoiceMetaDao
    abstract fun voiceAliasDao(): VoiceAliasDao
    abstract fun appAliasMappingDao(): AppAliasMappingDao
    abstract fun effectDao(): EffectDao
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

/**
 * v4 → v5 non-destructive migration. Adds a nullable
 * `phonemizationLanguage` column to `voice_alias` — null means "auto-
 * derive from voice prefix" so existing rows behave as before. The new
 * column is what KokoroDirect uses to swap espeak's language per-alias
 * (so a jf_alpha alias gets ja phonemes instead of garbled en-us).
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `voice_alias` ADD COLUMN `phonemizationLanguage` TEXT")
    }
}

/**
 * v5 → v6 non-destructive migration. Adds the `effect` table for user-visible
 * audio effects (composable DSP chains). Additive CREATE TABLE only — no other
 * tables touched, so all user data survives. Built-in presets are seeded by
 * [app.marmalade.tts.MarmaladeTtsApplication] after the DB opens; the alias
 * `effectId` reference + back-fill from the old `effectPreset` column lands in
 * v6→v7 (E-D).
 *
 * The CREATE TABLE statement is kept literally in sync with the exported
 * schema at `app/schemas/.../6.json` — any change to [Effect] must update both.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `effect` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`isBuiltin` INTEGER NOT NULL, " +
                "`blocksJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v6 → v7 non-destructive migration. Adds a nullable `effectId` column to
 * `voice_alias` and back-fills it from the legacy `effectPreset` string so
 * existing aliases keep their effect: CAVE/ROBOT/TELEPHONE map to the seeded
 * built-in ids; NONE (and anything else) stays null = "no effect". The
 * `effectPreset` column is left in place (read by nothing going forward) so
 * this migration has a stable source to back-fill from. Keep the built-in id
 * strings in sync with [app.marmalade.tts.data.BuiltinEffects].
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `voice_alias` ADD COLUMN `effectId` TEXT")
        db.execSQL("UPDATE `voice_alias` SET `effectId` = 'builtin:cave' WHERE `effectPreset` = 'CAVE'")
        db.execSQL("UPDATE `voice_alias` SET `effectId` = 'builtin:robot' WHERE `effectPreset` = 'ROBOT'")
        db.execSQL("UPDATE `voice_alias` SET `effectId` = 'builtin:telephone' WHERE `effectPreset` = 'TELEPHONE'")
    }
}

/**
 * v7 → v8 data migration. sherpa-onnx was removed entirely in 1.0.0-beta.1,
 * so the four sherpa engines (Kokoro v1.0/v1.1, Kitten Nano/Mini) no longer
 * exist. This deletes their now-orphaned `voice_meta` rows so the voice
 * picker / system-TTS enumeration don't surface voices that can never
 * synthesize. Only touches `voice_meta`; `voice_alias` is left alone (an
 * alias pointing at a removed engine is inert and surfaces as ModelMissing
 * on synth, which the UI already handles by steering the user to reinstall).
 *
 * No schema change — table structure is identical to v7; this is a pure
 * data scrub, so the exported v8 schema hash matches v7's shape.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM `voice_meta` WHERE `engine` IN " +
                "('kokoro-v1_0', 'kokoro-v1_1', 'kitten-nano-v0_8', 'kitten-mini-v0_8')"
        )
    }
}

/**
 * v9: adds [VoiceAlias.fallbackAliasName] — the alias to speak with when a
 * cloud voice can't be reached.
 *
 * Additive nullable column, so existing rows need no back-fill: null means
 * "no fallback", which is exactly the pre-v9 behaviour. Written explicitly
 * rather than leaning on `fallbackToDestructiveMigration()`, which is armed
 * in AppModule and would wipe every alias and per-app route on a hash drift.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `voice_alias` ADD COLUMN `fallbackAliasName` TEXT")
    }
}
