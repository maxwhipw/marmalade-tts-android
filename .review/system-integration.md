# System integration + navigation review

**Verdict:** PASS with notes

The share-sheet trampoline, Quick Settings tile, dispatcher, navigation refactor
and Hilt-VM refactor all hang together. The implementation is well-commented,
internally consistent, and the unit tests for `SpeakDispatcher.prepare` are
exactly the kind of high-value pure-logic tests we want. There are a handful
of warts (one factually wrong comment with real product implications around
the lock-screen path, a stale KDoc, some non-load-bearing decorators), but
nothing that blocks shipping the milestone. Notes below.

## Strengths

- **Single-source validation.** `SpeakDispatcher.prepare()` is the only place
  blank/clamp rules live; both `ShareIntentActivity` and
  `SpeakClipboardTileService` route through it, and `MarmaladeSynthService`
  still defends in depth (`MarmaladeSynthService.kt:202`) — three layers, one
  rule. That's the right shape for a "many entry points, one contract"
  surface.
- **Clamp policy is the right one for share-sheet.** Truncating to 10 000
  chars with a logged warning rather than silently dropping the request
  matches the comment's reasoning (`SpeakDispatcher.kt:55-60`) and is what
  users will actually want when they share a long article.
- **Dispatcher unit tests are excellent.** 8 cases including the
  off-by-one-at-MAX boundary (`SpeakDispatcherTest.kt:53-59`), the
  trim-then-clamp ordering (lines 73-83), and the "we kept the head, not a
  random window" check (line 69). These are the bugs that would otherwise be
  invisible at runtime — exactly the right thing to JVM-test.
- **Instrumented test rationale is honest.** The `ShareAndTileInstrumentedTest`
  KDoc explicitly distinguishes what the programmatic assertions catch
  (manifest regressions, intent extraction, Activity→Service handoff) from
  what they don't (audible output, lock-screen behaviour). The two manual-
  only tests are `Assume.assumeTrue(false)`-gated with full procedures in
  KDoc — a clear pattern, not a hidden skip.
- **Trampoline activity is correctly minimal.** `Theme.Translucent.NoTitleBar`
  + `excludeFromRecents="true"` + `noHistory="true"` (Manifest:71-73) prevents
  the flash-of-UI failure mode. `onCreate` does the work and `finish()`
  unconditionally — no `setContentView`, no Compose pull-in.
- **Dispatcher intent is explicit and safe.** `setPackage(context.packageName)`
  on the synth-service intent (`SpeakDispatcher.kt:83`) prevents accidental
  cross-package routing if the service ever becomes exported.
- **`KittenEngineInstrumentedTest` is genuinely useful.** The WAV-header
  validation (lines 380-399) confirms the system-TTS contract end-to-end
  (PCM/mono/24 kHz/16-bit), and the `Assume`-gated structure means CI
  without an installed engine bundle degrades gracefully instead of going
  red.
- **Nav refactor is clean.** All five screens (`AppRoot`, `Speak`,
  `Onboarding`, `Engines`, `Voices`, `Aliases`) consistently use
  `hiltViewModel()`, no `rememberActivityViewModel()` references remain
  anywhere in `app/src`, and `AppRoot`'s `hiltViewModel<AppRootViewModel>()`
  outside the NavHost resolves against the activity-scope store (MainActivity
  is `@AndroidEntryPoint`, supplying the `HiltViewModelFactory`) as the
  data-flow comment claims.
- **Onboarding correctly sits outside the nav graph.** `AppRoot`'s gate is a
  Flow-driven `if`, not a route — once `OnboardingViewModel.finish()` flips
  `SettingsRepository.setOnboarded(true)`, recomposition hands the user to
  the NavHost branch. Process death won't surface the wizard again
  (DataStore persists). Good design.

## Issues

### 1. Manifest does NOT actually declare unlock-required=false; data-flow comment and STUBS.md claim it does

- **Severity:** major
- **File:line:** `app/src/main/AndroidManifest.xml:92-107`,
  `app/src/main/java/app/marmalade/tts/service/SpeakClipboardTileService.kt:35-38`,
  `STUBS.md:47-49`
