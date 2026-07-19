package app.marmalade.tts.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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

/**
 * Returns true if Marmalade is currently the system's default
 * text-to-speech engine. Reads the `tts_default_synth` secure setting
 * (readable without any permission); it holds the package name of the
 * preferred engine. Composables can poll this via lifecycle events to
 * refresh after the user returns from the system TTS settings page.
 */
fun isDefaultSystemTts(context: Context): Boolean {
    val engine = Settings.Secure.getString(
        context.contentResolver,
        "tts_default_synth",
    )
    return engine == context.packageName
}

/**
 * Returns true if Android is currently allowing this app to run
 * unrestricted in the background (i.e. the user has exempted us from
 * battery optimisations). Composables can poll this via lifecycle
 * events to refresh after the user returns from the settings prompt.
 *
 * Always false on devices without a PowerManager (defensive — every
 * real device has one).
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Launch the system dialog that asks the user to add this app to the
 * battery-optimisation exemption list. Required so our foreground
 * synthesis service isn't paused mid-utterance when the screen sleeps
 * or the device enters Doze.
 *
 * Strategy:
 *   1. Try `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with a
 *      `package:<our-package>` URI — direct-grant yes/no dialog. This
 *      requires the manifest permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
 *      which we declare; Play Console accepts it for TTS engines that
 *      need uninterrupted foreground audio.
 *   2. Fall back to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` — the
 *      list of all apps, user finds ours and toggles us off restriction.
 *      Works on OEMs that have hidden the direct-grant dialog.
 *   3. Last resort, app-info page (Settings → App info → Battery).
 */
fun openBatteryOptimizationRequest(context: Context): Boolean {
    val pkgUri = Uri.parse("package:${context.packageName}")
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
    )
    for (intent in candidates) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "Battery-opt intent ${intent.action} not handled, trying next", e)
        }
    }
    Log.w(TAG, "No battery-optimisation intent could be resolved")
    return false
}

private const val TAG = "SystemSettings"
