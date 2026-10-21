package app.marmalade.tts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import app.marmalade.tts.MainActivity
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.audio.EffectChain
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.playback.ErrorNotice
import app.marmalade.tts.playback.FailureKind
import app.marmalade.tts.playback.Narrator
import app.marmalade.tts.playback.NarratorConfig
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.OneShotRequest
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.Origin
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Public intent contract
// -----------------------------------------------------------------------------
//
//   Action: "app.marmalade.tts.action.SPEAK"
//
//   Extras (sent on the intent):
//     EXTRA_TEXT   (String, required)   — the text to read aloud.
//     EXTRA_ENGINE (String, optional)   — engine name. When absent, the
//                                          engine is derived from EXTRA_VOICE's
//                                          prefix.
//     EXTRA_VOICE  (String, optional)   — voice id e.g.
//                                          "kokoro-direct-v1_0:af_bella" or
//                                          "kitten-direct-v0_8:Bella". Absent:
//                                          the primary alias speaks (with no
//                                          alias, KittenDirectVoiceCatalog.
//                                          DEFAULT_VOICE_ID and these extras).
//     EXTRA_SPEED  (Float, optional)    — length-scale style; 1.0 = native,
//                                          > 1 = faster, 0.25–4.0. Default 1.0.
//     EXTRA_EFFECT (String, optional)   — EffectPreset name (NONE / CAVE /
//                                          TELEPHONE). Default NONE.
//
//   Transport actions (media button / lock-screen):
//     ACTION_PAUSE / ACTION_RESUME / ACTION_STOP — wired via MediaSessionCompat.
//
// -----------------------------------------------------------------------------
// What this service is (reader-session redesign, step C1)
// -----------------------------------------------------------------------------
//
//   A SHELL. All in-app speech — the reader's article, the Speak screen and
//   previews, shares, Tasker — is owned by the process's one Narrator
//   (playback/Narrator.kt): queue, voice, speed, cursor, audio focus,
//   residency, keepalive, latency stats and the writer (AudioTrackOutput).
//   This service only:
//
//     - turns intents into Narrator commands. ACTION_SPEAK is a one-shot
//       through SpeakShim (one request id → one outcome → one
//       PreviewCompletions post; ACTION_STOP_REQUEST cancels that one). The
//       reader sends no speech intents: it drives the Narrator directly and
//       only sends ACTION_HOST (ServicePlaybackHost) to bring this service
//       up as the foreground host before a command that plays. A shared link
//       sends ACTION_HOST at once (ArticleLoadHold), while the share is still
//       in front: the service stays up showing "Loading article…" until the
//       reader plays it or gives up, so an article that loads after the user
//       switched apps still starts reading (Android refuses a foreground
//       service started from the background);
//     - is the Android 16 host: audio plays only while this service is
//       foreground with a media session (ServiceHost tells the Narrator);
//     - projects the Narrator's state onto the notification and the media
//       session (ServiceUi.render, one pure table), posts error
//       notifications for UI-less one-shots, and leaves the foreground (a
//       swipe-away-able notification) after a permanent audio-focus loss;
//     - stops itself once nothing is on air.
//
//   Only ACTION_SPEAK and ACTION_HOST are sent with startForegroundService,
//   so only they promote at once. The wake lock follows the Narrator's state
//   (NarratorWakeLock), not this service's lifetime.
// -----------------------------------------------------------------------------

/**
 * Foreground host for in-app speech. Use [ACTION_SPEAK] with [EXTRA_TEXT] (and
 * optionally [EXTRA_ENGINE], [EXTRA_VOICE], [EXTRA_SPEED]) to speak a text: it
 * plays at once, queues behind other spoken text, reads over an article that
 * is playing (which then resumes), and replaces an article the user paused.
 *
 * Returns `START_NOT_STICKY`: nothing survives process death.
 */
@AndroidEntryPoint
class MarmaladeSynthService : Service() {

    @Inject lateinit var narrator: Narrator

