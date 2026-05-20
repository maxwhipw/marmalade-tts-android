package app.marmalade.tts.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Marmalade TTS app theme.
 *
 * Uses Material You dynamic colors on Android 12+, falling back to the
 * hand-tuned marmalade-orange palette on older devices (minSdk=28).
 */
@Composable
fun MarmaladeTtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MarmaladeDarkColors
        else -> MarmaladeLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MarmaladeTtsShapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
