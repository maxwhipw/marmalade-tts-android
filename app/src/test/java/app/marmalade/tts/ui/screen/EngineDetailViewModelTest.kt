package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.preprocessing.EngineProfiles
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * State-transition coverage for [EngineDetailViewModel].
 *
 * These cases were ported from the pre-v0.1.11 SettingsViewModelTest when
 * the per-engine preprocessing rules UI moved to the detail screen. The
 * write-back lifecycle (`toggleRule` / `resetRules`) is worth testing
 * because it's a "read latest, mutate by one member, write back" pattern
 * that's easy to break with a stale-StateFlow-read bug — same risk as
 * before, just hosted on a new VM.
 *
 * Also exercises:
 * - navArg routing via [SavedStateHandle] (a programming error would
 *   silently make every engine look like "kitten-nano-v0_8").
 * - install-state flow-through (the screen consumes this for its status
 *   header — regressing it would render every card "not installed").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun enabledRules_initially_reflects_default_profile() = runTest {
        val vm = newViewModel(engineName = "kitten-nano-v0_8")
        val emitted = vm.enabledRules.filter { it.isNotEmpty() }.first()
        assertEquals(EngineProfiles.defaultsFor("kitten-nano-v0_8"), emitted)
    }

    @Test
    fun toggleRule_off_removes_rule_from_set() = runTest {
        val settings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(engineName = "kitten-nano-v0_8", settings = settings)
        // Let the StateFlow seed with the default set first.
        vm.enabledRules.filter { it.isNotEmpty() }.first()

        vm.toggleRule(rule = "currency", enabled = false)

        val persisted = settings.enabledRules("kitten-nano-v0_8")
            .filter { "currency" !in it }
            .first()
        assertFalse("currency must be removed from persisted set", "currency" in persisted)
        // Spot-check an untouched rule remains.
        assertTrue("number must remain in persisted set", "number" in persisted)
    }

    @Test
    fun toggleRule_on_adds_rule_to_set() = runTest {
        val settings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        // Seed empty so we have something to add to. ("user disabled all".)
        settings.setEnabledRules("kitten-nano-v0_8", emptySet())
        val vm = newViewModel(engineName = "kitten-nano-v0_8", settings = settings)
        vm.enabledRules.first()  // let it settle

        vm.toggleRule(rule = "html", enabled = true)

        val persisted = settings.enabledRules("kitten-nano-v0_8")
            .filter { "html" in it }
            .first()
        assertTrue("html should be added to stored set", "html" in persisted)
    }

    @Test
    fun resetRules_restores_default_profile() = runTest {
        val settings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        // Stash a non-default state.
        settings.setEnabledRules("kitten-nano-v0_8", setOf("emoji"))
        val vm = newViewModel(engineName = "kitten-nano-v0_8", settings = settings)
        vm.enabledRules.first()

        vm.resetRules()

        val persisted = settings.enabledRules("kitten-nano-v0_8").first()
        assertEquals(EngineProfiles.defaultsFor("kitten-nano-v0_8"), persisted)
    }

    @Test
    fun toggleRule_off_then_on_round_trips_to_same_set() = runTest {
        // No-side-state invariant: off+on lands on the starting set.
        val settings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(engineName = "kitten-nano-v0_8", settings = settings)
        val before = settings.enabledRules("kitten-nano-v0_8").first()

        vm.toggleRule(rule = "currency", enabled = false)
        settings.enabledRules("kitten-nano-v0_8").filter { "currency" !in it }.first()
        vm.toggleRule(rule = "currency", enabled = true)
        val after = settings.enabledRules("kitten-nano-v0_8").filter { "currency" in it }.first()

        assertEquals(before, after)
    }

    @Test
    fun engineName_is_honored_from_nav_arg() = runTest {
        // The VM picks up the engine name from SavedStateHandle["name"].
        // Use "kokoro-v1_0" — its default profile is intentionally different
        // from "kitten-nano-v0_8" (Kokoro skips number / abbreviation / ordinal),
        // so a misrouted VM that ignored the arg would show kitten's
        // defaults and this assertion would catch it.
        val vm = newViewModel(engineName = "kokoro-v1_0")
        assertEquals("kokoro-v1_0", vm.engineName)

        val emitted = vm.enabledRules.filter { it.isNotEmpty() }.first()
        assertEquals(EngineProfiles.defaultsFor("kokoro-v1_0"), emitted)
    }

    @Test
    fun installState_flows_through_from_installer() = runTest {
        // The detail screen renders its status header off the VM's
        // installState StateFlow. We seed an on-disk engine directory so
        // the installer's internal stateFlow() initialises to Installed,
        // then assert that the VM's StateFlow surfaces that value.
        val tmpDir = createTempDirAndEngine("kitten-nano-v0_8")
        val installer = FakeInstaller(tmpDir)
        val vm = newViewModel(engineName = "kitten-nano-v0_8", installer = installer)

        val seen = vm.installState.filter { it is InstallState.Installed }.first()
        assertEquals(InstallState.Installed, seen)
    }

    private fun createTempDirAndEngine(engineName: String): java.io.File {
        // Build the directory layout EngineInstaller.stateFlow() probes
        // ("$filesDir/engines/$engineName" being a directory triggers the
        // Installed initial value). The contents don't matter for the VM
        // test — we never call verify() through to verifyLayout().
        val root = java.io.File.createTempFile("engine-detail-test-", "")
        root.delete()
        root.mkdirs()
        java.io.File(root, "engines/$engineName").mkdirs()
        return root
    }

    // -- helpers --------------------------------------------------------------

    private fun newViewModel(
        engineName: String,
        settings: FakeSettings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID),
        installer: EngineInstaller = FakeInstaller(),
    ): EngineDetailViewModel {
        val savedState = SavedStateHandle(mapOf(EngineDetailViewModel.NAV_ARG_NAME to engineName))
        return EngineDetailViewModel(
            settings = settings,
            installer = installer,
            savedStateHandle = savedState,
        )
    }
}

/**
 * In-memory [EngineInstaller] for the detail-screen VM tests.
 *
 * The base class's `state()` and `stateFlow()` are final + private — we
 * can't override them. Instead we route the base class through a
 * caller-supplied [filesRoot] so the initial state computed by the base
 * class's `stateFlow()` reflects the on-disk engine directory we set up
 * in the test. Pre-creating `${filesRoot}/engines/<name>/` makes the
 * initial state `Installed`; not creating it leaves it `NotInstalled`.
 *
 * `install()` and `uninstall()` are open in the base class — override
 * them with deterministic no-ops to avoid an HTTP fetch.
 */
private class FakeInstaller(
    filesRoot: java.io.File = java.io.File.createTempFile("engine-detail-", "").let { temp ->
        temp.delete()
        temp.mkdirs()
        temp
    },
) : EngineInstaller(
    filesDir = { filesRoot },
    kittenEngine = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used") },
) {
    override suspend fun install(
        engineName: String,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun uninstall(engineName: String): Result<Unit> =
        Result.success(Unit)
}
