package app.marmalade.tts.ui.screen

import app.marmalade.tts.data.KittenVoiceCatalog
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
 * State-transition coverage for [SettingsViewModel].
 *
 * Worth testing because [toggleRule] / [resetRules] orchestrate the
 * "read latest set, mutate by one member, write back" lifecycle that's
 * easy to break with a stale-StateFlow-read bug. The simpler bindings
 * (themePreset, keepEngineLoaded, aliasCount) are 1:1 pass-throughs to
 * the repository / DAO and not worth their own tests beyond what
 * SettingsRepositoryTest + the runtime sanity provides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun enabledRules_initially_reflects_default_profile() = runTest {
        val vm = newViewModel()
        // The kitten profile is the CLI's full rule set on a fresh
        // install. Wait for the first non-empty emission to settle.
        val emitted = vm.enabledRules.filter { it.isNotEmpty() }.first()
        assertEquals(EngineProfiles.defaultsFor("kitten"), emitted["kitten"])
    }

    @Test
    fun toggleRule_off_removes_rule_from_set() = runTest {
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings)
        // Seed the StateFlow so the initial emission is the default set.
        vm.enabledRules.filter { it.isNotEmpty() }.first()

        vm.toggleRule(engine = "kitten", rule = "currency", enabled = false)

        // Persistence: the repository's flow should now omit "currency".
        val persisted = settings.enabledRules("kitten")
            .filter { "currency" !in it }
            .first()
        assertFalse("currency must be removed from persisted set", "currency" in persisted)
        // Spot-check a rule that wasn't touched is still in.
        assertTrue("number must remain in persisted set", "number" in persisted)
    }

    @Test
    fun toggleRule_on_adds_rule_to_set() = runTest {
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        // Pre-seed the engine's stored set to empty so we have something
        // to add to. (The repository's flow falls back to the defaults
        // when nothing is stored; explicit empty set means "the user
        // disabled everything".)
        settings.setEnabledRules("kitten", emptySet())
        val vm = newViewModel(settings = settings)
        vm.enabledRules.first()  // let the flow settle

        vm.toggleRule(engine = "kitten", rule = "html", enabled = true)

        val persisted = settings.enabledRules("kitten")
            .filter { "html" in it }
            .first()
        assertTrue("html should be added to stored set", "html" in persisted)
    }

    @Test
    fun resetRules_restores_default_profile() = runTest {
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        // Stash an obviously-non-default state.
        settings.setEnabledRules("kitten", setOf("emoji"))
        val vm = newViewModel(settings = settings)
        vm.enabledRules.first()

        vm.resetRules(engine = "kitten")

        val persisted = settings.enabledRules("kitten").first()
        assertEquals(EngineProfiles.defaultsFor("kitten"), persisted)
    }

    @Test
    fun toggleRule_off_then_on_round_trips_to_same_set() = runTest {
        // Pin the "no accidental side-state" invariant. After off+on the
        // stored set should be identical to the starting point.
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings)
        val before = settings.enabledRules("kitten").first()

        vm.toggleRule(engine = "kitten", rule = "currency", enabled = false)
        settings.enabledRules("kitten").filter { "currency" !in it }.first()
        vm.toggleRule(engine = "kitten", rule = "currency", enabled = true)
        val after = settings.enabledRules("kitten").filter { "currency" in it }.first()

        assertEquals(before, after)
    }

    // -- helpers --------------------------------------------------------------

    private fun newViewModel(
        settings: FakeSettings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID),
    ): SettingsViewModel {
        // The VM constructor needs a VoiceAliasDao for the aliasCount flow.
        // Hand it an empty in-memory one — aliasCount is irrelevant for
        // these preprocessing-focused tests.
        return SettingsViewModel(
            settings = settings,
            voiceAliasDao = FakeAliasDao(),
        )
    }
}
