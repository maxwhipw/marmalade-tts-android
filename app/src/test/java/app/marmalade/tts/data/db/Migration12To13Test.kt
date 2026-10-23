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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v12 → v13 ([MIGRATION_12_13], `voice_alias.sentencePauseMs`, #96) against
 * a real v12 database built from the exported `schemas/.../12.json`.
 *
 * The app's builder also arms `fallbackToDestructiveMigration()`, which
 * would hide a broken migration by wiping every alias. This test opens
 * the file with the migration alone, so Room's post-migration schema
 * check (against the v13 entities) fails loudly instead.
 *
 * room-testing's `MigrationTestHelper` reads schemas from instrumentation
 * assets, which a Robolectric unit test doesn't have, hence the
 * hand-built v12 file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration12To13Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    @Before
    fun createV12() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        val schema = JSONObject(File(SCHEMA_12).readText()).getJSONObject("database")
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
                "phonemizationLanguage, effectId, fallbackAliasId, expressiveness) VALUES " +
                "('a1', 'narrator', 'pocket-tts-en-v2026_04', 'pocket-tts-en-v2026_04:alba', 1.2, " +
                "'NONE', 1000, NULL, 'builtin:cave', NULL, 0.4)",
        )
        db.execSQL(
            "INSERT INTO app_alias_mapping (packageName, aliasId, displayName, createdAt) " +
                "VALUES ('org.example.reader', 'a1', 'Reader', 2000)",
        )
        db.version = 12
        db.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun openV13(): MarmaladeDb =
        Room.databaseBuilder(context, MarmaladeDb::class.java, DB_NAME)
            .addMigrations(MIGRATION_12_13)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `existing aliases and routes survive with the pause at Natural`() = runTest {
        val db = openV13()
        try {
            val alias = db.voiceAliasDao().findById("a1")
            assertNotNull(alias)
            alias!!
            assertEquals("narrator", alias.name)
            assertEquals("pocket-tts-en-v2026_04:alba", alias.voiceId)
            assertEquals(1.2f, alias.speed, 0f)
            assertEquals("builtin:cave", alias.effectId)
            assertEquals(0.4f, alias.expressiveness!!, 0f)
            assertEquals(0, alias.sentencePauseMs)
            assertEquals("a1", db.appAliasMappingDao().findByPackage("org.example.reader")?.aliasId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the new column stores a pause and goes back to Natural`() = runTest {
        val db = openV13()
        try {
            val dao = db.voiceAliasDao()
            val alias = dao.findById("a1")!!
            dao.upsert(alias.copy(sentencePauseMs = 500))
            assertEquals(500, dao.findById("a1")!!.sentencePauseMs)
            dao.upsert(alias.copy(sentencePauseMs = 0))
            assertEquals(0, dao.findById("a1")!!.sentencePauseMs)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-12-13.db"

        /** Unit tests run with the module directory as the working directory. */
        const val SCHEMA_12 = "schemas/app.marmalade.tts.data.db.MarmaladeDb/12.json"
    }
}
