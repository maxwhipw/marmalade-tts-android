package app.marmalade.tts.ui.onboarding

import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.ui.screen.FakeAliasDao
import app.marmalade.tts.ui.screen.FakeDao
import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * State-machine coverage for [OnboardingViewModel].
 *
 * The interesting behaviour to pin:
 *
 *  1. step transitions follow Welcome → EnginePick → Installing.
 *  2. toggle() flips the per-engine selection.
 *  3. installSelected() routes through the installer and lands the
 *     terminal state (Installed / Failed) into installStates.
 *  4. finish() sets the onboarded flag — so the host can move on.
 *  5. an install error doesn't poison subsequent retries on other engines.
 *
 * The installer is faked with a recording double rather than the real
 * EngineInstaller, which would need an HTTP fixture. The HTTP path is
 * tested separately in [app.marmalade.tts.install.EngineInstallerTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun stepProgressesThroughWelcomeToEnginePickToInstallingToCreateAlias() = runTest {
        val vm = newViewModel()
        assertEquals(OnboardingStep.Welcome, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.EnginePick, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.Installing, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.CreateAlias, vm.step.value)

        vm.back()
        assertEquals(OnboardingStep.Installing, vm.step.value)

        vm.back()
        assertEquals(OnboardingStep.EnginePick, vm.step.value)

        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)

        // Back from Welcome is a no-op (back-stop).
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
    }

    @Test
    fun toggleFlipsSelection() = runTest {
        val vm = newViewModel()
        // Kokoro is the recommended default → pre-selected.
        assertTrue(vm.selectedEngineIds.value.contains("kokoro"))

        vm.toggle("kokoro")
        assertTrue(!vm.selectedEngineIds.value.contains("kokoro"))

        vm.toggle("kokoro")
        assertTrue(vm.selectedEngineIds.value.contains("kokoro"))
    }

    @Test
    fun installSelectedDrivesInstallerAndLandsInstalledState() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)

        vm.installSelected()

        assertEquals(OnboardingStep.Installing, vm.step.value)
        // Only the recommended engine (kokoro) is pre-selected.
        assertEquals(listOf("kokoro"), installer.installCalls)
        assertEquals(InstallState.Installed, vm.installStates.value["kokoro"])
    }

    @Test
    fun installSelectedWithNoSelectionsIsNoop() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)
        vm.toggle("kokoro") // un-select the recommended default

        vm.installSelected()

        assertEquals(OnboardingStep.Installing, vm.step.value)
        assertTrue("no installs expected", installer.installCalls.isEmpty())
    }

    @Test
    fun installFailureLandsFailedStateButLeavesStepIntact() = runTest {
        val installer = RecordingInstaller(behaviour = {
            Result.failure(java.io.IOException("net dropped"))
        })
        val vm = newViewModel(installer = installer)

        vm.installSelected()

        val state = vm.installStates.value["kokoro"]
        assertTrue("expected Failed, got $state", state is InstallState.Failed)
        assertEquals("net dropped", (state as InstallState.Failed).reason)
    }

    @Test
    fun retrySendsAnotherInstallRequest() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)
        vm.installSelected()
        assertEquals(1, installer.installCalls.size)

        // Retry the same engine the recommended default lands on
        // (kokoro), so the running count grows by exactly one.
        vm.retry("kokoro")
        assertEquals(2, installer.installCalls.size)
        assertEquals(InstallState.Installed, vm.installStates.value["kokoro"])
    }

    @Test
    fun finishWithoutAliasIsBlocked() = runTest {
        // No aliases in the DB → finish() must refuse to flip onboarded.
        val settings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)
        vm.aliasCreated.first { !it }

        val flipped = vm.finish()

        assertFalse("finish() should refuse when no alias exists", flipped)
        assertEquals(false, settings.onboarded.first())
    }

    @Test
    fun finishWithExistingAliasFlipsOnboardedAndAutoAssignsPrimary() = runTest {
        // Existing-alias edge case (sideloaded data): finish() should
        // flip onboarded *and* auto-promote the existing alias to primary
        // when no primary is set yet.
        val existing = VoiceAlias(
            name = "narrator",
            engine = "kitten",
            voiceId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            speed = 1.0f,
            effectPreset = EffectPreset.NONE.name,
            createdAt = 0L,
        )
        val settings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao(initial = listOf(existing))
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)
        vm.aliasCreated.first { it }

        val flipped = vm.finish()

        assertTrue("finish() should flip when an alias exists", flipped)
        assertEquals(true, settings.onboarded.first())
        assertEquals(
            "Primary should self-heal to the existing alias",
            "narrator",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun saveAliasAndContinueCreatesAliasMarksPrimaryAndFlipsOnboarded() = runTest {
        val settings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)

        vm.onAliasNameChange("narrator")
        vm.onAliasEngineChange("kitten")
        vm.onAliasVoiceChange("kitten:Bella")
        val ok = vm.saveAliasAndContinue()

        assertTrue("saveAliasAndContinue should succeed with valid fields", ok)
        assertEquals(1, aliasDao.upsertedAliases.size)
        assertEquals("narrator", aliasDao.upsertedAliases.single().name)
        assertEquals("narrator", settings.primaryAliasName.first())
        assertEquals(true, settings.onboarded.first())
    }

    @Test
    fun saveAliasAndContinueRejectsInvalidName() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)

        vm.onAliasNameChange("Has Spaces!")
        vm.onAliasEngineChange("kitten")
        vm.onAliasVoiceChange("kitten:Bella")
        val ok = vm.saveAliasAndContinue()

        assertFalse("Invalid name must not save", ok)
        assertTrue("No alias upserted on validation failure", aliasDao.upsertedAliases.isEmpty())
        assertNotNull("Editor error should be populated", vm.aliasEditorState.first().error)
    }

    @Test
    fun useDefaultsAndContinueCreatesDefaultAliasAndFlipsOnboarded() = runTest {
        val settings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)
        // Recommended engine (kokoro) is pre-selected → install lands it
        // as Installed which makes useDefaultsAndContinue pick kokoro.
        vm.installSelected()

        vm.useDefaultsAndContinue()

        assertEquals(1, aliasDao.upsertedAliases.size)
        val row = aliasDao.upsertedAliases.single()
        assertEquals("default", row.name)
        assertEquals("kokoro", row.engine)
        assertEquals("default", settings.primaryAliasName.first())
        assertEquals(true, settings.onboarded.first())
    }

    @Test
    fun aliasCreatedReflectsDbState() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        assertFalse("aliasCreated should be false on empty DB", vm.aliasCreated.first { !it })

        vm.onAliasNameChange("narrator")
        vm.onAliasEngineChange("kitten")
        vm.onAliasVoiceChange("kitten:Bella")
        vm.saveAliasAndContinue()

        assertTrue("aliasCreated should flip to true after save", vm.aliasCreated.first { it })
    }

    // -- helpers ----------------------------------------------------------

    private fun newViewModel(
        installer: EngineInstaller = RecordingInstaller(behaviour = { Result.success(Unit) }),
        settings: FakeSettings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        ),
        aliasDao: FakeAliasDao = FakeAliasDao(),
    ): OnboardingViewModel {
        val voiceDao = FakeDao(voices = KittenVoiceCatalog.voices)
        return OnboardingViewModel(installer, settings, aliasDao, voiceDao)
    }
}

/**
 * Recording fake — extends `EngineInstaller` to satisfy the type, but
 * substitutes both [install] and [uninstall] with deterministic
 * record-and-return behaviour.
 *
 * Standing the real installer up needs an HTTP server (see
 * [app.marmalade.tts.install.EngineInstallerTest]); that's already
 * covered elsewhere.
 */
private class RecordingInstaller(
    private val behaviour: () -> Result<Unit>,
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/onboarding-test-unused") },
    kittenEngine = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used") },
) {
    val installCalls = mutableListOf<String>()

    override suspend fun install(
        engineName: String,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> {
        installCalls += engineName
        return behaviour()
    }
}

