package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
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
        val call = player.calls.single()
        assertEquals("Hello world", call.text)
        assertEquals("kitten:Bella", call.voiceId)
        // Default speed / effect when no alias has been applied.
        assertEquals(1.0f, call.speed, 0.0f)
        assertEquals(EffectPreset.NONE, call.effect)
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
    fun speak_passesEffectAndSpeedFromActiveAlias() = runTest {
        val alias = VoiceAlias(
            name = "robocop",
            engine = "kitten",
            voiceId = "kitten:Hugo",
            speed = 1.25f,
            effectPreset = "ROBOT",
            createdAt = 0L,
        )
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player, aliases = listOf(alias))
        vm.currentVoice.firstNonNull()

        vm.applyAlias("robocop")
        // The voice swap happens via SettingsRepository.setDefaultVoiceId,
        // so wait for currentVoice to reflect the new id before speaking.
        vm.currentVoice.filter { it?.id == "kitten:Hugo" }.first()

        vm.onTextChanged("Affirmative")
        vm.speak()

        val call = player.calls.single()
        assertEquals("kitten:Hugo", call.voiceId)
        assertEquals(1.25f, call.speed, 0.0f)
        assertEquals(EffectPreset.ROBOT, call.effect)
    }

    @Test
    fun applyAlias_thenManualVoiceChange_clearsEffect() = runTest {
        val alias = VoiceAlias(
            name = "echo",
            engine = "kitten",
            voiceId = "kitten:Luna",
            speed = 0.9f,
            effectPreset = "CAVE",
            createdAt = 0L,
        )
        val settings = FakeSettings(initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player, settings = settings, aliases = listOf(alias))
        vm.currentVoice.firstNonNull()

        vm.applyAlias("echo")
        // Wait for the alias to fully apply before changing voice manually.
        vm.activeAlias.filter { it == "echo" }.first()
        assertEquals(EffectPreset.CAVE, vm.currentEffect.value)
        assertEquals(0.9f, vm.currentSpeed.value, 0.0f)

        // Simulate the user picking a different voice in the picker.
        settings.setDefaultVoiceId("kitten:Kiki")
        vm.currentVoice.filter { it?.id == "kitten:Kiki" }.first()

        // Manual voice pick should drop the alias and reset effect + speed.
        assertEquals(null, vm.activeAlias.value)
        assertEquals(EffectPreset.NONE, vm.currentEffect.value)
        assertEquals(1.0f, vm.currentSpeed.value, 0.0f)
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
        settings: FakeSettings = FakeSettings(initialId = defaultVoiceId),
        aliases: List<VoiceAlias> = emptyList(),
    ): SpeakViewModel {
        val dao = FakeDao(voices = KittenVoiceCatalog.voices)
        val aliasDao = FakeAliasDao(initial = aliases)
        return SpeakViewModel(
            synthesizer = player,
            settings = settings,
            voiceDao = dao,
            aliasDao = aliasDao,
        )
    }

    private suspend fun <T> Flow<T?>.firstNonNull(): T =
        this.filter { it != null }.first()!!
}
