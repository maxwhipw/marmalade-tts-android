# STUBS — marmalade-tts-android

Entries added by implementation agents when functionality is deferred.
Each entry must explain *what* is missing, *why* it was deferred, and
how to finish it.

## v0.1 — Engine + System TTS milestone

### Kitten engine manifest SHA-256 hashes (PENDING_VERIFICATION sentinel)
- **Files:** `app/src/main/java/app/marmalade/tts/install/EngineCatalog.kt`,
  `app/src/main/java/app/marmalade/tts/install/KittenEspeakDataManifest.kt`
- **Status:** The Kitten file manifest uses
  `EngineCatalog.SHA256_PENDING` as the sha256 value for every file.
  `EngineInstaller` accepts this sentinel: the file is still downloaded
  and stored, but the post-download hash check is skipped with a logged
  WARNING. Real installs will succeed; integrity verification is
  effectively trust-on-first-fetch until the placeholders are replaced.
- **Why deferred:** The implementation agent does not have network access
  to fetch the real upstream files and compute hashes itself. The
  installer infrastructure is complete and verified by unit tests; only
  the manifest data is incomplete.
- **What needs to happen (one-time, pre-release):**
  1. `curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2 -o /tmp/kitten.tar.bz2`
  2. `mkdir -p /tmp/kitten && tar -xjf /tmp/kitten.tar.bz2 -C /tmp/kitten`
  3. `python3 scripts/generate-kitten-manifest.py --bundle-root /tmp/kitten/kitten-nano-en-v0_1-fp16 > app/src/main/java/app/marmalade/tts/install/KittenEspeakDataManifest.kt`
     (rebuilds the full ~355-entry espeak-ng-data manifest with real
     sha256s and sizes).
  4. Hand-edit `EngineCatalog.kt` to replace the three
     `SHA256_PENDING` entries for `model.fp16.onnx`, `voices.bin`,
     `tokens.txt` with the matching sha256 from `sha256sum` against
     the extracted files.
- **Why a unit test can't help here:** sha256 verification is the test
  — until the manifest has real hashes, the installer can either treat
  PENDING as a soft-pass (current behaviour, logged loudly) or fail
  every install. Soft-pass keeps the install flow exercisable in
  manual / instrumented tests today.

### KittenEngine end-to-end synthesis (instrumented test deferred)
- **File:** `app/src/main/java/app/marmalade/tts/engine/KittenEngine.kt`
- **Status:** Model assets (`kitten-nano-en-v0_1-fp16`) are now bundled
  in `app/src/main/assets/kitten/`. `ensureModelLoaded()` no longer
  throws; on first call it stages `espeak-ng-data/` to `filesDir` and
  builds an `OfflineTts`. The bundle was sourced from
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2`
  (Apache 2.0; see `LICENSES/kitten-tts.md`).
- **Why a unit test won't cut it:** `OfflineTts` loads native
  libraries through JNI and reads model bytes through Android's
  `AssetManager`. Neither survives in a JVM unit test (`./gradlew
  testDebugUnitTest`) — they need an Android runtime. Verification
  path is install the debug APK, pick Marmalade in Settings →
  Languages → Text-to-speech, tap "Listen to an example."
- **What an instrumented test would assert:** synthesize "hello world"
  with the default voice, expect a non-empty PCM ShortArray at 24 kHz,
  expect total duration roughly proportional to text length (catches
  zero-length output bugs). Requires adding `androidx.test.ext:junit`
  and writing it under `androidTest/`.

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

### androidx.navigation:navigation-compose deferred (P2 — UX improvement)
- **File:** `app/src/main/java/app/marmalade/tts/ui/AppRoot.kt`
- **Status:** v0.1 ships a hand-rolled two-state router
  (`enum Route { Speak, Voices }` + `rememberSaveable` + `AnimatedContent`).
  Functionally equivalent to a `NavHost` for the current two-screen surface
  and survives rotation. The implementation agent could not add
  `androidx.navigation:navigation-compose` because `app/build.gradle.kts`
  is off-limits.
- **What needs to happen:** Once a build agent can edit
  `app/build.gradle.kts`, add
  `implementation("androidx.navigation:navigation-compose:2.8.5")` (the
  Compose BOM 2024.12.01 manages a compatible version transitively, the
  artifact itself is offline-cached). Replace `AppRoot.kt` with a
  `NavHost` rooted at `"speak"`; keep the existing `SpeakScreen` /
  `VoicePickerScreen` composable signatures so the swap is contained to
  that one file.
- **Why a unit test won't cut it:** the router is a single enum-switch
  with rotation persistence — too thin to assert against in isolation.
  The thing worth verifying (route-preserving back-stack semantics,
  deep-link handling for the future share-sheet intent) only becomes
  real once the actual NavHost lands.

### hilt-navigation-compose deferred (P2 — boilerplate cleanup)
- **File:** `app/src/main/java/app/marmalade/tts/ui/screen/SpeakScreen.kt`
  and `VoicePickerScreen.kt` — both use
  `androidx.lifecycle.viewmodel.compose.viewModel()` instead of
  `androidx.hilt.navigation.compose.hiltViewModel()`.
- **Status:** `@HiltViewModel`-annotated ViewModels still resolve because
  `MainActivity` is `@AndroidEntryPoint`, which makes
  `defaultViewModelProviderFactory` a `HiltViewModelFactory`. That factory
  is what Compose's `viewModel()` picks up via the activity's
  `HasDefaultViewModelProviderFactory` contract.
- **What needs to happen:** add
  `implementation("androidx.hilt:hilt-navigation-compose:1.2.0")`
  (offline-cached version may need confirmation) and swap
  `viewModel()` → `hiltViewModel()` in both screen composables. Once a
  proper NavHost lands the `hiltViewModel()` version also gets nav-graph-
  scoped ViewModels for free.

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
  that a thrown `UnsupportedOperationException` from `KittenEngine`
  yields exactly one `error()` call. This requires test deps the
  current build doesn't have.
- **Why a unit test won't cut it:** can't fake the framework callbacks
  cleanly without Robolectric / instrumented test infrastructure.
