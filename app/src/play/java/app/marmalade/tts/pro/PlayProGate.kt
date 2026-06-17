package app.marmalade.tts.pro

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Play flavor's [ProGate] — process-singleton holding a "show the
 * paywall for this reason" signal that [ProGateHost] observes via
 * Compose's state-flow collection. Decoupling the trigger from the
 * Composable lets viewmodel/screen code call `proGate.show(...)` from
 * non-Composable handlers (FAB onClick, list-row taps) without
 * threading a callback through every screen parameter.
 */
@Singleton
class PlayProGate @Inject constructor() : ProGate {

    private val _pending = MutableStateFlow<PaywallReason?>(null)
    val pending: StateFlow<PaywallReason?> = _pending.asStateFlow()

    override fun show(reason: PaywallReason) {
        _pending.value = reason
    }

    fun dismiss() {
        _pending.value = null
    }
}
