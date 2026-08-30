package app.marmalade.tts.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marmalade.tts.R
import app.marmalade.tts.ui.MarmaladeFilterChip

// -----------------------------------------------------------------------------
// Reader display sheet — background, font, size. Three controls, no more.
//
// Opened from the "Aa" button on the left of the reader's transport bar. The
// sheet itself stays app-themed: it's a settings surface, not the page, and
// theming it to the reading preset would make the swatches unreadable against
// the preset they sit on.
// -----------------------------------------------------------------------------

/** Diameter of a background swatch — big enough to be a comfortable tap target. */
private val SWATCH_SIZE = 44.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTypographySheet(
    prefs: ReaderDisplayPrefs,
    appIsDark: Boolean,
    onBackgroundChange: (ReaderBackground) -> Unit,
    onFontChange: (ReaderFont) -> Unit,
    onFontSizeStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_display_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )

            SectionLabel(R.string.reader_display_background)
            BackgroundSwatches(
                // An untouched preference still shows a selected swatch: the
                // reader is already rendering the theme-derived preset, so
                // showing nothing selected would misreport the page.
                selected = prefs.resolveBackground(appIsDark),
                onSelect = onBackgroundChange,
            )

            SectionLabel(R.string.reader_display_font)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (font in ReaderFont.entries) {
                    MarmaladeFilterChip(
                        selected = prefs.font == font,
                        onClick = { onFontChange(font) },
                        label = { Text(stringResource(font.labelRes())) },
                    )
                }
            }

            SectionLabel(R.string.reader_display_text_size)
            TextSizeStepper(sizeSp = prefs.fontSizeSp, onStep = onFontSizeStep)
        }
    }
}

@Composable
private fun SectionLabel(textRes: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/**
 * One circle per preset, filled with that preset's own background and ringed
 * when selected — the swatch shows what you're choosing rather than naming it
 * twice.
 */
@Composable
private fun BackgroundSwatches(
    selected: ReaderBackground,
    onSelect: (ReaderBackground) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        for (background in ReaderBackground.entries) {
            val palette = background.palette()
            val isSelected = background == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                    onClick = { onSelect(background) },
                ),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .size(SWATCH_SIZE)
                        .clip(CircleShape)
                        .background(palette.background, CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                ) {
                    // "Aa" in the preset's own text color: the swatch has to
                    // show the contrast pair, not just the paper.
                    Text(
                        text = stringResource(R.string.reader_display_sample),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.text,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(background.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Small-A / big-A rather than −/+ : the buttons show the thing they change.
 * (The app bundles material-icons-core only, which has no "remove" glyph, so
 * a −/+ pair would mean shipping two drawables for a control that reads
 * better as type anyway.)
 */
@Composable
private fun TextSizeStepper(sizeSp: Int, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(
            onClick = { onStep(-ReaderDisplayPrefs.FONT_SIZE_STEP_SP) },
            enabled = sizeSp > ReaderDisplayPrefs.MIN_FONT_SIZE_SP,
        ) {
            StepperGlyph(
                sizeSp = 13,
                contentDescription = stringResource(R.string.reader_display_text_smaller),
            )
        }
        Text(
            text = stringResource(R.string.reader_display_text_size_value, sizeSp),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        OutlinedIconButton(
            onClick = { onStep(ReaderDisplayPrefs.FONT_SIZE_STEP_SP) },
            enabled = sizeSp < ReaderDisplayPrefs.MAX_FONT_SIZE_SP,
        ) {
            StepperGlyph(
                sizeSp = 21,
                contentDescription = stringResource(R.string.reader_display_text_larger),
            )
        }
    }
}

/** A single "A" at [sizeSp], labelled for TalkBack by [contentDescription]. */
@Composable
private fun StepperGlyph(sizeSp: Int, contentDescription: String) {
    Text(
        text = stringResource(R.string.reader_display_step_glyph),
        fontSize = sizeSp.sp,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    )
}

private fun ReaderBackground.labelRes(): Int = when (this) {
    ReaderBackground.White -> R.string.reader_display_background_white
    ReaderBackground.Black -> R.string.reader_display_background_black
    ReaderBackground.Paper -> R.string.reader_display_background_paper
}

private fun ReaderFont.labelRes(): Int = when (this) {
    ReaderFont.Sans -> R.string.reader_display_font_sans
    ReaderFont.Serif -> R.string.reader_display_font_serif
    ReaderFont.Mono -> R.string.reader_display_font_mono
}
