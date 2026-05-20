# Security Policy

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

This app:

- Implements an Android system TTS engine that can be called by any
  installed app via `android.speech.tts.TextToSpeech`. We treat caller
  text as untrusted input and apply standard sanitization (control-char
  strip, length caps).
- Runs ONNX models on-device. No telemetry. The only network use is
  the `EngineInstaller` downloading engine model files from a
  hostname listed in the catalog source. Each download is verified
  against a SHA-256 hash pinned in the catalog before the file lands
  on disk — a poisoned mirror cannot substitute model weights without
  the verifier rejecting them. See [PRIVACY.md](PRIVACY.md) for the
  user-facing summary.
- (v0.3+) May expose a local HTTP API on Wi-Fi. See
  [SPEC.md](SPEC.md) "Security" for the deployment rules — off by
  default, loopback when enabled, pairing-based auth, no write
  endpoints, no cloning over the wire.
- (v0.4+) Voice cloning. Cloned voices stay on device, stored
  encrypted at rest in app-private storage. Cloning is never exposed
  via any inter-process or network surface.

## Scope

- The Android app itself
- Any HTTP API endpoints shipped post-v0.3
- The system TTS service registration

Not in scope (report upstream):
- Sherpa-ONNX / ONNX Runtime Mobile bugs
- Bugs in voice model files distributed by upstream engine maintainers
- The marmalade-tts CLI (separate repo — report at
  https://github.com/maxwhipw/marmalade-tts/security/advisories/new)
