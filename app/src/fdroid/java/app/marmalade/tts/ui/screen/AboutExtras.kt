package app.marmalade.tts.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * F-Droid flavor: a "Support development" link to GitHub Sponsors,
 * rendered as a second link row in the About section. Play flavor's
 * symmetric file is intentionally empty — the donate link is omitted
 * from the Play build per the internal release notes (safe posture
 * for first-listing; Google's "alternative billing" policy treats
 * voluntary OSS donations as a gray area).
 *
 * Disclosure for donors lives at NOTICE.md + the README FAQ: donations
 * are not tax-deductible because Marmalade isn't a registered
 * 501(c)(3); the channel is GitHub Sponsors (Stripe-backed).
 */
@Composable
internal fun AboutExtras() {
    AboutLinkRow(
        label = "Support development",
        supporting = "Donate via GitHub Sponsors. Voluntary and not tax-deductible.",
        url = "https://github.com/sponsors/maxwhipw",
        leading = {
            // A vector heart (not an emoji), tinted primary so the row pops.
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}
