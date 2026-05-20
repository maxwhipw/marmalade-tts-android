package app.marmalade.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.marmalade.tts.ui.screen.PlaceholderScreen
import app.marmalade.tts.ui.theme.MarmaladeTtsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarmaladeTtsTheme {
                PlaceholderScreen()
            }
        }
    }
}
