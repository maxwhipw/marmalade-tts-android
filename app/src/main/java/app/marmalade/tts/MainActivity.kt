package app.marmalade.tts

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.tts.ui.AppRoot
import app.marmalade.tts.ui.AppRootViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                AppRoot(viewModel = rootVm)
            }
        }
    }
}
