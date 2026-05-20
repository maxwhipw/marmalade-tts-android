package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * State-transition coverage for [SpeakViewModel].
 *
 * Worth testing because the speak() path threads multiple async layers
 * (Synthesizer → engine → audio) and the mapping from a thrown exception
 * back to a typed UI state is the kind of glue that goes wrong silently.
 *
 * Uses hand-rolled fakes (see Fakes.kt) — no mockito/mockk in the project.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun successPath_idleToSpeakingToIdle() = runTest {
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player)
        // Wait for the currentVoice flow to resolve so speak() doesn't bail.
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("Hello world")
        vm.speak()

        // UnconfinedTestDispatcher means the suspend speak() ran to
        // completion before this assertion — we should be back at Idle.
        assertEquals(PlaybackState.Idle, vm.playbackState.value)
        assertEquals(1, player.calls.size)
        assertEquals("Hello world" to "kitten:Bella", player.calls.single())
    }

    @Test
    fun blankTextIsIgnored() = runTest {
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("   \n\t ")
        vm.speak()

        assertEquals(PlaybackState.Idle, vm.playbackState.value)
        assertTrue("Blank input should not reach the player", player.calls.isEmpty())
    }

    @Test
    fun modelMissingMapsToModelMissingState() = runTest {
        val player = RecordingPlayer(behaviour = {
            Result.failure(SynthesizerException.ModelMissing)
        })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("Test")
        vm.speak()

        assertEquals(PlaybackState.ModelMissing, vm.playbackState.value)
    }

    @Test
    fun otherFailureMapsToErrorStateWithMessage() = runTest {
        val player = RecordingPlayer(behaviour = {
            Result.failure(SynthesizerException.SynthesisFailed(RuntimeException("boom")))
        })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("Test")
        vm.speak()

        val state = vm.playbackState.value
        assertTrue("Expected Error, got $state", state is PlaybackState.Error)
        assertEquals("boom", (state as PlaybackState.Error).message)
    }

    @Test
    fun cancelReturnsToIdle() = runTest {
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.cancel()
        assertEquals(PlaybackState.Idle, vm.playbackState.value)
        assertEquals("cancel should reach the player", 1, player.cancelCount)
    }

    // -- helpers ---------------------------------------------------------------

    private fun newViewModel(
        player: SpeechPlayer,
        defaultVoiceId: String = KittenVoiceCatalog.DEFAULT_VOICE_ID,
    ): SpeakViewModel {
        val settings = FakeSettings(initialId = defaultVoiceId)
        val dao = FakeDao(voices = KittenVoiceCatalog.voices)
        return SpeakViewModel(
            synthesizer = player,
            settings = settings,
            voiceDao = dao,
        )
    }

    private suspend fun <T> Flow<T?>.firstNonNull(): T =
        this.filter { it != null }.first()!!
}
