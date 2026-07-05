package app.marmalade.tts.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Color for the "marmalade" wordmark, mode-aware per
 * marmalade-design-scheme-v0: orange in light, cream in dark — bright
 * orange on a dark surface is an explicit anti-pattern. Non-Marmalade
 * presets fall back to the scheme primary (same rule as
 * marmalade-android's extended-color fallback). Provided by
 * [MarmaladeTtsTheme]; the default matches the light-mode Marmalade value
 * so previews outside the theme still render sanely.
 */
val LocalWordmarkColor = staticCompositionLocalOf { Color(0xFFF97316) }

/**
 * Resolve whether dark theme should be active based on user preference.
 *
 * Pure function — testable without an Android context.
 * - "light"  -> false (always light)
 * - "dark"   -> true  (always dark)
 * - anything else (including "system", "", unknown) -> defer to the system
 *
 * Wired into [app.marmalade.tts.MainActivity], which reads the persisted
 * `themeMode` and the platform `isSystemInDarkTheme()` then passes the
 * resolved boolean into [MarmaladeTtsTheme]. Settings → Appearance → Mode
 * is what flips the persisted value between "system" / "light" / "dark".
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

    val wordmarkColor = when (themePreset) {
        // The brand palettes keep the locked wordmark tokens: Orange
        // #F97316 light / Cream #FFEDD5 dark. SYSTEM pre-12 falls back to
        // the Marmalade scheme, so it gets the brand tokens too on the
        // dynamic-color path below only when Material You is active.
        ThemePreset.MARMALADE -> if (darkTheme) Color(0xFFFFEDD5) else Color(0xFFF97316)
        ThemePreset.SYSTEM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                colorScheme.primary
            } else {
                if (darkTheme) Color(0xFFFFEDD5) else Color(0xFFF97316)
            }
        else -> colorScheme.primary
    }

    CompositionLocalProvider(LocalWordmarkColor provides wordmarkColor) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = MarmaladeTtsShapes,
            typography = MarmaladeTypography,
            content = content,
        )
    }
}
