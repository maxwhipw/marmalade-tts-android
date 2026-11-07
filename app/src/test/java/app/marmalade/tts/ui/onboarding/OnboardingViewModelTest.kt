package app.marmalade.tts.ui.onboarding

import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallException
import app.marmalade.tts.install.InstallFailure
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.perf.DeviceProbe
import app.marmalade.tts.perf.DeviceProbeSource
import app.marmalade.tts.perf.EngineFit
import app.marmalade.tts.ui.screen.FakeAliasDao
import app.marmalade.tts.ui.screen.FakeDao
import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.ui.screen.PreviewState
import app.marmalade.tts.ui.screen.RecordingPlayer
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

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
    fun stepProgressesThroughWelcomeToEnginePickToInstallingToChooseVoice() = runTest {
        val vm = newViewModel()
        assertEquals(OnboardingStep.Welcome, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.EnginePick, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.Installing, vm.step.value)

        vm.next()
        assertEquals(OnboardingStep.ChooseVoice, vm.step.value)

        vm.back()
        assertEquals(OnboardingStep.Installing, vm.step.value)

        vm.back()
        assertEquals(OnboardingStep.EnginePick, vm.step.value)

        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)

        // Back from Welcome is a no-op (back-stop).
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
    }

    // -- system Back mirrors vm.back() (#148) --------------------------------

    @Test
    fun backDuringADownloadStaysOnTheProgressStep() = runTest {
        val download = CompletableDeferred<Result<Unit>>()
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) }, waitFor = download)
        val vm = newViewModel(installer = installer)
        vm.next()
        vm.installSelected()

        vm.back()

        assertEquals("the step has no Back while it downloads", OnboardingStep.Installing, vm.step.value)
        download.complete(Result.failure(java.io.IOException("offline")))
        advanceUntilIdle()
        vm.back()
        assertEquals(OnboardingStep.EnginePick, vm.step.value)
    }

    @Test
    fun backWalksEveryStepToTheStart() = runTest {
        val vm = newViewModel(installer = KITTEN_ON_DISK)
        vm.next()
        vm.installSelected()
        vm.next()
        vm.prepareVoiceStep()
        vm.useVoice()
        vm.advancePastBackground()
        vm.advancePastNotifications()
        assertEquals(OnboardingStep.SystemDefault, vm.step.value)

        val walked = List(7) { vm.back(); vm.step.value }

        assertEquals(
            listOf(
                OnboardingStep.NotificationPermission,
                OnboardingStep.BackgroundUnrestricted,
                OnboardingStep.ChooseVoice,
                OnboardingStep.Installing,
                OnboardingStep.EnginePick,
                OnboardingStep.Welcome,
                OnboardingStep.Welcome,
            ),
            walked,
        )
    }

    @Test
    fun backFromTheLastStepSkipsANotificationStepThatIsNotAsked() = runTest {
        // Android 12 and below: the notification step moves straight on, so
        // Back onto it would bounce straight back.
        val vm = newViewModel(installer = KITTEN_ON_DISK, asksNotifications = false)
        vm.next()
        vm.skipEngineInstall()
        vm.prepareVoiceStep()
        vm.useVoice()
        vm.advancePastBackground()
        vm.advancePastNotifications()

        vm.back()

        assertEquals(OnboardingStep.BackgroundUnrestricted, vm.step.value)
    }

    @Test
    fun toggleFlipsSelection() = runTest {
        val vm = newViewModel()
        assertFalse(vm.selectedEngineIds.value.contains(KOKORO))

        vm.toggle(KOKORO)
        assertTrue(vm.selectedEngineIds.value.contains(KOKORO))

        vm.toggle(KOKORO)
        assertFalse(vm.selectedEngineIds.value.contains(KOKORO))
    }

    @Test
    fun bakedEngineCannotBeDeselected() = runTest {
        val vm = newViewModel()
        // Kitten is baked into the APK — pre-selected and not a choice.
        assertTrue(vm.selectedEngineIds.value.contains(KITTEN))

        vm.toggle(KITTEN)

        assertTrue(
            "The baked engine must survive a toggle",
            vm.selectedEngineIds.value.contains(KITTEN),
        )
    }

    @Test
    fun installSelectedDrivesInstallerAndLandsInstalledState() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)

        vm.installSelected()

        assertEquals(OnboardingStep.Installing, vm.step.value)
        // Only the recommended engine (kitten) is pre-selected.
        assertEquals(listOf("kitten-direct-v0_8"), installer.installCalls)
        assertEquals(InstallState.Installed, vm.installStates.value["kitten-direct-v0_8"])
    }

    @Test
    fun skipEngineInstallDownloadsNothing() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)
        vm.toggle(KOKORO)

        vm.skipEngineInstall()

        assertEquals(OnboardingStep.ChooseVoice, vm.step.value)
        assertTrue("no installs expected", installer.installCalls.isEmpty())
    }

    @Test
    fun installFailureLandsFailedStateButLeavesStepIntact() = runTest {
        val installer = RecordingInstaller(behaviour = {
            Result.failure(java.io.IOException("net dropped"))
        })
        val vm = newViewModel(installer = installer)

        vm.installSelected()

        val state = vm.installStates.value["kitten-direct-v0_8"]
        assertTrue("expected Failed, got $state", state is InstallState.Failed)
        assertEquals("net dropped", (state as InstallState.Failed).reason)
    }

    @Test
    fun installFailureKeepsTheFailureKindForTheLocalizedMessage() = runTest {
        // L9: the row used to rebuild Failed from the exception message alone,
        // dropping the kind the screen maps to a translated sentence.
        val installer = RecordingInstaller(behaviour = {
            Result.failure(InstallException(InstallFailure.NOT_AVAILABLE, "not published"))
        })
        val vm = newViewModel(installer = installer)

        vm.installSelected()

        val state = vm.installStates.value[KITTEN] as InstallState.Failed
        assertEquals(InstallFailure.NOT_AVAILABLE, state.failure)
    }

    @Test
    fun installOutcomePrefersTheInstallersOwnFailedState() {
        // The installer publishes its Failed (kind + kept partial bytes) before
        // install() returns; that is what "Remove download" keys off.
        val live = InstallState.Failed(
            reason = "connection reset",
            failure = null,
            partialDownloadBytes = 1024L,
        )
        val err = java.io.IOException("connection reset")

        assertEquals(live, installOutcome(Result.failure(err), live))
        // No live Failed (a fake, or a collector that missed it) → built from
        // the exception, kind preserved, blank reason when it has no message.
        assertEquals(
            InstallState.Failed("full", InstallFailure.NO_SPACE),
            installOutcome(
                Result.failure(InstallException(InstallFailure.NO_SPACE, "full")),
                InstallState.NotInstalled,
            ),
        )
        assertEquals(
            InstallState.Failed(""),
            installOutcome(Result.failure(RuntimeException()), InstallState.NotInstalled),
        )
        assertEquals(InstallState.Installed, installOutcome(Result.success(Unit), live))
    }

    @Test
    fun removeDownloadDiscardsThePartialButLeavesTheRowFailed() = runTest {
        val installer = RecordingInstaller(behaviour = {
            Result.failure(java.io.IOException("net dropped"))
        })
        val vm = newViewModel(installer = installer)
        vm.installSelected()

        vm.removeDownload(KITTEN)

        assertEquals(listOf(KITTEN), installer.discardCalls)
        // Still Failed, so Retry stays offered and Continue stays enabled —
        // flipping to NotInstalled would strand the Installing step.
        val state = vm.installStates.value[KITTEN] as InstallState.Failed
        assertEquals(0L, state.partialDownloadBytes)
        assertEquals("net dropped", state.reason)
    }

    @Test
    fun retrySendsAnotherInstallRequest() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer)
        vm.installSelected()
        assertEquals(1, installer.installCalls.size)

        // Retry the same engine the recommended default lands on
        // (kitten), so the running count grows by exactly one.
        vm.retry("kitten-direct-v0_8")
        assertEquals(2, installer.installCalls.size)
        assertEquals(InstallState.Installed, vm.installStates.value["kitten-direct-v0_8"])
    }

    @Test
    fun finishWithoutAliasIsBlocked() = runTest {
        // No aliases in the DB → finish() must refuse to flip onboarded.
        val settings = FakeSettings(
            initialId = "kitten-direct-v0_8:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)
        vm.aliasCreated.first { !it }

        val flipped = vm.finish()

        assertFalse("finish() should refuse when no alias exists", flipped)
        assertEquals(false, settings.onboarded.first())
    }

    @Test
    fun finishWithExistingAliasFlipsOnboardedAndAutoAssignsPrimary() = runTest {
        // Existing-alias edge case (sideloaded data): finish() should
        // flip onboarded *and* auto-promote the existing alias to primary
        // when no primary is set yet.
        val existing = VoiceAlias(
            name = "narrator",
            id = "id-test",
            engine = "kitten-direct-v0_8",
            voiceId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
            speed = 1.0f,
            effectPreset = EffectPreset.NONE.name,
            createdAt = 0L,
        )
        val settings = FakeSettings(
            initialId = "kitten-direct-v0_8:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao(initial = listOf(existing))
        val vm = newViewModel(settings = settings, aliasDao = aliasDao)
        vm.aliasCreated.first { it }

        val flipped = vm.finish()

        assertTrue("finish() should flip when an alias exists", flipped)
        assertEquals(true, settings.onboarded.first())
        assertEquals(
            "Primary should self-heal to the existing alias",
            "id-test",
            settings.primaryAliasId.first(),
        )
    }

    // -- the voice step (#87) ------------------------------------------------

    @Test
    fun useVoiceSavesTheFirstAliasNamedAfterItsVoiceMarksPrimaryAndGoesToTheBatteryStep() = runTest {
        val settings = FakeSettings(
            initialId = "kitten-direct-v0_8:Bella",
            initialOnboarded = false,
        )
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(settings = settings, aliasDao = aliasDao, installer = KITTEN_ON_DISK)
        vm.prepareVoiceStep()

        vm.useVoice()

        val row = aliasDao.upsertedAliases.single()
        // Kitten's best-first order puts Rosie first; the alias takes her name.
        assertEquals("Rosie", row.name)
        assertEquals(KittenDirectVoiceCatalog.voiceId("Rosie"), row.voiceId)
        assertEquals(KITTEN, row.engine)
        assertEquals(1.0f, row.speed)
        assertEquals(EffectPreset.NONE.name, row.effectPreset)
        assertEquals(
            "The primary points at the created row's id, not its label",
            row.id,
            settings.primaryAliasId.first(),
        )
        // One path for everyone: the battery step, then notifications.
        // onboarded stays false until finish() on the final step.
        assertEquals(false, settings.onboarded.first())
        assertEquals(OnboardingStep.BackgroundUnrestricted, vm.step.first())
        assertTrue("aliasCreated flips once the alias is saved", vm.aliasCreated.first { it })
        assertFalse(
            "Finish setup must not flash in while the step leaves",
            vm.aliasOnEntry.value,
        )
    }

    @Test
    fun aPickedVoiceIsTheOneSaved() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao, installer = KITTEN_ON_DISK)
        vm.prepareVoiceStep()

        vm.pickVoice(KittenDirectVoiceCatalog.voiceId("Leo"))
        assertEquals("Leo", vm.selectedVoice.first { it != null }!!.displayName)
        vm.useVoice()

        assertEquals("Leo", aliasDao.upsertedAliases.single().name)
    }

    @Test
    fun usingTheSameVoiceAgainEditsTheSameAlias() = runTest {
        // "Use Rosie" a second time (setup run again): one alias, not two.
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao, installer = KITTEN_ON_DISK)
        vm.prepareVoiceStep()

        vm.useVoice()
        vm.back()
        vm.useVoice()

        assertEquals(1, aliasDao.upsertedAliases.map { it.id }.distinct().size)
    }

    @Test
    fun withNothingInstalledThereIsNothingToUse() = runTest {
        val aliasDao = FakeAliasDao()
        val vm = newViewModel(aliasDao = aliasDao)
        vm.prepareVoiceStep()

        assertEquals(emptyList<VoiceGroup>(), vm.voiceGroups.first { it != null })
        assertNull(vm.selectedVoice.value)
        vm.useVoice()

        assertTrue(aliasDao.upsertedAliases.isEmpty())
    }

    @Test
    fun theListWaitsForTheDiskProbe() = runTest {
        val vm = newViewModel(installer = KITTEN_ON_DISK)
        assertNull("no empty state before the probe", vm.voiceGroups.value)

        vm.prepareVoiceStep()

        assertEquals(listOf(KITTEN), vm.voiceGroups.first { it != null }!!.map { it.engine.name })
    }

    @Test
    fun aSpanishPhoneLeadsWithAnInstalledEngineThatSpeaksSpanish() = runTest {
        // The phone check recommends Pocket (English only); Kokoro speaks
        // Spanish, so it leads and its first Spanish voice is picked.
        val vm = newViewModel(
            installer = DiskInstaller(engines = setOf(KITTEN, KOKORO, POCKET), packs = emptySet()),
            deviceProbe = FakeDeviceProbe(FAST_PHONE),
            voices = KittenDirectVoiceCatalog.voices + KokoroDirectVoiceCatalog.voices + PocketVoiceCatalog.voices,
            phone = Locale.forLanguageTag("es-ES"),
        )
        vm.recommendation.first { it != null }
        vm.prepareVoiceStep()

        val groups = vm.voiceGroups.first { it != null }!!
        assertEquals(listOf(KOKORO, POCKET, KITTEN), groups.map { it.engine.name })
        val firstSpanish = KokoroDirectVoiceCatalog.voices
            .filter { it.languageCode.startsWith("es") }
            .minBy { it.sortOrder }
        assertEquals(firstSpanish.id, vm.selectedVoice.value?.id)
        assertTrue("Pocket still carries the Recommended label", groups.first { it.engine.name == POCKET }.isRecommended)
    }

    @Test
    fun anEnglishPhoneLeadsWithTheRecommendedEngine() = runTest {
        val vm = newViewModel(
            installer = DiskInstaller(engines = setOf(KITTEN, KOKORO, POCKET), packs = emptySet()),
            deviceProbe = FakeDeviceProbe(FAST_PHONE),
            voices = KittenDirectVoiceCatalog.voices + KokoroDirectVoiceCatalog.voices + PocketVoiceCatalog.voices,
        )
        vm.recommendation.first { it != null }
        vm.prepareVoiceStep()

        val groups = vm.voiceGroups.first { it != null }!!
        assertEquals(listOf(POCKET, KITTEN, KOKORO), groups.map { it.engine.name })
        assertEquals(PocketVoiceCatalog.DEFAULT_VOICE_ID, vm.selectedVoice.value?.id)
    }

    @Test
    fun aSampleStopsWhenTheVoiceIsUsed() = runTest {
        val player = RecordingPlayer().apply { holdUntilCancel = true }
        val vm = newViewModel(installer = KITTEN_ON_DISK, player = player)
        vm.prepareVoiceStep()
        val leo = KittenDirectVoiceCatalog.voices.first { it.displayName == "Leo" }

        vm.togglePreview(leo, "Hello, I'm Leo.")
        assertEquals(PreviewState.Playing(leo.id), vm.previewState.value)
        assertEquals("hearing a voice doesn't pick it", "Rosie", vm.selectedVoice.value?.displayName)

        vm.useVoice()

        assertEquals(PreviewState.Idle, vm.previewState.value)
        assertEquals(leo.id, player.calls.single().voiceId)
    }

    @Test
    fun finishSetupWithAnAliasAlreadySavedMovesOnWithoutAnother() = runTest {
        val aliasDao = FakeAliasDao(initial = listOf(alias("narrator")))
        val vm = newViewModel(aliasDao = aliasDao, installer = KITTEN_ON_DISK)
        vm.aliasCreated.first { it }
        vm.prepareVoiceStep()

        assertTrue("the step offers Finish setup", vm.aliasOnEntry.value)
        assertTrue(vm.skipVoiceStep())

        assertEquals(OnboardingStep.BackgroundUnrestricted, vm.step.value)
        assertTrue(aliasDao.upsertedAliases.isEmpty())
    }

    // -- device-driven card labels; the Recommended card first (#63) ------

    @Test
    fun cardsStartInCatalogOrderWhileTheProbeIsPending() = runTest {
        // Catalog order (Kitten, Kokoro, Pocket) with the static
        // isRecommended flag is what the step shows before the probe lands:
        // the flagged card is Kitten, already first.
        val vm = newViewModel()

        assertEquals(
            CATALOG_CARDS,
            vm.engines.value.map { it.descriptor.name },
        )
        assertTrue("no measured fit before the probe answers",
            vm.engines.value.all { it.fit == null })
        assertNull(vm.recommendation.value)
    }

    @Test
    fun fastDeviceListsPocketFirstWithThePill() = runTest {
        val vm = newViewModel(deviceProbe = FakeDeviceProbe(FAST_PHONE))

        val cards = vm.engines.first { cards -> cards.any { it.fit != null } }

        // Issue #63: the Recommended card leads, the rest keep catalog order.
        assertEquals(
            listOf(POCKET) + (CATALOG_CARDS - POCKET),
            cards.map { it.descriptor.name },
        )
        // Issue #19: Pocket, on both flavors since #29.
        assertEquals(
            listOf(POCKET),
            cards.filter { it.showsRecommended }.map { it.descriptor.name },
        )
        assertEquals(EngineFit.RECOMMENDED, cards.first().fit)
        assertEquals(EngineFit.FINE, cards.first { it.descriptor.name == KITTEN }.fit)
    }

    @Test
    fun slowDeviceKeepsKittenFirstAndWarnsOnTheHeavyEngines() = runTest {
        val vm = newViewModel(
            deviceProbe = FakeDeviceProbe(
                DeviceProbe(measuredKittenRtf = 0.9, computeScore = 4.0),
            ),
        )

        val cards = vm.engines.first { cards -> cards.any { it.fit != null } }

        // Kitten is both recommended and first in the catalog: nothing moves.
        assertEquals(CATALOG_CARDS, cards.map { it.descriptor.name })
        assertEquals(listOf(KITTEN), cards.filter { it.showsRecommended }.map { it.descriptor.name })
        assertEquals(EngineFit.RECOMMENDED, cards.first { it.descriptor.name == KITTEN }.fit)
        assertEquals(EngineFit.MAY_BE_SLOW, cards.first { it.descriptor.name == KOKORO }.fit)
        assertEquals(EngineFit.MAY_BE_SLOW, cards.first { it.descriptor.name == POCKET }.fit)
    }

    @Test
    fun fastDevicePreTicksPocket() = runTest {
        val vm = newViewModel(deviceProbe = FakeDeviceProbe(FAST_PHONE))
        vm.recommendation.first { it != null }

        // #63 follow-up: the recommended download is ticked; the built-in
        // Kitten stays in the set (not a choice, and it waits for the seed).
        assertEquals(setOf(KITTEN, POCKET), vm.selectedEngineIds.value)
    }

    @Test
    fun slowDevicePreTicksOnlyKitten() = runTest {
        val vm = newViewModel(
            deviceProbe = FakeDeviceProbe(DeviceProbe(measuredKittenRtf = 0.9, computeScore = 4.0)),
        )
        vm.recommendation.first { it != null }

        assertEquals(setOf(KITTEN), vm.selectedEngineIds.value)
    }

    @Test
    fun aToggleBeforeTheVerdictKeepsTheUsersSelection() = runTest {
        val probe = CompletableDeferred<DeviceProbe>()
        val vm = newViewModel(
            deviceProbe = object : DeviceProbeSource {
                override suspend fun probe(): DeviceProbe = probe.await()
            },
        )

        vm.toggle(KOKORO)
        probe.complete(FAST_PHONE)
        vm.recommendation.first { it != null }

        assertEquals(setOf(KITTEN, KOKORO), vm.selectedEngineIds.value)
    }

    // -- the engine step waits for the phone check (#76) --------------------

    @Test
    fun whileCheckingTheListIsHiddenAndInstallDoesNothing() = runTest {
        val installer = RecordingInstaller(behaviour = { Result.success(Unit) })
        val vm = newViewModel(installer = installer, deviceProbe = NeverAnswers())
        vm.next()

        vm.installSelected()

        assertFalse("still checking", vm.probeDone.value)
        assertEquals(OnboardingStep.EnginePick, vm.step.value)
        assertTrue("no installs while checking", installer.installCalls.isEmpty())
    }

    @Test
    fun aVerdictFinishesTheCheckSortedAndTicked() = runTest {
        val vm = newViewModel(deviceProbe = FakeDeviceProbe(FAST_PHONE))

        vm.probeDone.first { it }

        assertEquals(POCKET, vm.recommendation.value?.recommended)
        assertEquals(POCKET, vm.engines.value.first().descriptor.name)
        assertEquals(setOf(KITTEN, POCKET), vm.selectedEngineIds.value)
    }

    @Test
    fun noVerdictStillFinishesTheCheckWithTheStaticList() = runTest {
        // No benchmark and no CPU score: recommend() returns null.
        val vm = newViewModel(deviceProbe = FakeDeviceProbe(DeviceProbe(null, null)))

        vm.probeDone.first { it }

        assertNull(vm.recommendation.value)
        assertStaticList(vm)
    }

    @Test
    fun aThrowingProbeStillFinishesTheCheckWithTheStaticList() = runTest {
        val vm = newViewModel(
            deviceProbe = object : DeviceProbeSource {
                override suspend fun probe(): DeviceProbe = throw IllegalStateException("ORT init")
            },
        )

        vm.probeDone.first { it }

        assertNull(vm.recommendation.value)
        assertStaticList(vm)
    }

    @Test
    fun aCheckPastTheCapShowsTheStaticListAndALateVerdictTicksNothing() = runTest {
        val probe = CompletableDeferred<DeviceProbe>()
        val vm = newViewModel(
            deviceProbe = object : DeviceProbeSource {
                override suspend fun probe(): DeviceProbe = probe.await()
            },
        )

        advanceTimeBy(PROBE_WAIT_MS - 1)
        assertFalse("still inside the cap", vm.probeDone.value)
        advanceTimeBy(2)
        assertTrue("the cap ends the wait", vm.probeDone.value)
        assertNull(vm.recommendation.value)
        assertStaticList(vm)

        // The verdict lands while the list is visible: it re-sorts and
        // relabels (the screen scrolls to the top) but ticks nothing.
        probe.complete(FAST_PHONE)
        vm.recommendation.first { it != null }
        assertEquals(POCKET, vm.engines.value.first().descriptor.name)
        assertEquals(setOf(KITTEN), vm.selectedEngineIds.value)
    }

    private fun assertStaticList(vm: OnboardingViewModel) {
        assertEquals(CATALOG_CARDS, vm.engines.value.map { it.descriptor.name })
        assertEquals(listOf(KITTEN), vm.engines.value.filter { it.showsRecommended }.map { it.descriptor.name })
        assertEquals(setOf(KITTEN), vm.selectedEngineIds.value)
    }

    @Test
    fun onlyTheBakedEngineIsMarkedBuiltIn() = runTest {
        val vm = newViewModel()
        assertEquals(
            listOf(KITTEN),
            vm.engines.value.filter { it.isBuiltIn }.map { it.descriptor.name },
        )
    }

    // -- voice step: only what can actually speak ---------------------------

    @Test
    fun theVoiceStepListsOnlyInstalledEngines() = runTest {
        val vm = newViewModel(
            installer = DiskInstaller(engines = setOf(KITTEN, VITS), packs = setOf(RELEASED_PACK)),
            voices = KittenDirectVoiceCatalog.voices + VitsVoiceCatalog.voices,
        )

        vm.prepareVoiceStep()

        assertEquals(
            "Catalog order, installed engines only — no Kokoro or Pocket",
            listOf(KITTEN, VITS),
            vm.voiceGroups.first { it != null }!!.map { it.engine.name },
        )
    }

    @Test
    fun vitsVoicesSkipUninstalledAndUnreleasedPacks() = runTest {
        // Both a released and an unreleased pack are on disk; every other
        // pack isn't. Production never flips VoiceMeta.isInstalled, so the
        // old isInstalled filter fell back to all seeded VITS rows.
        val vm = newViewModel(
            installer = DiskInstaller(
                engines = setOf(KITTEN, VITS),
                packs = setOf(RELEASED_PACK, UNRELEASED_PACK),
            ),
            voices = KittenDirectVoiceCatalog.voices + VitsVoiceCatalog.voices,
        )
        vm.prepareVoiceStep()

        val vits = vm.voiceGroups.first { it != null }!!.single { it.engine.name == VITS }.voices

        assertEquals(setOf(RELEASED_PACK), vits.map { VitsVoiceCatalog.packIdOf(it.id) }.toSet())
    }

    // -- helpers ----------------------------------------------------------

    private fun newViewModel(
        installer: EngineInstaller = RecordingInstaller(behaviour = { Result.success(Unit) }),
        settings: FakeSettings = FakeSettings(
            initialId = "kitten-direct-v0_8:Bella",
            initialOnboarded = false,
        ),
        aliasDao: FakeAliasDao = FakeAliasDao(),
        deviceProbe: DeviceProbeSource = FakeDeviceProbe(DeviceProbe(null, null)),
        voices: List<VoiceMeta> = KittenDirectVoiceCatalog.voices,
        player: RecordingPlayer = RecordingPlayer(),
        phone: Locale = Locale.US,
        asksNotifications: Boolean = true,
    ): OnboardingViewModel {
        val voiceDao = FakeDao(voices = voices)
        return OnboardingViewModel(installer, settings, aliasDao, voiceDao, deviceProbe, player)
            .apply {
                phoneLocale = { phone }
                this.asksNotifications = asksNotifications
            }
    }

    private fun alias(name: String) = VoiceAlias(
        name = name,
        id = "id-$name",
        engine = KITTEN,
        voiceId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
        speed = 1.0f,
        effectPreset = EffectPreset.NONE.name,
        createdAt = 0L,
    )

    private companion object {
        /** Renders Pocket and Kokoro comfortably (twice the Pixel 6a reference). */
        val FAST_PHONE = DeviceProbe(measuredKittenRtf = 0.13, computeScore = 20.0)

        const val KITTEN = KittenDirectVoiceCatalog.ENGINE
        const val KOKORO = KokoroDirectVoiceCatalog.ENGINE
        const val KOKORO_DE = KokoroGermanVoiceCatalog.ENGINE
        const val POCKET = PocketVoiceCatalog.ENGINE
        const val VITS = VoicePackCatalog.VITS_MARMALADE_ENGINE

        /** The engine's default pack — released for v1.1. */
        val RELEASED_PACK: String = VoicePackCatalog.releasedForEngine(VITS).first().id

        /** A staged pack that ordinary users must not be offered. */
        val UNRELEASED_PACK: String = VoicePackCatalog.forEngine(VITS).first { !it.released }.id

        /**
         * The cards onboarding shows, in catalog order. The native German
         * Kokoro (`kokoro-de-v1_0`) sits with the Kokoro family. Pocket ships
         * on both flavors since issue #29. VITS Marmalade went user-visible
         * for v1.1 (Max, 2026-09-22), so it appears last on this list.
         */
        val CATALOG_CARDS: List<String> = listOf(KITTEN, KOKORO, KOKORO_DE, POCKET, VITS)

        /** The built-in Kitten on disk, nothing else: a fresh install after Skip. */
        val KITTEN_ON_DISK: EngineInstaller
            get() = DiskInstaller(engines = setOf(KITTEN), packs = emptySet())
    }
}

