package app.marmalade.tts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import app.marmalade.tts.MainActivity
import app.marmalade.tts.R

/**
 * P-K — foreground service whose sole job is to keep the application
 * process alive so the Hilt-singleton engines (Pocket / Kokoro Direct /
 * Kitten Direct …) remain loaded between synth requests.
 *
 * No work happens here. The notification just announces presence; the
 * actual model loading is owned by the engines themselves and triggered
 * lazily when a synth request lands.
 *
 * Modes (passed via [EXTRA_PERSISTENT]):
 *   - non-persistent (Smart):  schedules self-stop after [SMART_TIMEOUT_MS].
 *                              Re-starting via [refresh] resets the timer.
 *   - persistent:              never schedules a self-stop. Only stopped
 *                              by [stop] (user-toggle off, OS request).
 *
 * Foreground-service type: `specialUse`. We're not playing media, not
 * tracking location, not making a phone call — we're holding a long-
 * lived ML model in memory so the next request is fast. `specialUse`
 * is the documented type for "your foreground service doesn't fit any
 * of the standard buckets", with a `propertyValues` justification.
 *
 * Android-version constraints:
 *   - Pre-Android 14 (API 34): no `specialUse` requirement; we just
 *     foreground with the legacy 2-arg [startForeground].
 *   - Android 14+: requires `specialUse` type both in manifest AND in
 *     the [startForeground] call.
 *
 * Lifecycle assumptions:
 *   - Start MUST come from a foreground app context or another
 *     foreground service. Android 12+ throws
 *     `ForegroundServiceStartNotAllowedException` if we attempt to
 *     start from a non-FG context (e.g. a BroadcastReceiver firing while
 *     the app is fully backgrounded). The smart-keepalive path is
 *     always initiated from an in-progress synth (UI or
 *     [MarmaladeSynthService]), both of which are foreground contexts.
 */
class MarmaladeKeepaliveService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var notificationManager: NotificationManager? = null
    private var persistent: Boolean = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newPersistent = intent?.getBooleanExtra(EXTRA_PERSISTENT, false) ?: false
        val now = System.currentTimeMillis()
        val notification = buildNotification(persistent = newPersistent, lastUsedAt = now)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        persistent = newPersistent
        rescheduleStopLocked()
        return START_NOT_STICKY
    }

    /**
     * Cancel any pending self-stop. Smart-mode start-or-refresh always
     * reschedules; persistent-mode never schedules.
     */
    private fun rescheduleStopLocked() {
        stopRunnable?.let(mainHandler::removeCallbacks)
        stopRunnable = null
        if (!persistent) {
            val r = Runnable {
                Log.d(TAG, "Smart-keepalive timeout — stopping")
                stopSelf()
            }
            stopRunnable = r
            mainHandler.postDelayed(r, SMART_TIMEOUT_MS)
        }
    }

    override fun onDestroy() {
        stopRunnable?.let(mainHandler::removeCallbacks)
        stopRunnable = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -- notification ---------------------------------------------------------

    private fun ensureChannel() {
        val mgr = notificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Marmalade TTS Keepalive",
                NotificationManager.IMPORTANCE_MIN, // silent + collapsed
            ).apply {
                description = "Shows while Marmalade keeps the TTS engine loaded for faster speak-onset."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(persistent: Boolean, lastUsedAt: Long): Notification {
        val title = if (persistent) "Marmalade TTS — ready" else "Marmalade TTS — recently used"
        val text = if (persistent) {
            "Engine kept loaded for instant speak-onset."
        } else {
            "Engine stays loaded for the next 10 minutes."
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.mascot_happy)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "MarmaladeKeepalive"
        private const val CHANNEL_ID = "marmalade_keepalive"
        // Distinct from MarmaladeSynthService.NOTIFICATION_ID (= 1) so
        // both can be foregrounded concurrently without one's
        // startForeground replacing the other's notification.
        private const val NOTIFICATION_ID = 1729
        private const val EXTRA_PERSISTENT = "app.marmalade.tts.extra.PERSISTENT"

        /**
         * Start (or refresh) the keepalive service in the given [mode].
         *
         *   - [KeepaliveMode.Off]:        calls [stop] and returns.
         *   - [KeepaliveMode.Smart]:      start/refresh in smart mode.
         *   - [KeepaliveMode.Persistent]: start in persistent mode.
         *
         * MUST be called from a foreground-allowed context — typically
         * from a UI thread tied to an active activity, or from inside
         * another foreground service. Calling from a background broadcast
         * receiver on Android 12+ throws and is caught + logged.
         */
        fun refresh(context: Context, mode: KeepaliveMode) {
            if (mode == KeepaliveMode.Off) {
                stop(context)
                return
            }
            val intent = Intent(context, MarmaladeKeepaliveService::class.java)
                .putExtra(EXTRA_PERSISTENT, mode == KeepaliveMode.Persistent)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Keepalive start refused — likely background-restricted: ${t.message}")
            }
        }

        /** Stop the keepalive service if it's running. Always safe. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MarmaladeKeepaliveService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "Keepalive stop failed: ${t.message}")
            }
        }
    }
}
