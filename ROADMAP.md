# marmalade-tts-android — ROADMAP

Version-by-version plan. See [SPEC.md](SPEC.md) for the architecture
and non-goals. Dates are aspirational, not commitments.

## v0.1.0 — MVP (the resume piece)

**Tweetable elevator pitch:** *"Marmalade TTS: an emotionally
expressive, FOSS, on-device system-TTS engine for Android. Emoji
controls feeling. No cloud. Cloneable voices."*

- [ ] **System TTS engine provider** — `TextToSpeechService` impl
- [ ] **Bundled `kitten` engine** via Sherpa-ONNX (~25 MB)
- [ ] **Emoji prosody layer** — emoji → emotion via post-synthesis
      pitch/rate/volume modulation
- [ ] **Share-sheet target** — "Share to Marmalade TTS"
- [ ] **Quick Settings tile** — Speak Clipboard
- [ ] **Foreground service** with media notification, audio-focus,
      Bluetooth
- [ ] **Voice picker UI** with per-voice preview
- [ ] **Voice aliases / personas** — save engine+voice+speed+effects
- [ ] **3 effect presets** — cave, robot, telephone

## v0.2.0 — More engines + subtitles

- [ ] **Piper engine** via Sherpa-ONNX with in-app voice store +
      downloader
- [ ] **Kokoro engine** ports
- [ ] **Full effect chain** (10 presets, matches the CLI) via Oboe +
      DSP graph
- [ ] **SRT / VTT subtitle export** for any synthesis (ports the
      CLI subtitle module)

## v0.3.0 — Long-form & accessibility

- [ ] **Background batch synthesis** — queue 50 articles, render
      overnight on charge, podcast-ready by morning
- [ ] **ePub / PDF reader integration** — render whole books offline
- [ ] **Custom pronunciation dictionary** — UI for adding entries,
      mirrors the CLI's `pronunciations.yaml`
- [ ] **Local HTTP API** — pairing-based auth, loopback-by-default,
      read-only endpoints; see SPEC.md "Security"

## v0.4.0 — Voice cloning (the headline feature)

- [ ] **Pocket-TTS ONNX port**
- [ ] **Record-and-clone flow** — 5–10 s recording with a guided
      consent UX as a screen (not a checkbox)
- [ ] **Cloned-voice library UI** — list, preview, delete
- [ ] **Encrypted at-rest storage** for cloned voice models

## v0.5.0 — Agent + integration surface

- [ ] **Tasker / MacroDroid plugin** — speak text, switch profile,
      start batch
- [ ] **KDE Connect integration** — desktop → phone TTS
- [ ] **On-device MCP bridge** so local LLMs (llama.cpp Android,
      Gemini Nano) can drive TTS the way the CLI's `mcp` subcommand
      does
- [ ] **NotificationListener integration** — "speak incoming messages
      from allowlisted apps"

## v0.6.0 — Auto & Wear

- [ ] **Android Auto** — read incoming messages, route via current
      voice
- [ ] **Wear OS companion** — push utterances from watch to phone

## v1.0.0 — Polish & lock

- [ ] **Language detection** — auto-route Japanese text to kokoro JP,
      Mandarin to zh, etc. (matches CLI's roadmap item)
- [ ] **Final Material You theming**, mascot animations from
      `marmalade-android/mascot-drafts/`
- [ ] **Public surface lock** — system TTS engine ID, share intent
      format, HTTP wire protocol, Tasker plugin contract, settings
      keys all frozen per semver

## Beyond 1.0 (not committed)

- Multi-language voice cloning (Coqui XTTS-style)
- Wider engine catalog (Matcha-TTS proper, Bark, etc.)
- F-Droid + Play Store dual-release
- Possibly: an `emojivoice`-only "lite" variant for very low-end devices
