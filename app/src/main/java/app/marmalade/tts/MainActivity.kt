package app.marmalade.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.marmalade.tts.ui.AppRoot
import app.marmalade.tts.ui.theme.MarmaladeTtsTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host for the Compose UI.
 *
 * @AndroidEntryPoint makes Hilt furnish `defaultViewModelProviderFactory`
 * with a `HiltViewModelFactory`, so the `viewModel<T>()` calls in the
 * screen composables resolve their @HiltViewModel-annotated ViewModels
 * without needing the `hilt-navigation-compose` artifact (not present
 * in the build's offline cache).
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
