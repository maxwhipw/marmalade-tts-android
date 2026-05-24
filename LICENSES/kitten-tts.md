# Kitten TTS model — third-party license notice

The Kitten TTS model is **not bundled in the APK**. It is downloaded
on-demand from `marmalade-tts-android-engines` (release `v7`) into
`${filesDir}/engines/kitten/` when the user opts in via the onboarding
wizard or Settings → Engines. Three upstream projects contribute to the
downloaded bundle:

## 1. Acoustic model — KittenML/kitten-tts-nano-0.8

- **Files:** `model.fp32.onnx`, `voices.bin`, `tokens.txt`
- **Upstream:** https://github.com/KittenML/KittenTTS
- **Distribution:** https://huggingface.co/KittenML/kitten-tts-nano-0.8-fp32
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) KittenML contributors. Licensed under the
  Apache License, Version 2.0; you may not use this file except in
  compliance with the License. A copy of the License is available at
  http://www.apache.org/licenses/LICENSE-2.0.

## 2. Conversion + packaging — k2-fsa/sherpa-onnx

- **Bundle:** `kitten-nano-en-v0_8-fp32.tar.bz2`
- **Source URL:** https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-fp32.tar.bz2
- **Upstream:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) k2-fsa / Sherpa-ONNX contributors. The
  Sherpa-ONNX project converts upstream KittenML weights into the
  ONNX layout used by `OfflineTtsKittenModelConfig` and assembles the
  packaging used by the runtime.

## 3. Phonemizer data — espeak-ng

- **Files:** everything under `${filesDir}/engines/kitten/espeak-ng-data/`
- **Upstream:** https://github.com/espeak-ng/espeak-ng
- **License:** GNU General Public License v3.0
- **Notice:** Copyright (c) The espeak-ng authors. The `espeak-ng-data`
  tree is part of the espeak-ng distribution. The kitten engine
  requires it at runtime because the phonemizer compiled into
  `libsherpa-onnx.so` is espeak-ng.

### GPL-3.0 implications (flagged)

espeak-ng is GPL-3.0. The Sherpa-ONNX AAR vendored in `app/libs/`
statically links espeak-ng for phonemization, so this project already
distributes GPL-licensed code regardless of whether the user installs
the Kitten engine. The downloadable Kitten bundle additionally ships
the `espeak-ng-data` directory needed at runtime.

The Kitten model itself is Apache 2.0 — the GPL contamination comes
exclusively from the phonemizer path Sherpa-ONNX wires into both
engines. The install dialog discloses this before the user accepts
the download.

Source for espeak-ng is available at the upstream URL above per the
GPL-3.0 source-availability requirement.
