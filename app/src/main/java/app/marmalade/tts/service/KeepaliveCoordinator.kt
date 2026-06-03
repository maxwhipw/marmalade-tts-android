package app.marmalade.tts.service

import android.content.Context
import android.util.Log
import app.marmalade.tts.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * P-K — single entry point for "I just finished a synth, please keep
 * the engine warm appropriately."
 *
 * Owns reading the current [KeepaliveMode] from settings and translating
 * it into the right [MarmaladeKeepaliveService] action. Synth paths
 * (in-app [app.marmalade.tts.audio.Synthesizer], share-sheet
 * [MarmaladeSynthService], system-TTS [MarmaladeTtsService]) inject and
 * call [onSynthCompleted] — no other coordination required.
 *
 * Persistent-mode bootstrap (when the user toggles ON in Settings or
 * when the app launches with the toggle already ON) calls
 * [applyCurrentMode] — same code path, just driven from a different
 * trigger.
 */
@Singleton
class KeepaliveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Called from any synth path immediately after a synth completes
     * (or starts — fine to call before too, since the service is
     * idempotent). Reads the current mode and refreshes the service.
     *
     * Smart-mode: starts/restarts the 10-min countdown.
     * Persistent-mode: starts the service if not already running (no-op
     *                  if already running).
     * Off:        stops the service if running, otherwise no-op.
     *
     * Suspending only because settings reads are suspending; the actual
     * service start is fire-and-forget on a coroutine.
     */
    fun onSynthCompleted() {
        scope.launch {
            try {
                val mode = settings.keepaliveMode.first()
                MarmaladeKeepaliveService.refresh(context, mode)
            } catch (t: Throwable) {
                Log.w(TAG, "onSynthCompleted refresh failed: ${t.message}")
            }
        }
    }

    /**
     * Used when the persistent-mode toggle is flipped in Settings, or
     * at app startup if the persistent mode was previously enabled.
     * Same effect as [onSynthCompleted] — reads current mode + acts —
     * but named separately so the call sites read clearly.
     */
    fun applyCurrentMode() = onSynthCompleted()

    companion object {
        private const val TAG = "KeepaliveCoordinator"
    }
}