    @Inject lateinit var host: ServiceHost

    @Inject lateinit var shim: SpeakShim

    @Inject lateinit var completions: PreviewCompletions

    @Inject lateinit var loadHold: ArticleLoadHold

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Newest start id delivered to [onStartCommand]. Stopping with
     * `stopSelfResult(lastStartId)` rather than a bare `stopSelf()` leaves the
     * service up when a newer start is already in flight — above all a
     * startForegroundService(SPEAK/HOST), which must reach its startForeground.
     */
    private var lastStartId = 0

    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null

    /** What is shown: [ServiceUi.render] of the latest state (null before the first). */
    private var ui: ServiceUi? = null
    private var mediaState = PlaybackStateCompat.STATE_NONE
    private var lastErrorId: Long? = null
    private var idleCheck: Job? = null

    /** Left the foreground because nothing was on air ([leaveForegroundIfIdle]); a new read brings it back. */
    private var demotedIdle = false

    /** The shared article being loaded under [ArticleLoadHold], or null. */
    private var loading: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        ensureMediaSession()
        shim.onSettled = {
            scope.launch {
                leaveForegroundIfIdle()
                scheduleIdleCheck()
            }
        }
        scope.launch {
            combine(narrator.state, loadHold.loading, ::Pair).collect { (state, url) ->
                loading = url
                show(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            // Sent with startForegroundService (Synthesizer, SpeakDispatcher,
            // Tasker): it owes a startForeground, immediately — Android 12+
            // throws if we delay.
            ACTION_SPEAK -> {
                promoteToForeground()
                val parsed = parseRequest(intent)
                if (parsed == null) {
                    Log.w(TAG, "ACTION_SPEAK without $EXTRA_TEXT — ignoring")
                    // A malformed in-app request must still resolve its
                    // awaiting speak() call.
                    completions.post(
                        intent.getLongExtra(EXTRA_REQUEST_ID, 0L),
                        PreviewCompletions.ErrorKind.FAILED,
                        "malformed request",
                    )
                } else {
                    shim.speak(parsed.request, parsed.requestId)
                }
            }
            // The reader (ServicePlaybackHost) before a command that plays,
            // or a shared link about to load (ArticleLoadHold).
            ACTION_HOST -> runCatching { promoteToForeground() }
                .onFailure { Log.w(TAG, "Could not enter the foreground", it) }
            // Everything else is a control intent, sent with plain
            // startService (Synthesizer.cancel, the notification's buttons)
            // and never promoted — except a resume, which must be foreground
            // to be heard.
            ACTION_PAUSE -> narrator.pause()
            ACTION_RESUME -> resume()
            // The notification's Stop, or a swipe of any of its notifications
            // (#94): a share still loading is cancelled too.
            ACTION_STOP -> {
                narrator.stop()
                loading?.let(loadHold::cancel)
            }
            ACTION_STOP_REQUEST -> shim.stop(intent.getLongExtra(EXTRA_REQUEST_ID, 0L))
            // Only shown while the article is on air, but a stale
            // PendingIntent can still land after it stopped.
            ACTION_READER_NEXT -> if (ui?.showNext == true) narrator.next()
            ACTION_READER_PREVIOUS -> if (ui?.showPrevious == true) narrator.previous()
            // A system restart with no intent — START_NOT_STICKY means this
            // should not happen.
            null -> Unit
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
        // A control intent can land on a fresh instance with nothing to play;
        // this puts it straight back down.
        scheduleIdleCheck()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Detached after a permanent focus loss with the article still paused
        // in place: the system may stop a background service like this one,
        // but the article and its notification stay.
        val detachedOnAir = !host.foreground && ui?.let { it.onAir && !it.ongoing } == true
        // Destroyed while hosting (the system took the service away): nothing
        // on air can be heard any more, so the Narrator stops it. The service's
        // own idle stop has already left the foreground, and a service detached
        // after a permanent focus loss keeps its paused article (the Narrator's
        // paused timeout ends it) — neither reports a loss, so a play racing
        // this teardown isn't stopped by it.
        if (host.foreground) {
            host.foreground = false
            narrator.onHostChanged(false)
        }
        shim.onSettled = {}
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        if (detachedOnAir) {
            // Re-posted without the released media session: a plain
            // notification whose Resume (which brings a new instance up),
            // Stop and swipe-away still reach the article.
            notificationManager?.notify(NOTIFICATION_ID, buildNotification(ui))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager?.cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
    }

    /**
     * Android 14+ (API 34) requires the type argument to match the manifest
     * declaration (mediaPlayback); the 3-arg overload exists since API 29.
     */
    private fun promoteToForeground() {
        val notification = foregroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        idleCheck?.cancel()
        demotedIdle = false
        host.foreground = true
        narrator.onHostChanged(true)
    }

    /**
     * Play/resume from the notification, the lock screen or a media key. After
     * a permanent focus loss the service left the foreground and must return
     * to it to be heard; if the OS refuses, the Narrator fails the session once
     * its host timeout passes.
     */
    private fun resume() {
        if (!host.foreground) {
            runCatching { promoteToForeground() }
                .onFailure { Log.w(TAG, "Could not return to the foreground", it) }
        }
        narrator.play()
    }

    // -- state → notification, media session, foreground --------------------

    private fun show(state: NarratorState) {
        val next = ServiceUi.render(state, mediaState)
        val wasOnAir = ui?.onAir == true
        ui = next
        mediaState = next.mediaState
        updateMediaSession(next)
        ServiceUi.errorToPost(state, lastErrorId)?.let {
            lastErrorId = it.oneShotId
            postErrorNotification(it)
            // The notice outlives this instance in the Narrator; acknowledged,
            // so the next instance doesn't post it again.
            narrator.onNoticeShown(it.oneShotId)
        }
        when {
            // A shared link is loading: stay up, saying so, until the reader
            // plays it or gives up (ArticleLoadHold).
            !next.onAir && loading != null -> {
                idleCheck?.cancel()
                if (host.foreground) notificationManager?.notify(NOTIFICATION_ID, foregroundNotification())
            }
            !next.onAir -> {
                // Stopped or done: the "Speaking…" notification and its live
                // Pause go now; only stopping the service waits for the grace.
                if (wasOnAir) leaveForegroundIfIdle()
                scheduleIdleCheck()
            }
            next.ongoing -> {
                // A read started again right after an idle demotion (Stop,
                // then Play in the reader): back to the foreground.
                if (demotedIdle && !host.foreground) {
                    runCatching { promoteToForeground() }
                        .onFailure { Log.w(TAG, "Could not return to the foreground", it) }
                }
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(next))
            }
            else -> {
                // §6 Q6: another app took the sound for good. Paused in place,
                // out of the foreground, and the notification can be swiped
                // away (its delete intent stops the article).
                if (host.foreground) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    host.foreground = false
                }
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(next))
            }
        }
    }

