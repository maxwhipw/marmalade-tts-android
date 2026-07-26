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
// Marmalade typography — Manrope (body/headings), Momo Trust Display (the
// "marmalade" wordmark; Fredoka is its fallback). Manrope + Fredoka are
// variable (weights derived from the `wght` axis); Momo Trust Display ships a
// single Regular master, so its wordmark weight is synthesized — same as the
// web UI. All three are OFL-1.1 and bundled in the APK. Tokens locked in
// marmalade-design-scheme-v0 (2026-06-21); mirrors marmalade-android's Type.kt
// minus Space Mono (this app has no code/mono surfaces).
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

/** Wordmark "marmalade" — Momo Trust Display, the brand display face (matches
 *  the marmalade-agent web UI); Fredoka is the fallback, reached only if the
 *  Momo resource is ever missing. Both sit in the Normal (400) slot — Momo
 *  ships a single Regular master, so the 600 requested at the call site is
 *  synthesized, exactly as the browser faux-bolds it on the web. Momo is first
 *  in the list, so it always wins over the bundled Fredoka. Always rendered
 *  lowercase, tracking 0. */
val Wordmark = FontFamily(
    Font(R.font.momo_trust_display, weight = FontWeight.Normal),
    Font(R.font.fredoka_variable, weight = FontWeight.Normal),
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
