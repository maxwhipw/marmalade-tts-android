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

## Remotes

**github is the only remote and is authoritative.**

```
github   https://github.com/maxwhipw/marmalade-tts-android.git
```

Push only to github (`git push github main`). Unlike the sister CLI
project at `/home/max/coding/marmalade-tts-cli`, this repo has no
Forgejo mirror configured — github is the single source of truth.

## Versioning

Bump `versionCode` + `versionName` in `app/build.gradle.kts` per
release. v0.1.x is debug-signed only — `applicationIdSuffix = ".debug"`,
so the installed package on devices is `app.marmalade.tts.debug`.
Commits of the form `vX.Y.Z: ...` mark a version bump.

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
- Compile-check via `./gradlew :app:compileDebugKotlin` (10s) catches
  trivially-bad refactors before the longer `assembleDebug` (1-2 min).
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

**Not yet implemented (2026-06-14); spec in
[docs/release/PAYWALL-PLAN.md](docs/release/PAYWALL-PLAN.md).** Once it
lands, the project has two product flavors sharing one signing config
and one applicationId:

- **`fdroid`** — every feature unlocked, no billing dep, no Google
  classes. What F-Droid's buildserver compiles.
- **`play`** — same build PLUS `com.android.billingclient:billing-ktx`
  (via `playImplementation`, so the dep never reaches the F-Droid
  source set). Per-app voices and custom effect creation are gated
  behind a one-time `marmalade_pro` IAP. Built-in effect presets,
  primary alias, and every synth feature stay free.

`ProEntitlement.isPro` is the single source of truth —
`FdroidProEntitlement` returns `MutableStateFlow(true)`;
`PlayProEntitlement` wraps `BillingClient`. UI trip-wires
(`AppRoutingViewModel`, `EffectsScreen`) open the paywall sheet only
when `!isPro`, so the paywall code path does not exist in the F-Droid
APK.

The CI workflow's `bundleRelease`/`assembleRelease` becomes
`bundlePlayRelease`/`assemblePlayRelease`; fdroiddata's recipe needs
`gradle: [fdroid]`.

## Engine bundle licensing

The Marmalade **source repo is MIT**; the **distributed APK is
GPL-3.0-or-later** because espeak-ng is compiled from source into it
(pinned submodule `third_party/espeak-ng`, tag 1.52.0, built by
`app/src/main/cpp/espeak-ng/CMakeLists.txt`). Play forbids
runtime-downloading `.so` files, so the lib must live in the APK; the
same from-source build satisfies F-Droid. One build serves both stores.
**Engine bundles** (downloaded after user opt-in into
`${filesDir}/engines/`) carry models + pronunciation data only —
espeak-ng-data is the GPL piece there. See [NOTICE.md](NOTICE.md).

The KittenDirect engine (alpha.9, May 2026) made this posture explicit:
a tiny C JNI shim in the APK does `dlopen`/`dlsym` against
`libttsespeak.so` shipped in the engine bundle, so the APK contains no
espeak code. The user assembles the GPL combination on their device
when they accept the engine install. This is the locked-in pattern for
future direct-ORT engines (KokoroDirect will follow the same model).

The dictionary-only phonemizer path (using BSD-3 OpenPhonemizer ONNX +
a CMUDict-derived IPA dictionary, no espeak at all) was explored and
deferred — phonemizer-side IPA convention mismatches with the trained
Kitten model caused enough quality regression to make espeak-in-bundle
the right call. Revisit if there's ever a no-GPL-anywhere requirement.


## Android app publishing — knowledge base TODO

We are building a durable knowledge base on Android app publishing (Play
Console process, tester requirements, ASO, launch channels, F-Droid) in
the **agent-wiki, under the coding section** — started 2026-07-27 at
`~/.nexus/agent-wiki/tech/coding/android-app-publishing.md`. When a
session learns something durable about publishing (a policy detail, a
review outcome, a channel that worked or didn't), record it THERE, not
only in this repo's docs — repo docs hold Marmalade-specific plans, the
wiki holds the reusable knowledge.
