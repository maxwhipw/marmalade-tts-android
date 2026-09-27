# marmalade-tts-android — project notes for Claude

## Orientation: read REPO-MAP.md first

`REPO-MAP.md` at the project root is the orientation pass for this
codebase. It covers module structure, key files by concern, data
flow, conventions, and known quirks (TTS engine registration
requirements, nested-Scaffold inset handling, Hilt + ComponentActivity
constraint, the runBlocking hot-path cache pattern, etc.). Read it
before doing exploratory Grep/Glob work.

When spawning a subagent for investigation or implementation in this
repo, include **"Read REPO-MAP.md first"** in the briefing. The
subagent inherits this CLAUDE.md but won't read the map unless told.

Keep `REPO-MAP.md` current — when you discover a new gotcha or
architectural choice a future agent should know, update the map in
the same commit as the change.

## Sessions: one head per workspace

This repo has its own workspace and runs the `head-session` pattern: one
head session talks to Max and spawns child sessions in their own
worktrees; its recovery record is the `## Head ledger` section of
HANDOFF.md (gitignored). CLI work belongs in the marmalade-tts-cli repo
and its own sessions, not here.

## Guardrails that have bitten before

- **Never run `connectedAndroidTest` against the daily phone.** It
  uninstalls the app under test and wipes Max's data. Before any device
  install, snapshot the app's DB and datastore, and install with
  `adb install -r` (keeps data).
- **Reproducible-build inputs are frozen** unless Max says otherwise:
  `app/build.gradle.kts` dependency/version/signing blocks, `gradle/`,
  `third_party/` submodules, the CMake files, and the release workflow.
  F-Droid verifies our signed APK against its own rebuild.
- **Planning docs stay local.** HANDOFF.md, `*-PLAN.md`, labs and design
  notes are gitignored; never add them to the tracked tree.
- **All public text needs Max's sign-off in final form**: CHANGELOG,
  fastlane metadata, README, NOTICE/LICENSES/PRIVACY, in-app copy.
- **New user-visible strings go in all 8 locales** (`values`, `-es`,
  `-fr`, `-hi`, `-it`, `-ja`, `-pt-rBR`, `-zh-rCN`).
- **All in-app playback goes through `MarmaladeSynthService`.** Android
  16 mutes a bare activity's AudioTrack.

## Remotes

**github is authoritative — and PUBLIC.** The repo is live at
github.com/maxwhipw/marmalade-tts-android with users' eyes on it
(issues enabled; Settings → Report a bug links straight to it, and the
privacy policy is served from it).

```
github   https://github.com/maxwhipw/marmalade-tts-android.git   (public, authoritative)
origin   <local-forgejo>/marmalade-tts-android.git   (Forgejo, local)
```

Push posture (Max, 2026-07-27): **Forgejo (`origin`) may be pushed
loosely** — it's local infrastructure, low consequence. **github is
heavily vetted**: every push there needs Max's explicit all-clear,
with the usual pre-push review (secrets, personal/infra details,
half-finished work) — an all-clear for one push does not carry to the
next.

## Versioning

Bump `versionCode` + `versionName` in `app/build.gradle.kts` per
release, following the scheme in its comment
(`MAJOR*10_000_000 + MINOR*10_000 + PATCH*10 + ABI`; 1.1.0 = `10010000`).
Debug builds install as `app.marmalade.tts.debug`; R8 smoke builds
(`-PsmokeRelease`) as `app.marmalade.tts.rc`, never distributed. Commits of the form `vX.Y.Z: ...` mark a version
bump.

When working on a batch of changes that would warrant separate
logical commits, split them — even if the work was done in one
session (recent v0.1.15/16/17 splits used `git stash` to peel apart
mixed working trees cleanly).

## Working patterns that have produced good results

These are conventions Max + Claude have converged on; following them
reproduces the rhythm that landed v0.3.0-alpha.7's perf work.

### Letter-named feature atoms

When working through a multi-step optimization or refactor, name each
discrete change by a single letter (A, B, C, …) and reuse those names
through the conversation, commits, and task descriptions. Lets both of
us track parallel threads at a glance — "did we land C yet?" beats
"the per-device thread autodetect change with the setting."

When you discover a follow-up to an already-named change after the
fact, suffix the digit: `A2` for "extension of A". Don't reflow letters.
Always keep the letter assignments in your task tracker.

### One change → compile → install → test → iterate

Land each lettered atom *individually* on the device before moving to
the next. Each step is small enough that:
- Compile-check via `./gradlew :app:compileFdroidDebugKotlin` catches
  trivially-bad refactors before the longer `assembleFdroidDebug`; the
  unit suite is `testFdroidDebugUnitTest`.
- Per-change logcat traces let you attribute deltas correctly. Bundling
  A+B+C into one APK and seeing a 40% speedup tells you nothing about
  *which* change earned it.

### Adaptive auto-detection + manual override

