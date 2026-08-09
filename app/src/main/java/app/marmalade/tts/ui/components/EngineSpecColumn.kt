package app.marmalade.tts.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
// Colour: the four-segment meter is a traffic light keyed to fill count —
// green (4) → light green (3) → amber (2) → red (1) — so a fuller bar is both
// longer and cooler, and the fastest engine reads as a win while the heaviest
// reads as a cost at a glance (an all-amber meter made even "Fastest" look
// slow). The shades are hand-picked per mode (see [speedMeterColor]) rather
// than pulled from the accent, so they stay legible under every theme preset
// and never spend the brand orange, which the Install CTA keeps precious. The
// tier word beside the meter still names the tier for anyone who can't lean
// on colour alone.
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
        verticalArrangement = Arrangement.Top,
    ) {
        SpecRow(label = stringResource(R.string.engine_spec_speed)) {
            // Meter only — the tier word ("Fastest"/"Fast"/"Heavy") is dropped
            // from the card and carried as the meter's contentDescription, so
            // the visual stays a bare gauge while TalkBack still names the tier
            // for anyone who can't read fill length or colour.
            val tierName = stringResource(speedTierLabelRes(engine.speedTier))
            SpeedMeter(
                tier = engine.speedTier,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .semantics { contentDescription = tierName },
            )
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
 * name. For a multi-language engine (Kokoro) it's a "See all <n>" action
 * (leading info glyph, trailing chevron) that opens a dialog listing which
 * languages — styled and phrased as an action so it plainly reads as tappable,
 * with a full-width ~44 dp touch target rather than the old text-sized sliver.
 * The whole Row is one TalkBack button: the visible "See all <n>" is its name
 * and the "show languages" label rides along; the two glyphs are decorative.
 * The "LANGUAGES" overline directly above supplies the noun the action drops.
 */
@Composable
private fun EngineLanguagesValue(languageCodes: List<String>) {
    if (languageCodes.size <= 1) {
        SpecValueText(text = stringResource(languageNameRes(languageCodes.firstOrNull() ?: "en")))
        return
    }

    var showDialog by remember { mutableStateOf(false) }
    val openLabel = stringResource(R.string.engine_languages_info_cd)
    // Primary/orange, matching the app's other in-app "link" actions (the
    // Engines-tab "Show more"); it's the tap cue. A single small action doesn't
    // flood the card the way a column of accent-filled meters would.
    val tint = MaterialTheme.colorScheme.primary
    // A 40 dp tap target kept centred on the text, but shifted up by its own
    // top padding so the value sits tight under its label like every other spec
    // value. Padding alone pushed "See all 9" 12 dp lower than Fastest/Natural,
    // which read as a hole in the column; dropping the padding instead would
    // have shrunk the target. The offset moves hit-testing too, so the top half
    // of the target simply overlaps the inert "LANGUAGES" overline.
    // No horizontal inset: that pushed the value right of the other values and
    // broke the column's left edge.
    Box(
        modifier = Modifier
            .offset(y = -LanguagesTapPadding)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = openLabel, role = Role.Button) { showDialog = true }
            .fillMaxWidth()
            .padding(vertical = LanguagesTapPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.engine_languages_see_all, languageCodes.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
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

/**
 * The four-segment speed meter. Filled segments take a traffic-light colour
 * keyed to how many are filled (green → light green → amber → red as the bar
 * shortens); empties fall to the neutral outline. Fill count comes from
 * [tier] — see [SpeedTier] and [speedMeterColor].
 */
@Composable
fun SpeedMeter(
    tier: SpeedTier,
    modifier: Modifier = Modifier,
    segments: Int = 4,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fill = tier.meterFill
    val on = speedMeterColor(fill, isDark)
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

/**
 * Traffic-light fill colour keyed to how many meter segments are lit: a fuller,
 * faster bar is cooler (green) and a shorter, heavier one is hotter (red). Both
 * greens and the amber shift a step between the two tiers so 4 vs 3 stays
 * readable. Shades are picked per mode so the meter is legible on both the cream
 * light surface and the stone dark one, and are independent of the accent preset
 * (a semantic green/amber/red should not shift when the user picks Berry or
 * Forest). The red matches the scheme's error red.
 */
private fun speedMeterColor(fill: Int, isDark: Boolean): Color = when (fill) {
    4 -> if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)     // green      400 / 600
    3 -> if (isDark) Color(0xFFA3E635) else Color(0xFF65A30D)     // light green (lime) 400 / 600
    2 -> if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)     // amber      400 / 600
    else -> if (isDark) Color(0xFFEF4444) else Color(0xFFDC2626)  // red        500 / 600
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

/**
 * Vertical padding that grows the languages value's tap target, and equally the
 * distance it is pulled back up so the text still aligns with the other spec
 * values. Half of it overhangs the "LANGUAGES" overline above.
 */
private val LanguagesTapPadding = 12.dp
