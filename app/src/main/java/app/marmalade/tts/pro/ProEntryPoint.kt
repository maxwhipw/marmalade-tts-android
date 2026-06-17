package app.marmalade.tts.pro

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point exposing [ProEntitlement] + [ProGate] to non-Hilt
 * call sites (Compose composables that want to read singletons without
 * wiring up a ViewModel). Both interfaces live in `src/main`; the
 * per-flavor binding modules under `src/<flavor>/` resolve them to
 * the right implementation.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProEntryPoint {
    fun proEntitlement(): ProEntitlement
    fun proGate(): ProGate
}
