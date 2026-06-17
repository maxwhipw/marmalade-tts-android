package app.marmalade.tts.pro

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

// -----------------------------------------------------------------------------
// Pro entitlement — single source of truth for "user has unlocked Pro features"
// -----------------------------------------------------------------------------
//
// Two implementations live under flavor source sets:
//
//   - `src/fdroid/.../FdroidProEntitlement` : isPro is always true. The
//     F-Droid build has no concept of a paywall, so callers can collect
//     [isPro] and gate UI as normal without flavor-specific code paths.
//
//   - `src/play/.../PlayProEntitlement` : wraps Google Play Billing.
//     `marmalade_pro` is a one-time INAPP product (USD 3.99). [isPro]
//     reflects Play's authoritative answer cached in DataStore for
//     offline launches; [launchPurchase] starts the Play flow;
//     [restorePurchases] re-queries Play for the on-device account.
//
// The Hilt module binding lives next to each impl (see
// `src/<flavor>/.../ProEntitlementModule.kt`), so the dependency graph
// for the F-Droid APK never includes the BillingClient class.
//
// See docs/release/PAYWALL-PLAN.md for the full design.
// -----------------------------------------------------------------------------

interface ProEntitlement {

    /**
     * `true` when the user has unlocked Pro features.
     *
     * - F-Droid: always `true` (every feature is free).
     * - Play: `true` after a successful `marmalade_pro` purchase has
     *   been observed AND most-recently-verified with Play; `false`
     *   when the user is still on the free tier or a previous purchase
     *   was refunded. Cached to DataStore so offline launches stay
     *   unlocked; re-verified on every foreground (`restorePurchases`).
     */
    val isPro: StateFlow<Boolean>

    /**
     * Kick off the Pro purchase flow. No-op in the F-Droid flavor.
     * Returns when Play's UI has dismissed (success, cancel, or
     * pending — Play decides which). [isPro] updates asynchronously
     * through the same `onPurchasesUpdated` path that `restorePurchases`
     * uses, so callers should observe the flow rather than treating
     * the [PurchaseResult] as the final word.
     */
    suspend fun launchPurchase(activity: Activity): PurchaseResult

    /**
     * Force a re-query of Play's view of the user's purchases. Called
     * on app foreground to keep the cached entitlement honest, and
     * exposed from the paywall sheet + Settings → About as a "Restore
     * purchases" button (Play policy requires it). No-op in the
     * F-Droid flavor.
     */
    suspend fun restorePurchases()
}

sealed interface PurchaseResult {
    /** Purchase completed and acknowledged — [ProEntitlement.isPro] should already be true. */
    data object Success : PurchaseResult
    /** Purchase is pending Play's review (e.g. gift card balance check). */
    data object Pending : PurchaseResult
    /** User dismissed the Play sheet without buying. */
    data object Cancelled : PurchaseResult
    /** Anything else: billing unavailable, network error, region-restricted, etc. */
    data class Error(val message: String) : PurchaseResult
    /** F-Droid flavor — purchase doesn't apply because every feature is already free. */
    data object NotApplicable : PurchaseResult
}
