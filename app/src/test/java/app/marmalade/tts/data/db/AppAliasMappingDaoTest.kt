package app.marmalade.tts.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The SQL that keeps a deleted alias's apps from staying stuck to it
 * (GitHub #32), against a real in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppAliasMappingDaoTest {

    private lateinit var db: MarmaladeDb
    private lateinit var aliases: VoiceAliasDao
    private lateinit var mappings: AppAliasMappingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MarmaladeDb::class.java)
            .allowMainThreadQueries()
            .build()
        aliases = db.voiceAliasDao()
        mappings = db.appAliasMappingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteOrphans_dropsRowsNamingAMissingAliasOnly() = runTest {
        aliases.upsert(alias("narrator"))
        mappings.upsert(AppAliasMapping("com.moon.reader", "narrator", null, 1L))
        mappings.upsert(AppAliasMapping("com.maps", "f7d1a5a1-500c-gone", null, 2L))

        mappings.deleteOrphans()

        assertEquals(listOf("com.moon.reader"), mappings.getAll().first().map { it.packageName })
    }

    @Test
    fun editingAnAlias_keepsItsApps() = runTest {
        // Why there's no foreign-key cascade: the alias upsert is a REPLACE,
        // a delete + insert, so a cascade would drop these rows on every edit.
        aliases.upsert(alias("narrator"))
        mappings.upsert(AppAliasMapping("com.moon.reader", "narrator", null, 1L))

        aliases.upsert(alias("narrator").copy(speed = 1.5f))
        mappings.deleteOrphans()

        assertEquals(listOf("com.moon.reader"), mappings.getAll().first().map { it.packageName })
    }

    @Test
    fun releaseAppsRoutedTo_dropsThatAliasesRowsOnly() = runTest {
        mappings.upsert(AppAliasMapping("com.maps", "doomed", null, 1L))
        mappings.upsert(AppAliasMapping("com.moon.reader", "narrator", null, 2L))

        mappings.releaseAppsRoutedTo("doomed")

        assertEquals(listOf("com.moon.reader"), mappings.getAll().first().map { it.packageName })
    }

    private fun alias(id: String) = VoiceAlias(
        id = id,
        name = id,
        engine = "kitten-direct-v0_8",
        voiceId = "kitten-direct-v0_8:expr-voice-2-f",
        speed = 1.0f,
        effectPreset = "NONE",
        createdAt = 0L,
    )
}
