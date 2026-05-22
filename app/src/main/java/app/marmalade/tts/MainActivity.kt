package app.marmalade.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.tts.ui.AppRoot
import app.marmalade.tts.ui.AppRootViewModel
import app.marmalade.tts.ui.theme.MarmaladeTtsTheme
import app.marmalade.tts.ui.theme.ThemePreset
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host for the Compose UI. `@AndroidEntryPoint` plumbs
 * Hilt through to every `hiltViewModel()` call in the screen composables,
 * and to the activity-scoped `viewModel<AppRootViewModel>()` we use to
 * read the theme preset before any screen renders.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val rootVm: AppRootViewModel = viewModel()
            val preset by rootVm.themePreset.collectAsStateWithLifecycle(initialValue = ThemePreset.SYSTEM)
            MarmaladeTtsTheme(themePreset = preset) {
                AppRoot(viewModel = rootVm)
            }
        }
    }
}
