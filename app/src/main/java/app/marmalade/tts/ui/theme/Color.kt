package app.marmalade.tts.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// =============================================================================
// Marmalade Orange Palette
// Matches the palette in marmalade-android for visual consistency.
// =============================================================================

val MarmaladeLightColors = lightColorScheme(
    primary = Color(0xFFF97316),            // Orange 500
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFED7AA),    // Orange 200
    onPrimaryContainer = Color(0xFF4A1808),  // Rich dark brown
    secondary = Color(0xFFEA580C),           // Orange 600
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF7ED),  // Orange 50
    onSecondaryContainer = Color(0xFF7C2D12),
    tertiary = Color(0xFFC2410C),            // Orange 700
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFED7AA),
    onTertiaryContainer = Color(0xFF7C2D12),
    surface = Color(0xFFFFF7ED),             // Orange 50
    onSurface = Color(0xFF1C1917),           // Stone 900
    surfaceVariant = Color(0xFFFFEDD5),      // Orange 100
    onSurfaceVariant = Color(0xFF57534E),    // Stone 600
    background = Color(0xFFFFF7ED),          // Orange 50
    onBackground = Color(0xFF1C1917),        // Stone 900
    error = Color(0xFFDC2626),               // Red 600
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFFA8A29E),             // Stone 400
    outlineVariant = Color(0xFFD6D3D1),      // Stone 300
    inverseSurface = Color(0xFF1C1917),
    inverseOnSurface = Color(0xFFFAFAF9),
    inversePrimary = Color(0xFFFB923C),      // Orange 400
    surfaceTint = Color(0xFFF97316),
)

val MarmaladeDarkColors = darkColorScheme(
    primary = Color(0xFFFB923C),             // Orange 400
    onPrimary = Color(0xFF1C1917),           // Stone 900
    primaryContainer = Color(0xFF9A3412),    // Orange 800
    onPrimaryContainer = Color(0xFFFFFBF7),
    secondary = Color(0xFFF97316),           // Orange 500
    onSecondary = Color(0xFF1C1917),
    secondaryContainer = Color(0xFF7C2D12),  // Orange 900
    onSecondaryContainer = Color(0xFFFED7AA),
    tertiary = Color(0xFFFDBA74),            // Orange 300
    onTertiary = Color(0xFF1C1917),
    tertiaryContainer = Color(0xFF9A3412),
    onTertiaryContainer = Color(0xFFFED7AA),
    surface = Color(0xFF262626),
    onSurface = Color(0xFFFAFAF9),           // Stone 50
    surfaceVariant = Color(0xFF3D3D3D),
    onSurfaceVariant = Color(0xFFD6D3D1),    // Stone 300
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFFAFAF9),        // Stone 50
    error = Color(0xFFEF4444),
    onError = Color(0xFF1A1A1A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    outline = Color(0xFF737373),
    outlineVariant = Color(0xFF3D3D3D),
    inverseSurface = Color(0xFFFAFAF9),
    inverseOnSurface = Color(0xFF1C1917),
    inversePrimary = Color(0xFFC2410C),      // Orange 700
    surfaceTint = Color(0xFFFB923C),
)

// =============================================================================
// Theme Presets
// =============================================================================

/**
 * Curated theme presets. [SYSTEM] uses Material You dynamic colors from the
 * device wallpaper on Android 12+. [MARMALADE] uses the hand-tuned orange
 * palette above. Other presets override the primary color family while
 * keeping the warm-stone surface tones from the Marmalade base scheme.
 *
 * Mirrors the marmalade-android ThemePreset enum so the two apps stay in
 * lockstep visually. Chat-specific tokens are intentionally absent — this
 * is a TTS app and doesn't need user-bubble / avatar colors.
 */
enum class ThemePreset(
    val displayName: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val lightPrimaryContainer: Color,
    val darkPrimaryContainer: Color,
) {
    SYSTEM("System", Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified),
    MARMALADE("Marmalade", Color(0xFFF97316), Color(0xFFFB923C), Color(0xFFFED7AA), Color(0xFF9A3412)),
    MIDNIGHT("Midnight", Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFFDBEAFE), Color(0xFF1E3A5F)),
    FOREST("Forest", Color(0xFF16A34A), Color(0xFF4ADE80), Color(0xFFDCFCE7), Color(0xFF14532D)),
    BERRY("Berry", Color(0xFFDC2626), Color(0xFFF87171), Color(0xFFFEE2E2), Color(0xFF7F1D1D)),
    ;

    companion object {
        /** Tolerant parser used when reading the persisted preset string. */
        fun fromString(name: String): ThemePreset =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Build a color scheme for a curated preset by overlaying its primary family
 * onto the supplied base scheme. Surface / background tones come from the
 * base — only primary-family roles get swapped — so every preset keeps the
 * same warm-stone look.
 */
fun buildPresetScheme(base: ColorScheme, preset: ThemePreset, isDark: Boolean): ColorScheme {
    val primary = if (isDark) preset.darkPrimary else preset.lightPrimary
    val primaryContainer = if (isDark) preset.darkPrimaryContainer else preset.lightPrimaryContainer
    val onPrimary = if (primary.luminance() < 0.5f) Color.White else Color(0xFF1C1917)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = if (primaryContainer.luminance() < 0.5f) Color.White else Color(0xFF1C1917),
        surfaceTint = primary,
        inversePrimary = if (isDark) preset.lightPrimary else preset.darkPrimary,
    )
}
