package app.marmalade.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.marmalade.tts.ui.AppRoot
import app.marmalade.tts.ui.theme.MarmaladeTtsTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host for the Compose UI. `@AndroidEntryPoint` plumbs
 * Hilt through to every `hiltViewModel()` call in the screen composables.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarmaladeTtsTheme {
                AppRoot()
            }
        }
    }
}
