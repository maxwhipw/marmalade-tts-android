# marmalade-tts-android — SPEC

This document captures the v1.0 product surface — feature scope,
architecture decisions, and what's deliberately out. Companion to
[ROADMAP.md](ROADMAP.md) (timeline) and the README (pitch).

## Pitch

The first emotionally expressive system-wide TTS for Android. Replaces
Google/Samsung TTS as the device's accessibility engine; every TTS call
from any app gets read with emotion via the emojivoice engine.
On-device, voice-cloneable, FOSS.

The gap-in-market answer to: *"why is mobile TTS still monotone in 2026?"*

## Non-goals (v1.0)

These are out by deliberate choice. Revisit only with a strong reason.

- **No cloud fallback.** No OpenAI/ElevenLabs/Azure backend. On-device only.
- **No iOS.** Not a serious cross-platform play.
- **No Coqui engine.** Too heavy for mobile (200 MB – 2 GB models).
- **No Matcha-TTS.** Possible v1.1; research codebase, painful to port.
- **No cross-device voice sync.** Cloned voices stay on the device they
  were cloned on. No silent upload, ever — non-negotiable.
- **No voice cloning over HTTP.** Cloning is UI-only. The HTTP server,
  when it lands (v0.3+), has *read* endpoints only.
- **No audiobook DRM ingestion.** Legal minefield, no upside.

## Architecture overview

```
                  ┌──────────────────────────┐
                  │   Android system TTS     │
   any app ───→   │   TextToSpeechService    │
                  │   (marmalade impl)       │
                  └────────────┬─────────────┘
                               ↓
                  ┌──────────────────────────┐
                  │   synth dispatcher        │
                  │   (chunking + effects)    │
                  └────────────┬─────────────┘
                               ↓
            ┌────────────┬─────┴─────┬─────────────┐
            ↓            ↓           ↓             ↓
        ┌────────┐  ┌────────┐  ┌────────┐    ┌─────────┐
        │ kitten │  │  piper │  │ kokoro │ …  │ pocket  │
        │  ONNX  │  │  ONNX  │  │  ONNX  │    │ (clone) │
        └────────┘  └────────┘  └────────┘    └─────────┘

                  ┌──────────────────────────┐
                  │   Compose UI              │
                  │   - voice picker          │
                  │   - aliases (personas)    │
                  │   - history / library     │
                  │   - clone-a-voice flow    │
                  │   - settings              │
                  └──────────────────────────┘
```

## Stack

- **Language:** Kotlin 2.1+
- **UI:** Jetpack Compose, Material 3 with Material You theming
- **TTS inference:** Sherpa-ONNX (vendored AAR — same one used in
  marmalade-android) on top of ONNX Runtime Mobile. Android's
  `android.speech.tts.TextToSpeech` registered as a fallback engine
  for low-end devices.
- **Audio:** Oboe for low-latency streaming output. AudioTrack on the
  TextToSpeechService callback path (mandated by the system TTS
  contract). `android.media.audiofx` for v0.1 effect presets.
- **DI:** Hilt.
- **Persistence:** Room (voice + alias metadata, synthesis history),
  DataStore (settings).
- **HTTP server (v0.3+):** Ktor embedded — only after pairing UX is
  designed; see Security below.
- **Local HTTP server framework only if/when needed:** NanoHTTPD as
  fallback if Ktor is too heavy.

## Feature surface — v0.1.0 MVP

1. **System TTS engine provider** — implements `TextToSpeechService`,
   registers in the system settings as a selectable TTS engine.
2. **Bundled `kitten` engine** via Sherpa-ONNX (~25 MB). Default voice.
3. **Emoji prosody layer** — `emojivoice`-style emotion injection.
   Even on monotone underlying engines, applies pitch/rate/volume
   curves per emoji on the AudioTrack stream as a degraded fallback.
4. **Share-sheet target** — "Share to Marmalade TTS" from any app
   speaks the selection. Mobile analog of the CLI's `speak-selection`
   KDE script.
5. **Quick Settings tile** — one-tap "Speak clipboard" from anywhere.
6. **Foreground service playback** — long-form text plays in a media
   notification with skip/pause/seek. Survives screen-off. Bluetooth
   and audio-focus aware.
7. **Voice picker UI** — Compose screen with per-voice preview button.
   Mirrors `marmalade-tts kokoro --list` from the CLI.
8. **Voice aliases / personas** — same concept as `aliases:` config in
   the CLI. UI: "Save current engine+voice+speed+effect as 'narrator'".
   Aliases appear as share-sheet sub-targets.
