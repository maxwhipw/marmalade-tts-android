package app.marmalade.tts.ui.screen

import app.marmalade.tts.data.BuiltinEffects
import app.marmalade.tts.data.KittenNanoVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
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
        //  poke save() to seed the error.) After the validation relaxation,
        // "@" is one of the few chars still rejected.
        vm.onEditorNameChange("narrator@home")
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
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
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        val saved = vm.save()
        assertFalse("Blank name must not save", saved)
        assertNotNull(
            "Blank name should produce a non-null error",
            vm.editorState.first().error,
        )
    }

    @Test
    fun onEditorNameChange_invalidChars_setsCharError() = runTest {
        // Post-relaxation: letters (any case), digits, spaces, dashes,
        // underscores, and apostrophes are all OK. Special chars like !
        // @ # / etc. are rejected, as are strings that exceed
        // [VoiceAlias.MAX_NAME_LENGTH].
        val invalid = listOf("narrator!", "narrator@home", "narrator/voice", "x".repeat(51))
        for (bad in invalid) {
            val vm = newViewModel()
            vm.openEditor()
            vm.onEditorNameChange(bad)
            vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
            val saved = vm.save()
            assertFalse("'$bad' must not save", saved)
            assertNotNull(
                "'$bad' should produce a non-null error",
                vm.editorState.first().error,
            )
        }

        // These all match the new rule and should clear the error after
        // save(). Includes the names Max specifically called out wanting
        // to support (uppercase + spaces, e.g. "Max Warren").
        // (Note: save() also requires a voiceId; we set one to isolate
        //  the name rule from the MissingVoice rule.)
        val valid = listOf(
            "narrator", "happy-mood", "voice_1",
            "Max Warren", "O'Brien", "Narrator 2",
        )
        for (good in valid) {
            val vm = newViewModel()
            vm.openEditor()
            vm.onEditorNameChange(good)
            vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
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
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
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
        vm.onEditorEngineChange("kitten-nano-v0_8")
        vm.onEditorVoiceChange("kitten-nano-v0_8:Hugo")
        vm.onEditorSpeedChange(1.25f)
        vm.onEditorEffectChange(BuiltinEffects.CAVE_ID)

        val saved = vm.save()
        assertTrue(saved)

        assertEquals(1, aliasDao.upsertedAliases.size)
        val row = aliasDao.upsertedAliases.single()
        assertEquals("storyteller", row.name)
        assertEquals("kitten-nano-v0_8", row.engine)
        assertEquals("kitten-nano-v0_8:Hugo", row.voiceId)
        assertEquals(1.25f, row.speed, 0.0f)
        // E-G: the picker writes effectId directly; effectPreset is the retired
        // legacy column, now always "NONE".
        assertEquals(BuiltinEffects.CAVE_ID, row.effectId)
        assertEquals("NONE", row.effectPreset)
    }

    @Test
    fun save_create_blockedByError_doesNotInsert() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        vm.openEditor()
        // Invalid: `@` is one of the few characters still rejected after
        // the validation relaxation (which now accepts uppercase + spaces).
        vm.onEditorNameChange("Invalid@Name")
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)

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
        vm.onEditorVoiceChange("kitten-nano-v0_8:Luna")
        vm.onEditorEffectChange(BuiltinEffects.ROBOT_ID)
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
        assertEquals("kitten-nano-v0_8:Luna", inserted!!.voiceId)
        assertEquals(BuiltinEffects.ROBOT_ID, inserted.effectId)
        assertEquals("NONE", inserted.effectPreset)
        assertEquals(0.9f, inserted.speed, 0.0f)
    }

    @Test
    fun save_clearsEditorOnSuccess() = runTest {
        val vm = newViewModel()
        vm.openEditor()
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)

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
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        assertNull("Primary should start null", settings.primaryAliasName.first())

        val vm = newViewModel(settings = settings)
        vm.openEditor()
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
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
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        val vm = newViewModel(settings = settings)

        // First alias — auto-promotes.
        vm.openEditor()
        vm.onEditorNameChange("narrator")
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)
        vm.save()
        assertEquals("narrator", settings.primaryAliasName.first())

        // Second alias — primary should NOT change.
        vm.openEditor()
        vm.onEditorNameChange("storyteller")
        vm.onEditorVoiceChange("kitten-nano-v0_8:Hugo")
        vm.save()

        assertEquals(
            "Primary should remain on the first alias",
            "narrator",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun deletingPrimaryAlias_promotesSuccessor() = runTest {
        // Invariant: while any alias exists, exactly one is primary. Deleting
        // the primary must promote a remaining alias, not clear the pointer.
        val primary = alias("narrator")
        val other = alias("storyteller")
        val settings = FakeSettings(
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        )
        settings.setPrimaryAliasName("narrator")
        val vm = newViewModel(
            aliasDao = FakeAliasDao(initial = listOf(primary, other)),
            settings = settings,
        )
        vm.aliases.first { it.size == 2 }

        vm.delete("narrator")

        assertEquals(
            "Deleting the primary should promote the remaining alias",
            "storyteller",
            settings.primaryAliasName.first(),
        )
    }

    @Test
    fun deletingLastAlias_isRefused() = runTest {
        // Cannot delete the final alias — every install with at least one
        // alias must always keep at least one.
        val only = alias("narrator")
        val aliasDao = FakeAliasDao(initial = listOf(only))
        val vm = newViewModel(aliasDao = aliasDao)
        vm.aliases.first { it.isNotEmpty() }

        val deleted = vm.delete("narrator")

        assertFalse("delete() should refuse the last alias", deleted)
        assertTrue(
            "the last alias must not reach the DAO's delete",
            aliasDao.deletedNames.isEmpty(),
        )
    }

    @Test
    fun deletingNonPrimaryAlias_preservesPrimaryPointer() = runTest {
        val primary = alias("narrator")
        val other = alias("storyteller")
        val settings = FakeSettings(
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
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
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
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
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
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
        // Two aliases so the delete is permitted (the last alias can't be
        // deleted — see deletingLastAlias_isRefused).
        val aliasDao = FakeAliasDao(initial = listOf(alias("narrator"), alias("storyteller")))
        val vm = newViewModel(aliasDao = aliasDao)
        vm.aliases.first { it.size == 2 }

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

        vm.onEditorEffectChange(BuiltinEffects.CAVE_ID)
        assertEquals(BuiltinEffects.CAVE_ID, vm.editorState.first().effectId)
    }

    // -- phonemization language (alpha.10.L / F7) ----------------------------

    @Test
    fun onEditorPhonemizationLanguageChange_updatesEditorState() = runTest {
        val vm = newViewModel()
        vm.openEditor()
        assertNull(
            "Default is null (= auto-derive from voice prefix)",
            vm.editorState.first().phonemizationLanguage,
        )

        vm.onEditorPhonemizationLanguageChange("ja")
        assertEquals("ja", vm.editorState.first().phonemizationLanguage)

        // Setting to null clears the override (Auto in the UI).
        vm.onEditorPhonemizationLanguageChange(null)
        assertNull(vm.editorState.first().phonemizationLanguage)
    }

    @Test
    fun save_writesPhonemizationLanguage() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        vm.openEditor()
        vm.onEditorNameChange("narrator-jp")
        vm.onEditorEngineChange("kokoro-direct-v1_0")
        vm.onEditorVoiceChange("kokoro-direct-v1_0:jf_alpha")
        vm.onEditorPhonemizationLanguageChange("ja")

        val saved = vm.save()
        assertTrue(saved)

        val row = aliasDao.upsertedAliases.single()
        assertEquals("ja", row.phonemizationLanguage)
    }

    @Test
    fun save_phonemizationLanguageDefaultsToNull() = runTest {
        // Without calling onEditorPhonemizationLanguageChange, the saved
        // row carries null — that's the "Auto" sentinel the engine resolves
        // via KokoroDirectVoiceCatalog.espeakVoiceFor.
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        vm.openEditor()
        vm.onEditorNameChange("plain")
        vm.onEditorVoiceChange(KittenNanoVoiceCatalog.DEFAULT_VOICE_ID)

        vm.save()
        assertNull(aliasDao.upsertedAliases.single().phonemizationLanguage)
    }

    @Test
    fun openEditor_existing_loadsPhonemizationLanguage() = runTest {
        // Edit-mode loader must round-trip the field — otherwise opening
        // an alias's editor would silently erase a user's override the
        // moment they Save.
        val existing = alias("narrator-jp", phonemizationLanguage = "ja")
        val vm = newViewModel(aliases = listOf(existing))
        vm.aliases.first { it.isNotEmpty() }

        vm.openEditor(existing)
        assertEquals("ja", vm.editorState.first().phonemizationLanguage)
    }

    @Test
    fun onEditorEngineChange_resetsVoiceWhenIncompatible() = runTest {
        // Set up: editor in create mode (defaults to engine = "kitten-nano-v0_8"),
        // pick a Kitten voice, then flip engine. The voice ID should clear
        // — otherwise a Kitten voice ID would be smuggled into a non-Kitten
        // alias row.
        val vm = newViewModel()
        vm.openEditor()
        vm.onEditorVoiceChange("kitten-nano-v0_8:Bella")
        assertEquals("kitten-nano-v0_8:Bella", vm.editorState.first().voiceId)

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
            initialId = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
            initialOnboarded = true,
        ),
    ): AliasViewModel {
        require(aliasDao == null || aliases.isEmpty()) {
            "Pass either aliasDao or aliases, not both"
        }
        val dao = aliasDao ?: FakeAliasDao(initial = aliases)
        val voiceDao = FakeDao(voices = KittenNanoVoiceCatalog.voices)
        return AliasViewModel(
            aliasDao = dao,
            voiceDao = voiceDao,
            settings = settings,
            installer = AliasFakeInstaller(),
            effectDao = FakeEffectDao(),
        )
    }

    private fun alias(
        name: String,
        engine: String = "kitten-nano-v0_8",
        voiceId: String = KittenNanoVoiceCatalog.DEFAULT_VOICE_ID,
        speed: Float = 1.0f,
        effectPreset: String = "NONE",
        createdAt: Long = 0L,
        phonemizationLanguage: String? = null,
    ): VoiceAlias = VoiceAlias(
        name = name,
        engine = engine,
        voiceId = voiceId,
        speed = speed,
        effectPreset = effectPreset,
        createdAt = createdAt,
        phonemizationLanguage = phonemizationLanguage,
    )
}

/**
 * Test double for [EngineInstaller] — stubs out file I/O + HTTP so the VM's
 * init-time `verify()` probe over the catalog resolves without touching disk.
 * The alias-editor tests don't assert on the engine *picker* (they exercise
 * save/validation), so it reports a sensible installed set and is otherwise
 * inert. Mirrors VoicePickerViewModelTest's PickerFakeInstaller (private to
 * that file, hence the duplicate).
 */
private class AliasFakeInstaller(
    private val installedEngines: Set<String> = setOf("kitten-nano-v0_8", "kokoro-direct-v1_0"),
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/aliasvm-test-unused") },
    kittenEngine = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used in this test") },
) {
    override suspend fun verify(engineName: String): InstallState =
        if (engineName in installedEngines) InstallState.Installed else InstallState.NotInstalled
}
