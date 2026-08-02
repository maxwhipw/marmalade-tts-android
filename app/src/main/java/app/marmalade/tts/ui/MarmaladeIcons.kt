package app.marmalade.tts.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icons drawn in-tree rather than pulled from `material-icons-extended`.
 *
 * The nav needed a speaker-with-waves and an equalizer, and the cloud
 * marker needed a cloud — none of which are in `material-icons-core`,
 * which is all `material3` brings in. Adding the extended artifact for
 * three glyphs would pull the whole Material catalogue into the build
 * just to have R8 throw ~99% of it away again.
 *
 * The path data is lifted verbatim from `docs/design/speak-screen-lab.html`,
 * so what shipped and what was signed off are the same drawing. If the lab
 * changes, change these with it.
 */
object MarmaladeIcons {

    /** Speaker with two sound waves — the Speak tab. */
    val Speak: ImageVector by lazy {
        filled24(
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3a4.5 4.5 0 0 0-2.5-4v8a4.5 4.5 0 0 0 " +
                "2.5-4zM14 3.2v2.06a6.99 6.99 0 0 1 0 13.48v2.06a9 9 0 0 0 0-17.6z",
        )
    }

    /**
     * Cloud — marks a voice that needs the network.
     *
     * Deliberately has no on-device counterpart: local is the default and
     * the overwhelming majority, so marking it too would put a glyph on
     * every row and communicate nothing. Absence is the signal.
     */
    val Cloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "MarmaladeCloud",
            defaultWidth = 24.dp,
            defaultHeight = 17.dp,
            viewportWidth = 24f,
            viewportHeight = 17f,
        ).addFilled(
            "M19.35 7.04A7.49 7.49 0 0 0 12 1a7.48 7.48 0 0 0-6.63 4.04A5.99 " +
                "5.99 0 0 0 6 17h13a4.99 4.99 0 0 0 .35-9.96z",
        ).build()
    }

    /**
     * Three faders with their knobs at different positions — the Effects
     * tab. A star said "favourites", which is what it was previously
     * mistaken for; a mixer strip says "this changes how it sounds".
     */
    val Effects: ImageVector by lazy {
        ImageVector.Builder(
            name = "MarmaladeEffects",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Rails, drawn as strokes so they stay hairline at any size.
            addPath(
                pathData = addPathNodes("M4 7h9M17 7h3M4 12h3M11 12h9M4 17h13M21 17h0"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            )
            // Knobs. Filled so they read as solid at 22 dp, where a stroked
            // circle turns into a grey smudge.
            addFilled("M15 5a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
            addFilled("M9 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
            addFilled("M19 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
        }.build()
    }

    /**
     * Bug (material `bug_report` outline) — the Settings "Report a bug"
     * row. Not in `material-icons-core`, same story as the nav glyphs.
     */
    val Bug: ImageVector by lazy {
        filled24(
            "M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 " +
                "2.17C12.96 5.06 12.49 5 12 5c-.49 0-.96.06-1.41.17L8.41 3 " +
                "7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33" +
                "-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 " +
                "2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09" +
                "-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0" +
                "-4h-4v-2h4v2z",
        )
    }

    /**
     * Document with text lines (material `description` filled) — the
     * Settings "Open-source licenses" row. Not in `material-icons-core`,
     * same story as the nav glyphs.
     */
    val LicenseDoc: ImageVector by lazy {
        filled24(
            "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2h12.01c1.1 " +
                "0 1.99-.9 1.99-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V" +
                "3.5L18.5 9H13z",
        )
    }

    private fun filled24(pathData: String): ImageVector =
        ImageVector.Builder(
            name = "MarmaladeIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addFilled(pathData).build()

    /**
     * Fills with black, not with the theme colour: `Icon` tints the whole
     * vector with `LocalContentColor`, so the authored colour only has to
     * be opaque. Using the theme here would double-tint.
     */
    private fun ImageVector.Builder.addFilled(pathData: String) = apply {
        addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
    }
}
