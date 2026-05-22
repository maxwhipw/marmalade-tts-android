# 🍊 marmalade-tts-android

<p align="center">
  <img src="assets/mascot.png" alt="marmalade-tts-android mascot" width="220">
</p>

> **Status:** v0.1.0 shipped (debug-signed APK on
> [GitHub Releases](https://github.com/maxwhipw/marmalade-tts-android/releases/tag/v0.1.0)).
> Early development — still pre-1.0.

Native Android text-to-speech app with on-device neural voices and
emotion-aware prosody. Registers as a system TTS engine, so every app
that reads text aloud — your screen reader, your e-reader, your podcast
client, your AI chat — can route through marmalade. No cloud.

Sister project to **[marmalade-tts](https://github.com/maxwhipw/marmalade-tts)**
(the Linux CLI it borrows its concept vocabulary from) and
**[marmalade-android](https://github.com/maxwhipw/marmalade-android)**
(the OpenClaw AI assistant client it borrows its visual identity from —
public release coming soon).

---

## What makes this different

Most Android TTS apps either ship Google's data-hungry default or
robotic FOSS alternatives like espeak-ng. Marmalade fills the middle:

- **System TTS engine provider.** Implements Android's
  `TextToSpeechService` so every app on the device that uses
  `android.speech.tts.TextToSpeech` can route through marmalade. Drop-in
  replacement for Google/Samsung TTS.
- **Emotionally expressive.** Emoji in the text drive emotional
  prosody. Built on the `emojivoice` engine from the CLI — even when
  the underlying voice is monotone, a post-synthesis prosody overlay
  applies emotion based on the emoji you typed.
- **On-device, always.** Neural voices run via Sherpa-ONNX / ONNX
  Runtime Mobile. No network calls, ever.
- **Voice cloning from a 5-second mic recording.** Cloned voices stay
  on the device they were cloned on; never uploaded. Consent UX is a
  guided screen, not a checkbox.
- **Familiar concepts from the CLI.** Voice aliases (personas), per-voice
  preprocessing, audio effects, batch synthesis for long-form text.

## Engines (planned)

| Engine | Status | Notes |
|--------|--------|-------|
| `kitten` | v0.1 | Downloaded on demand, ~42 MB, runs on every device |
| `emojivoice` | v0.1 | Emotion layer; ports from CLI |
| `piper` | v0.2 | Voice store + downloader, ~70 MB per voice |
| `kokoro` | v0.2 | High-quality multilingual |
| `pocket` | v0.4 | Voice cloning from 5s recording |

## How engines work

Marmalade ships small: the default APK does not bundle any neural
model files. On first launch you pick which engines to install — each
one downloads from a hostname pinned in the catalog
(`EngineCatalog.kt`) into `${filesDir}/engines/<engine>/`. You can
install or uninstall engines later from Settings → Engines. The
`INTERNET` permission is used solely for these downloads — see
[PRIVACY.md](PRIVACY.md).

## Related projects

- **[marmalade-tts](https://github.com/maxwhipw/marmalade-tts)** —
  Linux CLI with daemon mode, multi-engine, scripting-first.
- **[marmalade-android](https://github.com/maxwhipw/marmalade-android)** —
  OpenClaw AI assistant client (shares the mascot + visual language;
  public release coming soon).

## Project status

**v0.1.0 has shipped** as a debug-signed APK on the
[GitHub Releases page](https://github.com/maxwhipw/marmalade-tts-android/releases/tag/v0.1.0).
Working in this build: system TTS engine provider, the Kitten engine via
opt-in install, the emoji prosody layer, the share-sheet target, the
Quick Settings tile, voice aliases / personas, three audio effect presets
(cave / robot / telephone), and a foreground media-playback service for
long-form text.

Not yet: a production signing key (future releases will be release-signed
and will require a fresh install at that point), Piper and Kokoro engines,
and automated audible / lock-screen tests. See [SPEC.md](SPEC.md) and
[ROADMAP.md](ROADMAP.md) for the full v0.1 → v1.0 plan.

## License

MIT — see [LICENSE](LICENSE).
