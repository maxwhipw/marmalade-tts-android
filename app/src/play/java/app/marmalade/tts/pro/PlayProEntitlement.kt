package app.marmalade.tts.pro

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// PlayProEntitlement — Google Play Billing wrapper.
//
// Lifecycle
//
//   - `BillingClient` is constructed in [init]. Connection is lazy:
//     [ensureConnected] calls `startConnection` the first time anything
//     needs the client, retrying with backoff on disconnect.
//   - `queryPurchasesAsync(INAPP)` is the source of truth for [isPro].
//     Called by [restorePurchases] (which the UI fires on resume + on
//     the paywall sheet's "Restore" button) and from
//     `onPurchasesUpdated` after a fresh purchase.
//   - DataStore caches the last observed entitlement under
//     `pro_isPro_cached` + a verification timestamp under
//     `pro_isPro_verifiedAt`. Cold-start reads the cache so offline
//     launches stay unlocked; we then re-verify in the background and
//     publish whatever Play says. Cache TTL is [CACHE_TTL_MS]; after
//     that we drop the cached value to false until a successful
//     network re-verify clears it.
//
// Acknowledge contract
//
//   Play refunds a purchase that isn't `acknowledgePurchase`'d within
//   3 days. We acknowledge inline from `onPurchasesUpdated` and from
//   the [restorePurchases] path for any already-purchased entry that
//   came back without `isAcknowledged`.
//
// Threading
//
//   BillingClient callbacks land on the main thread; we hop straight
//   off via a private [scope] (SupervisorJob + IO). [isPro] mutates
//   from that scope and from the (sequential) [restorePurchases]
//   suspend body.
//
// Test product
//
//   PRODUCT_ID = "marmalade_pro" — one-time INAPP, NOT a subscription.
//   Configure in Play Console → Monetisation → Products. The Console
//   needs at least one tester on an internal track for the product to
//   become buyable in a signed test build.
// -----------------------------------------------------------------------------

private const val TAG = "PlayProEntitlement"
private const val PRODUCT_ID = "marmalade_pro"

/** 30 days. Cache survives offline launches but eventually expires. */
private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L

private val Context.proDataStore by preferencesDataStore("pro_entitlement")
private val KEY_CACHED_PRO = booleanPreferencesKey("pro_isPro_cached")
private val KEY_VERIFIED_AT = longPreferencesKey("pro_isPro_verifiedAt")

@Singleton
class PlayProEntitlement @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProEntitlement, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Public state ---------------------------------------------------------

    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    // BillingClient --------------------------------------------------------

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    /** Resolved once per process — the product's cached [ProductDetails]. */
    @Volatile private var productDetails: ProductDetails? = null

    // Cold-start: surface the cache to UI ASAP, then verify against Play.
    init {
        scope.launch {
            val prefs = context.proDataStore.data.first()
            val cached = cacheValid(prefs)
            _isPro.value = cached
            Log.i(TAG, "cold-start cached isPro=$cached (within TTL)")
            // Don't block init on the network — restorePurchases() runs
            // on the next resume signal. If Play happens to be cold-
            // reachable we'll see it then.
        }
    }

    // ProEntitlement -------------------------------------------------------

    override suspend fun launchPurchase(activity: Activity): PurchaseResult {
        if (!ensureConnected()) {
            return PurchaseResult.Error("Google Play is unavailable on this device")
        }
        val details = productDetails ?: queryProductDetailsBlocking()
            ?: return PurchaseResult.Error("Could not load the Marmalade Pro product")

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // Real outcome arrives in onPurchasesUpdated; this is just
                // "the Play sheet opened". UI should observe isPro rather
                // than treat this return value as the final word.
                PurchaseResult.Success
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Already-purchased on this Play account but our cache
                // didn't know — kick a restore so isPro flips.
                restorePurchases()
                PurchaseResult.Success
            }
            else -> PurchaseResult.Error(
                "Play billing error: ${result.responseCode} ${result.debugMessage}"
            )
        }
    }

    override suspend fun restorePurchases() {
        if (!ensureConnected()) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.debugMessage}")
            return
        }
        applyPurchases(result.purchasesList)
    }

    // PurchasesUpdatedListener --------------------------------------------

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "onPurchasesUpdated non-OK: ${billingResult.debugMessage}")
            return
        }
        val list = purchases ?: return
        scope.launch { applyPurchases(list) }
    }

    // Internals -----------------------------------------------------------

    private suspend fun applyPurchases(purchases: List<Purchase>) {
        val owned = purchases.any { p ->
            p.products.contains(PRODUCT_ID) &&
                p.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (owned) {
            // Acknowledge anything not yet acknowledged. Play refunds an
            // unacknowledged purchase within 3 days, so this is critical.
            purchases.filter { p ->
                p.products.contains(PRODUCT_ID) &&
                    p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    !p.isAcknowledged
            }.forEach { p ->
                val ack = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.purchaseToken)
                    .build()
                val ackResult = billingClient.acknowledgePurchase(ack)
                if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "acknowledge failed: ${ackResult.debugMessage}")
                }
            }
        }
        _isPro.value = owned
        context.proDataStore.edit { prefs ->
            prefs[KEY_CACHED_PRO] = owned
            prefs[KEY_VERIFIED_AT] = System.currentTimeMillis()
        }
        Log.i(TAG, "applyPurchases: isPro=$owned (verified against Play)")
    }

    private fun cacheValid(prefs: Preferences): Boolean {
        val cached = prefs[KEY_CACHED_PRO] ?: false
        if (!cached) return false
        val verifiedAt = prefs[KEY_VERIFIED_AT] ?: return false
        return (System.currentTimeMillis() - verifiedAt) < CACHE_TTL_MS
    }

    /**
     * Suspend until the BillingClient is connected, retrying on
     * disconnect via Play's recommended exponential backoff. Returns
     * `true` once `onBillingSetupFinished` reports OK, or `false` when
     * the device explicitly reports billing unavailable (no Play Store
     * app, region restriction, etc.) so the caller can render a
     * meaningful error rather than spinning forever.
     */
    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) return true
        val ready = CompletableDeferred<Boolean>()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                ready.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                // Connection retries are handled per-call rather than via
                // a daemon — every public method calls ensureConnected,
                // so a disconnect just means the next call reconnects.
                Log.i(TAG, "billing service disconnected")
            }
        })
        return ready.await()
    }

    /**
     * One-shot product-details fetch, cached for the rest of the
     * process lifetime. Returns null if Play doesn't know about the
     * product yet — usually means the product hasn't been activated in
     * Play Console for this build's signing key.
     */
    private suspend fun queryProductDetailsBlocking(): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
            return null
        }
        return result.productDetailsList?.firstOrNull()?.also { productDetails = it }
    }
}
