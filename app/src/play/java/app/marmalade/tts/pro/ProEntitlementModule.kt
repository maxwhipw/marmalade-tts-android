package app.marmalade.tts.pro

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Play flavor binding — every caller asking for [ProEntitlement] gets
 * the BillingClient-backed [PlayProEntitlement]. The F-Droid flavor
 * has a symmetric module under `src/fdroid/` binding
 * [FdroidProEntitlement].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProEntitlementModule {

    @Binds
    abstract fun bindProEntitlement(impl: PlayProEntitlement): ProEntitlement
}
