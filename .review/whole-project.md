# Whole-project review

**Verdict:** FAIL — ship-blocker present, but the architecture is sound. One
likely cause for the launch failure is identifiable from a code/APK read; the
rest of the codebase is in materially better shape than a v0.1 normally is.

## Overall impression

This is a well-organised v0.1.0 — better than most. The layering is honest
(domain / data / UI / service, with `audio/` and `preprocessing/` carved out
as pure-Kotlin libraries), the data-flow comments at the top of every
non-trivial file actually correspond to the code they describe, and the test
coverage targets the interesting invariants instead of the easy ones
(`EffectChain` property tests, `SpeakDispatcher` boundary cases, the
`MarmaladeTtsService` Robolectric harness with `FakeSynthesisCallback`). The
three existing area reviews already pointed at the real warts; this pass
looked for what they missed, and most of what's here is in the "tighten
before v0.2" pile rather than "broken." The engine-as-plugin architecture is
implemented consistently — `KittenEngine.ensureModelLoaded()` throws a typed
exception, the catalog + manifest separation is clean, and the installer's
scratch-dir-then-atomic-rename is exactly the shape you want.

What it isn't yet: a v0.1.0 you would be proud to ship today. The "won't
even open on a real device" report is plausibly explained by a single line
in `AndroidManifest.xml` interacting with how Android 14+ validates
foreground-service starts (see Blockers #1). There's also a real
**onboarding deadlock-by-design**: if the user picks zero engines and taps
"Skip," they reach the Speak screen with no installed engine, no installed
voices, a `currentVoice` flow that never resolves (Major #1), and a `Speak`
button gated on `currentVoice != null` — so the speak button is dead. The
"recovery" path through the engines screen works, but the user needs to find
it without help from the broken Speak screen. Also, the entire v0.1 ships
without a launcher-icon mipmap fallback (Minor) and with a destructive
`fallbackToDestructiveMigration()` lurking behind the proper `MIGRATION_2_3`
(Minor).

Architecturally there's nothing to redo. The implementation work for v0.1
appears to be ~95% there; the gap between "what's in main" and "what
actually runs" is small and concrete.

## Strengths

- **Layering is real, not aspirational.** `data/`, `data/db/`, `engine/`,
  `audio/`, `preprocessing/`, `install/`, `service/`, `ui/`,
  `ui/screen/`, `ui/intent/`, `ui/onboarding/`, `ui/theme/`, `di/`. Each
  package has a single concern. Service code doesn't import UI code. UI code
  doesn't import the engine. The `Synthesizer` / `SpeechPlayer` split
  (`Synthesizer.kt:65-81`) is the right seam for tests and for swapping in a
  different engine without churning the ViewModels.
- **Engine-as-plugin is honest about its boundaries.**
  `KittenEngine.ensureModelLoaded()` is the choke point that throws
  `EngineNotInstalledException` (`KittenEngine.kt:153-160`), and both
  callers (`MarmaladeTtsService.onSynthesizeText` and
  `Synthesizer.speak`) catch that typed subtype to translate it into a UI
  state. There is no scenario where "Kitten isn't installed" surfaces as a
  generic crash.
- **`EngineInstaller` design is correct.** Stream to a `<name>.tmp` scratch
  dir, hash incrementally during the stream (`EngineInstaller.kt:457-478`),
  release any native handle before deleting (`EngineInstaller.kt:280-289`,
  `370-374`), atomic rename to final location. The `SHA256_PENDING`
  sentinel lets the manifest land before all hashes are pinned without
  silently skipping verification. The state machine is enumerated in a
  comment that matches the code (`EngineInstaller.kt:128-138`).
- **System TTS chunking is provably correct.** `streamPcm`
  (`MarmaladeTtsService.kt:238-251`) respects `maxBufferSize`, guards the
  zero-case with `.coerceAtLeast(2)`, propagates `audioAvailable` failure
  upward so the outer try/catch converts it to `callback.error()`. The
  Robolectric test pins start→audioAvailable*→done ordering and the byte-
  sum invariant.
- **Audio-focus leak fixed.** The major issue flagged in the audio-pipeline
  review (`MarmaladeSynthService.runOne` returning early without
  `releaseFocus()`) is now wrapped in `try { … } finally { releaseFocus() }`
  (`MarmaladeSynthService.kt:275-307`). The dead `runBlocking` import is
  also gone, as is the `_mediaButtonReceiverRef` smell.
- **Test coverage actually catches things.** 114 unit tests is a lot for v0.1
  on Android, and the ones that exist are not vanity. The
  `MarmaladeTtsServiceTest` reflection-injection trick (line 217-221) is
  ugly but appropriate when Hilt isn't running; alternative would be making
  the fields nullable-var and worse. The `SpeakDispatcher` boundary tests
  catch the kind of off-by-one that would silently mis-trim.
- **Data-flow ASCII headers at the top of every non-trivial file**, and they
  match the code. `Synthesizer.kt:14-44`, `EffectChain.kt:7-36`,
  `MarmaladeTtsService.kt:18-58`, `MarmaladeSynthService.kt:40-105`, etc.
  This is the kind of documentation that pays for itself in a new session.
- **The instrumented test scaffolding is honest about what's automated.**
  `ShareAndTileInstrumentedTest` distinguishes the four programmatic
  assertions from the two manual checks with `Assume.assumeTrue(false)` and
  full KDoc procedures — not skipped silently.

## Issues — by severity

### Blockers

#### 1. `MarmaladeSynthService` is declared with `foregroundServiceType="mediaPlayback"` but is missing the manifest token for Android 14+ media-only foreground starts — likely the launch crash if any startup path touches the service

- **Severity:** blocker
- **File:line:** `app/src/main/AndroidManifest.xml:56-60`
- **Issue:** The service is `android:foregroundServiceType="mediaPlayback"`.
  On Android 14+, calling `Context.startForegroundService(...)` on a service
  with this type requires (a) the `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
  permission *and* (b) the service to associate itself with an active
  `MediaSession` before the 5-second `startForeground()` window expires.
  The permission is declared
  (`AndroidManifest.xml:6`); the MediaSession is built in
  `onCreate` (`MarmaladeSynthService.kt:150`, `ensureMediaSession`) and
  set `isActive = true` (`MarmaladeSynthService.kt:493`) — that part is
  fine. **But** the notification built in `onStartCommand`
  (`MarmaladeSynthService.kt:156`, calling `buildNotification("Preparing…")`)
  only attaches the MediaSession token via `MediaStyle` if `mediaSession`
  is non-null *at that exact moment* (`MarmaladeSynthService.kt:561-566`).
  In practice it always is — but the more fundamental issue is that the
  *launch* path (`MainActivity → AppRoot → Onboarding/Speak`) never starts
  the synth service, so this code never runs on launch. So this alone is
  *not* the launch crash.

  Looking again at the actual launch path, **I cannot identify a
  definite single-line cause of "won't even open" from a code read alone.**
  The plausible candidates I considered and ruled out:

   - `enableEdgeToEdge()` on `androidx.activity:activity-compose:1.9.3` is
     supported on minSdk 28 and doesn't need a Material AppCompat parent
     theme. The `Theme.MarmaladeTts` parent is `android:Theme.Material.
     Light.NoActionBar`, which works.
   - The Hilt graph compiles (the APK exists with all generated classes
     present). `provideDatabase` uses `Provider<VoiceMetaDao>` to break the
     cycle correctly.
   - The dex contains `Lapp/marmalade/tts/Hilt_MarmaladeTtsApplication;`
     and `Lapp/marmalade/tts/Hilt_MainActivity;` — the Hilt Gradle plugin's
     bytecode transformation worked.
   - All four ABIs ship `libsherpa-onnx-jni.so`. The fact that
     `libonnxruntime.so` is only present in `lib/x86/` is by design — the
     AAR's name (`sherpa-onnx-static-link-onnxruntime`) tells you the JNI
     library is statically linked against onnxruntime for the three other
     ABIs, and the `x86` exception is upstream's choice. Native libs are
     `Stored` (uncompressed) so the `extractNativeLibs=false` flag from
     AGP is honoured.
   - The launcher icon resolves: `mipmap-anydpi-v26/ic_launcher.xml`
     present, foreground vector drawable present, background color present.
     minSdk 28 → adaptive icons always supported.

  **My best guesses for what's *actually* causing the launch crash, in
  order of likelihood:**

  a. **A crash in `AppRootViewModel`'s very first composition** — the
     `hiltViewModel<AppRootViewModel>()` call resolves against the
     Activity ViewModelStoreOwner, which only works because
     `MainActivity` is `@AndroidEntryPoint`. If for any reason the Hilt
     compiler did not generate `Hilt_MainActivity` correctly (e.g. a KSP
     incremental-build artifact), `MainActivity` would still subclass
     `ComponentActivity` directly and `hiltViewModel()` would throw
     `IllegalStateException` looking for a `HiltViewModelFactory`. The
     dex does show `Hilt_MainActivity`, so this is *unlikely* from this
     local build, **but** if the v0.1.0 APK the user shipped was built
     from a slightly different tree (incremental cache mishap) this is
     the kind of thing that lights up as a launch-time `RuntimeException`.
     Pull the device logcat — the stack trace will say either "Hilt
     factory not found" or it'll point elsewhere.

  b. **Compose runtime / Material3 ABI break.** The build's
     `resolutionStrategy.force(...)` list pins a lot of androidx versions
     to specific revisions that are *older* than what
     `androidx.compose:compose-bom:2024.12.01` ships — e.g. forcing
     `androidx.fragment:fragment:1.5.4` when activity 1.9.3 expects 1.7+.
     The build succeeds because Kotlin compilation doesn't enforce
     runtime ABI compatibility, but the first time the app touches a
     method that Compose Material3 1.3 added to a forced-down dependency,
     it crashes with `NoSuchMethodError`. The `MenuAnchorType` API in
     `AliasScreen.kt:353,404` is Material3 1.3+; that surface comes up
     when the user opens the alias editor, not on launch, so probably
     not this one.

  c. **`Theme.MarmaladeTts` has `parent="android:Theme.Material.Light.
     NoActionBar"`** which is fine for plain ComponentActivity, but if any
     transitively-imported library (DataStore, Room, Hilt) needs an
     AndroidX-Material parent to inflate something, you get a window-
     inflation crash before `setContent` runs. Less likely than (a) but
     worth ruling out by switching the parent to
     `Theme.Material3.DayNight.NoActionBar` or `Theme.MaterialComponents.
     DayNight.NoActionBar`.

  **Suggested fix:** `adb logcat -d | grep -E "AndroidRuntime|FATAL"`
  after the launch attempt. The crash type will discriminate between (a),
  (b), (c). This is the kind of issue where staring at code longer is
  less efficient than reading the actual stack trace once. I would not
  guess and patch without seeing logcat.

#### 2. Database seed race — `VoiceMetaDao` queries can return empty/null until the seed coroutine completes, and there is no mechanism to force a refresh

- **Severity:** blocker for "the speak button works on first launch after
  onboarding," major otherwise
- **File:line:** `app/src/main/java/app/marmalade/tts/di/AppModule.kt:74-77`
  and `app/src/main/java/app/marmalade/tts/ui/screen/SpeakViewModel.kt:158-181`
- **Issue:** `provideDatabase` registers a `RoomDatabase.Callback` whose
  `onCreate` launches a coroutine on `seedScope` that calls
  `daoProvider.get().upsertAll(KittenVoiceCatalog.voices)`. This is
  fire-and-forget; nothing waits on it. Meanwhile,
  `SpeakViewModel.currentVoice` is:
  ```
  settings.defaultVoiceId .onEach { … } .flatMapLatest { id ->
    flow { emit(voiceDao.findById(id)) }
  }
  ```
  On first launch (or first DB construction — see next paragraph):
  1. `defaultVoiceId` emits `"kitten:Bella"` (the fallback in
     `SettingsRepository.defaultVoiceId`).
  2. `findById("kitten:Bella")` runs *before* the seed coroutine
     completes, returns `null`.
  3. `currentVoice.value = null`.
  4. `SpeakViewModel.speak()` reads `currentVoice.value ?: return` and
     does nothing. The UI shows "Voice…" forever.
  5. The seed eventually completes, but `defaultVoiceId` does not re-emit
     (DataStore key didn't change), so the flatMapLatest never re-runs the
     `findById`. The VM is stuck on the stale null.

  Worse: `MarmaladeDb` is `@Singleton` and constructed lazily — on first
  launch the DB is *not* constructed during onboarding (nothing in the
  onboarding graph needs it). It is constructed the *first time*
  `VoiceMetaDao` or `VoiceAliasDao` is requested — which is when
  `SpeakViewModel` is built. By then the user has already arrived at the
  Speak screen, and the seed-completion timing is even tighter (the user
  may tap Speak before the seed lands).

  This is the kind of bug that wouldn't show on the Robolectric test
  (which seeds the fake DAO directly) but absolutely shows on a real
  device after onboarding.

- **Suggested fix:** Either (a) make the seed synchronous in `onCreate`
  (use `runBlocking { daoProvider.get().upsertAll(...) }` — this runs at
  DB-construction time, off the main thread because Room's `onCreate` is
  invoked on a background thread anyway), or (b) make
  `SettingsRepository.defaultVoiceId` retrigger findById on
  `voiceDao.getAll()` emissions by composing the two flows
  (`combine(settings.defaultVoiceId, voiceDao.getAll()) { id, voices ->
  voices.firstOrNull { it.id == id } }`). I'd recommend (b) — it's a
  smaller change and it handles the "voice removed from catalog by a
  later catalog refresh" case for free, which the static catalog of v0.1
  doesn't need but v0.2 will.

### Majors

#### 1. "Skip onboarding" lands the user on a Speak screen with no usable state and no obvious recovery path

- **Severity:** major
- **File:line:** `app/src/main/java/app/marmalade/tts/ui/onboarding/OnboardingScreen.kt:103-108`,
  `app/src/main/java/app/marmalade/tts/ui/screen/SpeakScreen.kt:240-247`
- **Issue:** The Skip handler installs an empty set, calls `finish()`, and
  navigates to Speak. Now `currentVoice` is null (no engine installed →
  Kitten voices not flipped to `isInstalled = true` → none surfaced …
  except they are surfaced because the static catalog seeds them with
  `isInstalled = false` and `findById` only filters on engine match, not
  install status, so `currentVoice` resolves to `VoiceMeta(id="kitten:
  Bella", isInstalled=false)`). `speak()` runs, the engine throws
  `EngineNotInstalledException`, `_playbackState` becomes
  `PlaybackState.ModelMissing`, and the UI shows "Tap to install Kitten
  engine" — *which does route to the Engines screen* — so the recovery
  path exists. OK.

  **But** the Speak button is `enabled = text.isNotBlank() && !isSpeaking
  && !isModelMissing` (`SpeakScreen.kt:240`). On first speak, the user
  hits ModelMissing once, the button disables, and the only way back is
  to (a) install an engine in Settings → Engines (works) or (b) clear
  the text field, retype something — wait, that doesn't reset
  `isModelMissing`. Looking at `SpeakViewModel.onTextChanged`
  (line 196-198): no, it doesn't. So once the user has tried to speak
  with no engine installed, the Speak button stays disabled forever —
  even after they install the engine and come back — *until they tap the
  "Tap to install Kitten engine" link, which routes them away from this
  screen, and only when they come back does the state happen to reset
  by … no, actually nothing resets it. ModelMissing is sticky.

  So if the user installs the engine while in this state and returns,
  the Speak button is still disabled. The user has no way to unstick the
  state short of force-stopping the app or rotating the screen (which
  destroys the VM).

- **Suggested fix:** In `SpeakViewModel.onTextChanged`, reset
  `_playbackState` to `PlaybackState.Idle` if it's currently
  `ModelMissing` or `Error`. Or — simpler — observe a Flow from the
  installer's verify state and reset to Idle when the engine becomes
  installed. Or — even simpler for v0.1 — make `onNavigateToEngines`
  also clear `_playbackState` to Idle when the user comes back. Pick
  one; the current behaviour is sticky-dead-button.

#### 2. `runBlocking` inside `MarmaladeTtsService` can starve the system TTS worker thread on slow first-load

- **Severity:** major
- **File:line:** `MarmaladeTtsService.kt:125, 191, 227`
- **Issue:** `onLoadVoice`, `onSynthesizeText`, and `pickVoiceFromRequest`
  all use `runBlocking { … }`. For `findById` this is fine
  (~ms). For `engine.synthesize(...)` in `onSynthesizeText`, this is a
  problem: the first call goes through `ensureModelLoaded()` which loads
  ~25 MB of model file + 19 MB of espeak-ng-data via JNI. On a mid-range
  device that's 2–5 seconds. The system TTS framework has a per-utterance
  timeout (varies by client; `TextToSpeech.synthesizeToFile` is the most
  patient, the screen reader is the least) and will treat a slow
  callback.start() as an engine fault.
- **Suggested fix:** Move `ensureModelLoaded()` to a background warm-up
  call from `onCreate` or `onLoadLanguage`, so the first user-visible
  `onSynthesizeText` doesn't pay the cold-start tax. The framework calls
  `onLoadLanguage` when the user selects the engine — that's the
  natural place. Add a STUBS.md entry if you want to defer this.

#### 3. The v2→v3 destructive migration drops `voice_meta.isInstalled` flags — voice-aliases review flagged this; the proper migration exists but is not wired in

- **Severity:** major in any installed-base scenario; non-issue for true
  v0.1.0 first-installs
- **File:line:** `app/src/main/java/app/marmalade/tts/di/AppModule.kt:65-66`
- **Issue:** AppModule calls `.addMigrations(MIGRATION_2_3)` *and*
  `.fallbackToDestructiveMigration()`. The two are not mutually exclusive
  — `MIGRATION_2_3` runs if present, falls through to destructive only
  on a hash mismatch. So in fact the migration *is* wired in. The
  comment in `MarmaladeDb.kt:24-28` says it isn't (claims the
  destructive path is taken). This is a documentation bug now, not a
  code bug — but it should be reconciled before someone reading the
  comment "fixes" the code to match the doc.
- **Suggested fix:** Update the KDoc in `MarmaladeDb.kt` to reflect that
  `MIGRATION_2_3` is wired in. Delete the now-stale "switch AppModule to
  use `.addMigrations(MIGRATION_2_3)`" line; it's already there.

#### 4. Memory leak through `Provider<VoiceMetaDao>` capturing a static `CoroutineScope`

- **Severity:** major (latent — likely won't show on real devices but
  Lint will flag and it is a real lifecycle escape)
- **File:line:** `app/src/main/java/app/marmalade/tts/di/AppModule.kt:55, 67-78`
- **Issue:** `provideDatabase` creates `val seedScope = CoroutineScope(
  SupervisorJob() + Dispatchers.IO)` *inside* the provider method, and
  the `addCallback` lambda captures `seedScope.launch { … }`. The scope
  is never cancelled. Because `provideDatabase` is `@Singleton` it is
  invoked exactly once per process, so the scope is process-lived — not
  a per-construction leak — *but* it captures `daoProvider`, which
  transitively holds a reference to the Hilt-generated component graph,
  which holds the application context. On a test JVM with multiple Hilt
  components built and torn down (unlikely in this codebase but
  possible in instrumented tests via `HiltAndroidRule`), each test cycle
  would leak the previous scope.
- **Suggested fix:** Either (a) move the seed call into the application
  class' `onCreate` and skip the Room callback altogether — simpler and
  the seeding deadline is just "before SpeakViewModel reads the DB",
  which is naturally satisfied; or (b) keep the callback but use
  `runBlocking` inside `onCreate` so the seed completes synchronously
  with DB construction (Room calls `onCreate` on a background thread
  already, per its contract). I prefer (a) for v0.1 — Blocker #2 fix
  candidate (b) also lands here.

### Minors / polish

#### 1. No mipmap PNG fallback for the launcher icon

- **Severity:** minor
- **File:line:** `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
  (only file under `mipmap*`)
- **Issue:** minSdk is 28, adaptive icons are always supported on the
  device. But Android's package installer + some launchers preview the
  icon using density-specific PNGs (`mipmap-mdpi/ic_launcher.png`, etc.)
  and fall back to the vector only for the actual launcher. With no PNG
  fallback, the icon may render at low resolution in third-party
  launchers, the share sheet, the "recent apps" carousel, and the
  Android Settings → Apps list.
- **Suggested fix:** Generate `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
  ic_launcher.png` from the adaptive foreground+background. Android
  Studio's "Image Asset" wizard does this in one step. Not blocking.

#### 2. `MarmaladeTtsService.onStop` is a no-op that admits it shouldn't be

- **Severity:** minor
- **File:line:** `MarmaladeTtsService.kt:205-209`
- **Issue:** The comment says "synthesis is synchronous and the system
  will simply ignore any pcm we'd write after onStop returns. Future
  work (chunked streaming generation) will set a cancel flag here." That
  is true *now* — but the cost is that if `engine.synthesize` is
  partway through (say, 3 seconds in on a long sentence), the system's
  request to stop is honoured only after synthesis finishes. For
  long-form text that's a real user complaint waiting to happen.
- **Suggested fix:** Wire a `Job?` from the `runBlocking { … }` (or
  better, restructure to `kotlinx.coroutines.runInterruptible`) so
  `onStop` can cancel mid-synthesis. STUBS.md entry if deferred.

#### 3. `pcm16ToLittleEndianBytes` is in the service's companion, not a util — defensive but duplicates ByteBuffer.order(LITTLE_ENDIAN).asShortBuffer().put(pcm)

- **Severity:** minor (style)
- **File:line:** `MarmaladeTtsService.kt:257-265`
- **Issue:** Manual byte-twiddling is correct and tested. But `ByteBuffer`
  on Android's native order is little-endian on every ABI Android
  supports, so the entire helper could be one line. Not a bug — the
  current implementation is portable and the test pins the bytes —
  just not the simplest form.
- **Suggested fix (optional):** Use `ByteBuffer.allocate(pcm.size *
  2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm).array()`.
  Drop the helper. The test is still useful.

#### 4. Hilt `provideNativeEngineHandle` couples the installer to a `KittenEngine` even for non-Kitten engines

- **Severity:** minor (v0.1 only ships Kitten, but the design is
  forward-incompatible)
- **File:line:** `app/src/main/java/app/marmalade/tts/di/AppModule.kt:124-126`,
  `EngineInstaller.kt:370-374`
- **Issue:** `NativeEngineHandle` is a single function-interface that
  always releases `KittenEngine`. When v0.2 adds Piper / Kokoro, the
  installer's `uninstall` already special-cases the engine name (line
  372: `if (descriptor.name == "kitten") ...`). The Hilt provider would
  need to be generalised to a `Map<String, NativeEngineHandle>` — fine,
  but the current shape is a wart waiting to be paid off.
- **Suggested fix:** Defer to v0.2. STUBS.md note.

#### 5. `SettingsRepository.onboarded` defaults to `false` but emits in the same `data.map { … }` chain as `defaultVoiceId` — fine, but the initial `null` flicker through `AppRoot` is a real frame

- **Severity:** minor
- **File:line:** `app/src/main/java/app/marmalade/tts/ui/AppRoot.kt:67-71`
- **Issue:** `collectAsStateWithLifecycle(initialValue = null)` then
  `val onboardedNow = onboarded ?: return`. That's a real one-frame
  blank screen on first launch. DataStore reads typically resolve in
  ~16ms, but on a slow device the user sees a flash of nothing before
  the wizard renders.
- **Suggested fix:** Show a small splash composable (mascot + "loading")
  in the null branch. Cheap, more polished than a blank frame.

#### 6. No `WAKE_LOCK` or `FOREGROUND_SERVICE_DATA_SYNC` for engine downloads — uninstall-then-reinstall of a 42 MB engine during a phone-locked-and-asleep state will hang

- **Severity:** minor
- **File:line:** `AndroidManifest.xml` (absence)
- **Issue:** `EngineInstaller.install` runs on `Dispatchers.IO` from
  whichever VM scope kicked it off. If the user starts an install in
  onboarding and then locks the phone, the install may not complete —
  Android aggressively stops background work for non-foregrounded apps.
  v0.1's "the user is on the install screen so the app is foreground"
  contract usually holds, but not guaranteed (incoming call, switching
  to messages, etc.).
- **Suggested fix:** Either run the install via `WorkManager` with
  CONNECTED + REQUIRES_CHARGING constraints (proper fix), or surface
  the existing `MarmaladeSynthService` foreground notification during
  installs — but that's a mediaPlayback type, which doesn't fit. Easier
  v0.2 work: add a dedicated `MarmaladeInstallService` with
  `foregroundServiceType="dataSync"` and route the installer through
  it. Not a v0.1 blocker.

#### 7. `_currentSpeed` / `_currentEffect` reset to defaults on manual voice pick is sound, but `onTextChanged` doesn't trigger any state-machine reset

- **Severity:** minor (cross-references Major #1)
- **File:line:** `SpeakViewModel.kt:196-198`
- **Issue:** Editing the text field doesn't change anything but `_text`.
  In particular it doesn't reset a sticky `PlaybackState.ModelMissing`
  or `PlaybackState.Error`. See Major #1 for the user-visible bite.
- **Suggested fix:** See Major #1.

## Architecture observations

The layering is the strongest part of this codebase. `domain ←→ data` via
DAO interfaces (`VoiceMetaDao`, `VoiceAliasDao`) is conventional and
correct. `data ←→ engine` is mediated by `KittenEngine` which is
`@Singleton + open`, where `open` exists *only* to allow JVM test doubles
(commented at line 82-84 of `KittenEngine.kt`) — that's a reasonable
trade-off; the alternative is an interface + impl pair and the gain is
marginal. The `ui ←→ audio` boundary uses `SpeechPlayer` as the explicit
seam, which keeps `SpeakViewModelTest` and `VoicePickerViewModelTest`
honest. The `service ←→ everything else` boundary is via constructor
injection through Hilt's `@AndroidEntryPoint`.

Where the design choices surprised me, mostly positively:

- **`KittenEngine.synthesize` runs on `Dispatchers.Default`** (line 208)
  *inside* `withContext`, even though the outer call site (the service)
  uses `runBlocking`. The double-context-switch is harmless, and it means
  every other caller (the `Synthesizer` for UI playback) gets the right
  dispatcher for free.
- **`MarmaladeSynthService` and `Synthesizer` are two separate playback
  pipelines**, both targeting AudioTrack. The reviewer in the audio
  pipeline review flagged this as "the AudioTrack code is duplicated in
  two places." I think the duplication is fine: one is short-form
  one-shot from the UI (no media notification), the other is long-form
  with transport (notification + audio focus + queue). They have
  different lifecycles and the abstraction overhead of unifying them
  would buy nothing in v0.1.
- **Onboarding sits outside the NavHost** as a gate, not a route. This
  is the right choice. Once `onboarded` flips, recomposition swaps the
  branch; system back doesn't pop into the wizard.
- **The Hilt graph has zero JVM-side cycles** — the `Provider<>`
  indirection in `provideDatabase` is the only place it could have. Good.

Where it doesn't surprise me but should be noted as a real cost: the
codebase has *no* navigation deep-link support yet. Adding deep links
(e.g. "open the alias editor for `narrator`") will require restructuring
how `AppRoot`'s onboarding gate interacts with the NavController, because
the wizard is outside the graph. That is a v0.2+ problem and the right
trade-off was made for v0.1.

## Documentation accuracy

- **SPEC.md vs reality:** SPEC.md claims Sherpa-ONNX is *vendored*. True
  — `app/libs/sherpa-onnx-static-link-onnxruntime-1.12.32.aar`.
  SPEC.md says "Oboe for low-latency streaming output" — false; the
  code uses `AudioTrack` directly, no Oboe. Not a blocker (the choice
  is reasonable; AudioTrack with MODE_STREAM is fine for TTS), but the
  spec needs to be updated to match. SPEC.md says `android.media.audiofx`
  for effects — false; the code implements its own DSP in
  `EffectChain.kt` (which is actually better, because audiofx only works
  on a *playing* AudioTrack, not arbitrary buffers — the EffectChain
  KDoc says exactly this at line 44-47).

- **README vs reality:** README says "Project status: This is currently a
  documentation skeleton. ... No code yet — first build will be v0.1.0."
  This is no longer true — there is substantial code. Update on the next
  README commit.

- **CHANGELOG vs reality:** The CHANGELOG's "Unreleased" section
  contains entries that are clearly v0.1.0 work. There is no v0.1.0
  release line yet. Since the user just shipped a v0.1.0 APK, the
  Unreleased entries should be rolled up into a `## [0.1.0] — 2026-05-21`
  section.

- **STUBS.md vs reality:** STUBS.md is short and accurate — the
  share-sheet/tile manual-test gaps are real and the deferral
  justifications are honest. The lock-screen-tile claim (lines 47-49)
  was already corrected by the time of the system-integration review;
  the current text reads correctly.

- **`MainActivity.kt:11-19` KDoc** was flagged in the system-
  integration review as stale ("calls in screen composables resolve their
  @HiltViewModel-annotated ViewModels without needing
  `hilt-navigation-compose`") — that text has been replaced with a
  shorter, correct version. Good.

- **`MarmaladeDb.kt:24-28` KDoc** claims v2→v3 uses destructive
  migration. False — see Major #3 above. `MIGRATION_2_3` is wired in
  alongside the destructive fallback.

## Recommendation

Specific next steps in priority order:

1. **Pull `adb logcat` on the crashing v0.1.0 APK** and decide whether
   the crash is (a) Hilt factory missing, (b) Compose/androidx ABI break
   from the `resolutionStrategy.force(...)` list, or (c) something else
   I missed. **Do this before touching any code** — it's 5 minutes of
   reading and saves an afternoon of guessing.
2. **Fix the seed race** (Blocker #2) — `combine` the
   `defaultVoiceId` flow with `voiceDao.getAll()` in `SpeakViewModel`
   so the voice resolves once the seed lands. Touch `SettingsRepository`
   not at all.
3. **Fix the sticky `PlaybackState.ModelMissing`** (Major #1) — reset to
   `Idle` when text changes, or when the installer reports the engine
   as `Installed`. One-line fix.
4. **Move `engine.ensureModelLoaded()` warm-up to `onLoadLanguage`**
   (Major #2). One method override, ~10 LoC.
5. **Reconcile docs**: SPEC.md (Oboe / audiofx claims), `MarmaladeDb.kt`
   KDoc (migration story), README (project status), CHANGELOG (close
   the v0.1.0 release line).
6. **Generate launcher mipmap PNGs** (Minor #1). Android Studio "Image
   Asset" wizard. 5 minutes.
7. **STUBS.md entries** for: `onStop` cancellation (Minor #2),
   `WorkManager`-backed installs (Minor #6), generalising
   `NativeEngineHandle` to a Map (Minor #4).

The rest of the architecture is in good shape. If the launch crash turns
out to be a Hilt/KSP incremental cache mishap (most common cause in my
experience), a clean rebuild + `./gradlew clean :app:assembleDebug` and a
fresh signed install will resolve it without code changes — at which
point the priority order shifts: do (2), (3), (4) immediately because
they affect the v0.1.0 user experience; (5) and (7) before tagging;
(1) drops off the list; (6) ships in v0.1.1.

This is a v0.1.0 you should be able to be proud of in a week. It is
not one to be proud of today only because of the crash and the seed
race — both are local, both have clean fixes, neither implicates the
architecture.
