package app.marmalade.tts.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorLinkTest {
    @Test fun `play hides the sponsors link`() = assertFalse(showsSponsorLink("play"))

    @Test fun `fdroid keeps the sponsors link`() = assertTrue(showsSponsorLink("fdroid"))
}
