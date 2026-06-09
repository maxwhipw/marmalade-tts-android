# 🍊 marmalade-tts-android

<p align="center">
  <img src="assets/mascot.png" alt="marmalade-tts-android mascot" width="220">
</p>

> **Status:** `1.0.0-beta.1` — public beta. Feature-complete and
> production-ready; staying in beta until it's proven across a range of
> devices, then promoting to `1.0.0`.

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
- **On-device, always.** Neural voices run on ONNX Runtime, directly on
  your phone. No network calls during synthesis, ever.
- **Familiar concepts from the CLI.** Voice aliases (personas), per-voice
  preprocessing, audio effects, batch synthesis for long-form text.

## Engines

Engines install on demand — you only download the ones you want.

| Engine | Voices | Notes |
|--------|--------|-------|
| Kokoro (v1.0) | 53 across 9 languages | Best all-round quality; recommended default |
| Kitten Nano (v0.8) | 8 English | Smallest + fastest; runs on any device |
| Kitten Mini (v0.8) | 8 English | A step up in quality from Nano |
| Pocket TTS (English) | 6 English | The most expressive English voices |

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

**`1.0.0-beta.1`** — the first public beta. Feature-complete and
production-ready; held in beta until validated across a range of devices
(via real-world use), then promoted to `1.0.0`. Working: system TTS engine
provider, multiple on-device neural engines (Kokoro / Kitten / Pocket) via
opt-in install, the emoji prosody layer, a
composable audio-effects chain, voice aliases / personas, the share-sheet
target, the Quick Settings tile, and a foreground media-playback service
for long-form text.

Not yet: a production signing key (future releases will be release-signed
and will require a fresh install at that point), a Piper engine, and
automated audible / lock-screen tests. See [SPEC.md](SPEC.md) and
[ROADMAP.md](ROADMAP.md) for the full v0.1 → v1.0 plan.

## License

**Source code: MIT** — see [LICENSE](LICENSE). Every `.kt` file stays MIT.

**Store binary: GPL-3.0-or-later.** The build published to Google Play /
F-Droid statically links espeak-ng (GPL-3.0-or-later) via the sherpa-onnx
AAR, so the *distributed store APK as a whole* is a GPL-3.0-or-later
combined work.
This is allowed (MIT is one-directionally GPL-compatible) and does not
relicense the source — the corresponding source is this repository.

See [NOTICE.md](NOTICE.md) for the full breakdown and the per-component
license texts in [LICENSES/](LICENSES/).