    /**
     * Nothing on air and no one-shot handed over: leave the foreground and
     * remove the notification at once. The service itself stops after the
     * grace ([scheduleIdleCheck]); a read arriving meanwhile promotes again.
     */
    private fun leaveForegroundIfIdle() {
        if (!host.foreground || busy()) return
        if (ServiceUi.render(narrator.state.value, mediaState).onAir) return
        host.foreground = false
        demotedIdle = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    /**
     * Stop once nothing is on air and no one-shot handed over is unresolved —
     * checked after [IDLE_GRACE_MS], so a command sent right behind its start
     * intent (a reader play behind ACTION_HOST) reaches the Narrator first.
     */
    private fun scheduleIdleCheck() {
        idleCheck?.cancel()
        idleCheck = scope.launch {
            delay(IDLE_GRACE_MS)
            if (ServiceUi.render(narrator.state.value, mediaState).onAir || busy()) return@launch
            host.foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager?.cancel(NOTIFICATION_ID)
            stopSelfResult(lastStartId)
        }
    }

    /** Work handed over that isn't on air yet: a one-shot in the Narrator's inbox, or an article loading. */
    private fun busy(): Boolean = shim.busy || loadHold.loading.value != null

    // -- media session ----------------------------------------------------------

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val session = MediaSessionCompat(this, "MarmaladeSynth")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = narrator.pause()
            override fun onStop() = narrator.stop()

            // A headset's play/pause toggle resolves to onPlay for any state
            // but PLAYING (MediaSession's default handling), so while the
            // first audio is still being synthesised it would do nothing.
            // There it means "pause", as it will once audio plays.
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val key = IntentCompat.getParcelableExtra(
                    mediaButtonEvent,
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent::class.java,
                )
                if (key != null && key.action == KeyEvent.ACTION_DOWN &&
                    key.repeatCount == 0 && ServiceUi.playPauseKeyPauses(key.keyCode, mediaState)
                ) {
                    narrator.pause()
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }

            // Headset / Bluetooth track-skip buttons step the article while it
            // is on air. Shared text has no "next track" (§6 Q5).
            override fun onSkipToNext() {
                if (ui?.showNext == true) narrator.next()
            }

            override fun onSkipToPrevious() {
                if (ui?.showPrevious == true) narrator.previous()
            }

            override fun onCustomAction(action: String?, extras: Bundle?) {
                if (action == CUSTOM_ACTION_STOP) narrator.stop()
            }
        })
        session.isActive = true
        mediaSession = session
    }

    private fun updateMediaSession(ui: ServiceUi) {
        val session = mediaSession ?: return
        val pb = PlaybackStateCompat.Builder()
            .setActions(ui.actions)
            .setState(ui.mediaState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        // Android 13+ builds the shade's media controls from this state, not
        // from the notification's actions, and ACTION_STOP has no button
        // there — Stop only shows as a custom action. Older versions keep
        // using the notification's own Stop (buildNotification).
        if (ui.showStop) {
            pb.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    CUSTOM_ACTION_STOP,
                    getString(R.string.service_synth_action_stop),
                    android.R.drawable.ic_menu_close_clear_cancel,
                ).build(),
            )
        }
        session.setPlaybackState(pb.build())
    }

    // -- notification ------------------------------------------------------------

    private fun ensureNotificationChannel() {
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_synth_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_synth_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * A failure of a UI-less one-shot (share sheet, Tasker), as its own
     * dismissable notification on a separate id — the foreground one is
     * removed moments later when the service stops. In-app requests show
     * their errors on screen instead.
     */
    private fun postErrorNotification(notice: ErrorNotice) {
        val message = when (notice.kind) {
            FailureKind.MODEL_MISSING -> getString(
                R.string.service_synth_error_engine_not_installed,
                EngineRegistry.displayName(notice.engine ?: EngineRegistry.DEFAULT_ENGINE),
            )
            FailureKind.FAILED, FailureKind.FOCUS_DENIED, FailureKind.HOST_REFUSED ->
                getString(R.string.service_synth_error_failed)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_synth_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_marmalade_speaking)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        notificationManager?.notify(ERROR_NOTIFICATION_ID, n)
    }

    /**
     * The playback notification, drawn from [ui] (null: before the first
     * state — "Preparing…"). Pause/resume and stop always; previous/next
     * around the pause while the article is on air, and tapping it reopens the
     * article (reader-mode design point 7).
     */
    internal fun buildNotification(ui: ServiceUi?): Notification {
        val stateText = when (ui?.text) {
            ServiceUi.Text.Speaking ->
                if (ui.snippet.isNotBlank()) {
                    getString(R.string.service_synth_state_speaking_text, ui.snippet)
                } else {
                    getString(R.string.service_synth_state_speaking)
                }
            ServiceUi.Text.Paused -> getString(R.string.service_synth_state_paused)
            ServiceUi.Text.Preparing, null -> getString(R.string.service_synth_state_preparing)
        }
        val pauseAction = NotificationCompat.Action(
            android.R.drawable.ic_media_pause,
            getString(R.string.service_synth_action_pause),
            servicePendingIntent(ACTION_PAUSE),
        )
        val resumeAction = NotificationCompat.Action(
            android.R.drawable.ic_media_play,
            getString(R.string.service_synth_action_resume),
            servicePendingIntent(ACTION_RESUME),
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.service_synth_action_stop),
            servicePendingIntent(ACTION_STOP),
        )
        // Transport order, so the compact view (which takes the first three)
        // reads previous / play-pause / next like every other media
        // notification. Stop trails behind in the expanded view.
        val actions = buildList {
            if (ui?.showPrevious == true) {
                add(
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_previous,
                        getString(R.string.service_synth_action_previous_block),
                        servicePendingIntent(ACTION_READER_PREVIOUS),
                    ),
                )
            }
            add(if (ui?.paused == true) resumeAction else pauseAction)
            if (ui?.showNext == true) {
                add(
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_next,
                        getString(R.string.service_synth_action_next_block),
                        servicePendingIntent(ACTION_READER_NEXT),
                    ),
                )
            }
            add(stopAction)
        }
        val ongoing = ui?.ongoing ?: true
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_synth_notification_title))
            .setContentText(stateText)
            .setSmallIcon(R.drawable.ic_stat_marmalade_speaking)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        actions.forEach(builder::addAction)
        // Dismissing the notification stops the read (#94): Android 14+ lets
        // the user dismiss a foreground service's notification despite
        // setOngoing. Android 13+ shows this one as the shade's media player
        // instead, and swiping that player away only hides it: SystemUI marks
        // it timed out ("Media carousel swiped away") and tells the app
        // nothing, playing or paused (Pixel 6a, Android 14). It comes back on
        // the next pause/play.
        builder.setDeleteIntent(servicePendingIntent(ACTION_STOP))
        // The notification left after a permanent focus loss may outlive this
        // service (onDestroy), so it clears itself once the Narrator's paused
        // timeout has ended the read.
        if (!ongoing) builder.setTimeoutAfter(PAUSED_NOTIFICATION_TIMEOUT_MS)
        ui?.articleKey?.let { builder.setContentIntent(readerPendingIntent(it)) }
        mediaSession?.sessionToken?.let { token ->
            val compact = IntArray(minOf(COMPACT_ACTIONS, actions.size)) { it }
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(*compact),
            )
        }
        return builder.build()
    }

    /** What startForeground shows: the playback notification, or "Loading article…" while one loads. */
    private fun foregroundNotification(): Notification {
        val url = loading
        if (ui?.onAir == true || url == null) return buildNotification(ui)
        return loadingNotification(url)
    }

    /**
     * "Loading article…" for the shared [url]. No actions: there is nothing to
     * pause yet. Tapping opens the reader, and backing out of it before the
     * article loads cancels the read.
     */
    internal fun loadingNotification(url: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_synth_notification_title))
            .setContentText(getString(R.string.service_synth_state_loading_article))
            .setSmallIcon(R.drawable.ic_stat_marmalade_speaking)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(readerPendingIntent(url))
            // Swiped away: the article is not read once it loads (#94).
            .setDeleteIntent(servicePendingIntent(ACTION_STOP))
            .build()

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MarmaladeSynthService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Reopen the article being read. The reader screen asks
     * [app.marmalade.tts.reader.ReaderPlaybackController] for the article
     * before it fetches, so this rebinds to the block in progress off the copy
     * already in memory — no second trip to the network.
     *
     * The shared-text extra is deliberately empty: that extra exists for the
     * failure card's "read what you shared as-is" escape, and an article we
     * are part-way through reading did not fail. FLAG_UPDATE_CURRENT keeps
     * the URL current when the user moves on to a different article.
     */
    private fun readerPendingIntent(url: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_READER_URL, url)
            .putExtra(MainActivity.EXTRA_READER_SHARED_TEXT, "")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        return PendingIntent.getActivity(
            this,
            READER_CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // -- the intent shim -----------------------------------------------------------

    /** An `ACTION_SPEAK` as a one-shot, with the in-app request id it answers to (0 = external). */
    internal data class Parsed(val request: OneShotRequest, val requestId: Long)

    internal fun parseRequest(intent: Intent): Parsed? {
        // In-app callers hand very long text over as a cache file: intent
        // extras cross a binder transaction (~1 MB) even same-process. The
        // file is consumed (deleted) here.
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_TEXT_FILE)?.let { path ->
                val f = java.io.File(path)
                // Only ever our own cache files — refuse anything else.
                if (!f.absolutePath.startsWith(cacheDir.absolutePath)) return null
                runCatching { f.readText() }.getOrNull().also { f.delete() }
            }?.takeIf { it.isNotBlank() }
            ?: return null
        val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        return Parsed(
            OneShotRequest(
                text = text,
                voice = voiceOf(intent),
                origin = if (requestId != 0L) Origin.InApp else Origin.External,
            ),
            requestId,
        )
    }

    companion object {
        private const val TAG = "MarmaladeSynthService"
        private const val CHANNEL_ID = "marmalade_synth"
        private const val NOTIFICATION_ID = 1

        /** Separate id so the playback notification's removal can't take errors with it. */
        private const val ERROR_NOTIFICATION_ID = 2

        /** How many actions MediaStyle's collapsed view can show. */
        private const val COMPACT_ACTIONS = 3

        /** Any constant will do — the extras are what change, per article. */
        private const val READER_CONTENT_REQUEST_CODE = 100

        /** The Narrator's paused timeout (§6 Q7): a detached notification goes with it. */
        private val PAUSED_NOTIFICATION_TIMEOUT_MS = NarratorConfig().pausedTimeoutMs

        /** How long nothing on air must last before the service stops (see scheduleIdleCheck). */
        private const val IDLE_GRACE_MS = 1_500L

        /** The media session's Stop, as a custom action — see updateMediaSession. */
        internal const val CUSTOM_ACTION_STOP = "app.marmalade.tts.media.STOP"

        /**
         * The voice an `ACTION_SPEAK` asks for. A named voice is spoken exactly
         * (Speak screen, previews, a Tasker call naming one); otherwise the
         * primary alias — or, for shared text, the voice that speaks its
         * language — with the caller's own engine/speed/effect/language extras
         * as the fallback when no alias resolves.
         */
        internal fun voiceOf(intent: Intent): OneShotVoice {
            val effectPreset = intent.getStringExtra(EXTRA_EFFECT)
                // Unknown values fall back to NONE — safer than throwing on a
                // typo'd extra from a third-party caller (Tasker etc).
                ?.let { name -> EffectPreset.entries.firstOrNull { it.name == name } ?: EffectPreset.NONE }
                ?: EffectPreset.NONE
            // The intent contract speaks in presets; in-app requests carry
            // their exact chain as JSON instead (custom effects have no preset
            // name). A malformed payload degrades to the preset/dry chain.
            val effectBlocks = intent.getStringExtra(EXTRA_EFFECT_BLOCKS)
                ?.let { json -> runCatching { EffectBlockJson.decode(json) }.getOrNull() }
                ?: EffectChain.blocksForPreset(effectPreset)
            val named = intent.getStringExtra(EXTRA_VOICE)?.takeIf { it.isNotBlank() }
            val given = OneShotVoice.Explicit(
                voiceId = named ?: KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
                effectBlocks = effectBlocks,
                phonemizationLanguage = intent.getStringExtra(EXTRA_LANG)?.takeIf { it.isNotBlank() },
                speed = if (intent.hasExtra(EXTRA_SPEED)) intent.getFloatExtra(EXTRA_SPEED, 1.0f) else 1.0f,
                // The explicit EXTRA_ENGINE wins over the voice id's prefix,
                // so legacy callers that send an engine without a voice work.
                engine = intent.getStringExtra(EXTRA_ENGINE)?.takeIf { it.isNotBlank() },
                // A missing (or mistyped) extra means the engine's default.
                expressiveness = intent.extras?.get(EXTRA_EXPRESSIVENESS) as? Float,
            )
            return when {
                named != null -> given
                intent.getBooleanExtra(EXTRA_SHARED, false) -> OneShotVoice.LanguageAware(fallback = given)
                else -> OneShotVoice.Routed(VoiceChoice.Primary, fallback = given)
            }
        }

        // -- public intent contract --------------------------------------------
        const val ACTION_SPEAK: String = "app.marmalade.tts.action.SPEAK"
        const val ACTION_PAUSE: String = "app.marmalade.tts.action.PAUSE"
        const val ACTION_RESUME: String = "app.marmalade.tts.action.RESUME"
        const val ACTION_STOP: String = "app.marmalade.tts.action.STOP"

        /**
         * Stop a single in-app request by [EXTRA_REQUEST_ID] without touching
         * anything else — [ACTION_STOP] stops everything including an external
         * share-sheet read, which is exactly what a ViewModel teardown must NOT do.
         */
        const val ACTION_STOP_REQUEST: String = "app.marmalade.tts.action.STOP_REQUEST"

        /**
         * In-app only: bring the service up as the foreground host of the
         * Narrator's audio (the reader, before a command that plays). Sent
         * with startForegroundService.
         */
        const val ACTION_HOST: String = "app.marmalade.tts.action.HOST"

        /**
         * Step the article from the notification. In-app only — the buttons
         * exist only while the article is on air, and are ignored otherwise.
         */
        const val ACTION_READER_NEXT: String = "app.marmalade.tts.action.READER_NEXT"
        const val ACTION_READER_PREVIOUS: String = "app.marmalade.tts.action.READER_PREVIOUS"

        const val EXTRA_TEXT: String = "app.marmalade.tts.extra.TEXT"
        const val EXTRA_ENGINE: String = "app.marmalade.tts.extra.ENGINE"
        const val EXTRA_VOICE: String = "app.marmalade.tts.extra.VOICE"
        const val EXTRA_SPEED: String = "app.marmalade.tts.extra.SPEED"
        const val EXTRA_EFFECT: String = "app.marmalade.tts.extra.EFFECT"

        // -- in-app contract (Synthesizer → service; not for third parties) ----
        /** JSON-encoded List<EffectBlock> ([EffectBlockJson]); wins over [EXTRA_EFFECT]. */
        const val EXTRA_EFFECT_BLOCKS: String = "app.marmalade.tts.extra.EFFECT_BLOCKS"

        /** Espeak phonemization override — see [app.marmalade.tts.data.db.VoiceAlias.phonemizationLanguage]. */
        const val EXTRA_LANG: String = "app.marmalade.tts.extra.LANG"

        /** Float; absent = engine default. See [app.marmalade.tts.data.db.VoiceAlias.expressiveness]. */
        const val EXTRA_EXPRESSIVENESS: String = "app.marmalade.tts.extra.EXPRESSIVENESS"

        /** [PreviewCompletions] request id (Long). */
        const val EXTRA_REQUEST_ID: String = "app.marmalade.tts.extra.REQUEST_ID"

        /**
         * Boolean, default false. True marks plain text shared into the app
         * ([SpeakDispatcher]: the share sheet, PROCESS_TEXT, the reader's
         * "read text as-is"). With no [EXTRA_VOICE] it is spoken in a voice
         * that speaks its language when the primary alias's doesn't.
         */
        const val EXTRA_SHARED: String = "app.marmalade.tts.extra.SHARED"

        /**
         * Path (inside our own cacheDir) holding the text, used instead of
         * [EXTRA_TEXT] when the text is too large for a binder transaction.
         */
        const val EXTRA_TEXT_FILE: String = "app.marmalade.tts.extra.TEXT_FILE"
    }
}
