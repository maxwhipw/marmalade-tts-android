package app.marmalade.tts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service for long-form synthesis playback.
 *
 * Skeleton only — future home of:
 * - AudioTrack / Oboe streaming output
 * - Media session (skip/pause/seek)
 * - Audio-focus management
 * - Bluetooth A2DP awareness
 *
 * Returns START_NOT_STICKY so the system does not restart the service if
 * it is killed while no synthesis is in progress.
 */
class MarmaladeSynthService : Service() {

    companion object {
        private const val CHANNEL_ID = "marmalade_synth"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
        // No synthesis logic yet — v0.1 feature work adds this.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Marmalade TTS Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while Marmalade TTS is reading aloud"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildPlaceholderNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Marmalade TTS")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
