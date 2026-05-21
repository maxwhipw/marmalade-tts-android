# STUBS — marmalade-tts-android

Entries added by implementation agents when functionality is deferred.
Each entry must explain *what* is missing, *why* it was deferred, and
how to finish it.

## v0.1 — Engine + System TTS milestone

### Share-sheet + Quick Settings tile (audible / on-device manual checks remain)
- **Files:**
  - `app/src/main/java/app/marmalade/tts/ui/intent/ShareIntentActivity.kt`
  - `app/src/main/java/app/marmalade/tts/service/SpeakClipboardTileService.kt`
  - `app/src/main/java/app/marmalade/tts/service/SpeakDispatcher.kt`
- **Status:** Implementation in place; `SpeakDispatcher.prepare` is
  unit-tested for trim / blank / clamp logic (8 assertions). The
  instrumented test scaffold now exists at
  `app/src/androidTest/java/app/marmalade/tts/integration/ShareAndTileInstrumentedTest.kt`
  and compiles via `./gradlew :app:compileDebugAndroidTestKotlin`. It
  contains six `@Test` methods, four programmatic and two
  `Assume.assumeTrue(false)`-gated as manual-only.
- **What the instrumented test asserts programmatically:**
  1. `ShareIntentActivity` resolves for `ACTION_SEND` + `text/plain`
     and is exported (manifest-filter regression check).
  2. `ShareIntentActivity` resolves for `ACTION_PROCESS_TEXT`
     (text-selection menu regression check).
  3. Launching `ShareIntentActivity` with `EXTRA_TEXT = "hello world"`
     via `ActivityScenario` reaches `Lifecycle.State.DESTROYED` within
     5 seconds (the trampoline self-finishes).
  4. Launching `ShareIntentActivity` with whitespace-only `EXTRA_TEXT`
     also self-finishes cleanly (no crash, no hang).
  5. `SpeakClipboardTileService` resolves for the `QS_TILE` action,
     is exported, and is gated by `BIND_QUICK_SETTINGS_TILE`.
- **What still needs a human / device (deferred to v0.2):**
  1. **Audible speech** during `shareSheetActivity_launchesAndDispatchesAndFinishes` —
     the device should speak "hello world"; no way to assert this
     from instrumentation without a recording loopback.
  2. **No service start on blank input** during
     `shareSheetActivity_blankTextShowsToastAndFinishes` — must be
     verified by tailing `adb logcat | grep MarmaladeSynthService`.
  3. **Tile interactions** (`tileService_dispatchesWhenClipboardHasText`,
     `tileService_emptyClipboard_doesNotStartService`) — both are
     `Assume.assumeTrue(false)`-gated with inline KDoc procedures.
     Automating them would need `androidx.test.uiautomator:uiautomator`
     on the androidTest classpath plus a way to drag the tile into the
     user's active QS layout (not currently possible programmatically),
     so the audible/lock-screen halves stay manual.
  4. **Lock-screen tile behaviour** — the tile requires the device to
     be unlocked. We don't declare `UNLOCK_REQUIRED=false` because
     Android 10+ blocks background clipboard reads from a locked
     context, so trying the lock-screen path would only get an empty
     clip. Re-verify on each release device that the unlock-then-tap
     flow still works.
- **How to run:** `./gradlew :app:connectedDebugAndroidTest --tests
  '*ShareAndTileInstrumentedTest*'` with a device attached and the
  engine installed (run through onboarding once).