/** Canned [DeviceProbeSource] — the real one needs an ORT session + sysfs. */
private class FakeDeviceProbe(private val result: DeviceProbe) : DeviceProbeSource {
    override suspend fun probe(): DeviceProbe = result
}

/** A probe still running: the check never finishes inside the test. */
private class NeverAnswers : DeviceProbeSource {
    override suspend fun probe(): DeviceProbe = awaitCancellation()
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
    /** When set, every install waits for it and returns its result. */
    private val waitFor: CompletableDeferred<Result<Unit>>? = null,
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/onboarding-test-unused") },
    engineHandle = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used") },
) {
    val installCalls = mutableListOf<String>()
    val discardCalls = mutableListOf<String>()

    override suspend fun discardDownload(engineName: String): Result<Unit> {
        discardCalls += engineName
        return Result.success(Unit)
    }

    // Nothing on disk in these tests — a fresh onboarding. Overridden (rather
    // than inheriting the base disk check) so it resolves synchronously on the
    // test dispatcher instead of hopping to Dispatchers.IO, which runInstall's
    // "skip if already installed" guard now calls before installing.
    //
    // With [waitFor] set it also holds there: 1.3.3's runInstall mirrors the
    // installer's own state flow once it gets past this check, and this fake
    // never publishes Downloading on it, so waiting here is what keeps the
    // row in flight the way a real download does.
    override suspend fun verify(engineName: String): InstallState {
        waitFor?.await()
        return InstallState.NotInstalled
    }

    override suspend fun install(
        engineName: String,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> {
        installCalls += engineName
        return waitFor?.await() ?: behaviour()
    }
}

/**
 * [EngineInstaller] reporting a fixed on-disk state: [engines] verify as
 * installed, and so do [packs] of a pack-based engine. Both overrides resolve
 * synchronously so the alias step's disk probe needs no real files.
 */
private class DiskInstaller(
    private val engines: Set<String>,
    private val packs: Set<String>,
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/onboarding-test-unused") },
    engineHandle = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used") },
) {
    override suspend fun verify(engineName: String): InstallState =
        if (engineName in engines) InstallState.Installed else InstallState.NotInstalled

    override suspend fun verifyPack(packId: String): InstallState =
        if (packId in packs) InstallState.Installed else InstallState.NotInstalled

    // A complete Pocket download when Pocket is on disk.
    override suspend fun pocketVoiceKeys(): Set<String> =
        if (PocketVoiceCatalog.ENGINE in engines) {
            PocketVoiceCatalog.voices.mapTo(HashSet()) { it.id.substringAfter(':') }
        } else {
            emptySet()
        }
}