- **Issue:** The tile service `<meta-data>` block declares `ACTIVE_TILE=true`
  and `TOGGLEABLE_TILE=false`, but does **not** declare any
  `android.service.quicksettings.UNLOCK_REQUIRED` meta-data. The data-flow
  comment in `SpeakClipboardTileService.kt:35` claims "the tile is declared
  `unlock-required = false` ... the flag lives in the manifest" — that flag
  is not in the manifest. The AndroidManifest comment at line 89-90 makes
  the same false claim, and `STUBS.md:47-49` does too. The manifest comment
  ("unlock-required is false") is documenting an intent that wasn't
  implemented.

  Separately: even *with* the meta-data set, clipboard access from a tile
  service while the device is locked is restricted on Android 10+
  (clipboard reads are scoped to foreground / default IME). So on locked
  screens the clipboard read will likely return null and the user will get
  the "Clipboard is empty" Toast — but that Toast itself may not display
  over the lock screen depending on OEM.
- **Suggested fix:** Decide what the actual product intent is. Either:
  (a) Drop the lock-screen claim from the comments and STUBS.md and accept
  that the tile is unlock-gated by default (simpler, probably honest about
  the clipboard-access reality), or
  (b) Add `<meta-data android:name="android.service.quicksettings.UNLOCK_REQUIRED"
  android:value="false"/>` to the service block in `AndroidManifest.xml`,
  call `isSecure`/`unlockAndRun { ... }` in `onClick` for the locked case,
  and add a manual-test note that clipboard reads from the lock screen
  may return empty on Android 10+. I'd recommend (a) unless someone has
  actually tried it end-to-end on a locked device.

### 2. `coerceToText` accepts non-text clipboard content as a non-blank string

- **Severity:** minor
- **File:line:** `app/src/main/java/app/marmalade/tts/service/SpeakClipboardTileService.kt:89`
- **Issue:** `clip.getItemAt(0).coerceToText(this)` will return a non-blank
  string for clipboard items whose `text` is null but `uri` or `intent` is
  set — `coerceToText` falls back to the URI's textual representation or to
  loading the content. So if the user copies a photo and taps the tile, the
  dispatcher will happily speak something like
  `"content://media/external/images/media/12345"` — comically literal, and
  not what "speak my clipboard" should mean.
- **Suggested fix:** Check the primary MIME type first:
  ```kotlin
  if (!clip.description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) &&
      !clip.description.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML)) {
      return null
  }
  ```
  before calling `coerceToText`. The dispatcher's blank guard will then
  correctly surface the "Clipboard is empty" Toast.

### 3. `@AndroidEntryPoint` on components that inject nothing

- **Severity:** minor
- **File:line:**
  `app/src/main/java/app/marmalade/tts/ui/intent/ShareIntentActivity.kt:50`,
  `app/src/main/java/app/marmalade/tts/service/SpeakClipboardTileService.kt:47`
- **Issue:** Neither class has any `@Inject` field; both delegate to the
  object `SpeakDispatcher`. The `@AndroidEntryPoint` annotation is therefore
  pure noise — it adds the Hilt-generated `Hilt_ShareIntentActivity` /
  `Hilt_SpeakClipboardTileService` base class and the entry-point boilerplate
  for nothing.
- **Suggested fix:** Drop both annotations. If a future revision introduces
  an `@Inject` field for, say, an analytics logger, re-add the annotation
  then. Worth a one-line comment in the class KDoc explaining the deliberate
  omission so a well-meaning future contributor doesn't add it back "for
  consistency."

### 4. `MainActivity` KDoc is stale post-Hilt-nav-compose swap

- **Severity:** minor
- **File:line:** `app/src/main/java/app/marmalade/tts/MainActivity.kt:11-19`
- **Issue:** The KDoc reads:
  > "... so the `viewModel<T>()` calls in the screen composables resolve
  > their @HiltViewModel-annotated ViewModels without needing the
  > `hilt-navigation-compose` artifact (not present in the build's offline
  > cache)."
  This contradicts the actual code, which now imports
  `androidx.hilt.navigation.compose.hiltViewModel` from every screen
  composable and from `AppRoot`. The artifact is clearly present. Leaving
  this comment in place will confuse future maintainers.
- **Suggested fix:** Replace with something like:
  > "@AndroidEntryPoint provides the Hilt-furnished `HiltViewModelFactory`
  > so `hiltViewModel()` calls in the screen composables resolve their
  > @HiltViewModel-annotated ViewModels against the correct store
  > (NavBackStackEntry inside the NavHost, Activity for AppRoot itself)."

### 5. The "trampoline-finish race" question — analysis

