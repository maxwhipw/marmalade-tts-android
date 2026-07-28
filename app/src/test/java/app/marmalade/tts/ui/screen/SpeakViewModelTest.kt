package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectChain
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.BuiltinEffects
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import app.marmalade.tts.data.VoicePathResolver

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
        assertEquals("kitten-direct-v0_8:Bella", call.voiceId)
        // Default speed / effect when no alias has been applied.
        assertEquals(1.0f, call.speed, 0.0f)
        assertEquals(emptyList<EffectBlock>(), call.effectBlocks)
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
            id = "id-" + "robocop",
            name = "robocop",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Hugo",
            speed = 1.25f,
            effectPreset = "TELEPHONE",
            createdAt = 0L,
            effectId = BuiltinEffects.TELEPHONE_ID,
        )
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player, aliases = listOf(alias))
        vm.currentVoice.firstNonNull()

        vm.applyAlias("id-robocop")
        // The voice swap happens via SettingsRepository.setDefaultVoiceId,
        // so wait for currentVoice to reflect the new id before speaking.
        vm.currentVoice.filter { it?.id == "kitten-direct-v0_8:Hugo" }.first()

        vm.onTextChanged("Affirmative")
        vm.speak()

        val call = player.calls.single()
        assertEquals("kitten-direct-v0_8:Hugo", call.voiceId)
        assertEquals(1.25f, call.speed, 0.0f)
        assertEquals(EffectChain.TELEPHONE_BLOCKS, call.effectBlocks)
    }

    @Test
    fun speak_passesPhonemizationLanguageFromActiveAlias() = runTest {
        // alpha.10.L: F7 plumbing — alias.phonemizationLanguage must reach
        // the engine via the speak() call. Auto (null) and explicit values
        // both round-trip.
        val alias = VoiceAlias(
            id = "id-" + "narrator-jp",
            name = "narrator-jp",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Luna",
            speed = 1.0f,
            effectPreset = "NONE",
            createdAt = 0L,
            phonemizationLanguage = "ja",
        )
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player, aliases = listOf(alias))
        vm.currentVoice.firstNonNull()

        vm.applyAlias("id-narrator-jp")
        vm.currentVoice.filter { it?.id == "kitten-direct-v0_8:Luna" }.first()

        vm.onTextChanged("Konnichiwa")
        vm.speak()

        val call = player.calls.single()
        assertEquals("ja", call.phonemizationLanguage)
    }

    @Test
    fun speak_phonemizationLanguageDefaultsToNullWithoutAlias() = runTest {
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("Hello")
        vm.speak()

        assertNull(
            "Without an active alias, language should be null = engine decides",
            player.calls.single().phonemizationLanguage,
        )
    }

    @Test
    fun editingActiveAlias_updatesCachedEffectSpeedAndLanguage() = runTest {
        // alpha.10.M: editing the alias currently active on the Speak screen
        // must reflect live in the cached StateFlows. Pre-fix the user had
        // to re-select another alias and come back to pick up edits.
        val original = VoiceAlias(
            id = "id-" + "narrator",
            name = "narrator",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Bella",
            speed = 1.0f,
            effectPreset = "NONE",
            createdAt = 0L,
            phonemizationLanguage = null,
        )
        val aliasDao = FakeAliasDao(initial = listOf(original))
        val vm = newViewModel(aliasDao = aliasDao)
        vm.currentVoice.firstNonNull()

        vm.applyAlias("id-narrator")
        vm.activeAlias.filter { it == "id-narrator" }.first()
        // Sanity: cached state matches the original alias.
        assertEquals(emptyList<EffectBlock>(), vm.currentEffectBlocks.value)
        assertEquals(1.0f, vm.currentSpeed.value, 0.0f)
        assertNull(vm.currentPhonemizationLanguage.value)

        // Simulate editing the alias from the Aliases screen — same name,
        // same voiceId (so the manual-voice-change clearer doesn't fire),
        // but speed/effect/language all changed.
        val edited = original.copy(
            speed = 0.75f,
            effectPreset = "CAVE",
            effectId = BuiltinEffects.CAVE_ID,
            phonemizationLanguage = "en-gb",
        )
        aliasDao.upsert(edited)

        // The combine observer in init{} should re-apply the new snapshot.
        // Wait on the speed StateFlow rather than tail-asserting — the
        // upsert→DAO flow→combine pipeline has multiple coroutine hops.
        vm.currentSpeed.filter { it == 0.75f }.first()

        assertEquals(EffectChain.CAVE_BLOCKS, vm.currentEffectBlocks.value)
        assertEquals(0.75f, vm.currentSpeed.value, 0.0f)
        assertEquals("en-gb", vm.currentPhonemizationLanguage.value)
    }

    @Test
    fun editingInactiveAlias_doesNotTouchSpeakScreenState() = runTest {
        // Symmetric guard for alpha.10.M: editing a DIFFERENT alias from
        // the one currently selected must not bleed into the cached state.
        val active = VoiceAlias(
            id = "id-" + "narrator",
            name = "narrator",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Bella",
            speed = 1.0f,
            effectPreset = "NONE",
            createdAt = 0L,
        )
        val other = VoiceAlias(
            id = "id-" + "robocop",
            name = "robocop",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Hugo",
            speed = 2.0f,
            effectPreset = "TELEPHONE",
            createdAt = 0L,
        )
        val aliasDao = FakeAliasDao(initial = listOf(active, other))
        val vm = newViewModel(aliasDao = aliasDao)
        vm.currentVoice.firstNonNull()

        vm.applyAlias("id-narrator")
        vm.activeAlias.filter { it == "id-narrator" }.first()

        // Edit the OTHER alias.
        aliasDao.upsert(other.copy(speed = 0.5f, effectPreset = "TELEPHONE", effectId = BuiltinEffects.TELEPHONE_ID))

        // Cached state of the active alias must remain untouched.
        // Drain pending coroutines by reading aliases once.
        vm.aliases.first { it.size == 2 }
        assertEquals(emptyList<EffectBlock>(), vm.currentEffectBlocks.value)
        assertEquals(1.0f, vm.currentSpeed.value, 0.0f)
    }

    @Test
    fun applyAlias_thenManualVoiceChange_clearsEffect() = runTest {
        val alias = VoiceAlias(
            id = "id-" + "echo",
            name = "echo",
            engine = "kitten-direct-v0_8",
            voiceId = "kitten-direct-v0_8:Luna",
            speed = 0.9f,
            effectPreset = "CAVE",
            createdAt = 0L,
            effectId = BuiltinEffects.CAVE_ID,
        )
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val vm = newViewModel(player = player, settings = settings, aliases = listOf(alias))
        vm.currentVoice.firstNonNull()

        vm.applyAlias("id-echo")
        // Wait for the alias to fully apply before changing voice manually.
        vm.activeAlias.filter { it == "id-echo" }.first()
        assertEquals(EffectChain.CAVE_BLOCKS, vm.currentEffectBlocks.value)
        assertEquals(0.9f, vm.currentSpeed.value, 0.0f)

        // Simulate the user picking a different voice in the picker.
        settings.setDefaultVoiceId("kitten-direct-v0_8:Kiki")
        vm.currentVoice.filter { it?.id == "kitten-direct-v0_8:Kiki" }.first()

        // Manual voice pick should drop the alias and reset effect + speed.
        assertEquals(null, vm.activeAlias.value)
        assertEquals(emptyList<EffectBlock>(), vm.currentEffectBlocks.value)
        assertEquals(1.0f, vm.currentSpeed.value, 0.0f)
    }

    @Test
    fun currentVoice_emitsOnceVoicesArrive() = runTest {
        // Pin Blocker #2: the seed race. Before the fix, currentVoice
        // resolved findById once on first defaultVoiceId emission and got
        // null (DB empty), then never re-resolved when the seed completed.
        // After the fix, combine(defaultVoiceId, voiceDao.getAll()) re-emits
        // when the catalog flow emits the seeded voices.
        val voicesFlow = MutableStateFlow<List<VoiceMeta>>(emptyList())
        val dao = MutableVoiceDao(voicesFlow)
        val player = RecordingPlayer(behaviour = { Result.success(Unit) })
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = SpeakViewModel(
            synthesizer = player,
            settings = settings,
            voiceDao = dao,
            aliasDao = FakeAliasDao(),
            effectResolver = FakeEffectResolver(),
            // No cloud providers configured — every test voice is on-device.
            voicePaths = VoicePathResolver { null },
        )

        // Subscribe to keep WhileSubscribed hot. Initially voices is empty,
        // so currentVoice resolves to null.
        val initial = vm.currentVoice.first()
        assertNull("Expected null while catalog empty", initial)

        // Simulate the seed landing — catalog flips to non-empty.
        voicesFlow.value = KittenDirectVoiceCatalog.voices

        // currentVoice should now resolve to the Bella row.
        val resolved = vm.currentVoice.filter { it != null }.first()
        assertNotNull(resolved)
        assertEquals(KittenDirectVoiceCatalog.DEFAULT_VOICE_ID, resolved!!.id)
    }

    @Test
    fun onTextChanged_resetsStickyModelMissing() = runTest {
        // Pin Major #1: typing after a ModelMissing failure must reset the
        // Speak button (the UI's `enabled` predicate gates on !isModelMissing).
        val player = RecordingPlayer(behaviour = {
            Result.failure(SynthesizerException.ModelMissing)
        })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("Test")
        vm.speak()
        assertEquals(PlaybackState.ModelMissing, vm.playbackState.value)

        // Typing again should unstick the state.
        vm.onTextChanged("Test more")
        assertEquals(PlaybackState.Idle, vm.playbackState.value)
    }

    @Test
    fun onTextChanged_resetsStickyError() = runTest {
        val player = RecordingPlayer(behaviour = {
            Result.failure(SynthesizerException.SynthesisFailed(RuntimeException("boom")))
        })
        val vm = newViewModel(player = player)
        vm.currentVoice.firstNonNull()

        vm.onTextChanged("Test")
        vm.speak()
        assertTrue(vm.playbackState.value is PlaybackState.Error)

        vm.onTextChanged("Test more")
        assertEquals(PlaybackState.Idle, vm.playbackState.value)
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
        player: SpeechPlayer = RecordingPlayer(behaviour = { Result.success(Unit) }),
        defaultVoiceId: String = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
        settings: FakeSettings = FakeSettings(initialId = defaultVoiceId),
        aliases: List<VoiceAlias> = emptyList(),
        aliasDao: FakeAliasDao? = null,
    ): SpeakViewModel {
        require(aliasDao == null || aliases.isEmpty()) {
            "Pass either aliasDao or aliases, not both"
        }
        val dao = FakeDao(voices = KittenDirectVoiceCatalog.voices)
        val resolvedAliasDao = aliasDao ?: FakeAliasDao(initial = aliases)
        return SpeakViewModel(
            synthesizer = player,
            settings = settings,
            voiceDao = dao,
            aliasDao = resolvedAliasDao,
            effectResolver = FakeEffectResolver(),
            // No cloud providers configured — every test voice is on-device.
            voicePaths = VoicePathResolver { null },
        )
    }

    private suspend fun <T> Flow<T?>.firstNonNull(): T =
        this.filter { it != null }.first()!!

    /**
     * VoiceMetaDao backed by a mutable `Flow<List<VoiceMeta>>`. Used by the
     * seed-race regression test to simulate the catalog being empty until
     * the application-scoped seed coroutine writes to it.
     */
    private class MutableVoiceDao(
        private val voices: MutableStateFlow<List<VoiceMeta>>,
    ) : VoiceMetaDao {
        override fun getAll(): Flow<List<VoiceMeta>> = voices
        override fun getByEngine(engine: String): Flow<List<VoiceMeta>> =
            kotlinx.coroutines.flow.flowOf(voices.value.filter { it.engine == engine })
        override suspend fun findById(id: String): VoiceMeta? =
            voices.value.firstOrNull { it.id == id }
        override suspend fun count(): Int = voices.value.size
        override suspend fun upsert(voice: VoiceMeta) {
            voices.value = voices.value.filterNot { it.id == voice.id } + voice
        }
        override suspend fun upsertAll(rows: List<VoiceMeta>) {
            val ids = rows.map { it.id }.toSet()
            voices.value = voices.value.filterNot { it.id in ids } + rows
        }
        override suspend fun deleteByEngine(engine: String) {
            voices.value = voices.value.filterNot { it.engine == engine }
        }
    }
}
