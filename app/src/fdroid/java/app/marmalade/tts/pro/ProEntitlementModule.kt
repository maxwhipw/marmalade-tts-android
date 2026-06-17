package app.marmalade.tts.pro

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * F-Droid flavor binding — every caller asking for [ProEntitlement]
 * gets the always-true [FdroidProEntitlement]. The Play flavor has a
 * symmetric module under `src/play/` binding [PlayProEntitlement].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProEntitlementModule {

    @Binds
    abstract fun bindProEntitlement(impl: FdroidProEntitlement): ProEntitlement
}
