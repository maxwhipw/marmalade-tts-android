package app.marmalade.tts.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
// Reading-speed sheet — a row of chips, and a line saying it won't stick.
//
// The values are ABSOLUTE speeds that override the primary alias's own speed
// for this article (voice, effect and language still come from the alias).
// Reading starts at the alias's speed; when that isn't one of the curated
// chips (an alias tuned to 1.1, say) it gets a chip of its own, in sorted
// position, so the current speed is always visibly selected. See
// ReaderPlaybackController.setSpeed.
//
// Session-scoped by design (Max's second UX pass): this is how fast you want
// THIS article read, not a setting. Nothing here touches SettingsRepository.
// -----------------------------------------------------------------------------

/** The offered speeds. Curated — the controller clamps a wider range. */
private val SPEED_CHOICES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * The chips to show while reading at [current]: the curated set, plus
 * [current] in sorted position when it isn't one of them (it came from the
 * alias's own speed), so the selected chip always exists.
 */
internal fun readerSpeedChoices(current: Float): List<Float> =
    if (current in SPEED_CHOICES) SPEED_CHOICES else (SPEED_CHOICES + current).sorted()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSpeedSheet(
    speed: Float,
    showPerfWarning: Boolean,
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
            // FlowRow, not Row: the alias's own speed can add a sixth chip,
            // which must wrap on a narrow phone rather than be clipped.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                for (choice in readerSpeedChoices(speed)) {
                    MarmaladeFilterChip(
                        selected = choice == speed,
                        onClick = { onSpeedChange(choice) },
                        label = {
                            Text(stringResource(R.string.reader_speed_value, formatSpeed(choice)))
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
            // The perf warning fires when the chosen speed outruns the
            // primary alias engine's measured/predicted RTF — the ViewModel
            // does that resolution (see ReaderViewModel.showSpeedWarning).
            // Same copy as the alias editor's slider warning.
            if (showPerfWarning) {
                // ⚠️ prefix composed here, not in the shared string, so the
                // eight translations of alias_speed_perf_warning stay untouched.
                Text(
                    text = "⚠️ " + stringResource(R.string.alias_speed_perf_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** "1", "1.25" — a whole number loses its ".0" so the chips stay narrow. */
private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
