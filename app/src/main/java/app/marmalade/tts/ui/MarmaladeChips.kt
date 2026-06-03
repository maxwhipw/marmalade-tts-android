package app.marmalade.tts.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * App-wide FilterChip with consistent fill-only styling — no borders.
 *
 * Unselected: `surfaceVariant` — a soft neutral pastel that sits clearly
 * against the peach Marmalade light surface (and the dark counterpart in
 * dark mode) without needing an outline.
 *
 * Selected: `primaryContainer` — the vibrant orange tint that reads
 * unambiguously as "this is the active choice."
 *
 * No border on either state — the fill alone carries the chip shape,
 * which gives a cleaner UI than the default M3 outline-when-unselected
 * mix. Every FilterChip in the app should go through this helper so
 * the selected/unselected states are consistent everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarmaladeFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = null,
    )
}

/**
 * Convenience wrapper that supplies a leading person/icon when [selected].
 * Mirrors the SpeakScreen alias-chip pattern.
 */
@Composable
fun MarmaladeFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIconWhenSelected: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MarmaladeFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = leadingIconWhenSelected,
                    contentDescription = null,
                    modifier = Modifier.size(androidx.compose.material3.FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}
