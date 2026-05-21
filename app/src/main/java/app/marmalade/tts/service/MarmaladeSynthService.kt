package app.marmalade.tts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import app.marmalade.tts.R
import app.marmalade.tts.audio.EffectChain
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenVoiceCatalog
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.KittenEngine
import app.marmalade.tts.preprocessing.EmojiProsody
import app.marmalade.tts.preprocessing.ProsodyApplier
import dagger.hilt.android.AndroidEntryPoint
import java.util.ArrayDeque
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Public intent contract
// -----------------------------------------------------------------------------
//
//   Action: "app.marmalade.tts.action.SPEAK"
//
//   Extras (sent on the intent):
//     EXTRA_TEXT   (String, required)   — the text to read aloud.
//     EXTRA_ENGINE (String, optional)   — engine name; default "kitten".
//                                          v0.1 only ships kitten.
//     EXTRA_VOICE  (String, optional)   — voice id e.g. "kitten:Bella".
//                                          Defaults to KittenVoiceCatalog.DEFAULT_VOICE_ID.
//     EXTRA_SPEED  (Float, optional)    — length-scale style; 1.0 = native,
//                                          > 1 = faster. Default 1.0.
//     EXTRA_EFFECT (String, optional)   — EffectPreset name (NONE / CAVE /
//                                          ROBOT / TELEPHONE). Default NONE.
//
//   Transport actions (media button / lock-screen):
//     ACTION_PAUSE / ACTION_RESUME / ACTION_STOP — wired via MediaSessionCompat.
//
// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//
//   Caller (Activity / share-sheet / future Tasker integration)
//     │
//     │  Intent { action = ACTION_SPEAK, extras = text/engine/voice/speed }
//     ▼
//   MarmaladeSynthService.onStartCommand(intent, flags, startId)
//     │
//     ├── parseRequest(intent) ──► SpeakRequest
//     │
//     ├── ensureForegroundNotification()  (channel + Notification.MediaStyle)
//     │
//     ├── ensureMediaSession()  (lock-screen + BT transport)
//     │
//     ├── if a synthesis job is already active:
//     │     queue.offer(request)         (idempotent — see spec)
//     │   else:
//     │     beginJob(request)
//     │
//     ▼
//   beginJob(req):
//     │
//     ├── requestAudioFocus(AUDIOFOCUS_GAIN)
//     │     - LOSS_TRANSIENT → pause the AudioTrack
//     │     - LOSS           → stop + release; clear queue
//     │     - GAIN (after transient loss) → resume
//     │
//     ├── EmojiProsody.detect(raw text)  ──► ProsodyHint
//     ├── EmojiProsody.stripEmojis(raw)  ──► engine-safe text
//     ├── KittenEngine.synthesize(text, voice, speed) → SynthAudio
//     ├── ProsodyApplier.apply(pcm, sr, hint.emotion) → emotion-shaped PCM
//     ├── EffectChain.apply(pcm, sr, effect)          → effect-shaped PCM
//     │
//     ├── stream into AudioTrack (MODE_STREAM, 24 kHz PCM_16BIT mono)
//     │     - pausable / cancellable via volatile flags
//     │
//     └── on completion:
//           if queue.isNotEmpty() → beginJob(queue.poll())
//           else                  → releaseAudioFocus, stopForeground, stopSelf
//
//   Bluetooth headset hand-off: not implemented explicitly — AudioTrack
//   honours the system routing automatically, and audio-focus loss when
//   the BT headset takes over a call is handled by the focus listener.
// -----------------------------------------------------------------------------

/**
 * Foreground service for long-form synthesis playback.
 *
 * Use [ACTION_SPEAK] with [EXTRA_TEXT] (and optionally [EXTRA_ENGINE],
 * [EXTRA_VOICE], [EXTRA_SPEED]) to enqueue a synthesis. The service is
 * idempotent: starting it again while already speaking appends to the
 * queue.
 *
 * Returns `START_NOT_STICKY` so the system does not restart the service
 * if it is killed between jobs — there is nothing useful to do without
 * the original text in hand.
 */
