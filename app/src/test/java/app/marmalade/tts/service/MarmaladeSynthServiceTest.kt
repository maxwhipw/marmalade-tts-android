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

    // -- Session speed multiplier ---------------------------------------------

    /**
     * The reader's per-article speed rides its own extra so it can *scale* the
     * speed the primary alias resolves to instead of replacing it (EXTRA_SPEED
     * is an override, and on the alias route the alias wins over it anyway).
     * These cover the parse; the multiply itself is one line in `runOne`, past
     * where a JVM test can reach.
     */
    @Test
    fun `the speed multiplier is carried when the caller sends it`() {
        val request = service.parseRequest(speakIntent(0.75f))

        assertEquals(0.75f, request!!.speedMultiplier, 0f)
    }

    /**
     * Every non-reader caller — share sheet, tile, Tasker, the Speak screen —
     * omits the extra, and must be spoken exactly as before: 1.0 is the
     * identity for the multiply in `runOne`.
     */
    @Test
    fun `a request without the extra is unaffected`() {
        val request = service.parseRequest(speakIntent(multiplier = null))

        assertEquals(1.0f, request!!.speedMultiplier, 0f)
    }

    /** A zero or negative factor would silence the engine; degrade, don't fail. */
    @Test
    fun `a nonsense multiplier degrades to no change`() {
        assertEquals(1.0f, service.parseRequest(speakIntent(0f))!!.speedMultiplier, 0f)
        assertEquals(1.0f, service.parseRequest(speakIntent(-2f))!!.speedMultiplier, 0f)
    }

    private fun speakIntent(multiplier: Float?) =
        Intent(MarmaladeSynthService.ACTION_SPEAK).apply {
            putExtra(MarmaladeSynthService.EXTRA_TEXT, "Hello.")
            multiplier?.let {
                putExtra(MarmaladeSynthService.EXTRA_SPEED_MULTIPLIER, it)
            }
        }

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
        assertFalse(service.parseRequest(speakIntent(multiplier = null))!!.continuation)
        val continued = speakIntent(multiplier = null)
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