- **Severity:** not an issue, but worth recording
- **File:line:** `app/src/main/java/app/marmalade/tts/ui/intent/ShareIntentActivity.kt:53-72`
- **Assessment:** No race. `ContextCompat.startForegroundService(...)` is a
  fire-and-forget contract with the framework: once the call returns, the
  system has committed to delivering `onStartCommand` and the 5-second
  `startForeground()` window is owned by the *service's* lifecycle, not
  the calling Activity's. `finish()`-ing the trampoline before the service
  has actually been instantiated does not cancel the pending start — it
  just lets the Activity die early. This is the intended share-sheet
  trampoline pattern.

  The one real risk in this area — that calling `startForegroundService`
  from a background context throws on Android 12+ — does not apply, because
  `ShareIntentActivity.onCreate` runs in the foreground (the system just
  launched it via the share sheet, granting a foreground-launch token).

### 6. `popBackStack()` vs `navigateUp()` — analysis

- **Severity:** not an issue, but worth recording
- **File:line:** `app/src/main/java/app/marmalade/tts/ui/AppRoot.kt:104-115`
- **Assessment:** The comment ("popBackStack over navigateUp: no app-bar nav
  icon to honour 'up' semantics, and we always want to drop the current
  entry") is correct for the current screen set. `popBackStack()` on the
  start destination is a no-op that returns false (the back stack is empty)
  — for this graph the user reaching that state means a SpeakScreen tap on
  "Voices"/"Engines"/"Aliases" navigates *forward*, and any back from those
  pops cleanly. System back is wired through the NavController by default.
  The "exits the activity by default" claim in the data-flow header is
  technically wrong (`popBackStack` on the start destination doesn't finish
  the activity; the BackPressedDispatcher in `ComponentActivity` falls
  through to the default handler which does) — but this is a documentation
  nit, not a behaviour bug.

## Test coverage notes

- **`SpeakDispatcherTest` (JVM):** Genuinely valuable; covers every branch
  of `prepare()` including the at-MAX boundary and the trim-counts-not-
  toward-clamp ordering. This is the right place for these tests.
- **`ShareAndTileInstrumentedTest` (androidTest):** Programmatic assertions
  are *not* "things the package manager guarantees if the AAR builds" — the
  `<intent-filter>` block can absolutely be dropped from the manifest in a
  refactor without the build failing, and these tests would catch that.
  Specifically: tests 1, 2, and 5 (`shareSheetActivity_isExportedAndResolves`,
  `shareSheetActivity_acceptsProcessText`, `tileService_isExportedAndDeclared`)
  pin the three manifest invariants that are silent-failure-prone. Tests 3
  and 4 prove the trampoline self-finishes (catches a regression where
  someone wraps the dispatch in a coroutine and forgets to `finish()`).
  Tests 6 and 7 are deliberately `Assume`-gated as manual — that's
  documented in KDoc and in STUBS.md. Honest scoping.
- **`KittenEngineInstrumentedTest` (androidTest):** Solid coverage of the
  shapes that matter (sample rate, non-empty PCM, re-init after release,
  full system-TTS round-trip with WAV-header validation). The `Assume`-gated
  skip when the engine isn't installed is correct — fail-loud on the
  not-installed case (test 1) and degrade-gracefully on the others.
- **No nav-graph or onboarding tests.** Acceptable — these are UI glue;
  unit tests against Compose would test the test harness more than the
  product. An eventual Espresso/Compose-UI test for the onboarding flow
  would be valuable, but absence isn't a blocker for v0.1.

## Recommendation

**Ship with notes.** The implementation is solid and the test scaffolding
is honest about what's automated vs. manual. The one issue worth fixing
before release is **#1** (the misleading unlock-required claims in the
comments and STUBS.md) — that's a documentation/contract bug that will
mislead the next person who reads it, and it has product implications if
anyone tries to demo the lock-screen path. Issues #2-#4 are minor and can
be cleaned up opportunistically; **#5 and #6 are non-issues** captured
here because the review brief asked about them.

Recommend: a single follow-up commit that (a) reconciles the
unlock-required story (either drop the claim or add the meta-data + handle
clipboard-on-locked correctly), (b) adds the MIME-type guard around
`coerceToText`, (c) updates `MainActivity`'s KDoc, and (d) optionally drops
the unused `@AndroidEntryPoint` annotations. None of that blocks the
milestone-tagging commit.