@AndroidEntryPoint
class MarmaladeSynthService : Service() {

    @Inject lateinit var engine: KittenEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Synchronized by `lock`. Accessed from main (onStartCommand), the
    // audio-focus callback thread, and the synthesis coroutine.
    private val lock = Any()
    private val queue: ArrayDeque<SpeakRequest> = ArrayDeque()
    private var activeJob: Job? = null

    // AudioTrack owner-thread invariant: `currentTrack` is created and
    // destroyed by the synthesis coroutine. Read by `pause` / `resume`
    // / `stop` paths (volatile read is safe; we only call AudioTrack
    // state transitions which are documented as thread-safe).
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var paused: Boolean = false
    @Volatile private var cancelled: Boolean = false

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        ensureNotificationChannel()
        ensureMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote to foreground before doing any work — Android 12+
        // throws ForegroundServiceStartNotAllowedException if we delay.
        startForeground(NOTIFICATION_ID, buildNotification(stateText = "Preparing…"))

        if (intent == null) {
            // System restart with no intent — START_NOT_STICKY means this
            // should not happen, but be defensive.
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_SPEAK -> {
                val req = parseRequest(intent)
                if (req == null) {
                    Log.w(TAG, "ACTION_SPEAK without ${EXTRA_TEXT} — ignoring")
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                enqueue(req)
            }
            ACTION_PAUSE -> doPause()
            ACTION_RESUME -> doResume()
            ACTION_STOP -> doStop()
            else -> {
                // Unknown action — ignore but don't crash.
                Log.w(TAG, "Unknown action: ${intent.action}")
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        doStop()
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // -- request handling -----------------------------------------------------

    private fun parseRequest(intent: Intent): SpeakRequest? {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: return null
        val engineName = intent.getStringExtra(EXTRA_ENGINE)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ENGINE
        val voice = intent.getStringExtra(EXTRA_VOICE)?.takeIf { it.isNotBlank() }
            ?: KittenVoiceCatalog.DEFAULT_VOICE_ID
        val speed = if (intent.hasExtra(EXTRA_SPEED)) {
            intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        } else {
            1.0f
        }
        val effect = intent.getStringExtra(EXTRA_EFFECT)
            ?.let { name ->
                // Unknown values fall back to NONE — safer than throwing on a
                // typo'd extra from a third-party caller (Tasker etc).
                EffectPreset.entries.firstOrNull { it.name == name } ?: EffectPreset.NONE
            }
            ?: EffectPreset.NONE
        return SpeakRequest(text, engineName, voice, speed, effect)
    }

    private fun enqueue(req: SpeakRequest) {
        synchronized(lock) {
            queue.addLast(req)
            if (activeJob == null) {
                startNextLocked()
            } else {
                Log.d(TAG, "Queued request (queue size = ${queue.size})")
                updateNotification("Queued: ${req.text.take(40)}")
            }
        }
    }

    /** Caller must hold `lock`. */
    private fun startNextLocked() {
        val next = queue.pollFirst() ?: run {
            // Nothing left to do — release focus + foreground.
            stopIfIdle()
            return
        }
        cancelled = false
        paused = false
        activeJob = scope.launch {
            runOne(next)
            synchronized(lock) {
                activeJob = null
                if (queue.isNotEmpty()) {
                    startNextLocked()
                } else {
                    stopIfIdle()
                }
            }
        }
    }

    private suspend fun runOne(req: SpeakRequest) {
        // v0.1 supports kitten only — other engines fall through to the
        // default rather than failing loudly. The selection happens in
        // KittenEngine; speed and voice are honoured directly.
        if (req.engine != DEFAULT_ENGINE) {
            Log.w(TAG, "Engine '${req.engine}' not supported in v0.1 — using $DEFAULT_ENGINE")
        }

        if (!requestFocus()) {
            Log.w(TAG, "Audio focus denied — skipping request")
            return
        }
        // From this point on we MUST releaseFocus() before returning, on
        // every branch — otherwise the next queued request calls
        // requestFocus() again, overwrites `focusRequest`, and the previous
        // grant becomes unrecoverable (abandonAudioFocusRequest on API 26+
        // needs the exact original AudioFocusRequest object). doStop() /
        // stopIfIdle() only abandon the *latest* request, so they don't
        // recover earlier leaks either. try { … } finally { releaseFocus() }
        // is the only safe shape.
        try {
            updateNotification("Speaking: ${req.text.take(40)}")

            val hint = EmojiProsody.detect(req.text)
            val cleaned = EmojiProsody.stripEmojis(req.text)
            if (cleaned.isBlank()) return

            val audio = try {
                engine.synthesize(cleaned, req.voice, req.speed)
            } catch (e: EngineNotInstalledException) {
                // Surface as a notification update so the user sees it; the
                // app-launcher tap will route them to the Engines screen.
                Log.w(TAG, "Engine not installed", e)
                updateNotification("Kitten engine not installed")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Synthesis failed", t)
                updateNotification("Synthesis failed")
                return
            }

            val emotionShaped = ProsodyApplier.apply(audio.pcm, audio.sampleRate, hint.emotion)
            // EffectChain is a no-op for NONE (returns the same array unchanged),
            // so the dry path adds no extra allocation.
            val shaped = EffectChain.apply(emotionShaped, audio.sampleRate, req.effect)
            try {
                playPcm(shaped, audio.sampleRate)
            } catch (t: Throwable) {
                Log.e(TAG, "Playback failed", t)
            }
        } finally {
            releaseFocus()
        }
    }

    // -- transport ------------------------------------------------------------

    private fun doPause() {
        paused = true
        val track = currentTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
        } catch (_: IllegalStateException) { /* track gone */ }
        updateMediaState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification("Paused")
    }

    private fun doResume() {
        paused = false
        val track = currentTrack ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        } catch (_: IllegalStateException) { /* track gone */ }
        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        updateNotification("Speaking")
    }

    private fun doStop() {
        cancelled = true
        synchronized(lock) {
            queue.clear()
        }
        val track = currentTrack
        if (track != null) {
            try {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            } catch (_: IllegalStateException) { /* already stopped */ }
        }
        releaseFocus()
        updateMediaState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopIfIdle() {
        synchronized(lock) {
            if (activeJob == null && queue.isEmpty()) {
                releaseFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // -- audio focus ----------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> doPause()
            AudioManager.AUDIOFOCUS_GAIN -> if (paused) doResume()
            AudioManager.AUDIOFOCUS_LOSS -> doStop()
        }
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(true)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    // -- playback -------------------------------------------------------------

    /**
     * Allocate an AudioTrack at [sampleRate], stream [pcm] into it, and
     * return when the playback head has drained. Honours `paused` and
     * `cancelled` flags so the transport actions feel immediate.
     */
    private suspend fun playPcm(pcm: ShortArray, sampleRate: Int) =
        withContext(Dispatchers.IO) {
            // Aim for ~250 ms of headroom — small enough that pause/cancel
            // feels responsive (the audio device drains the buffer before we
            // see the effect), large enough that the write loop below isn't
            // constantly stalling on full-buffer back-pressure.
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRate * 2 / 4)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentTrack = track
            updateMediaState(PlaybackStateCompat.STATE_PLAYING)
            try {
                track.play()
                var written = 0
                while (written < pcm.size && !cancelled) {
                    while (paused && !cancelled) {
                        try { Thread.sleep(50L) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt(); break
                        }
                    }
                    if (cancelled) break
                    val remaining = pcm.size - written
                    val n = track.write(pcm, written, remaining, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) {
                        Log.w(TAG, "AudioTrack.write returned $n; aborting")
                        break
                    }
                    written += n
                }
                // Drain.
                while (!cancelled) {
                    val pos = try { track.playbackHeadPosition } catch (_: IllegalStateException) { written }
                    if (pos >= written) break
                    try { Thread.sleep(10L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                }
            } finally {
                try {
                    track.pause(); track.flush(); track.stop()
                } catch (_: IllegalStateException) { /* already stopped */ }
                track.release()
                if (currentTrack === track) currentTrack = null
            }
        }

    // -- media session --------------------------------------------------------

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val session = MediaSessionCompat(this, "MarmaladeSynth")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = doResume()
            override fun onPause() = doPause()
            override fun onStop() = doStop()
        })
        session.isActive = true
        mediaSession = session
    }

    private fun updateMediaState(state: Int) {
        val session = mediaSession ?: return
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        val pb = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(pb)
    }

    // -- notification ---------------------------------------------------------

    private fun ensureNotificationChannel() {
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Marmalade TTS Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while Marmalade TTS is reading aloud"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(stateText: String) {
        val n = buildNotification(stateText)
        notificationManager?.notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(stateText: String): Notification {
        val session = mediaSession
        val token = session?.sessionToken

        val pauseAction = NotificationCompat.Action(
            android.R.drawable.ic_media_pause,
            "Pause",
            servicePendingIntent(ACTION_PAUSE),
        )
        val resumeAction = NotificationCompat.Action(
            android.R.drawable.ic_media_play,
            "Resume",
            servicePendingIntent(ACTION_RESUME),
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            servicePendingIntent(ACTION_STOP),
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Marmalade TTS")
            .setContentText(stateText)
            .setSmallIcon(R.drawable.mascot_speaking)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(if (paused) resumeAction else pauseAction)
            .addAction(stopAction)

        if (token != null) {
            val style = androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1)
            builder.setStyle(style)
        }

        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MarmaladeSynthService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // -- request value type ---------------------------------------------------

    private data class SpeakRequest(
        val text: String,
        val engine: String,
        val voice: String,
        val speed: Float,
        val effect: EffectPreset,
    )

    companion object {
        private const val TAG = "MarmaladeSynthService"
        private const val CHANNEL_ID = "marmalade_synth"
        private const val NOTIFICATION_ID = 1

        /** Default engine when [EXTRA_ENGINE] is not provided. */
        const val DEFAULT_ENGINE: String = "kitten"

        // -- public intent contract --------------------------------------------
        const val ACTION_SPEAK: String = "app.marmalade.tts.action.SPEAK"
        const val ACTION_PAUSE: String = "app.marmalade.tts.action.PAUSE"
        const val ACTION_RESUME: String = "app.marmalade.tts.action.RESUME"
        const val ACTION_STOP: String = "app.marmalade.tts.action.STOP"

        const val EXTRA_TEXT: String = "app.marmalade.tts.extra.TEXT"
        const val EXTRA_ENGINE: String = "app.marmalade.tts.extra.ENGINE"
        const val EXTRA_VOICE: String = "app.marmalade.tts.extra.VOICE"
        const val EXTRA_SPEED: String = "app.marmalade.tts.extra.SPEED"
        const val EXTRA_EFFECT: String = "app.marmalade.tts.extra.EFFECT"

        /**
         * Convenience: build a SPEAK intent without the caller needing to
         * spell out the extras keys.
         */
        fun speakIntent(
            context: Context,
            text: String,
            engine: String = DEFAULT_ENGINE,
            voice: String? = null,
            speed: Float? = null,
            effect: EffectPreset? = null,
        ): Intent = Intent(context, MarmaladeSynthService::class.java).apply {
            action = ACTION_SPEAK
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_ENGINE, engine)
            voice?.let { putExtra(EXTRA_VOICE, it) }
            speed?.let { putExtra(EXTRA_SPEED, it) }
            effect?.let { putExtra(EXTRA_EFFECT, it.name) }
        }
    }
}
