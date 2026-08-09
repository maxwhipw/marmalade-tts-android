package app.marmalade.tts.ui.screen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.cloud.CloudJsonHttp
import app.marmalade.tts.data.cloud.CloudProviderStore
import app.marmalade.tts.util.MainDispatcherRule
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the cloud-consent contract: the provider-list refresh (the only
 * network request the Cloud tab makes on its own) must not fire until the
 * cloud disclaimer has been accepted.
 *
 * Robolectric because the store parses the bundled `cloud-providers.json`
 * asset with org.json (SDK 34 — see VoiceMetaDaoTest for why that level).
 * The ViewModel's init coroutine runs on real Dispatchers.IO, so the
 * positive cases synchronize on a latch inside the HTTP fake and the
 * negative case bounds its wait instead of asserting instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudApiViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * Records every GET and throws [IOException], which makes
     * `refreshProviders()` bail out before `sync()` — so the read-only
     * [FakeDao] is never written to.
     */
    private class RecordingHttp : CloudJsonHttp {
        val urls = CopyOnWriteArrayList<String>()
        val firstCall = CountDownLatch(1)
        override fun get(url: String, apiKey: String?): String {
            urls += url
            firstCall.countDown()
            throw IOException("offline test")
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val http = RecordingHttp()
    private val settings = FakeSettings("cloud-api-v1:venice:tts-kokoro:af_heart")

    private fun viewModel(): CloudApiViewModel {
        val store = CloudProviderStore(context, http, FakeDao(emptyList()), settings)
        return CloudApiViewModel(store, settings, FakeDao(emptyList()), context)
    }

    @Test
    fun `no network before the disclaimer is accepted`() {
        viewModel()
        // Negative assertion against a real IO coroutine: give it ample
        // time to (wrongly) fire before declaring the gate holds.
        assertTrue(!http.firstCall.await(500, TimeUnit.MILLISECONDS))
        assertEquals(emptyList<String>(), http.urls)
    }

    @Test
    fun `accepting the disclaimer triggers the provider refresh`() {
        val vm = viewModel()
        vm.acceptDisclaimer()
        assertTrue(http.firstCall.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(CloudProviderStore.REMOTE_PROVIDERS_URL), http.urls)
    }

    @Test
    fun `refresh fires on open when a past session already accepted`() {
        runBlocking { settings.acceptCloudDisclaimer() }
        viewModel()
        assertTrue(http.firstCall.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(CloudProviderStore.REMOTE_PROVIDERS_URL), http.urls)
    }
}
