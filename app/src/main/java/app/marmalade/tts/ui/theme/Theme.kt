package app.marmalade.tts.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Resolve whether dark theme should be active based on user preference.
 *
 * Pure function — testable without an Android context.
 * - "light"  -> false (always light)
 * - "dark"   -> true  (always dark)
 * - anything else (including "system", "", unknown) -> defer to the system
 *
 * Currently unused by the v0.1 Settings surface (which only exposes the
 * theme preset, not a light/dark/system override). Kept for parity with
 * marmalade-android so a follow-up agent can wire it up without inventing
 * a new helper.
 */
fun resolveThemeIsDark(themeMode: String, isSystemDark: Boolean): Boolean {
    return when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemDark
    }
}

/**
 * Marmalade TTS app theme with Material You dynamic colors and curated presets.
 *
 * @param darkTheme Whether to use the dark color scheme.
 * @param themePreset The selected theme preset. [ThemePreset.SYSTEM] uses
 *   Material You dynamic colors on Android 12+, falling back to the
 *   Marmalade base scheme on older devices. [ThemePreset.MARMALADE] uses
 *   the hand-tuned orange palette. Other presets overlay their primary
 *   family onto the same warm-stone base scheme.
 * @param content The composable content to render within this theme.
 */
@Composable
fun MarmaladeTtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themePreset: ThemePreset = ThemePreset.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = if (darkTheme) MarmaladeDarkColors else MarmaladeLightColors

    val colorScheme = remember(themePreset, darkTheme, context) {
        when (themePreset) {
            ThemePreset.SYSTEM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    // Pre-12 fallback — Material You isn't available, so we
                    // ship the Marmalade orange palette instead of a flat
                    // platform default.
                    baseScheme
                }
            }
            ThemePreset.MARMALADE -> baseScheme
            else -> buildPresetScheme(baseScheme, themePreset, darkTheme)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MarmaladeTtsShapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
