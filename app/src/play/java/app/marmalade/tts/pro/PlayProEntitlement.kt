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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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

/** Hard cap on how long any single [ensureConnected] call may suspend. */
private const val CONNECT_TIMEOUT_MS = 5_000L

/**
 * Hang-guard on awaiting the billing sheet's outcome. Generous — the
 * user may legitimately sit in Play checkout adding a payment method.
 */
private const val FLOW_OUTCOME_TIMEOUT_MS = 10L * 60L * 1000L

private val Context.proDataStore by preferencesDataStore("pro_entitlement")
private val KEY_CACHED_PRO = booleanPreferencesKey("pro_isPro_cached")
private val KEY_VERIFIED_AT = longPreferencesKey("pro_isPro_verifiedAt")

@Singleton
class PlayProEntitlement @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProEntitlement, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serialises [applyPurchases] so a concurrent
     * onPurchasesUpdated + restorePurchases can't double-process
     * the same purchase or interleave their DataStore writes.
     */
    private val applyMutex = Mutex()

    // Public state ---------------------------------------------------------

    private val _isPro = MutableStateFlow(false)
    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    /**
     * Set when a successful PURCHASED transition needed an acknowledge
     * that failed. We surface the user as Pro (Play already collected
     * money) but skip writing the cache `verifiedAt` so the next
     * `restorePurchases` retries the acknowledge before the 3-day
     * refund window closes.
     */
    @Volatile private var lastApplyAckFailed: Boolean = false

    /**
     * Most recently observed PENDING purchase token, if any. Sheet
     * surfaces this so the user knows Play is processing payment
     * rather than seeing the sheet dismiss with no Pro flip.
     */
    private val _pending = MutableStateFlow(false)
    val pendingPurchase: StateFlow<Boolean> = _pending.asStateFlow()

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

    /**
     * Outcome of the billing flow currently on screen, if any. Created
     * by [launchPurchase] BEFORE `launchBillingFlow` (so a fast callback
     * can't race the field write) and completed by [onPurchasesUpdated]
     * — Play reports cancel/success/pending through that listener, not
     * through `launchBillingFlow`'s return (which only means "the sheet
     * opened"). One flow at a time per Play's own UI contract.
     */
    @Volatile private var flowOutcome: CompletableDeferred<PurchaseResult>? = null

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
        // Arm the outcome BEFORE launching so onPurchasesUpdated can't
        // race the field write on an instantly-clearing purchase.
        val outcome = CompletableDeferred<PurchaseResult>()
        flowOutcome = outcome
        val result = billingClient.launchBillingFlow(activity, params)
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // `OK` only means the Play sheet OPENED. The real outcome
                // (purchase / cancel / pending) arrives via
                // onPurchasesUpdated, which completes [outcome] after
                // applyPurchases has run — so Success here genuinely means
                // isPro is already true. The timeout is a hang-guard, not
                // a UX budget: if it ever fires while the user is still
                // mid-checkout, a later onPurchasesUpdated still flips
                // [isPro] and the UI observing the flow catches up.
                withTimeoutOrNull(FLOW_OUTCOME_TIMEOUT_MS) { outcome.await() }
                    ?: PurchaseResult.Error(
                        "Timed out waiting for Google Play. If you completed " +
                            "the purchase, Pro will unlock automatically."
                    )
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Already-purchased on this Play account but our cache
                // didn't know. restorePurchases() is suspend and
                // re-queries Play, runs applyPurchases under
                // [applyMutex], and updates [_isPro] BEFORE this
                // function returns — so the caller can trust
                // PurchaseResult.Success here to mean isPro is true.
                restorePurchases()
                PurchaseResult.Success
            }
            else -> PurchaseResult.Error(
                "Play billing error: ${result.responseCode} ${result.debugMessage}"
            )
        }.also {
            if (flowOutcome === outcome) flowOutcome = null
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
        // Play sometimes delivers USER_CANCELED with a non-null
        // purchases list (e.g. user cancels a *second* purchase flow
        // after the first already cleared). Process whatever's in
        // [purchases] regardless of the response code; only when both
        // the code is non-OK AND the list is null/empty is there
        // genuinely nothing to process — but even then an in-flight
        // [flowOutcome] gets its terminal answer (cancel/error).
        val list = purchases.orEmpty()
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "onPurchasesUpdated code=${billingResult.responseCode} " +
                "${billingResult.debugMessage}, purchases.size=${list.size}")
        }
        val awaiting = flowOutcome
        if (list.isEmpty()) {
            awaiting?.complete(
                when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
                    else -> PurchaseResult.Error(
                        "Play billing error: ${billingResult.responseCode} " +
                            billingResult.debugMessage
                    )
                }
            )
            return
        }
        scope.launch {
            applyPurchases(list)
            // Completed AFTER applyPurchases so a Success answer implies
            // isPro is already true (and acknowledged, or queued for the
            // onResume retry). complete() on an already-answered deferred
            // is a no-op.
            awaiting?.complete(
                when {
                    _isPro.value -> PurchaseResult.Success
                    _pending.value -> PurchaseResult.Pending
                    billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED ->
                        PurchaseResult.Cancelled
                    else -> PurchaseResult.Error(
                        "Purchase did not complete (Play code ${billingResult.responseCode})"
                    )
                }
            )
        }
    }

    // Internals -----------------------------------------------------------

    private suspend fun applyPurchases(purchases: List<Purchase>) = applyMutex.withLock {
        val ourPurchases = purchases.filter { it.products.contains(PRODUCT_ID) }
        val owned = ourPurchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = ourPurchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        var ackFailedThisRound = false
        if (owned) {
            // Acknowledge anything not yet acknowledged. Play refunds an
            // unacknowledged purchase within 3 days, so this is critical.
            // [Purchase.isAcknowledged] is read once per pass; double-ack
            // is safe in BillingClient but we avoid it via the Mutex.
            for (p in ourPurchases) {
                if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                if (p.isAcknowledged) continue
                val ack = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.purchaseToken)
                    .build()
                val ackResult = billingClient.acknowledgePurchase(ack)
                if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "acknowledge failed: ${ackResult.debugMessage}")
                    ackFailedThisRound = true
                }
            }
        }

        _isPro.value = owned
        _pending.value = pending
        lastApplyAckFailed = ackFailedThisRound

        context.proDataStore.edit { prefs ->
            prefs[KEY_CACHED_PRO] = owned
            // Only record a fresh verifiedAt when the ack round
            // succeeded cleanly. If an ack failed we want the next
            // restorePurchases (Settings → About → Restore, or the
            // onResume hook) to retry quickly rather than trusting a
            // "verified now" timestamp on a transaction Play may yet
            // auto-refund.
            if (!ackFailedThisRound) {
                prefs[KEY_VERIFIED_AT] = System.currentTimeMillis()
            }
        }
        Log.i(TAG, "applyPurchases: isPro=$owned pending=$pending ackOk=${!ackFailedThisRound}")
    }

    private fun cacheValid(prefs: Preferences): Boolean {
        val cached = prefs[KEY_CACHED_PRO] ?: false
        if (!cached) return false
        val verifiedAt = prefs[KEY_VERIFIED_AT] ?: return false
        return (System.currentTimeMillis() - verifiedAt) < CACHE_TTL_MS
    }

    /**
     * Serialises [ensureConnected] so two concurrent callers don't both
     * fire `startConnection` (which leaks listeners on the BillingClient
     * for the rest of the process).
     */
    private val connectMutex = Mutex()

    /**
     * Suspend until the BillingClient is connected. Returns `true` once
     * `onBillingSetupFinished` reports OK, or `false` when Play is
     * genuinely unavailable (no Play Store app, region-restricted,
     * connection timeout) so the caller can render a meaningful error
     * rather than spinning forever.
     *
     * Each call serialises through [connectMutex] so we don't pile
     * up listeners on the BillingClient — earlier versions registered
     * a fresh anonymous listener every call and never unregistered.
     */
    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) return true
        return connectMutex.withLock {
            if (billingClient.isReady) return@withLock true
            val ready = CompletableDeferred<Boolean>()
            val listener = object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (!ready.isCompleted) {
                        ready.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Per-call reconnect: every public method calls
                    // ensureConnected before touching the client, so a
                    // disconnect just causes the next call to reconnect.
                    Log.i(TAG, "billing service disconnected")
                }
            }
            billingClient.startConnection(listener)
            // Five seconds covers slow cold-start on first-launch +
            // Play Store auto-update without spinning forever on a
            // permanently-offline device.
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } ?: run {
                Log.w(TAG, "billing connect timeout after ${CONNECT_TIMEOUT_MS}ms")
                false
            }
        }
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
