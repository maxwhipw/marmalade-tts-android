package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.data.db.Effect
import app.marmalade.tts.data.db.EffectDao
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// -----------------------------------------------------------------------------
// Covers EffectEditorViewModel: the chain-builder block ops + create / edit /
// duplicate / save / preview wiring.
//
// Robolectric (not plain JVM) because save() encodes and the edit/dupe load
// paths decode via EffectBlockJson, which needs the Android-bundled org.json —
// a throwing stub under plain unit tests. MainDispatcherRule swaps Main for an
// UnconfinedTestDispatcher so the init-block load + save()/preview() launches
// resolve synchronously inside runTest.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EffectEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val customChain = listOf(
        EffectBlock.Reverb(reverberance = 80f),
        EffectBlock.Pitch(cents = -200f),
    )

    /** A DAO pre-seeded with one custom effect to edit / duplicate. */
    private fun daoWithCustom(): FakeEffectDao = FakeEffectDao(
        initial = listOf(
            Effect(
                id = "custom:abc",
                name = "My effect",
                isBuiltin = false,
                blocksJson = EffectBlockJson.encode(customChain),
                createdAt = 100L,
            ),
        ),
    )

    private fun newViewModel(
        dao: EffectDao = FakeEffectDao(),
        editId: String? = null,
        dupeId: String? = null,
        player: RecordingPlayer = RecordingPlayer(),
        settings: FakeSettings = FakeSettings(initialId = "voice-x"),
    ): EffectEditorViewModel {
        val handle = SavedStateHandle(
            buildMap {
                editId?.let { put(EffectEditorViewModel.ARG_EDIT_ID, it) }
                dupeId?.let { put(EffectEditorViewModel.ARG_DUPE_ID, it) }
            },
        )
        return EffectEditorViewModel(dao, settings, player, handle)
    }

    // -- Initial load ---------------------------------------------------------

    @Test
    fun `blank create starts empty and loaded`() = runTest {
        val vm = newViewModel()
        val state = vm.state.value
        assertTrue(state.loaded)
        assertFalse(state.isEdit)
        assertEquals("", state.name)
        assertTrue(state.blocks.isEmpty())
    }

    @Test
    fun `edit loads the existing effect`() = runTest {
        val vm = newViewModel(dao = daoWithCustom(), editId = "custom:abc")
        val state = vm.state.value
        assertTrue(state.loaded)
        assertTrue(state.isEdit)
        assertEquals("My effect", state.name)
        assertEquals(customChain, state.blocks)
    }

    @Test
    fun `duplicate copies blocks but is not an edit and renames`() = runTest {
        val vm = newViewModel(dao = daoWithCustom(), dupeId = "custom:abc")
        val state = vm.state.value
        assertFalse(state.isEdit)
        assertEquals("My effect copy", state.name)
        assertEquals(customChain, state.blocks)
    }

    // -- Block operations -----------------------------------------------------

    @Test
    fun `addBlock appends and updateBlock replaces in place`() = runTest {
        val vm = newViewModel()
        vm.addBlock(EffectBlock.Reverb(reverberance = 50f))
        vm.addBlock(EffectBlock.Bass(db = 6f))
        assertEquals(2, vm.state.value.blocks.size)

        vm.updateBlock(0, EffectBlock.Reverb(reverberance = 90f))
        assertEquals(EffectBlock.Reverb(90f), vm.state.value.blocks[0])
        assertEquals(EffectBlock.Bass(6f), vm.state.value.blocks[1])
    }

    @Test
    fun `removeBlock drops the indexed block`() = runTest {
        val vm = newViewModel()
        vm.addBlock(EffectBlock.Reverb(50f))
        vm.addBlock(EffectBlock.Bass(6f))
        vm.removeBlock(0)
        assertEquals(listOf(EffectBlock.Bass(6f)), vm.state.value.blocks)
    }

    @Test
    fun `moveDown then moveUp reorders symmetrically`() = runTest {
        val vm = newViewModel()
        vm.addBlock(EffectBlock.Reverb(50f))
        vm.addBlock(EffectBlock.Bass(6f))

        vm.moveDown(0)
        assertEquals(listOf(EffectBlock.Bass(6f), EffectBlock.Reverb(50f)), vm.state.value.blocks)

        vm.moveUp(1)
        assertEquals(listOf(EffectBlock.Reverb(50f), EffectBlock.Bass(6f)), vm.state.value.blocks)
    }

    @Test
    fun `out-of-range block ops are no-ops`() = runTest {
        val vm = newViewModel()
        vm.addBlock(EffectBlock.Reverb(50f))
        vm.moveUp(0) // already at top
        vm.moveDown(0) // already at bottom
        vm.removeBlock(5) // index doesn't exist
        vm.updateBlock(5, EffectBlock.Bass(6f))
        assertEquals(listOf(EffectBlock.Reverb(50f)), vm.state.value.blocks)
    }

    // -- Save -----------------------------------------------------------------

    @Test
    fun `save persists a custom effect and fires onSaved`() = runTest {
        val dao = FakeEffectDao(initial = emptyList())
        val vm = newViewModel(dao = dao)
        vm.onNameChange("  Spacey  ")
        vm.addBlock(EffectBlock.Reverb(70f))

        var saved = false
        vm.save(onSaved = { saved = true })

        assertTrue(saved)
        val rows = dao.getAll().first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Spacey", row.name) // trimmed
        assertFalse(row.isBuiltin)
        assertEquals(listOf(EffectBlock.Reverb(70f)), EffectBlockJson.decode(row.blocksJson))
    }

    @Test
    fun `save with blank name sets error and persists nothing`() = runTest {
        val dao = FakeEffectDao(initial = emptyList())
        val vm = newViewModel(dao = dao)
        vm.onNameChange("   ")

        var saved = false
        vm.save(onSaved = { saved = true })

        assertFalse(saved)
        assertTrue(vm.state.value.nameError)
        assertTrue(dao.getAll().first().isEmpty())
    }

    @Test
    fun `edit save writes back to the same id`() = runTest {
        val dao = daoWithCustom()
        val vm = newViewModel(dao = dao, editId = "custom:abc")
        vm.onNameChange("Renamed")
        vm.save(onSaved = {})

        val rows = dao.getAll().first()
        assertEquals(1, rows.size) // overwrote, didn't add
        assertEquals("custom:abc", rows.single().id)
        assertEquals("Renamed", rows.single().name)
    }

    @Test
    fun `duplicate save mints a new id and keeps the original`() = runTest {
        val dao = daoWithCustom()
        val vm = newViewModel(dao = dao, dupeId = "custom:abc")
        vm.save(onSaved = {})

        val rows = dao.getAll().first()
        assertEquals(2, rows.size) // original + the copy
        val copy = rows.first { it.id != "custom:abc" }
        assertEquals("My effect copy", copy.name)
        assertTrue(copy.id.startsWith("custom:"))
        assertEquals(customChain, EffectBlockJson.decode(copy.blocksJson))
    }

    // -- Preview --------------------------------------------------------------

    @Test
    fun `preview sends the current chain to the player with the default voice`() = runTest {
        val player = RecordingPlayer()
        val vm = newViewModel(player = player, settings = FakeSettings(initialId = "voice-x"))
        vm.addBlock(EffectBlock.Reverb(40f))
        vm.preview()

        assertEquals(1, player.calls.size)
        val call = player.calls.single()
        assertEquals("voice-x", call.voiceId)
        assertEquals(listOf(EffectBlock.Reverb(40f)), call.effectBlocks)
        // Success path returns the preview button to Idle.
        assertEquals(EffectPreviewState.Idle, vm.state.value.preview)
    }

    @Test
    fun `preview with no default voice surfaces an error and does not synthesize`() = runTest {
        val player = RecordingPlayer()
        val vm = newViewModel(player = player, settings = FakeSettings(initialId = ""))
        vm.preview()

        assertTrue(player.calls.isEmpty())
        assertTrue(vm.state.value.preview is EffectPreviewState.Error)
    }
}
