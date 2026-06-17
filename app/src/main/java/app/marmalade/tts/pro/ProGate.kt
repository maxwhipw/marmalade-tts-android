package app.marmalade.tts.pro

// -----------------------------------------------------------------------------
// ProGate — UI-side seam for "this Pro feature was tapped while !isPro".
//
// Free flavor (F-Droid): [show] is a no-op because isPro is always
// `true`, so callers will never reach the `!isPro` branch.
//
// Play flavor: [show] tells the paywall host to display a
// ModalBottomSheet that explains the Pro upgrade, drives a purchase
// via [ProEntitlement.launchPurchase], and surfaces a "Restore
// purchases" button.
//
// The companion `@Composable ProGateHost(proGate, content)` lives
// under each flavor source set with the same FQN — the Play copy
// renders the paywall sheet alongside [content], the F-Droid copy
// just renders [content] verbatim.
// -----------------------------------------------------------------------------

interface ProGate {
    fun show(reason: PaywallReason)
}

enum class PaywallReason {
    PerAppVoice,
    CustomEffect,
}
