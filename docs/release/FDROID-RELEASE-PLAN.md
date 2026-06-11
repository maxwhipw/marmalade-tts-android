# F-Droid release plan — marmalade-tts-android

Step-by-step path to an F-Droid listing. F-Droid builds and signs the
APK themselves from a tagged commit, so no keystore is needed — but the
build must succeed on their buildserver from a clean checkout.

## Phase 0 — code prerequisites

1. **Repo hygiene: already passing.** `git ls-files` contains no
   prebuilt `.aar/.so/.onnx/.bin` (only the standard
   `gradle-wrapper.jar`, which fdroidserver validates). The vendored
   Open JTalk/MeCab C is *source* (BSD-3), espeak JNI shim compiles
   from source in-tree, deps come from allowlisted Maven repos, and
   the hardcoded `org.gradle.java.home` was moved out of the repo's
   `gradle.properties` (would have broken their buildserver).
2. **Runtime engine downloads are acceptable** with explicit opt-in
   consent (which the per-engine Install screen satisfies). Precedent:
   SherpaTTS downloads ONNX models at runtime and is listed (with a
   **NonFreeNet** anti-feature for downloading from a proprietary
   service); Termux downloads executable packages and is listed with
   no anti-feature. Expect maintainers to tag **NonFreeNet** — accept
   it. If the Play fix (espeak `.so` moved in-APK / built from source)
   lands first, the strongest objection disappears entirely.
3. **GPL §6 gap to close in the engines repo
   (marmalade-tts-android-engines):** the bundles redistribute a GPL
   `libttsespeak.so` but the repo doesn't document its exact source
   revision or build provenance. Add to its README: espeak-ng version
   (binary 1.52.0 from the official espeak Android APK; data from
   Debian 1.51+dfsg), a link to the exact upstream source tag, and —
   when the from-source build lands — the build script. Reviewers
   poke at exactly this.
4. **Commit + tag.** F-Droid builds from a signed/annotated git tag
   (e.g. `v1.0.0-beta.1`) on a public repo —
   https://github.com/maxwhipw/marmalade-tts-android. Push main + the
   tag when ready.

## Phase 1 — metadata in this repo

5. Fastlane structure already correct:
   `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt`
   and `changelogs/33.txt` all exist and are accurate (the
   full_description plainly states the opt-in GitHub download —
   reviewers want that disclosed).
6. Add `fastlane/metadata/android/en-US/images/icon.png` (512×512) and
   at least 2 `images/phoneScreenshots/*.png`. Same assets as the Play
   listing.

## Phase 2 — submission

7. Fork https://gitlab.com/fdroid/fdroiddata and add
   `metadata/app.marmalade.tts.yml`:

   ```yaml
   Categories: [Multimedia, Reading]
   License: MIT
   AuthorName: Max
   SourceCode: https://github.com/maxwhipw/marmalade-tts-android
   IssueTracker: https://github.com/maxwhipw/marmalade-tts-android/issues
   Changelog: https://github.com/maxwhipw/marmalade-tts-android/blob/main/CHANGELOG.md

   AntiFeatures: [NonFreeNet]   # engine bundles download from GitHub

   RepoType: git
   Repo: https://github.com/maxwhipw/marmalade-tts-android.git

   Builds:
     - versionName: 1.0.0-beta.1
       versionCode: 33
       commit: v1.0.0-beta.1        # the tag
       subdir: app
       gradle: [yes]
       ndk: r26d                    # match ndkVersion in build.gradle.kts

   AutoUpdateMode: Version
   UpdateCheckMode: Tags
   CurrentVersion: 1.0.0-beta.1
   CurrentVersionCode: 33
   ```

8. Test the recipe locally if possible (`fdroid build -v -l
   app.marmalade.tts` inside fdroidserver, or just rely on their CI),
   then open a merge request against fdroiddata. Title:
   "New app: Marmalade TTS". In the MR description, state up front:
   MIT app, no GPL in the APK, models downloaded on explicit opt-in
   (NonFreeNet pre-tagged), espeak-ng GPL only in opt-in bundles.
9. Respond to reviewer feedback (typical round-trips: anti-feature
   wording, NDK pinning, reproducibility nits). Listing appears
   automatically once merged + built.

## Phase 3 — maintenance

10. Future releases: bump versionCode/versionName, tag, push —
    `AutoUpdateMode: Version` picks it up without another MR.
11. Keep the engines repo releases immutable (the catalog pins
    SHA-256 per asset; replacing an asset breaks installs and F-Droid
    users' trust).

## Reference

- Inclusion policy: https://f-droid.org/en/docs/Inclusion_Policy/
- Anti-features: https://f-droid.org/en/docs/Anti-Features/
- Build metadata reference: https://f-droid.org/en/docs/Build_Metadata_Reference/
- Precedent (SherpaTTS, NonFreeNet): https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/org.woheller69.ttsengine.yml
