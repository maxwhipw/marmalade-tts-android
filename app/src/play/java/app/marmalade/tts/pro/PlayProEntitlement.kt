package app.marmalade.tts.pro

import android.app.Activity
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// -----------------------------------------------------------------------------
// PlayProEntitlement — STUB.
//
// This file's purpose is to let the `play` flavor compile and run before
// the BillingClient integration lands (Task #17 in the session's task
// list). It always reports `isPro = false`, [launchPurchase] returns
// Error, [restorePurchases] is a no-op.
//
// Real BillingClient wiring per docs/release/PAYWALL-PLAN.md §3 + §7:
//   - com.android.billingclient:billing-ktx 7.x  (via playImplementation)
//   - queryProductDetails("marmalade_pro", INAPP)
//   - launchBillingFlow(activity, flowParams)
//   - onPurchasesUpdated → acknowledgePurchase(token) within 3 days
//   - queryPurchasesAsync on app foreground (the user-of-truth check)
//   - DataStore-cached entitlement for offline launches (~30-day window)
// -----------------------------------------------------------------------------

private const val TAG = "PlayProEntitlement"

@Singleton
class PlayProEntitlement @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProEntitlement {

    init {
        Log.w(TAG, "PlayProEntitlement is a stub — BillingClient not yet wired. " +
            "All purchases will report Error until task #17 lands.")
    }

    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    override suspend fun launchPurchase(activity: Activity): PurchaseResult {
        Log.w(TAG, "launchPurchase: BillingClient not yet wired")
        return PurchaseResult.Error("Billing not yet implemented in this build")
    }

    override suspend fun restorePurchases() {
        Log.w(TAG, "restorePurchases: BillingClient not yet wired")
    }
}
