package app.marmalade.tts.service

import android.content.Intent
import android.media.AudioManager
import app.marmalade.tts.service.MarmaladeSynthService.FocusAction
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.PocketDevVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Engine narrowing, intent parsing, and the pure decisions pulled out of the
 * long-form foreground service (RTF sampling, audio-focus transitions).
 *
 * That is what a JVM test can reach here: everything past them needs audio
 * focus, a notification channel, Hilt injection and real ONNX sessions.
 * Narrowing is also where the bug was — the cloud and dev-Pocket engines were
 * absent from the dispatch list, so aliases pointing at them were synthesized
 * with Kokoro and no error was raised anywhere.
 *
 * Robolectric only so the bare Service can be constructed; no injected
 * field is touched, and [knownEngineOrDefault] is pure string logic.
 */
@RunWith(RobolectricTestRunner::class)
class MarmaladeSynthServiceTest {

    private val service = MarmaladeSynthService()

    @Test
    fun `every engine the app can alias survives narrowing`() {
        val engines = listOf(
            KokoroDirectVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            PocketVoiceCatalog.ENGINE,
            PocketDevVoiceCatalog.ENGINE,
            VitsVoiceCatalog.ENGINE,
            CloudApiVoiceCatalog.ENGINE,
        )
        for (engine in engines) {
            assertEquals(engine, service.knownEngineOrDefault(engine))
        }
    }

    // -- Session speed override -----------------------------------------------

    /**
     * The reader's per-article speed rides its own extra so it survives alias
     * routing and *replaces* the speed the primary alias resolves to
     * (EXTRA_SPEED can't: on the alias route the alias's speed replaces it).
     */
    @Test
    fun `the session speed is carried when the caller sends it`() {
        val request = service.parseRequest(speakIntent(0.75f))

        assertEquals(0.75f, request!!.sessionSpeed!!, 0f)
    }

    /**
     * Every non-reader caller — share sheet, Tasker, the Speak screen — omits
     * the extra, and must be spoken exactly as before.
     */
    @Test
    fun `a request without the extra carries no session speed`() {
        val request = service.parseRequest(speakIntent(sessionSpeed = null))

        assertNull(request!!.sessionSpeed)
    }

    /** A zero, negative or NaN speed would silence the engine; ignore it, don't fail. */
    @Test
    fun `a nonsense session speed is ignored`() {
        assertNull(service.parseRequest(speakIntent(0f))!!.sessionSpeed)
        assertNull(service.parseRequest(speakIntent(-2f))!!.sessionSpeed)
        assertNull(service.parseRequest(speakIntent(Float.NaN))!!.sessionSpeed)
    }

    /**
     * The bug Max hit: a 2x alias read at the reader's 1x chip came out at 2x,
     * because the chip multiplied the alias's speed. It must replace it.
     */
    @Test
    fun `the session speed replaces the alias-routed speed rather than scaling it`() {
        val routed = routedRequest(aliasSpeed = 2.0f, sessionSpeed = 1.0f)

        assertEquals(1.0f, service.effectiveSpeed(routed), 0f)
    }

    @Test
    fun `without a session speed the routed speed is unchanged`() {
        val routed = routedRequest(aliasSpeed = 2.0f, sessionSpeed = null)

        assertEquals(2.0f, service.effectiveSpeed(routed), 0f)
    }

    /**
     * The reader names another alias when the primary's voice doesn't speak
     * the article's language; it must still go through alias routing (no
     * explicit voice), just to that alias.
     */
    @Test
    fun `a named alias is carried and keeps the request on the alias route`() {
        val request = service.parseRequest(
            speakIntent(sessionSpeed = 1.0f).putExtra(MarmaladeSynthService.EXTRA_ALIAS_ID, "id-zh"),
        )!!

        assertEquals("id-zh", request.aliasId)
        assertEquals(false, request.voiceExplicit)
        assertNull(service.parseRequest(speakIntent(sessionSpeed = null))!!.aliasId)
    }

    private fun speakIntent(sessionSpeed: Float?) =
        Intent(MarmaladeSynthService.ACTION_SPEAK).apply {
            putExtra(MarmaladeSynthService.EXTRA_TEXT, "Hello.")
            sessionSpeed?.let {
                putExtra(MarmaladeSynthService.EXTRA_SESSION_SPEED, it)
            }
        }

    /** A request as it stands after alias routing put the alias's speed on it. */
    private fun routedRequest(aliasSpeed: Float, sessionSpeed: Float?) =
        service.parseRequest(speakIntent(sessionSpeed))!!.copy(speed = aliasSpeed)

    // -- A new speak vs paused work ---------------------------------------------

    /**
     * The approved P14 behaviour: a new speak while a read is paused drops
     * that read and plays the new text now, instead of queueing silently
     * behind a read the user may never resume.
     */
    @Test
    fun `a new speak replaces paused work`() {
        assertTrue(replaces(paused = true))
    }

