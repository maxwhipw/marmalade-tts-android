package app.marmalade.tts.data.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.ui.screen.FakeDao
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CloudProviderStore]'s key pin end to end: a provider that exists only in
 * the downloaded list is held to the site its saved key was saved for, and
 * keys saved before that URL was recorded get it on first load (trust on
 * first use). The rule itself is pinned in CloudProvidersTest.
 *
 * Robolectric for the bundled asset + filesDir (SDK 34, as
 * CloudApiViewModelTest); a real file-backed DataStore for the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudProviderStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val offline = CloudJsonHttp { _, _ -> throw IOException("offline test") }
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        File(context.filesDir, "cloud").deleteRecursively()
        scope = CoroutineScope(Job() + Dispatchers.Default)
        val file = File(tempFolder.newFolder(), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        settings = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A downloaded provider list: Venice as bundled plus remote-only "newco" at [newcoUrl]. */
    private fun cacheRemoteList(newcoUrl: String) {
        val newco = """
            {"id": "newco", "displayName": "NewCo", "baseUrl": "$newcoUrl", "keyHint": "",
             "models": [{"id": "m", "sampleRate": 24000, "voices": ["v"]}]}
        """.trimIndent()
        val venice = """
            {"id": "venice", "displayName": "Venice", "baseUrl": "https://api.venice.ai/api/v1",
             "keyHint": "", "models": []}
        """.trimIndent()
        File(context.filesDir, "cloud").apply { mkdirs() }
            .resolve("providers.json")
            .writeText("""{"version": 99, "providers": [$venice, $newco]}""")
    }

    /** A fresh store, so nothing is served from a previous load's cache. */
    private fun newco(): CloudProvider =
        CloudProviderStore(context, offline, FakeDao(emptyList()), settings).providerById("newco")!!

    @Test
    fun `a keyed remote-only provider moved off-site is refused and flagged`() {
        cacheRemoteList("https://tts.newco.example/v1")
        runBlocking { settings.setCloudApiKey("newco", "sk-new", newco().baseUrl) }

        cacheRemoteList("https://evil.example/v1")
        val moved = newco()

        assertEquals("https://tts.newco.example/v1", moved.baseUrl)
        assertTrue(moved.movedOffSite)
    }

    @Test
    fun `a keyed remote-only provider may move within its site`() {
        cacheRemoteList("https://tts.newco.example/v1")
        runBlocking { settings.setCloudApiKey("newco", "sk-new", newco().baseUrl) }

        cacheRemoteList("https://api.newco.example/v2")
        val moved = newco()

        assertEquals("https://api.newco.example/v2", moved.baseUrl)
        assertFalse(moved.movedOffSite)
    }

    @Test
    fun `without a saved key the remote list is taken as-is`() {
        cacheRemoteList("https://tts.newco.example/v1")
        newco()
        cacheRemoteList("https://elsewhere.example/v1")

        val p = newco()

        assertEquals("https://elsewhere.example/v1", p.baseUrl)
        assertFalse(p.movedOffSite)
        assertEquals(emptyMap<String, String>(), runBlocking { settings.cloudApiKeyBaseUrls.first() })
    }

    @Test
    fun `a key saved before URLs were recorded is pinned to its provider's URL on first load`() {
        cacheRemoteList("https://tts.newco.example/v1")
        // A 1.1.0-era key: the key pref alone, no URL.
        runBlocking { dataStore.edit { it[stringPreferencesKey("cloud_api_key_newco")] = "sk-old" } }

        assertEquals("https://tts.newco.example/v1", newco().baseUrl)
        assertEquals(
            mapOf("newco" to "https://tts.newco.example/v1"),
            runBlocking { settings.cloudApiKeyBaseUrls.first() },
        )

        // From then on it is held there like any keyed provider.
        cacheRemoteList("https://evil.example/v1")
        val moved = newco()
        assertEquals("https://tts.newco.example/v1", moved.baseUrl)
        assertTrue(moved.movedOffSite)
    }
}
