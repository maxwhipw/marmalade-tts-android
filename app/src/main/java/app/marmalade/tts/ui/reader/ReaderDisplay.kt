package app.marmalade.tts.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// -----------------------------------------------------------------------------
// Reader display preferences — background preset, font family, text size.
//
//   SettingsRepository (raw strings/int, nullable = "never set")
//     │
//     ▼
//   ReaderDisplayPrefs ── resolve(appIsDark) ──► ReaderBackground ──► ReaderPalette
//     │                                                                   │
//     └── textScale ─► headings + body scale off the same factor          │
//                                                                         ▼
//                                             ReaderScreen paints the article
//                                             surface with these colors INSTEAD
//                                             of the app theme, so a light-app
//                                             user can still read on black.
//
// Everything here is plain Kotlin (no composables) so the preset→color mapping
// and the size/scale arithmetic are unit-testable off-UI.
// -----------------------------------------------------------------------------

/** Reading-surface background presets. Three, deliberately — see step G. */
enum class ReaderBackground { White, Black, Paper }

/** Reading-surface font families. Sans is the app's own face (Manrope). */
enum class ReaderFont { Sans, Serif, Mono }

/**
 * Colors for one [ReaderBackground].
 *
 * [highlight] is the current-block tint. It is a shifted version of the
 * background rather than an accent color: the highlight moves on every block
 * boundary, and an accent-strength bar sweeping down the page is a distraction
 * while reading. Each one is picked to stay visible against its own background
 * without reading as neon.
 */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val muted: Color,
    val highlight: Color,
)

/**
 * The user's reading-surface settings.
 *
 * [background] is nullable on purpose: until the user picks one, the reader
 * follows the app's own light/dark (see [resolveBackground]). Storing "unset"
 * rather than baking a value in at first read means a user who later flips the
 * app to dark gets a dark reader too, instead of a white page they never chose.
 */
data class ReaderDisplayPrefs(
    val background: ReaderBackground? = null,
    val font: ReaderFont = ReaderFont.Sans,
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
) {
    /**
     * Multiplier applied to every text size on the reading surface, so
     * headings grow and shrink with the body rather than staying put.
     */
    val textScale: Float get() = fontSizeSp / DEFAULT_FONT_SIZE_SP.toFloat()

    /** The chosen preset, or the one matching the app's brightness. */
    fun resolveBackground(appIsDark: Boolean): ReaderBackground =
        background ?: if (appIsDark) ReaderBackground.Black else ReaderBackground.White

    companion object {
        /** Matches `MarmaladeTypography.bodyLarge` — the reader's body size. */
        const val DEFAULT_FONT_SIZE_SP = 16

        /** Below this, the article stops being comfortable to read. */
        const val MIN_FONT_SIZE_SP = 14

        /** Above this, a phone-width column holds too few words per line. */
        const val MAX_FONT_SIZE_SP = 26

        /** One tap of the stepper. */
        const val FONT_SIZE_STEP_SP = 2
    }
}

/** Colors for this preset. */
fun ReaderBackground.palette(): ReaderPalette = when (this) {
    ReaderBackground.White -> ReaderPalette(
        background = Color(0xFFFFFFFF),
        text = Color(0xFF1A1A1A),
        muted = Color(0xFF5F5F5F),
        highlight = Color(0xFFFFE7C2),
    )
    ReaderBackground.Black -> ReaderPalette(
        background = Color(0xFF101010),
        text = Color(0xFFE8E4DC),
        muted = Color(0xFF9A958C),
        highlight = Color(0xFF322B20),
    )
    ReaderBackground.Paper -> ReaderPalette(
        background = Color(0xFFF4ECD8),
        text = Color(0xFF3B3226),
        muted = Color(0xFF6E6152),
        highlight = Color(0xFFE3D2AA),
    )
}

/** `null` keeps the theme default — Sans is the app's own Manrope. */
fun ReaderFont.fontFamily(): FontFamily? = when (this) {
    ReaderFont.Sans -> null
    ReaderFont.Serif -> FontFamily.Serif
    ReaderFont.Mono -> FontFamily.Monospace
}

/** Parse a stored [ReaderBackground] name; unknown/absent means "unset". */
fun readerBackgroundOf(name: String?): ReaderBackground? =
    ReaderBackground.entries.firstOrNull { it.name == name }

/** Parse a stored [ReaderFont] name, falling back to [ReaderFont.Sans]. */
fun readerFontOf(name: String?): ReaderFont =
    ReaderFont.entries.firstOrNull { it.name == name } ?: ReaderFont.Sans

/** Clamp a stored or stepped size into the supported range. */
fun clampReaderFontSize(sp: Int): Int = sp.coerceIn(
    ReaderDisplayPrefs.MIN_FONT_SIZE_SP,
    ReaderDisplayPrefs.MAX_FONT_SIZE_SP,
)
