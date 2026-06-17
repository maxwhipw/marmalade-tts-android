package app.marmalade.tts.ui.screen

import androidx.compose.runtime.Composable

/**
 * Play flavor: deliberately empty. The donate link is F-Droid-only per
 * docs/release/PAYWALL-PLAN.md — Google's "alternative billing" policy
 * is unclear on voluntary OSS donations, and the safest first-listing
 * posture is no donate link at all in the Play build. Revisit after
 * Play approval if a donate link makes sense.
 */
@Composable
internal fun AboutExtras() {
    // intentionally empty
}
