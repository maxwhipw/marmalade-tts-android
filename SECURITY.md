# Security Policy

_Last reviewed: 2026-07-27._

## Supported versions

This project is pre-1.0. Only the latest released version receives
security fixes.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security reports.

Use GitHub's private vulnerability reporting:
https://github.com/maxwhipw/marmalade-tts-android/security/advisories/new

Acknowledgement target: 7 days. Fix or mitigation target: 30 days where
practical.

## Threat model

### Untrusted input

Marmalade registers as an Android system TTS engine, so **any installed
app** can hand it text via `android.speech.tts.TextToSpeech`. Caller text
is treated as untrusted: control characters are stripped and length is
capped before it reaches an engine.

### On-device synthesis

The built-in engines run ONNX models locally. No telemetry, no analytics,
no crash reporting, no accounts. The espeak-ng phonemizer is **compiled
into the APK** from a pinned submodule — the app never downloads
executable code, which is both a Play policy requirement and the reason
an engine bundle cannot introduce new code.

### Network use

There are exactly three outbound paths. All are HTTPS; there is no
cleartext path (see below).

1. **Engine downloads** — `EngineInstaller` fetches voice models and
   espeak-ng pronunciation data from GitHub Releases. Each archive is
   verified against a **SHA-256 hash pinned in the catalog source**
   before it lands on disk, so a poisoned mirror cannot substitute model
   weights without the verifier rejecting them. Bundles carry no
   executable code at rest.
2. **Cloud provider descriptor** — a small JSON list of available cloud
   providers, fetched from the same repository so a new provider can ship
   without an app update. It carries no user data. It is schema-parsed,
   and **any parse failure discards the remote copy entirely**, leaving
   the bundled asset in force — one bad entry cannot half-apply.
3. **Cloud synthesis (opt-in only)** — if the user configures a provider,
   the text to be spoken and the user's own API key are POSTed to that
   provider. Nothing is sent unless the user has entered a key and
   selected one of that provider's voices, which is gated behind a
   consent screen they must accept.

### Cloud API keys

- Stored in **app-private storage** (DataStore). Never written to logs,
  never included in diagnostics, and sent nowhere except to the provider
  that issued it, in an `Authorization: Bearer` header over HTTPS.
- A key's blast radius is the user's own account with that provider.
  Marmalade has no accounts and no server, so there is no aggregate store
  of user credentials to breach — a compromise is necessarily one device
  at a time.
- Removing the key deletes the stored value.

### Transport hardening

- `android:usesCleartextTraffic="false"` in the manifest. This is already
  the platform default at `targetSdk` 36; it is stated explicitly so that
  a dependency merging in `"true"` fails the manifest merge rather than
  silently winning.
- Provider endpoints are **rejected at parse time unless they are
  `https://`**. The provider list is remote input and is where the user's
  text and key get POSTed, so the scheme is enforced in code rather than
  left to a build setting.

### Log hygiene

- User text is **never** logged at info/warn/error level. The synthesis
  path logs a character count, not content.
- Debug builds do log text at `Log.d` for phonemizer diagnostics. Release
  builds strip `Log.d`/`Log.v` entirely via R8 `-assumenosideeffects`;
  this is verified against the built APK, not assumed from the config.
- A cloud provider's HTTP **error body is discarded unread**. Providers
  routinely echo the offending request back in validation errors, so
  folding one into an exception message would put user text into logcat
  and from there into any bug report. Failures carry the status code and
  a fixed explanation only.

### Not present

- The app exposes **no** HTTP or network API of any kind — no server,
  loopback or otherwise. All processing is in-process and on-device.
- Voice cloning is **not shipped** in this release (no cloning UI).
- No `QUERY_ALL_PACKAGES`. The per-app voice feature enumerates only apps
  with a launcher icon, and the mapping stays on the device.

## Scope

In scope:

- The Android app itself
- The system TTS service registration
- The engine installer and its hash verification
- The cloud API engine, its key handling, and provider-descriptor parsing

Not in scope (report upstream):

- ONNX Runtime Mobile bugs
- Bugs in voice model files distributed by upstream engine maintainers
- Vulnerabilities in a third-party cloud provider's own service — report
  those to that provider, with whom the user contracts directly
- The marmalade-tts CLI (separate repo — report at
  https://github.com/maxwhipw/marmalade-tts/security/advisories/new)

See [PRIVACY.md](PRIVACY.md) for the user-facing summary of what leaves
the device and when.
