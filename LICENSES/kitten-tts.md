# Kitten TTS model — third-party license notice

The Kitten TTS model bundled under `app/src/main/assets/kitten/` is the
Sherpa-ONNX-repackaged form of KittenML's nano-0.1 model. Three
upstream projects contribute to what ships in the APK:

## 1. Acoustic model — KittenML/kitten-tts-nano-0.1

- **Files:** `model.fp16.onnx`, `voices.bin`, `tokens.txt`
- **Upstream:** https://github.com/KittenML/KittenTTS
- **Distribution:** https://huggingface.co/KittenML/kitten-tts-nano-0.1
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) KittenML contributors. Licensed under the
  Apache License, Version 2.0; you may not use this file except in
  compliance with the License. A copy of the License is available at
  http://www.apache.org/licenses/LICENSE-2.0.

## 2. Conversion + packaging — k2-fsa/sherpa-onnx

- **Bundle:** `kitten-nano-en-v0_1-fp16.tar.bz2`
- **Source URL:** https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2
- **Upstream:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) k2-fsa / Sherpa-ONNX contributors. The
  Sherpa-ONNX project converts upstream KittenML weights into the
  ONNX layout used by `OfflineTtsKittenModelConfig` and assembles the
  packaging used by the runtime.

## 3. Phonemizer data — espeak-ng

- **Files:** everything under `app/src/main/assets/kitten/espeak-ng-data/`
- **Upstream:** https://github.com/espeak-ng/espeak-ng
- **License:** GNU General Public License v3.0
- **Notice:** Copyright (c) The espeak-ng authors. The `espeak-ng-data`
  tree is part of the espeak-ng distribution. The kitten engine
  requires it at runtime because the phonemizer compiled into
  `libsherpa-onnx.so` is espeak-ng.

### GPL-3.0 implications (flagged)

espeak-ng is GPL-3.0. The Sherpa-ONNX AAR vendored in `app/libs/`
statically links espeak-ng for phonemization, so this project already
distributes GPL-licensed code regardless of whether the data files
are bundled here. Bundling the data files under
`app/src/main/assets/kitten/espeak-ng-data/` is a packaging convenience
— it does not change the underlying license situation introduced by
linking against espeak-ng.

If MIT-only distribution is a hard requirement, the alternatives are:

1. Swap espeak-ng for a permissively-licensed phonemizer
   (e.g. open-phonemizer, MIT). Requires a different Sherpa-ONNX build
   that does not link espeak-ng, or a different inference path entirely.
2. Use a model that does not need a phonemizer (e.g. Piper variants
   trained on graphemes). Out of scope for the v0.1 Kitten engine.

For v0.1, this project ships an MIT codebase that includes GPL-3.0
phonemizer data and links a GPL-3.0 phonemizer library through the
Sherpa-ONNX AAR. Source for espeak-ng is available at the upstream URL
above per the GPL-3.0 source-availability requirement.
