package app.marmalade.tts.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps [MarmaladeSynthService] in the foreground, showing "Loading article…",
 * from the moment a link is shared until the reader has started reading it or
 * given up (Max, 2026-10-03: "start reading anyway").
 *
 * Why: Android 12+ refuses `startForegroundService` from the background. A
 * user who shares a link and switches apps before the article loads used to
 * get "Synthesis failed" when the reader's autoplay tried to bring the host up.
 * The share trampoline is still in front when the share arrives, so the host
 * is started there, and the reader's play later finds it already up.
 *
 * Ended by the reader ([end]) once its load is over — played, failed, a rebind
 * with nothing to fetch, or the user backed out — and after [timeoutMs] in any
 * case (onboarding still open, a reader that never opened), so no foreground
 * service is left stuck.
 */
@Singleton
class ArticleLoadHold internal constructor(
    private val startHost: () -> Boolean,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        startHost = { startLoadingHost(context) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val key = MutableStateFlow<String?>(null)
    private var timeout: Job? = null

    /** Links whose "Loading article…" the user dismissed: the reader must not autoplay them. */
    private val cancelled = mutableSetOf<String>()

    /** The article being loaded under the hold, or null. The service shows "Loading article…" meanwhile. */
    val loading: StateFlow<String?> = key.asStateFlow()

    /** A link to [url] was shared, and we are in front: bring the host up now. False if Android refused. */
    fun begin(url: String): Boolean {
        // A new share of a link cancelled before means "read me" again.
        synchronized(this) { cancelled -= url }
        // Set first: the service's startForeground shows the loading notification.
        key.value = url
        if (!startHost()) {
            end(url)
            return false
        }
        synchronized(this) {
            timeout?.cancel()
            timeout = scope.launch {
                delay(timeoutMs)
                if (key.value == url) Log.w(TAG, "Article load hold timed out")
                end(url)
            }
        }
        return true
    }

    /** The load of [url] is over. A later share's hold (another url) is left alone. */
    fun end(url: String) {
        key.compareAndSet(url, null)
    }

    /**
     * The user dismissed "Loading article…" (or sent a stop) while [url] was
     * loading: the hold ends, and the reader opens the article without
     * reading it ([consumeCancelled]).
     */
    fun cancel(url: String) {
        synchronized(this) { cancelled += url }
        end(url)
    }

    /** Whether the load of [url] was cancelled; answers true once. */
    fun consumeCancelled(url: String): Boolean = synchronized(this) { cancelled.remove(url) }

    companion object {
        private const val TAG = "ArticleLoadHold"

        /** Comfortably past a slow fetch (15 s connect + 15 s read per hop) and the extraction. */
        const val TIMEOUT_MS = 90_000L

        private fun startLoadingHost(context: Context): Boolean {
            val intent = Intent(context, MarmaladeSynthService::class.java)
                .setAction(MarmaladeSynthService.ACTION_HOST)
                .setPackage(context.packageName)
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Host refused to start for an article load", it) }
                .isSuccess
        }
    }
}
