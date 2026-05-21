package app.marmalade.tts.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.marmalade.tts.service.SpeakClipboardTileService
import app.marmalade.tts.ui.intent.ShareIntentActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented tests for the share-sheet trampoline
 * ([ShareIntentActivity]) and the clipboard Quick Settings tile
 * ([SpeakClipboardTileService]).
 *
 * # How to run
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest --tests '*ShareAndTileInstrumentedTest*'
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
 * Three things make these tests "real":
 *
 *  1. The APK is installed (`./gradlew :app:installDebug`).
 *  2. The user has walked through onboarding once and tapped
 *     "Install Kitten", so `${filesDir}/engines/kitten/model.fp16.onnx`
 *     exists. The audible-output tests use [Assume.assumeTrue] on
 *     [engineInstalled] so they *skip* (not fail) when this is absent —
 *     CI without an installed bundle will simply have fewer green ticks.
 *  3. For tile interaction, the user has long-pressed the Quick Settings
 *     drawer and dragged the Marmalade tile into the active set. There
 *     is no programmatic way to add the tile to the user's chosen
 *     layout from instrumentation, so all tile-tap assertions are
 *     `Assume.assumeTrue(false, ...)`-gated and documented as manual
 *     checklist items below.
 *
 * # Manual verification steps (what these tests can't prove on their own)
 *
 * The programmatic assertions below catch manifest regressions, intent
 * extraction bugs, and dispatcher wiring problems. They cannot prove the
 * device actually emits sound or that the tile appears on the lock screen.
 * A human tester must verify:
 *
 *  - **Audible speech via share sheet:** While [shareSheetActivity_launchesAndDispatchesAndFinishes]
 *    is running, listen for "hello world" from the device speaker. No
 *    speech = the dispatcher reached MarmaladeSynthService but the
 *    synth/playback chain is broken.
 *  - **No service start on blank text:** Tail `adb logcat | grep
 *    MarmaladeSynthService` while [shareSheetActivity_blankTextShowsToastAndFinishes]
 *    runs. You should see *no* `onStartCommand` line — the dispatcher's
 *    blank-input guard should reject before any service intent is sent.
 *  - **Tile visible on lock screen:** Lock the device. Swipe down from
 *    the lock-screen status bar. The Marmalade tile must be present and
 *    tappable without unlocking. (Manifest declares `BIND_QUICK_SETTINGS_TILE`
 *    but Android also honours the per-tile `unlock-required` flag —
 *    re-verify after any manifest edit.)
 *  - **Tile speaks clipboard text:** With "hello world" copied to the
 *    clipboard, tap the tile. Audible playback must start. Then clear
 *    the clipboard, tap again — a "Clipboard is empty" Toast must
 *    appear and no playback must start.
 *
 * # Why JVM unit tests don't cover this
 *
 * `ShareIntentActivity` extends `ComponentActivity` and uses `@AndroidEntryPoint`
 * (Hilt) — both require a real Android lifecycle to bind. The dispatcher's
 * pure-validation half is already exercised in
 * `SpeakDispatcherTest` (JVM); what's left is the manifest plumbing
 * (intent filters, exported flags, tile permission) and the
 * Activity → Service handoff, neither of which Robolectric can simulate
 * faithfully.
 */
@RunWith(AndroidJUnit4::class)
class ShareAndTileInstrumentedTest {

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

    /**
     * The tile service must be exported, gated by
     * `BIND_QUICK_SETTINGS_TILE`, and respond to the QS_TILE action.
     * Without all three the tile silently disappears from the Quick
     * Settings drawer — another regression that wouldn't crash anything.
     */
    @Test
    fun tileService_isExportedAndDeclared() {
        val pm = context.packageManager
        val intent = Intent("android.service.quicksettings.action.QS_TILE").apply {
            setPackage(context.packageName)
        }
        val resolved = pm.queryIntentServices(intent, pmFlags())
        val match = resolved.firstOrNull {
            it.serviceInfo.name == SpeakClipboardTileService::class.java.name
        }
        assertNotNull(
            "SpeakClipboardTileService did not resolve for QS_TILE action. " +
                "Got: ${resolved.map { it.serviceInfo.name }}",
            match,
        )
        assertTrue(
            "SpeakClipboardTileService must be exported so SystemUI can bind to it",
            match!!.serviceInfo.exported,
        )
        assertEquals(
            "SpeakClipboardTileService must be gated by BIND_QUICK_SETTINGS_TILE",
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            match.serviceInfo.permission,
        )
    }

    /**
     * Tile-tap → audible speech. This test is **manual-only** for v0.1.
     *
     * Why not automated: `TileService.onClick` only fires when SystemUI
     * has bound the service via the platform tile-host machinery. We
     * cannot construct a `SpeakClipboardTileService` instance and call
     * `onClick()` directly — `qsTile` and `getSystemService` rely on the
     * service having gone through `attachBaseContext` / `onCreate` under
     * SystemUI's binding, neither of which instrumentation can fake.
     *
     * The clean way to automate this would be UiAutomator:
     *
     *  1. `UiDevice.openQuickSettings()` (swipe down from the top).
     *  2. `device.findObject(By.text("Speak clipboard"))` (label from
     *     `R.string.quick_tile_label`).
     *  3. `tile.click()`.
     *  4. Assert audible playback — which still requires a human ear,
     *     so we'd be back to a partly-manual test anyway.
     *
     * That setup is beyond v0.1 scope (we'd need
     * `androidx.test.uiautomator:uiautomator` on the androidTest
     * classpath; not currently declared). For now: assume-skip with a
     * documented procedure below.
     *
     * **Manual procedure:**
     *  1. Drag the Marmalade tile into the active Quick Settings layout.
     *  2. Copy "hello world" to the clipboard from any app.
     *  3. Pull down the QS drawer, tap the Marmalade tile.
     *  4. Listen — the device should speak "hello world".
     */
    @Test
    fun tileService_dispatchesWhenClipboardHasText() {
        Assume.assumeTrue(
            "Tile interaction requires UiAutomator setup + a human ear — " +
                "follow the manual procedure in this test's KDoc instead.",
            false,
        )

        // Unreachable, kept as documentation of the intended assertion
        // shape if/when UiAutomator gets wired in for v0.2.
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("test", "hello world"))
    }

    /**
     * Empty-clipboard tile-tap → "Clipboard is empty" Toast, no service
     * start. Same automation blockers as
     * [tileService_dispatchesWhenClipboardHasText]; same manual gate.
     *
     * **Manual procedure:**
     *  1. Drag the Marmalade tile into the active Quick Settings layout.
     *  2. Clear the clipboard (`adb shell service call clipboard 2` on
     *     a rooted device, or copy an empty string from a text field).
     *  3. Pull down the QS drawer, tap the Marmalade tile.
     *  4. A Toast must appear reading "Clipboard is empty".
     *  5. No audible speech must occur.
     *  6. Verify `adb logcat | grep MarmaladeSynthService` shows no
     *     `onStartCommand` from the tap.
     */
    @Test
    fun tileService_emptyClipboard_doesNotStartService() {
        Assume.assumeTrue(
            "Tile interaction requires UiAutomator setup — follow the " +
                "manual procedure in this test's KDoc instead.",
            false,
        )

        // Unreachable; documents the precondition the manual tester sets up.
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("test", ""))
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
