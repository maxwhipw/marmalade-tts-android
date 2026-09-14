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

## VITS Marmalade (engine + voice packs)

### No `v24` release asset is uploaded yet (every pack 404s)
- **Files:** `app/src/main/java/app/marmalade/tts/install/VoicePackCatalog.kt`
  (each pack's URL / sha256 / sizes).
- **What's missing:** every catalog URL 404s until the pack tarballs are
  published to the `marmalade-tts-android-engines` release `v24`. The
  hashes and both sizes per pack were computed from the built tarballs
  under `~/coding/scratch/vits-marmalade-lab/packs/` (staged trees in
  `packs/stage/<packId>/`), so the numbers are final — only the upload is
  pending.
- **Pending upload:** `uk-lada-x_low`, `is-bui-medium`,
  `is-salka-medium`, `is-steinn-medium`, `is-ugla-medium`,
  `sv-nst-medium`, `kk-issai-high`, `no-nvcc-medium`,
  `uk-ukrainian_tts-medium`.
- **Why deferred:** publishing a GitHub release asset is Max's call (it
  is a public surface), and the unit tests drive synthetic archives
  through fake fetchers, so nothing here depends on the upload.
- **How to finish:** upload the tarballs as release `v24`, then install
  each pack on device (developer engines ON) and speak a sentence in its
  language.

### Voice-picker filtering is per engine, not per pack
- **Files:** `app/src/main/java/app/marmalade/tts/data/VitsVoiceCatalog.kt`,
  `app/src/main/java/app/marmalade/tts/ui/screen/VoicePickerViewModel.kt`.
- **What's missing:** the picker hides voices whose *engine* isn't
  installed. The catalog now carries several packs, so once ANY pack is
  installed every pack's voice is listed — picking one whose tarball was
  never downloaded fails at synth with `EngineNotInstalledException`.
- **Why deferred:** the fix belongs with the pack-management UI slice
  (which needs a per-pack install/uninstall surface anyway). **This is a
  promotion blocker:** the engine must stay `developerOnly` until the
  picker filters per pack.
- **How to finish:** expose `VitsDirectEngine.installedPackIds()` (already
  public) to the picker ViewModel and filter VITS rows by it — or, more
  generally, add an optional per-voice "asset present" probe to the
  picker's installed-engine filter.

### No end-to-end audio test for the VITS path
- **Files:** `app/src/main/java/app/marmalade/tts/engine/vits/VitsDirectEngine.kt`.
- **What's missing:** the phoneme→id mapper and the config parser are
  unit-tested against the real pack config (golden id vector from a
  verified desktop run), but the ORT run itself — tensor names, dtypes,
  output squeeze, PCM conversion — has no automated coverage: it needs a
  real 20–120 MB ONNX session, which a JVM unit test cannot create and
  `connectedAndroidTest` must not run here (it wipes app data on the
  daily phone). The `sid` input for multi-speaker packs is in the same
  position: the catalog's sids are pinned against the real checkpoint
  configs in `VitsPackConfigTest`, but that each sid actually renders the
  labelled speaker can only be heard.
- **Why deferred:** a unit test would have to fake the session, at which
  point it only asserts our own mock's behaviour.
- **How to finish:** device check — install the pack, speak Ukrainian
  text from the Speak screen and from the Benchmark screen, and compare
  against `~/coding/scratch/vits-marmalade-lab/reference/lada-direct.wav`
  (the desktop render of the same checkpoint through the same recipe).
  For `kk-issai-high` and `no-nvcc-medium`, step through the per-speaker
  voices and confirm each sounds like a distinct person and that the two
  named Kazakh voices (sid 1 "Iseke", sid 3 "Raya") match their genders.
  For `uk-ukrainian_tts-medium` (grapheme frontend) the thing to hear is
  whether the case-fold-and-codepoint path pronounces normal punctuated
  Ukrainian correctly — `VitsPhonemeIdsTest` pins the id structure, but
  only a listen confirms the recipe matches what the model was trained
  on. Also confirm the pack loads with espeak never initialised (its
  frontend needs none): `adb logcat -s VitsDirectEngine` should show no
  "espeak version=" line if it is the first pack loaded in the process.
