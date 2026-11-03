package app.marmalade.tts.ui.screen

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// -----------------------------------------------------------------------------
// #118 UI-10: a cloud provider's voice count read "1 voices". Robolectric for
// the merged resources.
// -----------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
class CloudVoiceSummaryTest {

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    @Test
    fun `the cloud voice count agrees in number`() {
        val summary = R.plurals.engines_cloud_voice_summary
        assertEquals("1 voice · M", resources.getQuantityString(summary, 1, 1, "M"))
        assertEquals("2 voices · M", resources.getQuantityString(summary, 2, 2, "M"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the cloud voice count in French`() {
        val summary = R.plurals.engines_cloud_voice_summary
        assertEquals("1 voix · M", resources.getQuantityString(summary, 1, 1, "M"))
    }
}
