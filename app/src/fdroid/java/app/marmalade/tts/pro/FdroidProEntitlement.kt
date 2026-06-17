package app.marmalade.tts.pro

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F-Droid flavor implementation: every Pro feature is free, so [isPro]
 * is a constant `true`. Lives under `src/fdroid/`; the class doesn't
 * exist on the Play classpath.
 *
 * Kept as a `@Singleton` purely for graph symmetry with the Play impl
 * — the StateFlow is a no-op observer otherwise.
 */
@Singleton
class FdroidProEntitlement @Inject constructor() : ProEntitlement {

    private val _isPro = MutableStateFlow(true)
    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    override suspend fun launchPurchase(activity: Activity): PurchaseResult =
        PurchaseResult.NotApplicable

    override suspend fun restorePurchases() = Unit
}
