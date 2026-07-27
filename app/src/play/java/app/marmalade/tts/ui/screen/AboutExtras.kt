package app.marmalade.tts.ui.screen

import androidx.compose.runtime.Composable

/**
 * Play flavor: no extra About rows.
 *
 * The F-Droid build's symmetric file carries a GitHub Sponsors link. It is
 * deliberately absent here: Google Play's payments policy is unclear on
 * out-of-app donation links for a developer who isn't a registered charity,
 * and a first listing is the wrong place to test that boundary. The link
 * lives in the README and on the repo for Play users who go looking.
 *
 * This is the only difference between the two flavors now that the Pro
 * paywall is gone — see docs/release/PAYWALL-PLAN.md for why it went.
 */
@Composable
internal fun AboutExtras() = Unit
