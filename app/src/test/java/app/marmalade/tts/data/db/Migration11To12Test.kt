package app.marmalade.tts.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v11 → v12 ([MIGRATION_11_12], `voice_alias.expressiveness`) against a
 * real v11 database built from the exported `schemas/.../11.json`.
 *
 * The app's builder also arms `fallbackToDestructiveMigration()`, which
 * would hide a broken migration by wiping every alias. This test opens
 * the file with the migration alone, so Room's post-migration schema
 * check (against the v12 entities) fails loudly instead.
 *
 * room-testing's `MigrationTestHelper` reads schemas from instrumentation
 * assets, which a Robolectric unit test doesn't have, hence the
 * hand-built v11 file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration11To12Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    @Before
    fun createV11() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val schema = JSONObject(File(SCHEMA_11).readText()).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL(
            "INSERT INTO voice_alias (id, name, engine, voiceId, speed, effectPreset, createdAt, " +
                "phonemizationLanguage, effectId, fallbackAliasId) VALUES " +
                "('a1', 'narrator', 'pocket-tts-en-v2026_04', 'pocket-tts-en-v2026_04:alba', 1.2, " +
                "'NONE', 1000, NULL, 'builtin:cave', NULL)",
        )
        db.execSQL(
            "INSERT INTO app_alias_mapping (packageName, aliasId, displayName, createdAt) " +
                "VALUES ('org.example.reader', 'a1', 'Reader', 2000)",
        )
        db.version = 11
        db.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun openV12(): MarmaladeDb =
        Room.databaseBuilder(context, MarmaladeDb::class.java, DB_NAME)
            // The chain on to the current version: Room opens at the latest.
            .addMigrations(MIGRATION_11_12, MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `existing aliases and routes survive with expressiveness at the engine default`() = runTest {
        val db = openV12()
        try {
            val alias = db.voiceAliasDao().findById("a1")
            assertNotNull(alias)
            alias!!
            assertEquals("narrator", alias.name)
            assertEquals("pocket-tts-en-v2026_04:alba", alias.voiceId)
            assertEquals(1.2f, alias.speed, 0f)
            assertEquals("builtin:cave", alias.effectId)
            assertNull(alias.expressiveness)
            assertEquals("a1", db.appAliasMappingDao().findByPackage("org.example.reader")?.aliasId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the new column stores and clears a value`() = runTest {
        val db = openV12()
        try {
            val dao = db.voiceAliasDao()
            val alias = dao.findById("a1")!!
            dao.upsert(alias.copy(expressiveness = 0.3f))
            assertEquals(0.3f, dao.findById("a1")!!.expressiveness!!, 0f)
            dao.upsert(alias.copy(expressiveness = null))
            assertNull(dao.findById("a1")!!.expressiveness)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-11-12.db"

        /** Unit tests run with the module directory as the working directory. */
        const val SCHEMA_11 = "schemas/app.marmalade.tts.data.db.MarmaladeDb/11.json"
    }
}
