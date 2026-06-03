package app.marmalade.tts.service

/**
 * P-K — three-way choice for engine keepalive behaviour.
 *
 * The TTS engines are Hilt `@Singleton`s, so they live as long as the
 * application process does. Android otherwise reclaims background
 * processes within minutes; without keepalive the user pays the
 * engine cold-load tax (~1 s for Pocket, ~500 ms for Kokoro/Kitten)
 * every time they come back.
 *
 * - [Off]: no foreground service. Process can be reclaimed at any time
 *   (matches pre-P-K behaviour). Smallest RAM footprint when idle but
 *   re-loads every time.
 *
 * - [Smart] (default): foreground service runs for [SMART_TIMEOUT_MS]
 *   after each synth, refreshed on every new request. Covers the "user
 *   is reading a book and triggering TTS every minute" pattern at zero
 *   long-term cost. Notification only appears during the keepalive
 *   window.
 *
 * - [Persistent]: foreground service runs forever once the user enables
 *   it. Primary alias's engine is pre-loaded at start. RAM cost is
 *   visible in the toggle's helper text + the persistent notification.
 *   For power users who want zero-cold-start system TTS.
 */
enum class KeepaliveMode { Off, Smart, Persistent }

/**
 * How long the smart-keepalive service stays alive after the most
 * recent synth. 10 minutes covers reading patterns where the user
 * triggers TTS every paragraph, while letting the OS reclaim the
 * process when the user genuinely walks away.
 */
const val SMART_TIMEOUT_MS: Long = 10L * 60L * 1000L
