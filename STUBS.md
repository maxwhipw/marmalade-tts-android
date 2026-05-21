# STUBS — marmalade-tts-android

Entries added by implementation agents when functionality is deferred.
Each entry must explain *what* is missing, *why* it was deferred, and
how to finish it.

## v0.1 — Engine + System TTS milestone

### Share-sheet + Quick Settings tile (device verification deferred)
- **Files:**
  - `app/src/main/java/app/marmalade/tts/ui/intent/ShareIntentActivity.kt`
  - `app/src/main/java/app/marmalade/tts/service/SpeakClipboardTileService.kt`
  - `app/src/main/java/app/marmalade/tts/service/SpeakDispatcher.kt`
- **Status:** Implementation in place; `SpeakDispatcher.prepare` is
  unit-tested for trim / blank / clamp logic (8 assertions). The
  end-to-end intent plumbing (manifest filters, foreground-service
  hand-off, ClipboardManager read) needs a real device to exercise —
  an instrumented test scaffold lives at
  `app/src/androidTest/java/.../ShareAndTileInstrumentedTest.kt`.
- **What the instrumented test asserts:**
  1. `adb shell am start -a android.intent.action.SEND -t text/plain \
     --es android.intent.extra.TEXT "hello world" \
     -n app.marmalade.tts/.ui.intent.ShareIntentActivity` produces
     audible speech and finishes the trampoline activity.
  2. Same for `ACTION_PROCESS_TEXT` via a UiAutomator selection menu
     interaction.
  3. Adding the tile from Quick Settings, copying text to clipboard,
     tapping the tile → audible speech (also from the lock screen).
  4. Empty clipboard tap → Toast "Clipboard is empty", no service start.
- **How to run:** `./gradlew :app:connectedDebugAndroidTest` with a
  device attached and the engine installed (run through onboarding once).
