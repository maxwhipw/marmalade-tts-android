package app.marmalade.tts.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marmalade.tts.ui.intent.ShareIntentActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented tests for the share-sheet trampoline
 * ([ShareIntentActivity]).
 *
 * # How to run
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest --tests '*ShareInstrumentedTest*'
 * ```
 *
 * Requires a connected Android device (or emulator) with `adb` access. The
 * debug APK has `applicationIdSuffix = ".debug"` so the runtime package is
 * `app.marmalade.tts.debug`; we always query the package manager with
 * `context.packageName` so the tests work regardless of which variant is
 * installed.
 *
 * # Prerequisites — engine bundle on device
 *
 * Two things make these tests "real":
 *
 *  1. The APK is installed (`./gradlew :app:installDebug`).
 *  2. The user has walked through onboarding once and tapped
 *     "Install Kitten", so `${filesDir}/engines/kitten/model.fp16.onnx`
 *     exists. The audible-output tests use [Assume.assumeTrue] on
 *     [engineInstalled] so they *skip* (not fail) when this is absent —
 *     CI without an installed bundle will simply have fewer green ticks.
 *
 * # Manual verification steps (what these tests can't prove on their own)
 *
 * The programmatic assertions below catch manifest regressions, intent
 * extraction bugs, and dispatcher wiring problems. They cannot prove the
 * device actually emits sound. A human tester must verify:
 *
 *  - **Audible speech via share sheet:** While [shareSheetActivity_launchesAndDispatchesAndFinishes]
 *    is running, listen for "hello world" from the device speaker. No
 *    speech = the dispatcher reached MarmaladeSynthService but the
 *    synth/playback chain is broken.
 *  - **No service start on blank text:** Tail `adb logcat | grep
 *    MarmaladeSynthService` while [shareSheetActivity_blankTextShowsToastAndFinishes]
 *    runs. You should see *no* `onStartCommand` line — the dispatcher's
 *    blank-input guard should reject before any service intent is sent.
 *
 * # Why JVM unit tests don't cover this
 *
 * `ShareIntentActivity` extends `ComponentActivity` and uses `@AndroidEntryPoint`
 * (Hilt) — both require a real Android lifecycle to bind. The dispatcher's
 * pure-validation half is already exercised in
 * `SpeakDispatcherTest` (JVM); what's left is the manifest plumbing
 * (intent filters, exported flags) and the Activity → Service handoff,
 * neither of which Robolectric can simulate faithfully.
 */
@RunWith(AndroidJUnit4::class)
class ShareInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The share-sheet intent-filter for `ACTION_SEND` + `text/plain` must
     * resolve to [ShareIntentActivity]. Catches the regression where the
     * `<intent-filter>` block gets dropped from the manifest — a silent
     * failure mode otherwise, because nothing would crash; Marmalade would
     * simply disappear from the share sheet.
     */
    @Test
    fun shareSheetActivity_isExportedAndResolves() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(context.packageName)
        }
        val resolved = pm.queryIntentActivities(intent, pmFlags())
        val match = resolved.firstOrNull {
            it.activityInfo.name == ShareIntentActivity::class.java.name
        }
        assertNotNull(
            "ShareIntentActivity did not resolve for ACTION_SEND + text/plain. " +
                "Got: ${resolved.map { it.activityInfo.name }}",
            match,
        )
        assertTrue(
            "ShareIntentActivity must be exported so the system share sheet can launch it",
            match!!.activityInfo.exported,
        )
    }

    /**
     * Same idea as the SEND case but for `ACTION_PROCESS_TEXT` — the
     * text-selection floating menu entry point. Without this filter
     * Marmalade vanishes from the "Process text" submenu on long-press.
     */
    @Test
    fun shareSheetActivity_acceptsProcessText() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            setPackage(context.packageName)
        }
        val resolved = pm.queryIntentActivities(intent, pmFlags())
        val match = resolved.firstOrNull {
            it.activityInfo.name == ShareIntentActivity::class.java.name
        }
        assertNotNull(
            "ShareIntentActivity did not resolve for ACTION_PROCESS_TEXT. " +
                "Got: ${resolved.map { it.activityInfo.name }}",
            match,
        )
    }

    /**
     * End-to-end: launch the trampoline programmatically with a real
     * `EXTRA_TEXT` payload and confirm it self-finishes within 5 seconds.
     * The transparent activity is meant to dispatch and immediately die;
     * if it sticks around in the task stack it's a regression.
     *
     * **Manual verification (audible half):** While this test runs, the
     * device should also speak "hello world" aloud — that confirms the
     * dispatcher reached MarmaladeSynthService and the synth/playback
     * chain is wired up. Listen. If silent, the assertion below still
     * passes (the activity dispatched and finished), but the audible
     * half of the contract is broken — file a bug.
     */
    @Test
    fun shareSheetActivity_launchesAndDispatchesAndFinishes() {
        Assume.assumeTrue(
            "Kitten engine not installed — audible half won't speak. " +
                "Install via onboarding then re-run.",
            engineInstalled(),
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "hello world")
            setClass(context, ShareIntentActivity::class.java)
        }

        val destroyed = CountDownLatch(1)
        ActivityScenario.launch<ShareIntentActivity>(intent).use { scenario ->
            // Poll the lifecycle state until the trampoline finishes
            // itself. The activity's onCreate dispatches and calls
            // finish() unconditionally, so DESTROYED should be reached
            // well under a second on any real device — the 5 s timeout
            // is slack for cold-start and emulator overhead.
            val pollStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - pollStart < 5_000) {
                val state = scenario.state
                if (state == Lifecycle.State.DESTROYED) {
                    destroyed.countDown()
                    break
                }
                Thread.sleep(50)
            }
        }
        assertTrue(
            "ShareIntentActivity did not finish itself within 5 s — " +
                "the trampoline is supposed to dispatch and immediately destroy.",
            destroyed.await(0, TimeUnit.MILLISECONDS) || destroyed.count == 0L,
        )
    }

    /**
     * Whitespace-only input should be rejected by the dispatcher's blank
     * guard (`SpeakDispatcher.prepare` returns null on trim → empty).
     * The trampoline should still launch and finish cleanly — no crash,
     * no hang.
     *
     * **Manual verification (no-service-start half):** Tail
     * `adb logcat | grep MarmaladeSynthService` while this runs. You
     * should see *zero* `onStartCommand` log lines from this test. If
     * one appears, the blank guard is leaking and we're starting a
     * foreground service for no reason — file a bug.
     */
    @Test
    fun shareSheetActivity_blankTextShowsToastAndFinishes() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
            setClass(context, ShareIntentActivity::class.java)
        }

        ActivityScenario.launch<ShareIntentActivity>(intent).use { scenario ->
            val pollStart = System.currentTimeMillis()
            var reachedDestroyed = false
            while (System.currentTimeMillis() - pollStart < 5_000) {
                if (scenario.state == Lifecycle.State.DESTROYED) {
                    reachedDestroyed = true
                    break
                }
                Thread.sleep(50)
            }
            assertTrue(
                "ShareIntentActivity did not finish itself within 5 s on blank input — " +
                    "the trampoline should reject and self-destroy.",
                reachedDestroyed,
            )
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * True if the Kitten engine bundle has been installed (the user has
     * walked through onboarding once). Tests that need audible output
     * gate themselves on this with [Assume.assumeTrue].
     */
    private fun engineInstalled(): Boolean {
        return File(context.filesDir, "engines/kitten/model.fp16.onnx").exists()
    }

    /**
     * Appropriate `PackageManager.MATCH_*` flags for the running SDK.
     * `MATCH_ALL` (API 23+) returns components even when their export
     * defaults would otherwise hide them from the standard resolver —
     * we want the full set so we can assert exported-ness ourselves.
     *
     * minSdk is 28, so `MATCH_ALL` is always available; the version
     * check is belt-and-braces.
     */
    private fun pmFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }
    }
}
