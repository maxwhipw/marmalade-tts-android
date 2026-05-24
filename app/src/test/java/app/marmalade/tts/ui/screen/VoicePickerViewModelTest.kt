package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        val settings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings)

        vm.selectVoice("kitten-nano-v0_8:Kiki")

        assertEquals("kitten-nano-v0_8:Kiki", settings.defaultVoiceId.first())
    }

    @Test
    fun selectedIdReflectsCurrentSetting() = runTest {
        val settings = FakeSettings(initialId = "kitten-nano-v0_8:Leo")
        val vm = newViewModel(settings = settings)

        // stateIn(Eagerly) + an UnconfinedTestDispatcher means the first
        // emission has already landed by the time we read .value.
        assertEquals("kitten-nano-v0_8:Leo", vm.selectedId.value)
    }

    @Test
    fun previewSendsCannedPhraseThroughPlayer() = runTest {
        val player = RecordingPlayer()
        val settings = FakeSettings(initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        val voice = KittenNanoVoiceCatalog.voices.first { it.displayName == "Bella" }
        val vm = newViewModel(settings = settings, player = player)

        vm.preview(voice)

        // 1 cancel before preview + the speak call itself.
        assertTrue(player.cancelCount >= 1)
        assertEquals(1, player.calls.size)
        val (text, voiceId) = player.calls.single()
        assertEquals("kitten-nano-v0_8:Bella", voiceId)
        // Phrase contains the voice's name so the user hears who is speaking.
        assertTrue("expected name in '$text'", text.contains("Bella"))
    }

    private fun newViewModel(
        settings: SettingsRepository,
        player: SpeechPlayer = RecordingPlayer(),
    ): VoicePickerViewModel {
        val dao = FakeDao(voices = KittenNanoVoiceCatalog.voices)
        return VoicePickerViewModel(
            voiceDao = dao,
            settings = settings,
            synthesizer = player,
            installer = PickerFakeInstaller(),
        )
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
    private val installedEngines: Set<String> = setOf("kitten-nano-v0_8", "kokoro-v1_0"),
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/voicepicker-test-unused") },
    kittenEngine = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used in this test") },
) {
    override suspend fun verify(engineName: String): InstallState =
        if (engineName in installedEngines) InstallState.Installed else InstallState.NotInstalled
}
