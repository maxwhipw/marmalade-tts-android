# STUBS — marmalade-tts-android

Entries added by implementation agents when functionality is deferred.
Each entry must explain *what* is missing, *why* it was deferred, and
how to finish it.

## v0.1 — Engine + System TTS milestone

### KittenEngine end-to-end synthesis (instrumented test deferred)
- **File:** `app/src/main/java/app/marmalade/tts/engine/KittenEngine.kt`
- **Status:** Engine + installer + onboarding all in place. The
  installer downloads Kitten into `${filesDir}/engines/kitten/` from
  the HF mirror, sha256-verifies every file, and atomically renames
  on success. `KittenEngine.ensureModelLoaded()` then reads from that
  directory and builds an `OfflineTts`. **Not yet verified on a real
  device** — no instrumented test exists.
- **Why a unit test won't cut it:** `OfflineTts` loads native libraries
  through JNI. Neither JNI nor the Sherpa-ONNX `.so` survives in a JVM
  unit test (`./gradlew testDebugUnitTest`) — they need an Android
  runtime.
- **What an instrumented test would assert:**
  1. Install the debug APK on a connected device.
  2. Run onboarding to completion (installs Kitten over the network).
  3. Pick Marmalade in Settings → Languages → Text-to-speech, tap
     "Listen to an example" — audio should play at 24 kHz.
  4. Programmatically: synthesize "hello world" via the system TTS
     API, expect a non-empty PCM ShortArray at 24 kHz, duration
     roughly proportional to text length (catches zero-length-output
     bugs), no crashes through an uninstall + reinstall cycle.
- Requires adding `androidx.test.ext:junit` (`androidTest/` source set).

### VoiceMetaDao Room queries (integration test deferred)
- **File:** `app/src/main/java/app/marmalade/tts/data/db/VoiceMetaDao.kt`
- **Status:** DAO implemented; pure data is unit-tested via
  `KittenVoiceCatalogTest`. The DAO itself is a thin Room interface —
  no business logic to assert in isolation.
- **What needs to happen:** add `androidx.room:room-testing` and
  `org.robolectric:robolectric` to `testImplementation` and write an
  in-memory DB test that:
  - upserts the `KittenVoiceCatalog.voices` list,
  - observes `getByEngine("kitten")` as a Flow,
  - flips one row's `isInstalled` and asserts the Flow emits again.
  Build-config edits are off-limits for the implementation agent —
  this is a follow-up for the orchestrator / a build agent.
- **Why a unit test won't cut it (without new deps):** Room needs a
  runtime; the bare `junit:4.13.2` setup can't host an in-memory DB
  without `room-testing` + a Robolectric (or instrumented) host. The
  DAO is trivial enough that an integration test is the right tool.

### hilt-navigation-compose deferred (P2 — boilerplate cleanup)
- **Files:** `SpeakScreen.kt`, `VoicePickerScreen.kt`,
  `OnboardingScreen.kt`, `EnginesScreen.kt` — all use
  `androidx.lifecycle.viewmodel.compose.viewModel()` instead of
  `androidx.hilt.navigation.compose.hiltViewModel()`.
- **Status:** `@HiltViewModel`-annotated ViewModels still resolve because
  `MainActivity` is `@AndroidEntryPoint`, which makes
  `defaultViewModelProviderFactory` a `HiltViewModelFactory`. That factory
  is what Compose's `viewModel()` picks up via the activity's
  `HasDefaultViewModelProviderFactory` contract.
- **What needs to happen:** add
  `implementation("androidx.hilt:hilt-navigation-compose:1.2.0")` and
  swap `viewModel()` → `hiltViewModel()` in all four screen composables.
  Once a proper NavHost lands the `hiltViewModel()` version also gets
  nav-graph-scoped ViewModels for free.

### Share-sheet + Quick Settings tile (device verification deferred)
- **Files:**
  - `app/src/main/java/app/marmalade/tts/ui/intent/ShareIntentActivity.kt`
  - `app/src/main/java/app/marmalade/tts/service/SpeakClipboardTileService.kt`
  - `app/src/main/java/app/marmalade/tts/service/SpeakDispatcher.kt`
- **Status:** Implementation in place; `SpeakDispatcher.prepare` is
  unit-tested for trim / blank / clamp logic (8 assertions). The
  end-to-end intent plumbing (manifest filters, foreground-service
  hand-off, ClipboardManager read) needs a real device or Robolectric
  to exercise.
- **What an instrumented test would assert:**
  1. `adb shell am start -a android.intent.action.SEND -t text/plain \
     --es android.intent.extra.TEXT "hello world" \
     -n app.marmalade.tts/.ui.intent.ShareIntentActivity` produces
     audible speech and finishes the trampoline activity.
  2. Same for `ACTION_PROCESS_TEXT` via a UiAutomator selection menu
     interaction.
  3. Adding the tile from Quick Settings, copying text to clipboard,
     tapping the tile → audible speech (also from the lock screen).
  4. Empty clipboard tap → Toast "Clipboard is empty", no service start.
- **Why a unit test won't cut it:** `ContextCompat.startForegroundService`,
  `ClipboardManager.primaryClip`, and the manifest-driven intent
  routing all require an Android runtime. Robolectric would handle 1–2
  with the right `androidTest` setup but isn't currently a project dep.

### MarmaladeTtsService onSynthesizeText flow (integration test deferred)
- **File:** `app/src/main/java/app/marmalade/tts/service/MarmaladeTtsService.kt`
- **Status:** Pure helpers (`pcm16ToLittleEndianBytes`) are unit-tested.
  The orchestration around `SynthesisCallback` / `SynthesisRequest` is
  not — those are framework classes with no public constructor accessible
  from JVM tests.
- **What needs to happen:** add Robolectric + a small fake
  `SynthesisCallback`/`SynthesisRequest` (using reflection or
  `androidx.test.ext.junit`) and assert call ordering:
  `start(24000, PCM_16BIT, 1)` → N×`audioAvailable` → `done`. Validate
  that a thrown `EngineNotInstalledException` from `KittenEngine`
  yields exactly one `error()` call. This requires test deps the
  current build doesn't have.
- **Why a unit test won't cut it:** can't fake the framework callbacks
  cleanly without Robolectric / instrumented test infrastructure.