9. **Audio effects (3 presets)** — `cave`, `robot`, `telephone` via
   `android.media.audiofx`. Full chain (10 presets, matching CLI) in v0.2.

## Feature surface — v1.0 (extended)

See [ROADMAP.md](ROADMAP.md) for the version-by-version breakdown.

Headline additions beyond MVP:
- **Piper + kokoro engines** with an in-app voice store + downloader (v0.2).
- **SRT/VTT subtitle export** for any synthesis (v0.2).
- **Long-form workflows** — ePub/PDF reader integration, background
  batch synthesis, custom pronunciation dictionary (v0.3).
- **Voice cloning** from a 5-second mic recording with the consent flow
  as a screen, not a checkbox (v0.4).
- **Local network TTS API** (v0.3+, see Security).
- **Tasker / MacroDroid plugin**, KDE Connect integration, on-device
  MCP bridge (v0.5).
- **Android Auto + Wear OS** companion (v0.6).
- **Language detection** auto-routes to the appropriate engine + voice
  (matches the CLI's roadmap item).

## Security — the HTTP API specifically

The CLI's `docker/server.py` is the wire-protocol reference, **not** the
deployment model. Phone-as-HTTP-server is a materially different
threat surface from a Docker container in a homelab.

**The HTTP server is deferred to v0.3+.** It is not in MVP. Reasons:

- Public Wi-Fi exposure is the default Android reality (phones change
  networks ~5×/day). A set-and-forget bearer token doesn't survive that.
- Voice cloning via API would be a turnkey deepfake factory. The
  cloning surface must stay UI-only on the device, even after the
  server ships.
- Pairing-based auth (QR code or PIN, per-device tokens, revocation
  UI) is a project unto itself. Designing it after the MVP exists is
  much safer than rushing it.

**When the server lands, rules:**

- **Off by default.** Opt in via a settings toggle with explicit
  warning copy.
- **Loopback by default when enabled.** Network exposure is a *second*
  toggle, with the user picking which network interfaces (and which
  saved Wi-Fi SSIDs are trusted).
- **Pairing-based auth, not pre-shared key.** Pattern modeled on KDE
  Connect: phone shows QR code → desktop scans → device-specific token
  registered, revocable from a paired-devices screen.
- **No write endpoints.** `/v1/audio/speech`, `/v1/voices`, `/v1/health`.
  No `/clone`, no `/upload`, no `/exec`, no admin surface.
- **Foreground service notification mandatory** when the server is
  running. One-tap kill switch. Shows current bind address.
- **Auto-shutdown:** N hours idle → off; device locked >X min → off;
  left a trusted SSID → off.
- **Per-token rate limit + daily synthesis cap.** Defense in depth.
- **CORS:** no web origins permitted. Browser pages can't drive the API.

## Privacy stance

- **No analytics, telemetry, or crash reporters that phone home.**
  Local crash logs only.
- **No accounts.** No login screen, no email gate.
- **Cloned voices never leave the device.** Voice models are stored
  encrypted at rest in app-private storage.
- **The emoji-driven prosody layer never sees text outside what the
  caller hands the TTS service.** No keylogging adjacent surfaces.

## Code reuse from marmalade-tts

The Python CLI is the *concept reference*, not the codebase. Shared
surfaces:

- **Voice catalog** — same voice names (Bella, Kiki, george, …) so
  users moving between CLI and app see the same identifiers.
- **Effect names** — same preset names (cave, robot, telephone, …).
- **Alias schema** — same fields (`engine`, `voice`, `speed`, `effects`,
  `lang`).
- **`emoji → emotion` mapping** — ported from
  `marmalade-tts/marmalade_tts/preprocessing/emoji.py`.
- **HTTP wire protocol** (when server ships) — `/v1/audio/speech`
  endpoint shape matches `docker/server.py`, so an agent that can call
  the CLI's Docker server can call the phone with no client changes.

What does NOT cross over:
- Synth code (subprocess-based on Linux vs in-process ONNX on Android).
- Daemon mode (Android equivalent is foreground service / bound service).
- sox effects (mobile uses `android.media.audiofx` / Oboe DSP graph).

## Versioning and stability

Follows [Semantic Versioning](https://semver.org/), same conventions
as the CLI:

- **Pre-1.0 (`0.x.y`):** beta. Surface may change.
- **1.0.0:** the documented surface (system TTS provider config, share
  intent format, HTTP wire protocol if shipped, settings keys) is locked.
- **Major bumps thereafter** only for breaking changes.