For per-device tunables (thread count is the canonical example), pair
a runtime autodetect (`CpuClusterDetector`) with a Settings-screen
manual override. Auto handles the 95% case; the override exists for
the long tail (exotic SoCs, user benchmarks). Same pattern fits
intra-op spinning, XNNPACK toggles, EP selection, etc.

### Make on-device behavior identifiable from logs

When the build state has multiple dimensions a future you might want
to attribute behavior to (precision variant, EP choice, thread count,
quantization strategy), log the active selection at engine load. The
goal isn't a particular log format — it's that when comparing a synth
that sounds good against one that doesn't, you should be able to tell
from logcat alone which build state produced each.

### Trust on-device evidence over speculation

The session that landed the perf work moved fast because each
hypothesis was verified on the device before committing to the next
step — even when the model said "this should help." XNNPACK looked
like it was regressing per-frame time until logcat surfaced the
spinning-contention warning; F (Euler tensor reuse) turned out to be
below measurement noise, which we'd never have known without
shipping it alone. Default to measuring rather than assuming.

### Compare at the model boundary when integrations diverge

When two integrations of the same model disagree on audio quality
(e.g. sherpa-Kitten vs KittenDirect, both running the identical
KittenML ONNX), the difference is in the **inputs**, not the model.
Inspect, in order: ONNX `metadata_props` for baked-in priors (this is
how we found sherpa's hidden `speaker_speed_priors: 0.8,...,0.9`), the
exact token sequence reaching the model, the voice/style indexing
logic, and the scalar reaching the `speed` input. Days of code
investigation won't find what one `onnx.load(...).metadata_props` will.

## Distribution flavors — `play` vs `fdroid`

Two product flavors share one applicationId, one signing config and one
feature set. Every feature is free in both; there is no billing
dependency and no paywall (the Pro IAP was removed in 1.0.0-beta.1).
Neither flavor has its own source set. The only differences are driven
by `BuildConfig.FLAVOR`:

- **Engine catalog:** engines marked `fdroidOnly` (Pocket TTS and its
  developer twin) are hidden from every user-facing list in the Play
  build, so its catalog matches its store listing. Routing still
  resolves them by name (`EngineCatalog.visibleTo` vs `byName`).
- **Voice packs:** unreleased packs (`VoicePack.released = false`) are
  never listed in the Play build, developer mode included
  (`VoicePackCatalog.showsUnreleased`). With that, developer mode reveals
  nothing on Play, so Advanced settings hides its toggle there
  (`EngineCatalog.developerModeRevealsAnything`).
- **Bug reports:** the Settings "Report a bug" link records the flavor.

The release workflow builds `bundlePlayRelease`/`assemblePlayRelease`;
fdroiddata's recipe needs `gradle: [fdroid]`.

## Engine bundle licensing

The Marmalade **source repo is MIT**; the **distributed APK is
GPL-3.0-or-later** because espeak-ng is compiled from source into it
(pinned submodule `third_party/espeak-ng`, commit 96f0dbfb: 1.52.0 plus
upstream's determinism fix, built by
`app/src/main/cpp/espeak-ng/CMakeLists.txt`). The full `espeak-ng-data`
tree is generated from the same source at build time and ships in the
APK too. Play forbids runtime-downloading `.so` files, so the lib must
live in the APK; the same from-source build satisfies F-Droid. One build
serves both stores. The MIT JNI shim (`app/src/main/cpp/espeak_jni.c`)
`dlopen`s the APK's own `libespeak-ng.so` and contains no espeak code.

**Engine bundles and voice packs** (downloaded after user opt-in into
`${filesDir}/engines/`) carry models and pronunciation data only, never
executable code. The Kitten and Kokoro bundles still hold a copy of
`espeak-ng-data` that the app no longer reads. [NOTICE.md](NOTICE.md) is
the authority on the licensing posture; keep it, `LICENSES/`, CREDITS.md
and the in-app catalog (`data/LicenseCatalog.kt`) in agreement.

The dictionary-only phonemizer path (using BSD-3 OpenPhonemizer ONNX +
a CMUDict-derived IPA dictionary, no espeak at all) was explored and
deferred — phonemizer-side IPA convention mismatches with the trained
Kitten model caused enough quality regression to make espeak the right
call. Revisit if there's ever a no-GPL-anywhere requirement.


## Android app publishing — knowledge base TODO

We are building a durable knowledge base on Android app publishing (Play
Console process, tester requirements, ASO, launch channels, F-Droid) in
the **agent-wiki, under the coding section** — started 2026-07-27 at
`~/.nexus/agent-wiki/tech/coding/android-app-publishing.md`. When a
session learns something durable about publishing (a policy detail, a
review outcome, a channel that worked or didn't), record it THERE, not
only in this repo's docs — repo docs hold Marmalade-specific plans, the
wiki holds the reusable knowledge.
