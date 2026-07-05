@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.marmalade.tts.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.marmalade.tts.R

// =============================================================================
// Marmalade typography — Manrope (body/headings), Fredoka (wordmark fallback
// for Momo Trust Display, which isn't freely distributable). Weights are
// derived from the variable fonts' `wght` axis. Tokens locked in
// marmalade-design-scheme-v0 (2026-06-21); mirrors marmalade-android's
// Type.kt minus Space Mono (this app has no code/mono surfaces).
// =============================================================================

private fun manrope(weight: FontWeight) = Font(
    R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Body + headings. */
val Manrope = FontFamily(
    manrope(FontWeight.Normal),   // 400
    manrope(FontWeight.Medium),   // 500
    manrope(FontWeight.SemiBold), // 600
    manrope(FontWeight.Bold),     // 700
)

/** Wordmark "marmalade" — Fredoka 600 (Momo Trust Display fallback). Always
 *  rendered lowercase, tracking 0. */
val Wordmark = FontFamily(
    Font(
        R.font.fredoka_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

/**
 * Material 3 typography with Manrope as the family across the board. Sizes
 * follow the scheme: body 14sp/400, H1 (headlineSmall) 26sp/600, H2
 * (titleLarge) 17sp/600, all headings tracking −0.3sp, normal case.
 */
val MarmaladeTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Manrope),
        displayMedium = displayMedium.copy(fontFamily = Manrope),
        displaySmall = displaySmall.copy(fontFamily = Manrope),
        headlineLarge = headlineLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineMedium = headlineMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(
            fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp, letterSpacing = (-0.3).sp,
        ),
        titleLarge = titleLarge.copy(
            fontFamily = Manrope, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, letterSpacing = (-0.3).sp,
        ),
        titleMedium = titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = Manrope),
        bodyMedium = bodyMedium.copy(fontFamily = Manrope, fontSize = 14.sp),
        bodySmall = bodySmall.copy(fontFamily = Manrope),
        labelLarge = labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Medium),
    )
}
