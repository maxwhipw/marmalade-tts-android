# Release signing — one-time setup

Play requires an AAB signed with your **upload key** (Google re-signs
with the app signing key via Play App Signing). F-Droid signs its own
builds, so none of this affects the F-Droid path.

The build wiring is already in place: `app/build.gradle.kts` reads
`keystore.properties` at the repo root when it exists, and builds
unsigned when it doesn't. `keystore.properties` and `*.jks` are
gitignored.

## 1. Generate the upload keystore (once)

Pick a strong password and store it in KeePassXC first (entry name
`marmalade-upload`, password = the keystore password). Then:

```bash
mkdir -p ~/secure
keytool -genkeypair -v \
  -keystore ~/secure/marmalade-upload.jks \
  -alias marmalade-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Marmalade TTS"
```

`keytool` prompts for the password — paste it from KeePassXC. Modern
keytool uses PKCS12, where the store and key share one password.

**Back it up.** A copy of the `.jks` outside this machine (encrypted
drive, attached to the KeePassXC entry, etc.). Losing the upload key
means a support ticket with Google; losing it *and* not being enrolled
in Play App Signing means losing the app listing.

## 2. Write keystore.properties from KeePassXC

```bash
scripts/make-keystore-properties.sh ~/path/to/Passwords.kdbx marmalade-upload
```

Re-run it any time (e.g. after a fresh clone). The file it writes is
mode 600 and gitignored.

## 3. Build the signed release

```bash
./gradlew :app:bundleRelease   # AAB for Play → app/build/outputs/bundle/release/
./gradlew :app:assembleRelease # APK for sideload/testing → app/build/outputs/apk/release/
```

At first upload, enroll in **Play App Signing** (the console offers it
during AAB upload) — Google holds the final signing key, and the
keystore above remains your upload key only.
