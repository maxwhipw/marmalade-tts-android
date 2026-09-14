package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.data.VoiceLatencySource
import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Coverage for [VoicePickerViewModel].
 *
 * The interesting behaviour to pin: selecting a voice writes to the
 * settings repository (so the Speak screen picks up the change), and the
 * preview path resolves through the SpeechPlayer with the canned phrase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun selectVoiceWritesToSettings() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings)

        vm.selectVoice("kitten-direct-v0_8:Kiki")

        assertEquals("kitten-direct-v0_8:Kiki", settings.defaultVoiceId.first())
    }

    @Test
    fun selectedIdReflectsCurrentSetting() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Leo")
        val vm = newViewModel(settings = settings)

        // stateIn(Eagerly) + an UnconfinedTestDispatcher means the first
        // emission has already landed by the time we read .value.
        assertEquals("kitten-direct-v0_8:Leo", vm.selectedId.value)
    }

    @Test
    fun previewSendsCannedPhraseThroughPlayer() = runTest {
        val player = RecordingPlayer()
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val voice = KittenDirectVoiceCatalog.voices.first { it.displayName == "Bella" }
        val vm = newViewModel(settings = settings, player = player)

        vm.preview(voice)

        // 1 cancel before preview + the speak call itself.
        assertTrue(player.cancelCount >= 1)
        assertEquals(1, player.calls.size)
        val (text, voiceId) = player.calls.single()
        assertEquals("kitten-direct-v0_8:Bella", voiceId)
        // Phrase contains the voice's name so the user hears who is speaking.
        assertTrue("expected name in '$text'", text.contains("Bella"))
    }

    @Test
    fun `a vits voice is listed only when its own pack is installed`() = runTest {
        // The promotion blocker this closes: the engine verifies as installed
        // from ONE pack, so an engine-level filter offered all 25 voices and
        // picking an absent one failed at synthesis.
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        // VITS Marmalade is developerOnly, so the picker hides it otherwise.
        settings.setShowDeveloperEngines(true)
        val vm = newViewModel(
            settings = settings,
            voices = VitsVoiceCatalog.voices,
            installer = PickerFakeInstaller(
                installedEngines = setOf(VitsVoiceCatalog.ENGINE),
                installedPacks = setOf("is-salka-medium"),
            ),
        )

        val listed = vm.voices.first { it.isNotEmpty() }

        assertEquals(
            listOf(VitsVoiceCatalog.voiceId("is-salka-medium")),
            listed.map { it.id },
        )
    }

    @Test
    fun `installing a multi-speaker pack lists every one of its speakers`() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        settings.setShowDeveloperEngines(true)
        val vm = newViewModel(
            settings = settings,
            voices = VitsVoiceCatalog.voices,
            installer = PickerFakeInstaller(
                installedEngines = setOf(VitsVoiceCatalog.ENGINE),
                installedPacks = setOf("kk-issai-high"),
            ),
        )

        val listed = vm.voices.first { it.isNotEmpty() }

        assertEquals(6, listed.size)
        assertTrue(listed.all { VitsVoiceCatalog.packIdOf(it.id) == "kk-issai-high" })
    }

    @Test
    fun `an installed engine with no packs lists no voices at all`() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        settings.setShowDeveloperEngines(true)
        val vm = newViewModel(
            settings = settings,
            voices = VitsVoiceCatalog.voices,
            installer = PickerFakeInstaller(
                installedEngines = setOf(VitsVoiceCatalog.ENGINE),
                installedPacks = emptySet(),
            ),
        )

        // The probe has run (installedEngines is non-empty) and still nothing
        // is pickable — which is the honest answer, not an empty-because-
        // unprobed list.
        vm.installedEngines.first { it.isNotEmpty() }
        assertTrue(vm.voices.value.isEmpty())
    }

    private fun newViewModel(
        settings: SettingsRepository,
        player: SpeechPlayer = RecordingPlayer(),
        voices: List<VoiceMeta> = KittenDirectVoiceCatalog.voices,
        installer: EngineInstaller = PickerFakeInstaller(),
    ): VoicePickerViewModel {
        val dao = FakeDao(voices = voices)
        return VoicePickerViewModel(
            voiceDao = dao,
            settings = settings,
            synthesizer = player,
            installer = installer,
            // No cloud providers configured — on-device voices resolve
            // entirely from EngineCatalog, which is what these tests use.
            voicePaths = VoicePathResolver { null },
            latencySource = VoiceLatencySource { flowOf(emptyMap()) },
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun `tree groups the installed voices under their engine`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))

        val tree = vm.voiceTree.first { it.isNotEmpty() }

        // One on-device engine → one source, and its single model level is
        // the degenerate one the drill-down skips.
        assertEquals(1, tree.size)
        assertTrue(tree.single().models.size == 1)
        assertEquals(KittenDirectVoiceCatalog.voices.size, tree.single().voiceCount)
    }

    @Test
    fun `drilling into a single-model source lands straight on its voices`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))
        val source = vm.voiceTree.first { it.isNotEmpty() }.single()

        vm.selectSource(source.name)

        // Model auto-selected, so the screen renders the voice list rather
        // than a one-item model list.
        assertEquals(source.name, vm.pickerState.value.source)
        assertEquals(source.models.single().name, vm.pickerState.value.model)
    }

    @Test
    fun `back unwinds a skipped model level in one step`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))
        val source = vm.voiceTree.first { it.isNotEmpty() }.single()
        vm.selectSource(source.name)

        assertTrue(vm.drillBack())

        // Both levels cleared — stopping at "source set, model null" would
        // show a one-item list the user never chose from.
        assertEquals(null, vm.pickerState.value.source)
        assertEquals(null, vm.pickerState.value.model)
        // At the top level Back belongs to the screen, not the drill-down.
        assertTrue(!vm.drillBack())
    }

    @Test
    fun `search clears before the hierarchy unwinds`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))
        val source = vm.voiceTree.first { it.isNotEmpty() }.single()
        vm.selectSource(source.name)
        vm.onQueryChange("bel")

        assertTrue(vm.drillBack())

        assertEquals("", vm.pickerState.value.query)
        assertEquals(source.name, vm.pickerState.value.source)
    }
}

/**
 * Test double for [EngineInstaller]: stubs out file I/O + HTTP and lets
 * the caller declare which engines should report installed. v0.1.18's
 * voice-filter logic calls `verify(engineName)` on every catalog engine
 * at VM init; this fake answers that without standing up the real
 * installer (which needs disk + DI scaffolding).
 */
private class PickerFakeInstaller(
    private val installedEngines: Set<String> = setOf("kitten-direct-v0_8", "kokoro-direct-v1_0"),
    /**
     * Voice packs that should report installed. A pack-based engine verifies as
     * installed as soon as one pack is present, so the two sets are
     * independent: `engines` says the runtime is there, `packs` says which
     * languages are.
     */
    private val installedPacks: Set<String> = emptySet(),
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/voicepicker-test-unused") },
    engineHandle = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used in this test") },
) {
    override suspend fun verify(engineName: String): InstallState =
        if (engineName in installedEngines) InstallState.Installed else InstallState.NotInstalled

    override suspend fun verifyPack(packId: String): InstallState =
        if (packId in installedPacks) InstallState.Installed else InstallState.NotInstalled
}
