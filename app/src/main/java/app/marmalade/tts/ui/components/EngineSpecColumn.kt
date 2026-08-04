package app.marmalade.tts.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.install.EngineDescriptor
import app.marmalade.tts.install.QualityTier
import app.marmalade.tts.install.SpeedTier

// -----------------------------------------------------------------------------
// A3 "spec columns" — shared engine-card chrome
// -----------------------------------------------------------------------------
// The fixed right-hand stat column that both the onboarding engine picker
// (OnboardingScreen) and the Engines tab (EnginesScreen) hang off their card.
// It stacks Speed / Quality / Languages so the three engines compare
// axis-to-axis straight down the list. Speed is the hero: a tier word plus a
// three-segment meter.
//
// Colour: the meter fills from MaterialTheme.colorScheme.tertiary — the warm
// amber/peach family, mode-aware and stable across the curated theme presets —
// NOT primary. The brand keeps orange precious for the Install CTA, so a whole
// column of filled meters must never flood the screen with accent. Tiers are
// distinguished by fill + label, never by traffic-light colour, so "Heavy"
// (Pocket) never reads as a failure.
// -----------------------------------------------------------------------------

/**
 * The three stacked spec rows (Speed / Quality / Languages) for [engine].
 * Fixed width so the values line up column-to-column down a list of cards.
 */
@Composable
fun EngineSpecColumn(
    engine: EngineDescriptor,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(118.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        SpecRow(label = stringResource(R.string.engine_spec_speed)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpecValueText(text = stringResource(speedTierLabelRes(engine.speedTier)))
                Spacer(Modifier.width(6.dp))
                SpeedMeter(fill = engine.speedTier.meterFill)
            }
        }
        SpecRow(label = stringResource(R.string.engine_spec_quality)) {
            SpecValueText(text = stringResource(qualityTierLabelRes(engine.qualityTier)))
        }
        SpecRow(label = stringResource(R.string.engine_spec_languages)) {
            EngineLanguagesValue(languageCodes = engine.languageCodes)
        }
    }
}

/** Label above value, matching the lab's "spec sheet" overline treatment. */
@Composable
private fun SpecRow(
    label: String,
    value: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        value()
    }
}

@Composable
private fun SpecValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Languages spec value. For a single-language engine it's just the language
 * name. For a multi-language engine (Kokoro) it's "<n> languages" plus a small
 * info affordance that opens a dialog listing which languages — the whole
 * value is a TalkBack-navigable button, so the count reads as the accessible
 * name and the dialog action rides along as its click label.
 */
@Composable
private fun EngineLanguagesValue(languageCodes: List<String>) {
    if (languageCodes.size <= 1) {
        SpecValueText(text = stringResource(languageNameRes(languageCodes.firstOrNull() ?: "en")))
        return
    }

    var showDialog by remember { mutableStateOf(false) }
    val openLabel = stringResource(R.string.engine_languages_info_cd)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = openLabel, role = Role.Button) { showDialog = true }
            .padding(vertical = 4.dp, horizontal = 2.dp),
    ) {
        SpecValueText(
            text = pluralStringResource(
                R.plurals.engine_languages_count,
                languageCodes.size,
                languageCodes.size,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.Info,
            // The Row is the button; the glyph is decorative, so it carries no
            // separate description (avoids TalkBack announcing it twice).
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }

    if (showDialog) {
        EngineLanguagesDialog(languageCodes = languageCodes, onDismiss = { showDialog = false })
    }
}

@Composable
private fun EngineLanguagesDialog(
    languageCodes: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engine_languages_dialog_title)) },
        text = {
            Column {
                for (code in languageCodes) {
                    Text(
                        text = stringResource(languageNameRes(code)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.engine_languages_dialog_close))
            }
        },
    )
}

/** The three-segment speed meter. Peach/amber fill, never accent orange. */
@Composable
fun SpeedMeter(
    fill: Int,
    modifier: Modifier = Modifier,
    segments: Int = 3,
) {
    val on = MaterialTheme.colorScheme.tertiary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(segments) { i ->
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < fill) on else off),
            )
        }
    }
}

@StringRes
fun speedTierLabelRes(tier: SpeedTier): Int = when (tier) {
    SpeedTier.FASTEST -> R.string.engine_speed_fastest
    SpeedTier.FAST -> R.string.engine_speed_fast
    SpeedTier.HEAVY -> R.string.engine_speed_heavy
}

@StringRes
fun qualityTierLabelRes(tier: QualityTier): Int = when (tier) {
    QualityTier.NATURAL -> R.string.engine_quality_natural
    QualityTier.BEST_OVERALL -> R.string.engine_quality_best
    QualityTier.MOST_EXPRESSIVE -> R.string.engine_quality_expressive
}

/**
 * Localized display name for a BCP-47 language code. American and British
 * English are region-qualified so Kokoro's "9 languages" reads honestly;
 * every other entry is the language's own name. The bare `"en"` used by the
 * single-English engines falls through to the unqualified "English".
 */
@StringRes
private fun languageNameRes(code: String): Int = when (code) {
    "en-US" -> R.string.language_en_us
    "en-GB" -> R.string.language_en_gb
    "es-ES", "es" -> R.string.language_es
    "fr-FR", "fr" -> R.string.language_fr
    "it-IT", "it" -> R.string.language_it
    "hi-IN", "hi" -> R.string.language_hi
    "pt-BR", "pt" -> R.string.language_pt_br
    "ja-JP", "ja" -> R.string.language_ja
    "zh-CN", "zh" -> R.string.language_zh
    else -> R.string.language_english
}
