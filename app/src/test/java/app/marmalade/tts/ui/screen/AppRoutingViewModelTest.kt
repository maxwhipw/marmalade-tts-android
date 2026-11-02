package app.marmalade.tts.ui.screen

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// -----------------------------------------------------------------------------
// Data flow under test
// -----------------------------------------------------------------------------
//   AppRoutingViewModelTest
//     │
//     ├── seeds FakeAppAliasMappingDao with existing (package → alias) rows
//     ├── seeds FakeInstalledAppsProvider with a roster
//     ├── drives openSheet / toggle / saveRouting
//     └── asserts on:
//          ├── sheetState (which alias, which ticks)
//          ├── FakeAppAliasMappingDao.upserted / .deleted
//
// No Android runtime — pure JVM. The PackageManager roster sits behind
// InstalledAppsProvider precisely so this stays a plain JUnit test.
//
// `mappings` is a WhileSubscribed StateFlow, so tests that seed rows must
// prime it (`vm.mappings.first { it.isNotEmpty() }`) before any code path
// that reads `.value` — same rule as AliasViewModelTest's `aliases`.
// -----------------------------------------------------------------------------

/**
 * Covers the alias-first ⇄ app-first translation in [AppRoutingViewModel].
 *
 * That translation is the point of the class: the table is keyed by package
 * (one alias per app) while the sheet edits one alias at a time, so every save
 * is a diff — add the newly ticked, delete the unticked, and leave rows owned
 * by other aliases alone. The Pro gate rides the same path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppRoutingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // -- Opening the sheet ----------------------------------------------------

    @Test
    fun openSheet_ticksTheAppsAlreadyRoutedToThatAlias() = runTest {
        val vm = newViewModel(
            dao = FakeAppAliasMappingDao(
                listOf(
                    mapping("com.moon.reader", "narrator"),
                    mapping("com.bible.study", "narrator"),
                    mapping("com.pocket", "quick read"),
                ),
            ),
        )
        vm.mappings.first { it.isNotEmpty() }

        vm.openSheet("narrator", "narrator")

        val state = vm.sheetState.first()
        assertTrue("Sheet should be open", state.isOpen)
        assertEquals("narrator", state.aliasId)
        assertEquals(
            "Only narrator's apps should start ticked",
            setOf("com.moon.reader", "com.bible.study"),
            state.selected,
        )
    }

    @Test
    fun openSheet_loadsTheAppRoster() = runTest {
        val vm = newViewModel(roster = listOf(installed("com.moon.reader", "Moon+ Reader")))

        vm.openSheet("narrator", "narrator")

        assertEquals(
            listOf("com.moon.reader"),
            vm.installedApps.first().map { it.packageName },
        )
    }

    // -- Saving ---------------------------------------------------------------

    @Test
    fun saveRouting_addsTickedAppsAndRemovesUnticked() = runTest {
        val dao = FakeAppAliasMappingDao(listOf(mapping("com.moon.reader", "narrator")))
        val vm = newViewModel(
            dao = dao,
            roster = listOf(
                installed("com.moon.reader", "Moon+ Reader"),
                installed("com.bible.study", "Bible Study"),
            ),
        )
        vm.mappings.first { it.isNotEmpty() }

        vm.openSheet("narrator", "narrator")
        vm.toggle("com.bible.study") // tick a new one
        vm.toggle("com.moon.reader") // untick an existing one
        vm.saveRouting()

        assertEquals(listOf("com.bible.study"), dao.upserted.map { it.packageName })
        assertEquals("narrator", dao.upserted.single().aliasId)
        assertEquals(
            "The roster label should be cached on the new row",
            "Bible Study",
            dao.upserted.single().displayName,
        )
        assertEquals(listOf("com.moon.reader"), dao.deleted)
    }

    @Test
    fun saveRouting_stealingAnAppLeavesTheOtherAliasesRemainingRowsAlone() = runTest {
        val dao = FakeAppAliasMappingDao(
            listOf(
                mapping("com.pocket", "quick read"),
                mapping("com.maps", "quick read"),
            ),
        )
        val vm = newViewModel(
            dao = dao,
            roster = listOf(installed("com.pocket", "Pocket"), installed("com.maps", "Maps")),
        )
        vm.mappings.first { it.isNotEmpty() }

        vm.openSheet("narrator", "narrator")
        vm.toggle("com.pocket")
        vm.saveRouting()

        // The stolen row is re-pointed (PK replace), never deleted...
        assertEquals(listOf("com.pocket"), dao.upserted.map { it.packageName })
        assertEquals("narrator", dao.upserted.single().aliasId)
        assertTrue("Stealing must not delete anything", dao.deleted.isEmpty())
        // ...and the other alias keeps the app this sheet never touched.
        assertEquals(
            "quick read",
            dao.getAll().first().single { it.packageName == "com.maps" }.aliasId,
        )
    }

    @Test
    fun saveRouting_preservesCreatedAtWhenAnAppMovesBetweenAliases() = runTest {
        val dao = FakeAppAliasMappingDao(
            listOf(mapping("com.pocket", "quick read", createdAt = 1_000L)),
        )
        val vm = newViewModel(dao = dao, roster = listOf(installed("com.pocket", "Pocket")))
        vm.now = { 9_999L }
        vm.mappings.first { it.isNotEmpty() }

        vm.openSheet("narrator", "narrator")
        vm.toggle("com.pocket")
        vm.saveRouting()

        assertEquals(
            "A move should keep the original timestamp so list order stays stable",
            1_000L,
            dao.upserted.single().createdAt,
        )
    }

    @Test
    fun saveRouting_withNoChanges_writesNothing() = runTest {
        val dao = FakeAppAliasMappingDao(listOf(mapping("com.moon.reader", "narrator")))
        val vm = newViewModel(dao = dao, roster = listOf(installed("com.moon.reader", "Moon+")))
        vm.mappings.first { it.isNotEmpty() }

        vm.openSheet("narrator", "narrator")
        vm.saveRouting()

        assertTrue(dao.upserted.isEmpty())
        assertTrue(dao.deleted.isEmpty())
    }

    @Test
    fun dismissSheet_discardsTicksWithoutWriting() = runTest {
        val dao = FakeAppAliasMappingDao()
        val vm = newViewModel(dao = dao, roster = listOf(installed("com.moon.reader", "Moon+")))

        vm.openSheet("narrator", "narrator")
        vm.toggle("com.moon.reader")
        vm.dismissSheet()

        assertTrue(dao.upserted.isEmpty())
        assertFalse(vm.sheetState.first().isOpen)
    }

    @Test
    fun toggle_unticksAnExistingRouteAndSaveDeletesIt() = runTest {
        val dao = FakeAppAliasMappingDao(listOf(mapping("com.moon.reader", "narrator")))
        val vm = newViewModel(dao = dao)
        vm.mappings.first { it.isNotEmpty() }

        vm.openSheet("narrator", "narrator")
        vm.toggle("com.moon.reader")
        vm.saveRouting()

        assertEquals(listOf("com.moon.reader"), dao.deleted)
    }

    // -- A deleted alias's apps (GitHub #32) -----------------------------------
    // Deleting through the alias screen is AliasViewModelTest's
    // deletingAnAlias_freesItsAppsToBeRoutedElsewhere.

    @Test
    fun aMappingToAMissingAlias_isPurgedOnLoadAndCountsAsUnrouted() = runTest {
        // A row an older version left behind when it deleted an alias.
        val aliasDao = FakeAliasDao(listOf(alias("narrator"), alias("quick read")))
        val dao = FakeAppAliasMappingDao(
            listOf(
                mapping("com.maps", "f7d1a5a1-500c-gone"),
                mapping("com.pocket", "quick read"),
            ),
            aliases = aliasDao,
        )

        val vm = newViewModel(dao = dao, aliasDao = aliasDao)

        assertEquals(1, dao.orphanSweeps)
        assertEquals(listOf("com.pocket"), dao.getAll().first().map { it.packageName })
        // The live alias's route still shows as owned there, by name.
        assertEquals(listOf("com.pocket"), vm.mappings.first { it.isNotEmpty() }.map { it.packageName })
        assertEquals("quick read", vm.aliasNames.first { it.isNotEmpty() }["quick read"])
    }

    @Test
    fun mappings_hideARowNamingAMissingAlias_evenBeforeThePurge() = runTest {
        // The fake without an alias table can't purge, which stands in for
        // the moment between opening the screen and the sweep landing.
        val aliasDao = FakeAliasDao(listOf(alias("narrator")))
        val dao = FakeAppAliasMappingDao(
            listOf(mapping("com.maps", "gone"), mapping("com.moon.reader", "narrator")),
        )
        val vm = newViewModel(dao = dao, aliasDao = aliasDao, roster = listOf(installed("com.maps", "Maps")))

        assertEquals(listOf("com.moon.reader"), vm.mappings.first { it.isNotEmpty() }.map { it.packageName })

        // So the sheet offers the app as free, and ticking it re-routes it.
        vm.openSheet("narrator", "narrator")
        vm.toggle("com.maps")
        vm.saveRouting()
        assertEquals("narrator", dao.getAll().first().single { it.packageName == "com.maps" }.aliasId)
    }

    // -- helpers --------------------------------------------------------------

    /**
     * A ViewModel whose alias table holds every alias [dao]'s rows name, plus
     * "narrator", unless the test passes its own [aliasDao]: `mappings` hides
     * rows naming a missing alias.
     */
    private fun newViewModel(
        dao: FakeAppAliasMappingDao = FakeAppAliasMappingDao(),
        roster: List<InstalledApp> = emptyList(),
        aliasDao: FakeAliasDao = FakeAliasDao(
            (dao.getAll().value.map { it.aliasId } + "narrator").distinct().map(::aliasWithId),
        ),
    ) = AppRoutingViewModel(
        mappingDao = dao,
        aliasDao = aliasDao,
        appsProvider = FakeInstalledAppsProvider(roster),
    )

    /** An alias whose id and name are both [name], as [mapping] keys them. */
    private fun alias(name: String) = aliasWithId(name)

    private fun aliasWithId(id: String) = VoiceAlias(
        id = id,
        name = id,
        engine = "kitten-direct-v0_8",
        voiceId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
        speed = 1.0f,
        effectPreset = "NONE",
        createdAt = 0L,
    )

    private fun mapping(
        packageName: String,
        aliasName: String,
        createdAt: Long = 0L,
    ) = AppAliasMapping(
        packageName = packageName,
        aliasId = aliasName,
        displayName = null,
        createdAt = createdAt,
    )

    private fun installed(packageName: String, displayName: String) =
        InstalledApp(packageName = packageName, displayName = displayName, icon = null)
}
