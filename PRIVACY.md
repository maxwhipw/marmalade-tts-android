# Privacy Policy — Marmalade TTS

_Last updated: 2026-06-08_

Marmalade TTS is an **offline, on-device text-to-speech app**. It is
private by design: the text you have it read never leaves your phone,
and the app collects no personal data. This policy explains exactly
what the app does and does not do with your information.

## Summary

- **No data collection.** We do not collect, store, or transmit any
  personal data.
- **No analytics, tracking, telemetry, crash reporting, ads, or
  accounts.** None. There is no backend that Marmalade controls.
- **Speech happens on your device.** The text you (or other apps) send
  to Marmalade is converted to audio locally and never uploaded.
- **The only network use** is downloading optional voice/engine files
  from GitHub when you explicitly choose to install an engine.

## What stays on your device

- **Text to be spoken** — whether you type it, share it to Marmalade,
  or another app sends it through Android's system text-to-speech
  interface. Synthesis is local; the text is never sent anywhere.
- **Your settings and voice choices** — voice selections, aliases /
  personas, audio-effect presets, per-app voice routing, and history
  are stored in app-private storage on the device.
- **Cloned voices** (for engines that support voice cloning) — created
  and stored on-device only. No recording, audio, or voice embedding
  is ever uploaded. The cloning screen requires you to confirm you have
  permission to clone the voice in question.

## The only data that leaves your device

When you tap "Install" on an engine (during onboarding or in
Settings → Engines), the app downloads that engine's model and
phonemizer files over HTTPS from:

> **github.com/maxwhipw/marmalade-tts-android-engines** (GitHub
> Releases)

These are ordinary file downloads. The contents of each file are
verified against a pinned SHA-256 hash before being saved. This is the
**entire** list of network activity. The app does not contact any other
server, does not send any information about you with these requests
beyond what a normal HTTPS file download requires, and works fully
offline once your chosen engines are installed.

GitHub's handling of the network request (e.g. server logs) is governed
by [GitHub's Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
Marmalade has no server of its own.

## Permissions and why they are needed

| Permission | Why Marmalade needs it |
|---|---|
| `INTERNET` | Solely to download optional engine/model files from GitHub Releases (above). Used for nothing else. |
| `POST_NOTIFICATIONS` (Android 13+) | To show the "speaking" / "keeping engine loaded" foreground notice required by Android when the app plays audio or runs a foreground service. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | To keep long-form speech playing reliably (including with the screen off) and to expose lock-screen / Bluetooth playback controls. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | For the optional "keep engine loaded" service so the next speak request is instant. You opt into this in Settings → Performance. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | To offer an in-app prompt asking Android not to pause the speech service mid-sentence when the screen sleeps. The prompt is optional and you can decline it. |

The app does **not** request `QUERY_ALL_PACKAGES`. The "per-app voices"
feature lists only apps that have a launcher icon, so you can pick which
app uses which voice; this stays on your device.

## Children's privacy

Marmalade does not collect any data from anyone, including children, and
contains no ads or in-app purchases.

## Changes to this policy

If this policy changes, the updated version will be published in the app
repository with a new "Last updated" date.

## Contact

Questions about privacy can be sent to:

> **maxwellw@posteo.com**

You can also report any behaviour that contradicts this policy through
the project's security disclosure process (see [SECURITY.md](SECURITY.md)).
