# Privacy Policy

Marmalade TTS for Android is designed to be private by default. This
document explains what data the app handles, what leaves your device,
and what the `INTERNET` permission is actually used for.

## What leaves your device

- **Engine model downloads.** When you tap "Install" on an engine (in
  the onboarding wizard or Settings → Engines), the app downloads the
  engine's model files from a hostname listed in the catalog source
  (currently: `huggingface.co` only — see
  `app/src/main/java/app/marmalade/tts/install/EngineCatalog.kt`).
  Each file's SHA-256 is verified against a pinned hash before it
  lands on disk.

That's the entire list. The app does not:

- send any analytics, telemetry, crash reports, usage metrics, or
  diagnostic pings,
- create any accounts,
- log you in to any service,
- talk to any backend Marmalade controls (there isn't one),
- talk to advertising networks,
- include any third-party SDK that phones home.

## What stays on your device

- **Text you type** to be spoken. Synthesis is on-device — the text
  never leaves the phone.
- **Text other apps send** to Marmalade through the Android
  `TextToSpeechService` interface. Same path: on-device only.
- **Voice selections, aliases, history.** Stored in app-private
  storage (`/data/data/app.marmalade.tts/`). No backup to cloud unless
  you explicitly enable Android system backup.
- **Cloned voices** (when v0.4 lands) will be stored encrypted at
  rest in app-private storage and never uploaded.

## The `INTERNET` permission

`android.permission.INTERNET` is declared in `AndroidManifest.xml`
solely so `EngineInstaller` can fetch engine model files. It is not
used for anything else. The manifest comment names this purpose
inline so it cannot be quietly repurposed:

```xml
<!--
  INTERNET is used exclusively for engine model downloads via
  EngineInstaller. No telemetry, no analytics, no other network use.
  See PRIVACY.md / SECURITY.md for details. The set of hosts contacted
  is enumerated in EngineCatalog (currently: huggingface.co only).
-->
<uses-permission android:name="android.permission.INTERNET" />
```

## When the HTTP API ships (v0.3+)

A future version will expose an opt-in local HTTP API for speaking
text from other devices on your network. The rules for that surface,
already documented in [SPEC.md](SPEC.md) "Security", include: off by
default, loopback by default, pairing-based auth (modeled on KDE
Connect), no write endpoints, no voice-cloning endpoint, foreground
service with one-tap kill switch.

That feature does not change the engine-download behaviour described
above.

## Reporting

If you discover behaviour that contradicts this document, please
report it under the existing security disclosure process (see
[SECURITY.md](SECURITY.md)).
