package app.marmalade.tts.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-trips the default voice ID through a real (file-backed)
 * preference DataStore.
 *
 * Why a real DataStore instead of a mock: the repository is a
 * one-line wrapper, and mocking DataStore proves nothing. The
 * useful thing to verify is "set X, observe X comes back" — which
 * is just an integration round-trip. `TemporaryFolder` keeps the
 * file scoped to the test method.
 */
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        scope = CoroutineScope(Job() + Dispatchers.Default)
        dataStoreFile = File(tempFolder.newFolder(), "test_settings.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultValueFallsBackToKittenDirectWhenUnset() = runTest {
        val repo = newRepo()
        // Nothing written yet → fallback to the baked default (Kitten Direct),
        // the one engine guaranteed present on a fresh/offline install.
        assertEquals(KittenDirectVoiceCatalog.DEFAULT_VOICE_ID, repo.defaultVoiceId.first())
    }

    @Test
    fun setDefaultVoiceIdRoundTrips() = runTest {
        val repo = newRepo()
        repo.setDefaultVoiceId("kitten-direct-v0_8:Kiki")
        assertEquals("kitten-direct-v0_8:Kiki", repo.defaultVoiceId.first())
    }

    @Test
    fun overwriteReplacesPreviousValue() = runTest {
        val repo = newRepo()
        repo.setDefaultVoiceId("kitten-direct-v0_8:Bella")
        repo.setDefaultVoiceId("kitten-direct-v0_8:Leo")
        assertEquals("kitten-direct-v0_8:Leo", repo.defaultVoiceId.first())
    }

    // -- primaryAliasId -----------------------------------------------------

    @Test
    fun primaryAliasId_defaultsToNullWhenUnset() = runTest {
        val repo = newRepo()
        assertNull(
            "Fresh install should have a null primary alias",
            repo.primaryAliasId.first(),
        )
    }

    @Test
    fun setPrimaryAliasId_roundTripsThroughDataStore() = runTest {
        val repo = newRepo()
        repo.setPrimaryAliasId("narrator")
        assertEquals("narrator", repo.primaryAliasId.first())
    }

    @Test
    fun setPrimaryAliasId_nullClearsThePointer() = runTest {
        val repo = newRepo()
        repo.setPrimaryAliasId("narrator")
        assertEquals("narrator", repo.primaryAliasId.first())

        // Null write must remove the key, not store an empty string —
        // otherwise consumers can't distinguish "user cleared" from
        // "never set".
        repo.setPrimaryAliasId(null)
        assertNull(
            "Writing null should remove the key entirely",
            repo.primaryAliasId.first(),
        )
    }

    private fun newRepo(): SettingsRepository {
        val ds = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { dataStoreFile },
        )
        return SettingsRepository(ds)
    }

    // -- latency samples + weekly quota ---------------------------------------

    @Test
    fun latencySamples_rollOffOldestBeyondTheWindow() = runTest {
        val repo = newRepo()
        for (ms in 1..5) repo.recordLatencySample("venice:m", millis = ms * 100, keep = 3)
        assertEquals(listOf(300, 400, 500), repo.latencySamples.first().getValue("venice:m"))
    }

    @Test
    fun latencySamples_ignoreTheQuotaKeys() = runTest {
        // The quota prefix deliberately doesn't nest under the sample
        // prefix; if it ever did, this would surface it as a phantom model.
        val repo = newRepo()
        repo.claimLatencyQuota("venice:m", week = 1L, perWeek = 3)
        assertEquals(emptyMap<String, List<Int>>(), repo.latencySamples.first())
    }

    @Test
    fun cloudDisclaimer_defaultsToUnacceptedAndIsOneWay() = runTest {
        val repo = newRepo()
        // The gate fails open if this ever defaults true — a fresh install
        // would reach the key field having been told nothing.
        assertFalse(repo.cloudDisclaimerAccepted.first())

        repo.acceptCloudDisclaimer()
        assertTrue(repo.cloudDisclaimerAccepted.first())

        // Removing every key must not re-arm the gate. Acceptance records
        // "was told", not "is using"; re-prompting a user who already read
        // it is how people learn to dismiss disclaimers unread.
        repo.setCloudApiKey("venice", "sk-test")
        repo.setCloudApiKey("venice", "")
        assertTrue(repo.cloudDisclaimerAccepted.first())
    }

    @Test
    fun latencyQuota_grantsExactlyPerWeekThenRefusesUntilTheWeekTurns() = runTest {
        val repo = newRepo()
        assertEquals(
            listOf(true, true, true, false, false),
            (1..5).map { repo.claimLatencyQuota("venice:m", week = 900L, perWeek = 3) },
        )
        // A new week starts a fresh budget...
        assertTrue(repo.claimLatencyQuota("venice:m", week = 901L, perWeek = 3))
        // ...and each model has its own.
        assertTrue(repo.claimLatencyQuota("venice:other", week = 901L, perWeek = 3))
    }
}
