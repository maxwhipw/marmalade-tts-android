package app.marmalade.tts

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.MarmaladeTtsApplication.Companion.CATALOG_VERSION
import app.marmalade.tts.MarmaladeTtsApplication.Companion.needsCatalogSeed
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.db.MarmaladeDb
import app.marmalade.tts.di.AppModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalog seed's gate against a database Room's destructive fallback
 * wiped (#110): the DataStore stamp survives the wipe, so the stamp alone
 * left every voice list empty for good.
 *
 * The wipe is the real one: a database file at a version this build has no
 * path from (a newer build's, as on the 6a when a v14 sibling build was
 * replaced by a v13 one), opened through the app's own builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogSeedGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MarmaladeDb

    @Before
    fun leaveANewerDatabase() {
        context.deleteDatabase(DB_NAME)
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val newer = SQLiteDatabase.openOrCreateDatabase(file, null)
        newer.execSQL("CREATE TABLE voice_meta (id TEXT PRIMARY KEY NOT NULL)")
        newer.execSQL("INSERT INTO voice_meta (id) VALUES ('${KittenDirectVoiceCatalog.DEFAULT_VOICE_ID}')")
        newer.version = 99
        newer.close()
        db = AppModule.provideDatabase(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    // One test, not one per claim: Robolectric's SQLite kept the first
    // test's table at this path for the second (setup's CREATE TABLE failed
    // in full-suite runs), and the app's builder fixes the file name.
    @Test
    fun wipedDatabase_reseedsOnce_thenNotOnANormalStart() = runBlocking {
        val dao = db.voiceMetaDao()
        // The fallback dropped the newer build's rows...
        assertEquals(0, dao.count())
        // ...while the stamp still says this catalog was seeded.
        assertTrue(needsCatalogSeed(CATALOG_VERSION, dao))

        // The seed wrote its rows: a normal start doesn't reseed...
        dao.upsertAll(KittenDirectVoiceCatalog.voices)
        assertFalse(needsCatalogSeed(CATALOG_VERSION, dao))
        // ...and a catalog bump still does, over existing rows.
        assertTrue(needsCatalogSeed(CATALOG_VERSION - 1, dao))
    }

    private companion object {
        const val DB_NAME = "marmalade_db"
    }
}
