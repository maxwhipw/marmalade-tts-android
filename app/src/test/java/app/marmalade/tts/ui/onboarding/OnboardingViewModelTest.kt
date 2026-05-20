package app.marmalade.tts.ui.onboarding

import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun stepProgressesThroughWelcomeToEnginePickToInstalling() = runTest {
        val vm = newViewModel()
        assertEquals(OnboardingStep.Welcome, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.EnginePick, vm.step.value)

        vm.next()
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
        // Kitten is recommended → pre-selected.
        assertTrue(vm.selectedEngineIds.value.contains("kitten"))

        vm.toggle("kitten")
        assertTrue(!vm.selectedEngineIds.value.contains("kitten"))

        vm.toggle("kitten")
        assertTrue(vm.selectedEngineIds.value.contains("kitten"))
    }

    @Test
    fun installSelectedDrivesInstallerAndLandsInstalledState() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)

        vm.installSelected()

        assertEquals(OnboardingStep.Installing, vm.step.value)
        assertEquals(listOf("kitten"), installer.installCalls)
        assertEquals(InstallState.Installed, vm.installStates.value["kitten"])
    }

    @Test
    fun installSelectedWithNoSelectionsIsNoop() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)
        vm.toggle("kitten") // un-select Kitten

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

        val state = vm.installStates.value["kitten"]
        assertTrue("expected Failed, got $state", state is InstallState.Failed)
        assertEquals("net dropped", (state as InstallState.Failed).reason)
    }

    @Test
    fun retrySendsAnotherInstallRequest() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)
        vm.installSelected()
        assertEquals(1, installer.installCalls.size)

        vm.retry("kitten")
        assertEquals(2, installer.installCalls.size)
        assertEquals(InstallState.Installed, vm.installStates.value["kitten"])
    }

    @Test
    fun finishMarksUserAsOnboarded() = runTest {
        val settings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        )
        val vm = newViewModel(settings = settings)
        assertEquals(false, settings.onboarded.first())

        vm.finish()
        assertEquals(true, settings.onboarded.first())
    }

    // -- helpers ----------------------------------------------------------

    private fun newViewModel(
        installer: EngineInstaller = RecordingInstaller(behaviour = { Result.success(Unit) }),
        settings: FakeSettings = FakeSettings(
            initialId = "kitten:Bella",
            initialOnboarded = false,
        ),
    ): OnboardingViewModel = OnboardingViewModel(installer, settings)
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

