package app.marmalade.tts.pro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid flavor: every feature is free, so the paywall never opens.
 * [ProGateHost] just renders [content] verbatim and [NoopProGate.show]
 * is a no-op (callers never reach it because [ProEntitlement.isPro]
 * is always `true` in this flavor).
 */
@Composable
fun ProGateHost(
    proGate: ProGate,
    content: @Composable () -> Unit,
) {
    // No paywall in the F-Droid flavor — the host just forwards.
    content()
}

/**
 * Provided via Hilt — see ProGateModule. Picks up the F-Droid binding
 * automatically because the Play binding lives under src/play/ only.
 */
@Singleton
class NoopProGate @Inject constructor() : ProGate {
    override fun show(reason: PaywallReason) = Unit
}
