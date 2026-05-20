package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
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
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings)

        vm.selectVoice("kitten:Kiki")

        assertEquals("kitten:Kiki", settings.defaultVoiceId.first())
    }

    @Test
    fun selectedIdReflectsCurrentSetting() = runTest {
        val settings = FakeSettings(initialId = "kitten:Leo")
        val vm = newViewModel(settings = settings)

        // stateIn(Eagerly) + an UnconfinedTestDispatcher means the first
        // emission has already landed by the time we read .value.
        assertEquals("kitten:Leo", vm.selectedId.value)
    }

    @Test
    fun previewSendsCannedPhraseThroughPlayer() = runTest {
        val player = RecordingPlayer()
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val voice = KittenVoiceCatalog.voices.first { it.displayName == "Bella" }
        val vm = newViewModel(settings = settings, player = player)

        vm.preview(voice)

        // 1 cancel before preview + the speak call itself.
        assertTrue(player.cancelCount >= 1)
        assertEquals(1, player.calls.size)
        val (text, voiceId) = player.calls.single()
        assertEquals("kitten:Bella", voiceId)
        // Phrase contains the voice's name so the user hears who is speaking.
        assertTrue("expected name in '$text'", text.contains("Bella"))
    }

    private fun newViewModel(
        settings: SettingsRepository,
        player: SpeechPlayer = RecordingPlayer(),
    ): VoicePickerViewModel {
        val dao = FakeDao(voices = KittenVoiceCatalog.voices)
        return VoicePickerViewModel(
            voiceDao = dao,
            settings = settings,
            synthesizer = player,
        )
    }
}
