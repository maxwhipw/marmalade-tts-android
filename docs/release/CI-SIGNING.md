# CI signing — release with one `git push --tags`

Goal: never hand-sign a release. Push a tag → GitHub Actions builds,
signs, and attaches the signed AAB + APK to a GitHub Release. No local
keystore juggling, no `gradlew bundleRelease` from a laptop with battery
left.

Companion to [SIGNING.md](SIGNING.md) (one-time keystore generation) and
[PLAY-RELEASE-PLAN.md](PLAY-RELEASE-PLAN.md) (the rest of the path to a
listing).

## One-time setup

You need an upload keystore — if you don't have one yet, generate it per
[SIGNING.md §1](SIGNING.md). Then encode it for GitHub:

```bash
base64 -w0 ~/secure/marmalade-upload.jks | xclip -selection clipboard
```

(`-w0` = no line wrapping; `xclip` puts it on the clipboard. macOS: pipe
to `pbcopy`. Or just write it to a file you delete after pasting.)

Open https://github.com/maxwhipw/marmalade-tts-android/settings/secrets/actions
and add four repository secrets:

| Name                      | Value                                                     |
|---------------------------|-----------------------------------------------------------|
| `UPLOAD_KEYSTORE_BASE64`  | Paste the base64 above                                    |
| `KEYSTORE_PASSWORD`       | The keystore password (= key password for PKCS12)         |
| `KEY_PASSWORD`            | Same value as above (PKCS12 default — set both for safety)|
| `KEY_ALIAS`               | `marmalade-upload` (or whatever alias you used)           |

### Second keystore: the distribution key (F-Droid/GitHub)

Decided 2026-08-09 (FDROID-RELEASE-PLAN.md R-track): the fdroid-flavor
APK — the reproducible-build reference binary and the GitHub sideload
artifact — signs with a **dedicated distribution key**, not the Play
upload key. The F-Droid identity is permanent (pinned by
`AllowedAPKSigningKeys`, no rotation without every user reinstalling),
so it must not share fate with a key Google can reset. Generate it the
same way as SIGNING.md §1 but with keystore `marmalade-dist.jks` and
alias `marmalade-dist`, then add four more secrets:

| Name                     | Value                                          |
|--------------------------|------------------------------------------------|
| `DIST_KEYSTORE_BASE64`   | base64 of `marmalade-dist.jks`                 |
| `DIST_KEYSTORE_PASSWORD` | The dist keystore password                     |
| `DIST_KEY_PASSWORD`      | Same value (PKCS12)                            |
| `DIST_KEY_ALIAS`         | `marmalade-dist`                               |

The workflow **fails without both keystores** — publishing a release
whose fdroid APK carries the wrong signature would break F-Droid's
reproducible-build verification. Locally the `dist*` quartet is an
optional extension of the same `keystore.properties`
(`distStoreFile` / `distStorePassword` / `distKeyAlias` /
`distKeyPassword`); when absent, fdroid release builds fall back to the
upload key or unsigned, which is fine for everything except a real
distribution build.

That's the whole setup. The workflow at
[`.github/workflows/release.yml`](../../.github/workflows/release.yml)
reads them, never logs them, and they live only in GitHub's encrypted
secret store.

## Releasing

```bash
# bump versionCode + versionName in app/build.gradle.kts
git commit -am "release: 1.0.0-beta.2 (versionCode 34)"
git push github main
git tag -a v1.0.0-beta.2 -m "1.0.0-beta.2"
git push github v1.0.0-beta.2
```

The workflow fires on the tag push:
1. Checks out the repo **with submodules** (espeak-ng builds from
   `third_party/espeak-ng`; CI must clone that too).
2. Installs JDK 17, NDK r26d, CMake 3.22.1 — same versions pinned in
   `app/build.gradle.kts`.
3. Decodes both keystores (`UPLOAD_KEYSTORE_BASE64` +
   `DIST_KEYSTORE_BASE64`), writes `keystore.properties`,
   sanity-checks each with `keytool`.
4. Builds the Play AAB + APK (upload key) and the F-Droid APK
   (distribution key).
5. Attaches `marmalade-tts-<version>-play.aab`, `-play.apk`,
   `-fdroid.apk`, and `SHA256SUMS.txt` to a freshly-created GitHub
   Release for the tag, with auto-generated release notes from the
   commit log.

You upload the AAB to Play yourself (one drag-and-drop). F-Droid pulls
from the tag automatically once the recipe is merged.

## Optional v2 — fully unattended Play upload

To skip the AAB-drag-and-drop step, wire up
[gradle-play-publisher](https://github.com/Triple-T/gradle-play-publisher):

1. In Play Console: **Setup → API access** → create a service account →
   grant it "Release manager" → download its JSON key.
2. Add a fifth GitHub secret `PLAY_SERVICE_ACCOUNT_JSON` containing the
   entire JSON.
3. Add the plugin to `app/build.gradle.kts`:
   ```kotlin
   plugins { id("com.github.triplet.play") version "3.10.1" }
   play {
       serviceAccountCredentials.set(file("play-service-account.json"))
       track.set("internal")           // or "alpha" / "beta" / "production"
       defaultToAppBundles.set(true)
   }
   ```
4. In the workflow, before the build step, write the secret to the
   gitignored credentials file, then change the gradle invocation to
   `./gradlew :app:publishReleaseBundle`.

I'd ship this as a follow-up PR once the first manual upload to Play
has worked end-to-end. Going straight to "fully automatic" first time
hides what's wrong if Play rejects the listing.

## Why upload key vs app-signing key

When you upload your first AAB, Play offers **Play App Signing**. Accept
it. Google holds the *real* app-signing key in their HSM; you sign with
the upload key the workflow uses; Google verifies your signature, strips
it, re-signs with the app-signing key, and ships that to devices. The
practical upshot:
- The upload key is rotatable (lose it → support ticket → new key).
- The keystore generated in [SIGNING.md](SIGNING.md) is an **upload
  key**, not the app-signing key.
- Losing the upload key isn't terminal. Losing both an upload key *and*
  failing to enroll in Play App Signing on first upload is.

## How F-Droid uses this (reproducible builds — decided 2026-08-03)

By default F-Droid builds and signs on their own buildserver, giving
their APK a different signature from ours. We're instead doing
**reproducible builds**: F-Droid rebuilds from the tag, verifies the
result byte-matches the CI-signed fdroid-flavor APK on the GitHub
Release (`Binaries:` + `AllowedAPKSigningKeys:` in the recipe), and
publishes *our* signed APK — so F-Droid, GitHub and Obtainium installs
share one signature and cross-update. This makes the release keystore
(`marmalade-upload.jks`, reused as the permanent distribution key) and
this CI workflow load-bearing for F-Droid too. Play remains separate:
Google re-signs with their app-signing key regardless. Details:
[FDROID-RELEASE-PLAN.md](FDROID-RELEASE-PLAN.md) §R.

## Failure modes

- **`UPLOAD_KEYSTORE_BASE64 secret is missing`** — you didn't add the
  secret yet, or scoped it to an environment that the `release` job
  doesn't run in. Add it at the repo level.
- **`keytool` exits non-zero in the sanity step** — the base64 is wrong
  (was wrapped or truncated) or the password doesn't match. Re-encode
  the keystore with `base64 -w0`, double-check the password in
  KeePassXC.
- **`ndkVersion 26.3.11579264 not found`** — the action's NDK install
  step failed. Re-run; transient SDK manager flake.
- **`refusing to release on non-v tag`** — the workflow only releases on
  `v[0-9]…` tags. Use `v1.0.0`, not `1.0.0` or `release-1`.
