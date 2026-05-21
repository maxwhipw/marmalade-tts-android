package app.marmalade.tts.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//
//   System trigger (share-sheet target / Quick Settings tile / future hooks)
//     │
//     │  raw text (may be null, blank, or arbitrarily long)
//     ▼
//   SpeakDispatcher.dispatch(context, text)
//     │
//     ├── trim() + reject blank  ──► DispatchResult.Blank
//     │
//     ├── clamp to MAX_TEXT_LENGTH (10 000 chars) — logs a warning if it
//     │   has to truncate so the caller's logs surface what happened
//     │
//     └── build Intent {
//           component   = MarmaladeSynthService
//           action      = MarmaladeSynthService.ACTION_SPEAK
//           extras      = { EXTRA_TEXT = clamped text }
//           package     = caller's package (explicit, safe across exports)
//         }
//           │
//           ▼
//         ContextCompat.startForegroundService(context, intent)
//           │
//           ▼
//         MarmaladeSynthService picks up the request (queues if busy,
//         starts immediately if idle).
//
//   Returns DispatchResult so callers can render an appropriate Toast
//   (or otherwise react) without each caller re-implementing the
//   validation rules.
// -----------------------------------------------------------------------------

/**
 * Centralised dispatch helper for "speak this text" callers that aren't
 * inside the main app UI — share-sheet, Quick Settings tile, future Tasker
 * integration, etc.
 *
 * Keeping validation here means every entry point applies the same rules:
 * blank rejection, length clamp, and the exact intent shape that
 * [MarmaladeSynthService] expects.
 */
internal object SpeakDispatcher {

    private const val TAG = "SpeakDispatcher"

    /**
     * Maximum allowed text length, in characters. Texts longer than this
     * are clamped (with a warning logged) rather than rejected — long
     * paste-ins from the share sheet are a common case and silently
     * dropping them would be a worse user experience than truncating.
     */
    const val MAX_TEXT_LENGTH: Int = 10_000

    /** Result of a dispatch attempt. */
    sealed interface DispatchResult {
        /** Text was non-blank; intent was sent to MarmaladeSynthService. */
        data class Dispatched(val length: Int, val clamped: Boolean) : DispatchResult

        /** Text was null, empty, or whitespace-only — nothing sent. */
        data object Blank : DispatchResult
    }

    /**
     * Validate [rawText], clamp to [MAX_TEXT_LENGTH], and start the
     * synthesis service. Returns a [DispatchResult] describing what
     * happened so the caller can show an appropriate user-facing message.
     */
    fun dispatch(context: Context, rawText: String?): DispatchResult {
        val prepared = prepare(rawText) ?: return DispatchResult.Blank

        val intent = Intent(context, MarmaladeSynthService::class.java).apply {
            action = MarmaladeSynthService.ACTION_SPEAK
            putExtra(MarmaladeSynthService.EXTRA_TEXT, prepared.text)
            setPackage(context.packageName)
        }
        ContextCompat.startForegroundService(context, intent)
        return DispatchResult.Dispatched(
            length = prepared.text.length,
            clamped = prepared.clamped,
        )
    }

    /**
     * Pure validation/clamp step — extracted from [dispatch] so it can be
     * unit-tested without an Android framework Context. Returns null if
     * the input is blank, or a [Prepared] payload otherwise.
     *
     * `internal` rather than `private` so the test module can call into
     * it; it is not part of the public API.
     */
    internal fun prepare(rawText: String?): Prepared? {
        val trimmed = rawText?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= MAX_TEXT_LENGTH) {
            return Prepared(text = trimmed, clamped = false)
        }
        Log.w(
            TAG,
            "Input text length ${trimmed.length} exceeds MAX_TEXT_LENGTH " +
                "($MAX_TEXT_LENGTH); truncating.",
        )
        return Prepared(text = trimmed.substring(0, MAX_TEXT_LENGTH), clamped = true)
    }

    /** Output of [prepare]: trimmed/clamped text plus whether truncation happened. */
    internal data class Prepared(val text: String, val clamped: Boolean)
}
