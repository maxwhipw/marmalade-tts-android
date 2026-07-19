package app.marmalade.tts.service

import android.util.Log
import app.marmalade.tts.engine.PocketEngine
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kitten.KittenDirectMiniEngine
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Eagerly loads every installed engine so the first synth request after
 * process start doesn't pay model-mmap + phonemizer-init on the user's
 * critical path (~300–500 ms for the ORT session alone).
 *
 * Two triggers share this:
 *  - [MarmaladeTtsService.onLoadLanguage] — the framework's documented
 *    warm-up hook, fires when a client binds the system-TTS engine.
 *  - [KeepaliveCoordinator] — whenever keepalive is Smart/Persistent.
 *    Pre-fix the keepalive service only held the *process* alive; a fresh
 *    process in persistent mode still cold-loaded the model on the first
 *    request, which is exactly the latency the toggle promises to remove.
 *
 * Engines whose bundle isn't on disk throw `EngineNotInstalledException`
 * from `ensureModelLoaded()`; that's expected and skipped. Every engine's
 * load is idempotent + internally locked, so concurrent triggers are safe.
 */
@Singleton
class EngineWarmup @Inject constructor(
    private val kokoroDirect: KokoroDirectEngine,
    private val kittenDirect: KittenDirectEngine,
    private val kittenDirectMini: KittenDirectMiniEngine,
    private val pocket: PocketEngine,
) {
    // Application-lifetime scope; never cancelled (singletons live as long
    // as the process). IO because ensureModelLoaded reads model bytes off
    // disk before handing to JNI.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget: load every installed engine in the background. */
    fun warmInstalledAsync() {
        scope.launch {
            val engines = listOf(
                "kokoro-direct" to kokoroDirect,
                "kitten-direct" to kittenDirect,
                "kitten-direct-mini" to kittenDirectMini,
                "pocket" to pocket,
            )
            for ((name, engine) in engines) {
                try {
                    engine.ensureModelLoaded()
                    Log.d(TAG, "$name engine warm-up complete")
                } catch (ex: Exception) {
                    // Not-installed is the common case; real failures will
                    // resurface with context on the first explicit synth.
                    Log.d(TAG, "$name engine warm-up skipped: ${ex.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "EngineWarmup"
    }
}
