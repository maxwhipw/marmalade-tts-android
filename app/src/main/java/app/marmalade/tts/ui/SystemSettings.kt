package app.marmalade.tts.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Launch the device's Text-to-Speech engine settings screen so the user
 * can pick Marmalade as their system TTS engine.
 *
 * Strategy:
 *   1. Try the most-direct action `com.android.settings.TTS_SETTINGS`,
 *      which on stock Android lands directly on the TTS configuration
 *      page where the "Preferred engine" dropdown lives.
 *   2. Fall back to the generic locale / language settings — TTS is
 *      always reachable via "Languages & input" or "Languages" or
 *      "System" depending on OEM.
 *   3. Last resort, open the top-level Settings app.
 *
 * Different OEMs (Samsung One UI, MIUI, etc.) bury TTS in slightly
 * different paths, so we don't claim any of these strategies is
 * authoritative — but the user is one or two taps closer than if we
 * hadn't pointed them at all.
 */
fun openSystemTtsSettings(context: Context): Boolean {
    val candidates = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_LOCALE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "TTS settings intent ${intent.action} not handled, trying next", e)
        }
    }
    Log.w(TAG, "No system settings intent could be resolved")
    return false
}

private const val TAG = "SystemSettings"
