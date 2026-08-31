package app.marmalade.tts.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.ui.MarmaladeFilterChip

// -----------------------------------------------------------------------------
// Reading-speed sheet — one row of chips, and a line saying it won't stick.
//
// The values are FACTORS on the voice's own speed, not absolute rates: the
// user's primary alias carries a speed tuned for that voice (several of the
// bundled voices want 0.84 or so), and "1.25×" has to mean a quarter faster
// than that voice normally is. See ReaderPlaybackController.setSpeedMultiplier.
//
// Session-scoped by design (Max's second UX pass): this is how fast you want
// THIS article read, not a setting. Nothing here touches SettingsRepository.
// -----------------------------------------------------------------------------

/** The offered speeds. Curated — the controller clamps a wider range. */
private val SPEED_CHOICES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSpeedSheet(
    speedMultiplier: Float,
    onSpeedChange: (Float) -> Unit,
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
                text = stringResource(R.string.reader_speed_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                for (speed in SPEED_CHOICES) {
                    MarmaladeFilterChip(
                        selected = speed == speedMultiplier,
                        onClick = { onSpeedChange(speed) },
                        label = {
                            Text(stringResource(R.string.reader_speed_value, formatSpeed(speed)))
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.reader_speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** "1", "1.25" — a whole number loses its ".0" so the chips stay narrow. */
private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
