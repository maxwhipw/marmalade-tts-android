# Session handoff — 2026-06-11 store-release work

Overnight session (Claude Fable 5). Everything below is committed
locally on `main` — **30 commits ahead of `github/main`, not pushed**.

## The Opus 4.8 question, answered

**The promised espeak integration was never done.** A full search
(branches, stash, `build-scratch/`, `scripts/`, `tools/`,
`~/coding/scratch/`) found no espeak build work anywhere. What existed:
the MIT dlopen JNI shim, and prebuilt `libttsespeak.so` binaries
(lifted from the official espeak 1.52.0 Android APK) packed into the
engine bundles — exactly the thing Google Play forbids downloading.
The old CLAUDE.md even contained the plan ("compile espeak-ng from
source into the APK") as a parenthetical TODO. It is now actually done,
properly:

## What landed tonight

1. **The stranded 79-file diff** was split into 5 logical commits
   (sherpa-onnx removal, ExecuTorch tooling removal, in-app licenses
   screen, license-docs restructure, release plans + build hygiene).
2. **espeak-ng compiled from source into the APK** (`c175343`):
   - `third_party/espeak-ng` = pinned submodule at upstream tag
     **1.52.0**. Fresh clones need `git submodule update --init`.
   - `app/src/main/cpp/espeak-ng/CMakeLists.txt` builds *only*
     `libespeak-ng.so` (klatt + speechPlayer on; async/mbrola/sonic/
     pcaudio off) — deliberately skipping upstream's top-level CMake,
     which needs a host espeak binary for data compilation and does a
     **network FetchContent of libsonic** that would break F-Droid's
     offline buildserver.
   - Both ABIs verified: the 5 dlsym'd symbols exported (`llvm-nm`),
     LOAD segments 16 KB-aligned (`0x4000`), `zipalign -c -P 16` passes
     on the release APK. APK grew 51.7 → 52.8 MB.
   - JNI shim unchanged (still dlopen, still espeak-header-free MIT
     source); Kotlin now loads via `EspeakPhonemizer.apkLibFile()` from
     `nativeLibraryDir`. Installer/`isInstalled()` no longer require the
     bundle `.so`; old bundles keep working (their `.so` is ignored).
3. **Licensing posture flipped everywhere** (`96f9fe7`): source MIT,
   **distributed APK = GPL-3.0-or-later** (espeak compiled in). NOTICE,
   README (+ new badge), CHANGELOG, SPEC, CREDITS, CLAUDE.md, REPO-MAP,
   LICENSES/*, in-app posture text, store description, both release
   plans (F-Droid recipe: `License: GPL-3.0-or-later`,
   `submodules: yes`). One identical build serves both stores.
4. **Release signing wired** (`817cdcb`): `keystore.properties`
   (gitignored) → `signingConfigs.release`; absent file = unsigned
   build (what F-Droid wants). `scripts/make-keystore-properties.sh`
   fills it from a KeePassXC entry via `keepassxc-cli`
   (not currently on PATH — it ships with the keepassxc package).
   One-time keystore steps: `docs/release/SIGNING.md`.
5. **Fastlane 512×512 icon** rendered from the adaptive-icon parts.
6. **Play Console answers** ready to paste:
   `docs/release/PLAY-CONSOLE-RESPONSES.md` (data safety, both FGS
   declarations, battery-optimization justification, IARC, target
   audience, 10-step path). Privacy policy URL = the GitHub PRIVACY.md
   blob link — yes, that's accepted.

Verified: debug + release unit tests green, `assembleRelease`,
`bundleRelease` (AAB 36 MB), `assembleDebug` all build. **Not
verified: anything on a device** (intermediate commits also weren't
individually compiled — only the final tree is).

## Walking the checklist tonight (interrupted)

Started Opus 4.7 driving the device against the new release APK. What
got done:

- ✅ ADB connected (`<phone-ip>`), release APK installed
  fresh as `app.marmalade.tts` (your daily `.debug` app is untouched).
- ✅ Onboarding loaded with the new "phonemized by the app's built-in
  espeak-ng" copy live. Started installing **all four engines** in the
  onboarding flow (Kitten Nano + Kitten Mini finished, Kokoro + Pocket
  were downloading).
- 🐛 **Found and fixed a real bug** (commit `b9c5b4f`): on the device,
  `/data/app/.../lib/arm64-v8a/` was **empty** — AGP's default
  `extractNativeLibs=false` keeps the .so inside the APK, so
  `File(nativeLibraryDir, "libespeak-ng.so").isFile` returned false
  and the engines would have failed to load. The fix:
  `System.loadLibrary("espeak-ng")` in `EspeakPhonemizer.init()` so
  Android pulls the lib from the APK regardless of extraction, and the
  shim dlopens it by basename so the linker resolves it from the app's
  namespace. The lib's five dlsym'd symbols re-verified after rebuild,
  tests still green.
- ❌ Could not re-verify on device: the Pixel's wireless ADB session
  dropped (connection refused). Need fresh wireless-debug + port.

## Morning checklist (Max)

1. **Install + smoke test** — wireless ADB pair fresh, then:
   ```
   adb -s <phone-ip>:<port> install -r \
     app/build/outputs/apk/release/app-release-debugsigned.apk
   ```
   The APK already contains the fix above. The daily `.debug` app is
   unaffected (release APK uses `app.marmalade.tts`, no suffix).
   Engines from tonight's partial install are still on device — check
   `app.marmalade.tts`'s filesDir to see what survived, or just re-run
   onboarding (it'll skip already-installed engines).
   **Test all four engines** — listen for: Kitten Nano/Mini English,
   Kokoro English + European + Japanese + Mandarin, Pocket. Engine load
   logs `espeak open: <version>` in logcat (tag `EspeakPhonemizer`).
   Negative-log fingerprint of the bug if the fix didn't take:
   `EspeakPhonemizer  IllegalArgumentException: espeak-ng-data not found`
   or any `dlopen` failure in tag `EspeakJni`.
2. **Audit the licensing/console docs** I wrote (NOTICE.md, README
   license section, PLAY-CONSOLE-RESPONSES.md).
3. **Push**: `git push github main` (never origin — Forgejo mirror
   rules in CLAUDE.md).
4. **Keystore**: follow `docs/release/SIGNING.md` (KeePassXC entry →
   script → `bundleRelease`).
5. **Screenshots** (≥2) on the Pixel → `fastlane/.../phoneScreenshots/`
   — Speak / Voices / Effects / Licenses screens. Feature graphic
   1024×500 still TODO (mascot art in `assets/` is the base; I can
   generate one next session).
6. **Tag when happy**: `git tag -a v1.0.0-beta.1 -m "first public
   beta" && git push github v1.0.0-beta.1`.
7. Play Console + fdroiddata MR per the two release plans.

## Open follow-ups (engines repo — needs your GitHub auth/decision)

- **Re-spin Kitten/Kokoro bundles without `libttsespeak.so`** so the
  downloads contain no executable code at rest (the app already ignores
  it; this just removes the optics/policy risk and shrinks downloads).
  Requires new releases + updated SHA-256s in `EngineCatalog.kt`.
  While at it, **regenerate espeak-ng-data from the 1.52.0 tag** to
  close the 1.52-lib/1.51-data version split that NOTICE.md discloses.
- **engines repo README**: add the GPL §6 provenance block (exact
  espeak-ng tag, data origin, build pointer) — F-Droid reviewers ask.
- Decide keepalive `specialUse` posture if Play bounces it (fallback
  text is in PLAY-CONSOLE-RESPONSES.md).

## Build-system notes for future sessions

- `./gradlew :app:externalNativeBuildDebug` is the fast native-only
  check (~10 s incremental).
- The espeak feature set lives in `app/src/main/cpp/espeak-ng/
  CMakeLists.txt`; bump its `project(... VERSION x.y.z)` in lockstep
  with the submodule tag (it feeds `espeak_Info()`/PACKAGE_VERSION).
- Never run `connectedAndroidTest` against the daily app (wipes data).