    @Test
    fun `a new speak queues behind playing work`() {
        assertFalse(replaces(paused = false))
    }

    /** Nothing active: the request starts at once either way. */
    @Test
    fun `an idle service has nothing to replace`() {
        assertFalse(replaces(paused = true, hasActive = false))
    }

    /**
     * Paused work already being stopped — by a stop, or by the request that
     * replaced it — is gone; a second new speak right behind the first must
     * queue behind it, not knock it out too.
     */
    @Test
    fun `a speak behind a replacement queues`() {
        assertFalse(replaces(paused = true, stopping = true))
    }

    /** The reader's next block must not throw out the article it continues. */
    @Test
    fun `a continuation never replaces paused work`() {
        assertFalse(replaces(paused = true, continuation = true))
    }

    @Test
    fun `the continuation flag is carried and defaults off`() {
        assertFalse(service.parseRequest(speakIntent(sessionSpeed = null))!!.continuation)
        val continued = speakIntent(sessionSpeed = null)
            .putExtra(MarmaladeSynthService.EXTRA_CONTINUATION, true)
        assertTrue(service.parseRequest(continued)!!.continuation)
    }

    private fun replaces(
        paused: Boolean,
        hasActive: Boolean = true,
        stopping: Boolean = false,
        continuation: Boolean = false,
    ) = MarmaladeSynthService.replacesPausedWork(paused, hasActive, stopping, continuation)

    // -- Rolling engine RTF -----------------------------------------------------

    /** One second of 24 kHz audio rendered in half a second: RTF 0.5. */
    @Test
    fun `a clean warm utterance yields its RTF`() {
        val rtf = MarmaladeSynthService.engineRtfSample(
            warm = true, skewed = false,
            renderNanos = 500_000_000L, audioSamples = 24_000L, sampleRate = 24_000,
        )

        assertEquals(0.5, rtf!!, 1e-9)
    }

    /**
     * The engines keep rendering into their own flow buffer while the
     * collector is backpressured or paused, so the gaps after it under-measure
     * render time. Sampling those used to drive the rolling RTF so low that
     * the speed-up warning stopped firing.
     */
    @Test
    fun `a backpressured or paused utterance is not sampled`() {
        assertNull(
            MarmaladeSynthService.engineRtfSample(
                warm = true, skewed = true,
                renderNanos = 10_000_000L, audioSamples = 240_000L, sampleRate = 24_000,
            ),
        )
    }

    @Test
    fun `cold or too-short utterances are not sampled`() {
        assertNull(
            MarmaladeSynthService.engineRtfSample(
                warm = false, skewed = false,
                renderNanos = 500_000_000L, audioSamples = 24_000L, sampleRate = 24_000,
            ),
        )
        // 0.1 s of audio — below the 0.2 s floor.
        assertNull(
            MarmaladeSynthService.engineRtfSample(
                warm = true, skewed = false,
                renderNanos = 50_000_000L, audioSamples = 2_400L, sampleRate = 24_000,
            ),
        )
    }

    // -- Audio focus ------------------------------------------------------------

    @Test
    fun `a transient loss pauses playing audio`() {
        for (loss in listOf(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        )) {
            assertEquals(
                FocusAction.PAUSE,
                MarmaladeSynthService.focusAction(loss, paused = false, pausedByFocus = false),
            )
        }
    }

    @Test
    fun `regaining focus resumes a pause the focus loss caused`() {
        assertEquals(
            FocusAction.RESUME,
            MarmaladeSynthService.focusAction(
                AudioManager.AUDIOFOCUS_GAIN, paused = true, pausedByFocus = true,
            ),
        )
    }

    /** The bug: any GAIN resumed any pause, undoing the user's own. */
    @Test
    fun `regaining focus leaves a user pause alone`() {
        assertEquals(
            FocusAction.NONE,
            MarmaladeSynthService.focusAction(
                AudioManager.AUDIOFOCUS_GAIN, paused = true, pausedByFocus = false,
            ),
        )
    }

    /** A transient loss on top of a user pause must not adopt it as its own. */
    @Test
    fun `a transient loss while user-paused changes nothing`() {
        assertEquals(
            FocusAction.NONE,
            MarmaladeSynthService.focusAction(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, paused = true, pausedByFocus = false,
            ),
        )
    }

    @Test
    fun `a permanent loss stops`() {
        assertEquals(
            FocusAction.STOP,
            MarmaladeSynthService.focusAction(
                AudioManager.AUDIOFOCUS_LOSS, paused = false, pausedByFocus = false,
            ),
        )
    }

    @Test
    fun `unknown engine falls back to the default`() {
        assertEquals(
            MarmaladeSynthService.DEFAULT_ENGINE,
            service.knownEngineOrDefault("piper-en-us-v1"),
        )
        assertEquals(MarmaladeSynthService.DEFAULT_ENGINE, service.knownEngineOrDefault(""))
    }
}
