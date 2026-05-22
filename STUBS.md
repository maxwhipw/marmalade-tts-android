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

### `MarmaladeTtsService.onStop` cancellation
- **Reference:** whole-project review, Major / Minor #2 (lines 363–376).
- **File:** `app/src/main/java/app/marmalade/tts/service/MarmaladeTtsService.kt`
  (the `// TODO: STUBS.md — onStop cancellation` marker near `onStop`
  points at this entry).
- **What's missing:** `onStop` is a no-op while `engine.synthesize(...)`
  is running. Because synthesis is wrapped in `runBlocking { … }` inside
  `onSynthesizeText`, long-form text can't be interrupted mid-synthesis
  — the system's stop request is honoured only after the current
  synthesise call returns. For a multi-sentence read on a mid-range
  device that's a 2–5 second lag before the engine actually stops.
- **Why deferred:** the proper fix is restructuring `runBlocking { … }`
  to `kotlinx.coroutines.runInterruptible` plus a tracked `Job?` so
  `onStop` can cancel it. Out of scope for v0.1's fix-the-bug pass.
- **How to finish:** introduce a `currentSynthJob: Job?` field on the
  service, store the launched job in `onSynthesizeText`, and call
  `currentSynthJob?.cancel()` from `onStop`. Make sure the cancellation
  unwinds cleanly back through the JNI boundary (Sherpa-ONNX
  `OfflineTts.generate` is synchronous, so the cancel won't interrupt
  the in-flight inference — it just discards the result post-hoc).

### `WorkManager`-backed engine installs
- **Reference:** whole-project review, Minor #6 (lines 417–434).
- **File:** `app/src/main/java/app/marmalade/tts/install/EngineInstaller.kt`
  (no current change needed — this is forward work).
- **What's missing:** engine downloads run on `Dispatchers.IO` from the
  ViewModel scope. If the user starts an install in onboarding and then
  locks the phone or switches apps, Android may stop the work and the
  install can hang. v0.1's "user is on the install screen so the app is
  foreground" contract usually holds, but it isn't guaranteed (incoming
  call, switch to messages, etc.).
- **Why deferred:** a real fix is non-trivial — either `WorkManager`
  with `CONNECTED` / `REQUIRES_CHARGING` constraints, or a dedicated
  `MarmaladeInstallService` with `foregroundServiceType="dataSync"`
  (separate from `MarmaladeSynthService`, which is `mediaPlayback` and
  doesn't fit).
- **How to finish:** add `MarmaladeInstallService` (separate fgs type
  from the synth service), refactor `EngineInstaller.install` to route
  through it so the install survives backgrounding. v0.2 work.

### `NativeEngineHandle` generalisation to `Map<String, NativeEngineHandle>`
- **Reference:** whole-project review, Minor #4 (lines 391–403).
- **Files:** `app/src/main/java/app/marmalade/tts/di/AppModule.kt:124-126`,
  `app/src/main/java/app/marmalade/tts/install/EngineInstaller.kt:370-374`.
- **What's missing:** `NativeEngineHandle` is a single function-
  interface that always releases `KittenEngine`. `EngineInstaller.uninstall`
  already special-cases `if (descriptor.name == "kitten")`. When v0.2
  adds Piper / Kokoro / etc., the provider needs to become a map keyed
  by engine name so the right JNI handle is released for the right
  uninstall.
- **Why deferred:** v0.1 only ships Kitten, so the current shape works.
  The forward-incompatibility cost is one trivial refactor when adding
  the second engine.
- **How to finish:** change the Hilt provider to
  `Provider<Map<String, NativeEngineHandle>>` populated via `@IntoMap`
  + `@StringKey(engineName)`; update `EngineInstaller.uninstall` to
  look up the right handle by `descriptor.name` and drop the
  `if (descriptor.name == "kitten")` special-case.
