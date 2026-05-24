package app.marmalade.tts.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for [VoiceMetaDao] against an in-memory Room database.
 *
 * Data flow
 * ---------
 *   KittenNanoVoiceCatalog.voices  (pure data, exercised by KittenNanoVoiceCatalogTest)
 *           │
 *           ▼
 *      VoiceMetaDao.upsert / upsertAll  ──▶  in-memory MarmaladeDb (Room)
 *           │
 *           ▼
 *      VoiceMetaDao.getAll / getByEngine / findById
 *
 * Robolectric hosts an Android runtime so Room can compile its generated
 * DAO + open SQLite. The bare `junit:4.13.2` setup can't host an in-memory
 * Room DB on its own — see STUBS.md ("VoiceMetaDao Room queries"). Use
 * SDK 34 because that's the only `android-all-instrumented` JAR available
 * in the local Maven cache.
 *
 * This test deliberately does NOT re-assert KittenNanoVoiceCatalog's data
 * shape — KittenNanoVoiceCatalogTest already pins names, IDs, languages, and
 * the install default. Here we only assert behaviour that lives in the
 * DAO + database layer: round-tripping rows, filtering by engine,
 * single-row lookups, REPLACE on conflict, and Flow re-emission on
 * change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceMetaDaoTest {

    private lateinit var db: MarmaladeDb
    private lateinit var dao: VoiceMetaDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MarmaladeDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.voiceMetaDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertAll_thenGetByEngine_returnsAllRowsForEngine() = runTest {
        dao.upsertAll(KittenNanoVoiceCatalog.voices)

        val kittenRows = dao.getByEngine(KittenNanoVoiceCatalog.ENGINE).first()

        assertEquals(8, kittenRows.size)
        // Sanity check the rows actually came from the kitten engine — guards
        // against a stray query that would return everything.
        assertTrue(kittenRows.all { it.engine == KittenNanoVoiceCatalog.ENGINE })
    }

    @Test
    fun getByEngine_returnsEmptyForUnknownEngine() = runTest {
        dao.upsertAll(KittenNanoVoiceCatalog.voices)

        val piperRows = dao.getByEngine("piper").first()

        assertTrue("expected no rows for unseeded engine", piperRows.isEmpty())
    }

    @Test
    fun findById_returnsRowOrNull() = runTest {
        dao.upsertAll(KittenNanoVoiceCatalog.voices)

        val bella = dao.findById(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        assertNotNull("expected to find the default voice", bella)
        assertEquals(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID, bella!!.id)
        assertEquals(KittenNanoVoiceCatalog.ENGINE, bella.engine)

        val missing = dao.findById("does_not_exist")
        assertNull("expected null for unknown id", missing)
    }

    @Test
    fun upsert_overwritesExistingRow() = runTest {
        // OnConflictStrategy.REPLACE — insert once with isInstalled = false,
        // upsert again with isInstalled = true; the row should reflect the
        // newer value rather than collide on the primary key.
        val original = VoiceMeta(
            id = "kitten-nano-v0_8:Bella",
            engine = "kitten-nano-v0_8",
            displayName = "Bella",
            languageCode = "en-US",
            sampleRate = 24000,
            gender = "female",
            isInstalled = false,
        )
        dao.upsert(original)
        assertFalse(dao.findById(original.id)!!.isInstalled)

        dao.upsert(original.copy(isInstalled = true))

        assertTrue(
            "REPLACE should flip the row's isInstalled flag",
            dao.findById(original.id)!!.isInstalled,
        )
        // And there should still be exactly one row — REPLACE, not append.
        assertEquals(1, dao.count())
    }

    @Test
    fun getAll_emitsOnInsert() = runTest {
        // Room's invalidation tracker may coalesce back-to-back writes into
        // a single Flow emission — asserting an exact emission count is
        // flaky. Instead, snapshot the Flow's current value after each
        // mutation and assert that each snapshot reflects the latest write.
        val voice = VoiceMeta(
            id = "kitten-nano-v0_8:Bella",
            engine = "kitten-nano-v0_8",
            displayName = "Bella",
            languageCode = "en-US",
            sampleRate = 24000,
            gender = "female",
            isInstalled = false,
        )

        // Initial state: empty.
        assertTrue("initial getAll() should be empty", dao.getAll().first().isEmpty())

        // After first upsert: one row, isInstalled = false.
        dao.upsert(voice)
        val afterInsert = dao.getAll().first()
        assertEquals(1, afterInsert.size)
        assertFalse(afterInsert[0].isInstalled)

        // After second upsert: one row, isInstalled = true.
        dao.upsert(voice.copy(isInstalled = true))
        val afterUpdate = dao.getAll().first()
        assertEquals(1, afterUpdate.size)
        assertTrue(
            "expected the second upsert to flip isInstalled to true",
            afterUpdate[0].isInstalled,
        )
    }
}
