# Google Play release plan — marmalade-tts-android

Step-by-step path from the current tree (1.0.0-beta.1, versionCode 33)
to a published Play listing. Steps marked **[Max]** need a human;
everything else Claude can do or has done.

## Phase 0 — code prerequisites (one real blocker)

1. **Move `libttsespeak.so` into the APK.** Play's Device & Network
   Abuse policy explicitly forbids downloading executable code
   ("dex, JAR, .so files") from outside Play — the engine bundles'
   espeak `.so` is a literal violation (models-as-data are fine and
   stay external). Plan: bundle the per-ABI `libttsespeak.so` in
   `app/src/main/jniLibs/` (or build espeak-ng from source in-tree,
   which F-Droid also wants), point `EspeakPhonemizer` at
   `applicationInfo.nativeLibraryDir` first and fall back to the
   bundle path, and strip the `.so` from future engine bundles.
   Re-link it with `-Wl,-z,max-page-size=16384` (16 KB pages) —
   required wherever it ships.
2. **Commit everything + device-verify.** The sherpa removal, license
   screen, and licensing-posture fixes are uncommitted as of
   2026-06-10. Split into logical commits, install on the Pixel 8a,
   and smoke-test all four engines (Kokoro, Kitten ×2, Pocket) —
   including a **minified release build** (`assembleRelease` now
   passes; runtime smoke of the R8 build is still pending).
3. Done already: R8/proguard fixed (ORT keep + commons-compress
   dontwarn), 16 KB alignment for in-APK libs, Room v7→v8 migration,
   licensing docs/in-app screen tell the MIT-APK story.

## Phase 1 — signing **[Max]**

4. Generate an upload keystore (keep it OUT of the repo):
   `keytool -genkeypair -v -keystore ~/secure/marmalade-upload.jks
   -alias marmalade-upload -keyalg RSA -keysize 4096 -validity 10000`
   Back it up somewhere safe (password manager + offline copy).
5. Wire it in WITHOUT committing secrets: `keystore.properties` at the
   repo root (gitignored) holding storeFile/storePassword/keyAlias/
   keyPassword, read from `app/build.gradle.kts` into a
   `signingConfigs.release` block. Claude can write this wiring.
6. Build the release artifact: `./gradlew :app:bundleRelease` → AAB at
   `app/build/outputs/bundle/release/`. (Play requires AAB; enroll in
   Play App Signing at upload — Google holds the signing key, the
   keystore above is your upload key.)

## Phase 2 — Play Console setup **[Max]**

7. Play Console developer account ($25 one-time). **Note:** new
   personal accounts must run a closed test with 12+ testers for 14
   days before production access — check whether this applies and plan
   timeline accordingly.
8. Create app → "Marmalade TTS", Free, App (not game).
9. **Privacy policy URL** (required even with zero data collection):
   host PRIVACY.md publicly — GitHub blob URL works; GitHub Pages is
   nicer. Add the same link to the in-app About section.
10. **Data safety form:** "No data collected, no data shared." The
    user-initiated engine download is exempt (ephemeral processing).
11. **Foreground service declarations** (App content page, each needs
    a use-case text + demo video):
    - `mediaPlayback` (MarmaladeSynthService) — straightforward.
    - `specialUse` (MarmaladeKeepaliveService) — weak fit, moderate
      rejection risk. Decide: keep (it's user-opt-in) or cut the
      keepalive service from the Play build if it bounces.
12. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: defensible (uninterrupted
    FGS audio is core function for a TTS engine); explain in review
    notes; the prompt is user-initiated.
13. Content rating questionnaire (IARC) → Everyone.

## Phase 3 — listing assets

14. Screenshots: at least 2 phone screenshots (Speak / Voices /
    Effects / Licenses screens) — captured from the device, dropped in
    `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
15. App icon 512×512 + feature graphic 1024×500 (mascot art from
    `assets/` is the base).
16. Listing text: reuse `fastlane/metadata/android/en-US/`
    (title/short/full descriptions are current and accurate).

## Phase 4 — submit

17. Upload the AAB to a **closed testing** track first; run the
    pre-launch report (catches 16 KB/page-size and crash issues on
    Google's device farm).
18. Fix anything it flags → promote to production (or open testing
    first if the 12-tester rule applies).
19. Post-launch calendar items: **targetSdk 36 required by
    ~Aug 31, 2026** for updates; keep engine-bundle URLs stable
    (catalog pins exact GitHub release assets + SHA-256).

## Reference: policy citations

- Runtime code download ban: https://support.google.com/googleplay/android-developer/answer/9888379
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Target API: https://developer.android.com/google/play/requirements/target-sdk
- 16 KB pages: https://developer.android.com/guide/practices/page-sizes
- FGS declarations: https://support.google.com/googleplay/android-developer/answer/13392821
