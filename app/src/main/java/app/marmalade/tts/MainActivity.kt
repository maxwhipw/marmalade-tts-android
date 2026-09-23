package app.marmalade.tts

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.tts.ui.AppRoot
import app.marmalade.tts.ui.AppRootViewModel
import app.marmalade.tts.ui.ReaderRequest
import app.marmalade.tts.ui.theme.MarmaladeTtsTheme
import app.marmalade.tts.ui.theme.ThemePreset
import app.marmalade.tts.ui.theme.resolveThemeIsDark
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host for the Compose UI. `@AndroidEntryPoint` plumbs
 * Hilt through to every `hiltViewModel()` call in the screen composables,
 * and to the activity-scoped `viewModel<AppRootViewModel>()` we use to
 * read the theme preset + dark-mode override before any screen renders.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Set when we were started by [app.marmalade.tts.ui.intent.ShareIntentActivity]
     * with a shared link (or by the reader's playback notification). Held as
     * state (rather than read once from `intent`) so a second share arriving
     * at an already-running MainActivity — which lands in [onNewIntent] —
     * reopens the reader on the new link. AppRoot clears it once handled.
     */
    private val readerRequest = mutableStateOf<ReaderRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readerRequest.value = when {
            // A recreation — rotation, dark mode or locale change, a restore
            // after process death. The launch intent was handled the first
            // time round; `intent` still carries it, and re-reading it would
            // reopen (and after process death, restart) the reader unasked.
            // Only a request that never got handled carries over: one that
            // arrived while onboarding was still on screen.
            savedInstanceState != null -> savedInstanceState.pendingReaderRequest()
            // Relaunched from Recents: Android replays the original launch
            // intent, extras and all, but the share it carried is old news.
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0 -> null
            else -> readerRequestFrom(intent)
        }
        enableEdgeToEdge()
        setContent {
            val rootVm: AppRootViewModel = viewModel()
            val preset by rootVm.themePreset.collectAsStateWithLifecycle(
                initialValue = ThemePreset.MARMALADE,
            )
            val themeMode by rootVm.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val systemDark = isSystemInDarkTheme()
            val darkTheme = resolveThemeIsDark(themeMode, systemDark)
            // Default enableEdgeToEdge() keys system-bar icon contrast off the
            // SYSTEM dark setting; the in-app theme override can disagree with
            // it, leaving light icons on the light theme (or vice versa).
            LaunchedEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MarmaladeTtsTheme(darkTheme = darkTheme, themePreset = preset) {
                AppRoot(
                    viewModel = rootVm,
                    readerRequest = readerRequest.value,
                    onReaderRequestConsumed = { readerRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readerRequest.value = readerRequestFrom(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        readerRequest.value?.let {
            outState.putString(STATE_PENDING_READER_URL, it.url)
            outState.putString(STATE_PENDING_READER_TEXT, it.sharedText)
        }
    }

    /**
     * This activity is exported, so these extras can come from any app, not
     * just our own share trampoline — [ReaderRequest.of] validates them.
     */
    private fun readerRequestFrom(intent: Intent?): ReaderRequest? = ReaderRequest.of(
        url = intent?.getStringExtra(EXTRA_READER_URL),
        sharedText = intent?.getStringExtra(EXTRA_READER_SHARED_TEXT),
    )

    private fun Bundle.pendingReaderRequest(): ReaderRequest? = ReaderRequest.of(
        url = getString(STATE_PENDING_READER_URL),
        sharedText = getString(STATE_PENDING_READER_TEXT),
    )

    companion object {
        /** The link ShareIntentActivity found in a share, to open in reader mode. */
        const val EXTRA_READER_URL = "app.marmalade.tts.extra.READER_URL"

        /** The full share payload the link came from; the reader's read-as-is fallback. */
        const val EXTRA_READER_SHARED_TEXT = "app.marmalade.tts.extra.READER_SHARED_TEXT"

        /** Saved-state keys for a reader request not yet handled when we were recreated. */
        private const val STATE_PENDING_READER_URL = "pending_reader_url"
        private const val STATE_PENDING_READER_TEXT = "pending_reader_text"
    }
}
