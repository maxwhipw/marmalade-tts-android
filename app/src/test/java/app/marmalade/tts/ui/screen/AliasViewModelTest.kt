package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// -----------------------------------------------------------------------------
// Data flow under test
// -----------------------------------------------------------------------------
//   AliasViewModelTest
//     │
//     ├── seeds FakeAliasDao with initial aliases (or empty)
//     ├── constructs AliasViewModel(aliasDao, voiceDao)
//     ├── drives the editor through openEditor / onEditor*Change / save
//     └── asserts on:
//          ├── editorState (name/voiceId/effect/error/isOpen) snapshots
//          ├── FakeAliasDao.upsertedAliases (what got persisted)
//          └── FakeAliasDao.deletedNames    (what got removed)
//
// No Android runtime — pure JVM. MainDispatcherRule swaps Dispatchers.Main
// for UnconfinedTestDispatcher so viewModelScope.launch in save()/delete()
// resolves synchronously inside runTest.
// -----------------------------------------------------------------------------

/**
 * Covers the validation + persistence logic in [AliasViewModel].
 *
 * Worth testing because this class owns every user-input rule for the
 * voice-alias feature: the lowercase-alphanumeric name regex, the
 * "rename vs create" collision distinction, the scoped error-clearing,
 * and the rename-deletes-old-row branch in [AliasViewModel.save]. None
 * of those are reachable from the SpeakViewModel test surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AliasViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // -- Name validation ------------------------------------------------------

    @Test
    fun onEditorNameChange_validName_clearsError() = runTest {
        val vm = newViewModel()
        vm.openEditor()

        // Set an invalid name first so the editor enters an error state.
        // (Validation runs in save(), not on each keystroke, so we have to
        //  poke save() to seed the error.)
        vm.onEditorNameChange("has space")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
        vm.save()
        assertNotNull(
            "Invalid name should produce an error after save()",
            vm.editorState.first().error,
        )

        // Now switch to a valid name — the scoped clearing should drop the
        // InvalidName error (since onEditorNameChange clears name-related errors).
        vm.onEditorNameChange("narrator")
        assertNull(
            "Editing the name should clear the name-related error",
            vm.editorState.first().error,
        )
    }

    @Test
    fun onEditorNameChange_blank_setsBlankError() = runTest {
        val vm = newViewModel()
        vm.openEditor()

        // Validation triggers at save() time, not on keystrokes. To assert
        // a blank name is rejected we feed it through save() and check the
        // resulting editor error.
        vm.onEditorNameChange("   ")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val saved = vm.save()
        assertFalse("Blank name must not save", saved)
        assertNotNull(
            "Blank name should produce a non-null error",
            vm.editorState.first().error,
        )
    }

    @Test
    fun onEditorNameChange_invalidChars_setsCharError() = runTest {
        // Each of these must be rejected by VoiceAlias.NAME_REGEX
        // (^[a-z][a-z0-9_-]*$): leading digit, uppercase, space, special chars.
        val invalid = listOf("narrator's", "narrator!", "narrator@home", "narr ator", "Narrator")
        for (bad in invalid) {
            val vm = newViewModel()
            vm.openEditor()
            vm.onEditorNameChange(bad)
            vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
            val saved = vm.save()
            assertFalse("'$bad' must not save", saved)
            assertNotNull(
                "'$bad' should produce a non-null error",
                vm.editorState.first().error,
            )
        }

        // These all match the regex and should clear the error after save().
        // (Note: save() also requires a voiceId; we set one to isolate the
        //  name rule from the MissingVoice rule.)
        val valid = listOf("narrator", "happy-mood", "voice_1")
        for (good in valid) {
            val vm = newViewModel()
            vm.openEditor()
            vm.onEditorNameChange(good)
            vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
            val saved = vm.save()
            assertTrue("'$good' should save", saved)
            // save() clears editorState on success, so error is null and the
            // editor is closed. Both prove the name passed validation.
            assertNull(
                "'$good' should not leave an error",
                vm.editorState.first().error,
            )
        }
    }

    @Test
    fun onEditorNameChange_existingAliasName_setsCollisionError() = runTest {
        val existing = alias("narrator")
        val vm = newViewModel(aliases = listOf(existing))
        // Wait for the aliases StateFlow to surface the seeded row before
        // entering the editor — save()'s collision check reads aliases.value.
        vm.aliases.first { it.isNotEmpty() }

        vm.openEditor() // create mode (existing = null)
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val saved = vm.save()

        assertFalse("Save with colliding name must be rejected", saved)
        assertEquals(
            SaveError.NameTaken,
            vm.editorState.first().error,
        )
    }

    @Test
    fun onEditorNameChange_editingOwnAlias_doesNotCollide() = runTest {
        val existing = alias("narrator")
        val vm = newViewModel(aliases = listOf(existing))
        vm.aliases.first { it.isNotEmpty() }

        // Edit mode: opening with `existing` carves out its own name from
        // the collision check. Keeping the name the same must not collide.
        vm.openEditor(existing)
        // (voiceId is already set from the existing alias)
        val saved = vm.save()

        assertTrue("Editing own alias with unchanged name should save", saved)
        assertNull(vm.editorState.first().error)
    }

    // -- Save behaviour -------------------------------------------------------

    @Test
    fun save_create_insertsNewAlias() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        vm.openEditor()
        vm.onEditorNameChange("storyteller")
        vm.onEditorEngineChange("kitten")
        vm.onEditorVoiceChange("kitten:Hugo")
        vm.onEditorSpeedChange(1.25f)
        vm.onEditorEffectChange(EffectPreset.CAVE)

        val saved = vm.save()
        assertTrue(saved)

        assertEquals(1, aliasDao.upsertedAliases.size)
        val row = aliasDao.upsertedAliases.single()
        assertEquals("storyteller", row.name)
        assertEquals("kitten", row.engine)
        assertEquals("kitten:Hugo", row.voiceId)
        assertEquals(1.25f, row.speed, 0.0f)
        assertEquals("CAVE", row.effectPreset)
    }

    @Test
    fun save_create_blockedByError_doesNotInsert() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        vm.openEditor()
        // Invalid: leading uppercase fails the regex.
        vm.onEditorNameChange("Invalid Name")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)

        val saved = vm.save()
        assertFalse(saved)

        // No persistence side effect, and the editor stays open with the
        // error populated so the user can fix the field.
        assertTrue(
            "No upsert should fire on validation failure",
            aliasDao.upsertedAliases.isEmpty(),
        )
        assertTrue(
            "No delete should fire on validation failure",
            aliasDao.deletedNames.isEmpty(),
        )
        val state = vm.editorState.first()
        assertTrue("Editor should stay open after failed save", state.isOpen)
        assertNotNull(state.error)
    }

    @Test
    fun save_renameOfExisting_deletesOldRowAndInsertsNew() = runTest {
        val existing = alias("narrator")
        val aliasDao = FakeAliasDao(initial = listOf(existing))
        val vm = newViewModel(aliasDao = aliasDao)
        vm.aliases.first { it.isNotEmpty() }

        vm.openEditor(existing) // edit mode for "narrator"
        vm.onEditorNameChange("storyteller")
        vm.onEditorVoiceChange("kitten:Luna")
        vm.onEditorEffectChange(EffectPreset.ROBOT)
        vm.onEditorSpeedChange(0.9f)

        val saved = vm.save()
        assertTrue(saved)

        // Rename path: drops the old PK row first so we don't carry both.
        assertTrue(
            "Old name 'narrator' should be deleted on rename",
            aliasDao.deletedNames.contains("narrator"),
        )
        val inserted = aliasDao.upsertedAliases.singleOrNull { it.name == "storyteller" }
        assertNotNull(
            "A row named 'storyteller' should be upserted on rename",
            inserted,
        )
        assertEquals("kitten:Luna", inserted!!.voiceId)
        assertEquals("ROBOT", inserted.effectPreset)
        assertEquals(0.9f, inserted.speed, 0.0f)
    }

    @Test
    fun save_clearsEditorOnSuccess() = runTest {
        val vm = newViewModel()
        vm.openEditor()
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)

        val saved = vm.save()
        assertTrue(saved)

        val state = vm.editorState.first()
        assertFalse("Editor should be closed after successful save", state.isOpen)
        assertNull(state.error)
    }

    // -- Primary alias behaviour ----------------------------------------------

    @Test
    fun firstCreatedAlias_becomesPrimary() = runTest {
        val settings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        assertNull("Primary should start null", settings.primaryAliasName.first())

        val vm = newViewModel(settings = settings)
        vm.openEditor()
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
        val ok = vm.save()
        assertTrue(ok)

        assertEquals(
            "First-created alias should auto-promote to primary",
            "narrator",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun creatingSecondAlias_doesNotOverridePrimary() = runTest {
        val settings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        val vm = newViewModel(settings = settings)

        // First alias — auto-promotes.
        vm.openEditor()
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenVoiceCatalog.DEFAULT_VOICE_ID)
        vm.save()
        assertEquals("narrator", settings.primaryAliasName.first())

        // Second alias — primary should NOT change.
        vm.openEditor()
        vm.onEditorNameChange("storyteller")
        vm.onEditorVoiceChange("kitten:Hugo")
        vm.save()

        assertEquals(
            "Primary should remain on the first alias",
            "narrator",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun deletingPrimaryAlias_clearsPrimaryPointer() = runTest {
        val existing = alias("narrator")
        val settings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        settings.setPrimaryAliasName("narrator")
        val vm = newViewModel(
            aliasDao = FakeAliasDao(initial = listOf(existing)),
            settings = settings,
        )
        vm.aliases.first { it.isNotEmpty() }

        vm.delete("narrator")

        assertNull(
            "Deleting the primary alias should clear the pointer",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun deletingNonPrimaryAlias_preservesPrimaryPointer() = runTest {
        val primary = alias("narrator")
        val other = alias("storyteller")
        val settings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        settings.setPrimaryAliasName("narrator")
        val vm = newViewModel(
            aliasDao = FakeAliasDao(initial = listOf(primary, other)),
            settings = settings,
        )
        vm.aliases.first { it.size == 2 }

        vm.delete("storyteller")

        assertEquals(
            "Deleting a non-primary alias should not affect the primary pointer",
            "narrator",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun setPrimary_explicitlyChangesPointer() = runTest {
        val first = alias("narrator")
        val second = alias("storyteller")
        val settings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        settings.setPrimaryAliasName("narrator")
        val vm = newViewModel(
            aliasDao = FakeAliasDao(initial = listOf(first, second)),
            settings = settings,
        )
        vm.aliases.first { it.size == 2 }

        vm.setPrimary("storyteller")

        assertEquals(
            "Primary should follow the explicit setPrimary call",
            "storyteller",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun renamingPrimaryAlias_retargetsPointer() = runTest {
        val existing = alias("narrator")
        val settings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        settings.setPrimaryAliasName("narrator")
        val vm = newViewModel(
            aliasDao = FakeAliasDao(initial = listOf(existing)),
            settings = settings,
        )
        vm.aliases.first { it.isNotEmpty() }

        vm.openEditor(existing)
        vm.onEditorNameChange("storyteller")
        vm.save()

        assertEquals(
            "Renaming the primary alias should follow it to the new name",
            "storyteller",
            settings.primaryAliasName.first(),
        )
    }

    // -- Delete behaviour -----------------------------------------------------

    @Test
    fun delete_removesByName() = runTest {
        val existing = alias("narrator")
        val aliasDao = FakeAliasDao(initial = listOf(existing))
        val vm = newViewModel(aliasDao = aliasDao)
        vm.aliases.first { it.isNotEmpty() }

        vm.delete("narrator")

        assertTrue(
            "delete() should reach the DAO with the given name",
            aliasDao.deletedNames.contains("narrator"),
        )
    }

    // -- Effect / engine change ----------------------------------------------

    @Test
    fun onEditorEffectChange_updatesEditorState() = runTest {
        val vm = newViewModel()
        vm.openEditor()

        vm.onEditorEffectChange(EffectPreset.CAVE)
        assertEquals(EffectPreset.CAVE, vm.editorState.first().effect)
    }

    @Test
    fun onEditorEngineChange_resetsVoiceWhenIncompatible() = runTest {
        // Set up: editor in create mode (defaults to engine = "kitten"),
        // pick a Kitten voice, then flip engine. The voice ID should clear
        // — otherwise a Kitten voice ID would be smuggled into a non-Kitten
        // alias row.
        val vm = newViewModel()
        vm.openEditor()
        vm.onEditorVoiceChange("kitten:Bella")
        assertEquals("kitten:Bella", vm.editorState.first().voiceId)

        // Engine names are accepted as opaque strings — no need for a real
        // second engine in the catalog to exercise this branch.
        vm.onEditorEngineChange("piper")

        val state = vm.editorState.first()
        assertEquals("piper", state.engine)
        assertEquals(
            "Switching engine must clear the previously-selected voice ID",
            "",
            state.voiceId,
        )
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Build a ViewModel with a FakeAliasDao. Pass `aliases` to seed initial
     * rows, or pass a constructed `aliasDao` to inspect upsert/delete
     * recorder lists after the action. Passing both is an error in tests —
     * seed via the DAO directly when you need both.
     */
    private fun newViewModel(
        aliasDao: FakeAliasDao? = null,
        aliases: List<VoiceAlias> = emptyList(),
        settings: FakeSettings = FakeSettings(
            initialId = KittenVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        ),
    ): AliasViewModel {
        require(aliasDao == null || aliases.isEmpty()) {
            "Pass either aliasDao or aliases, not both"
        }
        val dao = aliasDao ?: FakeAliasDao(initial = aliases)
        val voiceDao = FakeDao(voices = KittenVoiceCatalog.voices)
        return AliasViewModel(aliasDao = dao, voiceDao = voiceDao, settings = settings)
    }

    private fun alias(
        name: String,
        engine: String = "kitten",
        voiceId: String = KittenVoiceCatalog.DEFAULT_VOICE_ID,
        speed: Float = 1.0f,
        effectPreset: String = "NONE",
        createdAt: Long = 0L,
    ): VoiceAlias = VoiceAlias(
        name = name,
        engine = engine,
        voiceId = voiceId,
        speed = speed,
        effectPreset = effectPreset,
        createdAt = createdAt,
    )
}
