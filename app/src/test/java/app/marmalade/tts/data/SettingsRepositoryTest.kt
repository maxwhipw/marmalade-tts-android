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
    fun defaultValueFallsBackToBellaWhenUnset() = runTest {
        val repo = newRepo()
        // Nothing written yet → fallback to the catalog default.
        assertEquals(KittenVoiceCatalog.DEFAULT_VOICE_ID, repo.defaultVoiceId.first())
    }

    @Test
    fun setDefaultVoiceIdRoundTrips() = runTest {
        val repo = newRepo()
        repo.setDefaultVoiceId("kitten:Kiki")
        assertEquals("kitten:Kiki", repo.defaultVoiceId.first())
    }

    @Test
    fun overwriteReplacesPreviousValue() = runTest {
        val repo = newRepo()
        repo.setDefaultVoiceId("kitten:Bella")
        repo.setDefaultVoiceId("kitten:Leo")
        assertEquals("kitten:Leo", repo.defaultVoiceId.first())
    }

    private fun newRepo(): SettingsRepository {
        val ds = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { dataStoreFile },
        )
        return SettingsRepository(ds)
    }
}
