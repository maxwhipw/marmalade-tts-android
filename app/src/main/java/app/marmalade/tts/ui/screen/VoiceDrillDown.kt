package app.marmalade.tts.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.data.LatencyBucket
import app.marmalade.tts.data.latencyKeyFor

// -----------------------------------------------------------------------------
// The voice drill-down: shared level-1 and level-2 lists
// -----------------------------------------------------------------------------
// The source and model lists are identical on every voice-browsing surface, so
// they live here. The LEAF rows are not shared: the alias sheet's voice row is
// a name and a checkmark, while the full-screen picker adds a gender glyph and
// a preview button. Forcing one row to serve both would mean a row with four
// optional slots, which is worse than two short composables.
//
// [horizontalPadding] differs because a bottom sheet and a full screen have
// different gutters (24dp vs 16dp); everything else is identical.
// -----------------------------------------------------------------------------

@Composable
fun VoiceSourceList(
    tree: List<VoiceSource>,
    latency: Map<String, LatencyBucket>,
    horizontalPadding: Dp,
    onSelect: (String) -> Unit,
) {
    LazyColumn {
        items(items = tree, key = { it.name }) { source ->
            val kind = stringResource(
                if (source.isCloud) {
                    R.string.voices_source_cloud
                } else {
                    R.string.voices_source_on_device
                },
            )
            val counted = pluralStringResource(
                R.plurals.voices_source_voice_count,
                source.voiceCount,
                kind,
                source.voiceCount,
            )
            VoiceDrillRow(
                title = source.name,
                // Only worth naming the model count when there's a choice to
                // make at the next level.
                subtitle = if (source.models.size > 1) {
                    stringResource(R.string.voices_source_with_models, counted, source.models.size)
                } else {
                    counted
                },
                badge = source.latencyBucket(latency),
                horizontalPadding = horizontalPadding,
                onClick = { onSelect(source.name) },
            )
        }
    }
}

@Composable
fun VoiceModelList(
    source: VoiceSource,
    latency: Map<String, LatencyBucket>,
    horizontalPadding: Dp,
    onSelect: (String) -> Unit,
) {
    LazyColumn {
        items(items = source.models, key = { it.name }) { model ->
            VoiceDrillRow(
                title = model.name,
                subtitle = pluralStringResource(
                    R.plurals.voices_model_voice_count,
                    model.voices.size,
                    model.voices.size,
                ),
                badge = model.latencyBucket(latency),
                horizontalPadding = horizontalPadding,
                onClick = { onSelect(model.name) },
            )
        }
    }
}

/**
 * Latency is a property of the model, so a model row always has one and a
 * *source* row only has one when every model beneath it agrees — Venice
 * fronts an instant Kokoro and a painfully slow Gemini, and averaging those
 * into a single badge on the Venice row would be worse than saying nothing.
 */
fun VoiceModel.latencyBucket(latency: Map<String, LatencyBucket>): LatencyBucket? {
    val voice = voices.firstOrNull() ?: return null
    return latency[latencyKeyFor(voice.id, voice.engine)]
}

fun VoiceSource.latencyBucket(latency: Map<String, LatencyBucket>): LatencyBucket? =
    models.map { it.latencyBucket(latency) }.distinct().singleOrNull()

@Composable
fun VoiceDrillRow(
    title: String,
    subtitle: String,
    badge: LatencyBucket?,
    horizontalPadding: Dp,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VoiceLatencyChip(badge)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How long this thing makes you wait. Renders nothing for a null [bucket] —
 * a model nobody has measured and the descriptor doesn't describe gets no
 * badge, because an empty space is honest and a guess isn't.
 *
 * Only "Instant" is filled. The point of the badge is to make the fast
 * choice findable in a list where everything else costs a network round
 * trip; giving "Slow" the same visual weight would just make the list
 * noisier without making the decision easier.
 */
@Composable
fun VoiceLatencyChip(bucket: LatencyBucket?) {
    bucket ?: return
    val filled = bucket == LatencyBucket.INSTANT
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (filled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        border = if (filled) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        },
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            text = stringResource(bucket.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

/** Shared empty/status line for both picker surfaces. */
@Composable
fun VoicePickerEmpty(text: String, horizontalPadding: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 14.dp),
    )
}
