# Pocket TTS model — third-party license notice

The Pocket TTS model is **not bundled in the APK**. It is downloaded
on-demand from `marmalade-tts-android-engines` (release `v9`) into
`${filesDir}/engines/pocket-tts-en-v2026_04/` when the user opts in via
the onboarding wizard or Settings → Engines.

Three upstream projects contribute to the downloaded bundle. **None of
them are GPL** — this is the only engine in the catalog without GPL
contamination from the phonemizer path.

## 1. Acoustic model — kyutai-labs/pocket-tts

- **Files:** `flow_lm_main_int8.onnx`, `flow_lm_flow_int8.onnx`,
  `mimi_encoder_int8.onnx`, `mimi_decoder_int8.onnx`,
  `text_conditioner_int8.onnx`, `bundle.json`, `bos_before_voice.npy`,
  `voices/<name>.wav` (8 reference voices)
- **Upstream:** https://github.com/kyutai-labs/pocket-tts
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) Kyutai Labs. Licensed under the Apache
  License, Version 2.0; you may not use these files except in compliance
  with the License. A copy is available at
  http://www.apache.org/licenses/LICENSE-2.0.

## 2. ONNX export tooling — KevinAHM/pocket-tts-onnx

- **Files:** packaging layout (state manifests, file naming) of the
  downloaded bundle
- **Upstream:** https://github.com/KevinAHM/pocket-tts-onnx
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) Kevin AHM. The ONNX export pipeline produces
  the 5-graph model layout described in `bundle.json` from the upstream
  PyTorch sources.

## 3. Tokenizer — SentencePiece model file

- **File:** `tokenizer.model`
- **Upstream:** ships with the Pocket TTS export; trained alongside the
  model on the same data
- **License:** Apache License, Version 2.0 (inherited from Pocket TTS)
- **Notice:** Tokenizer model bundled with the Pocket TTS export. The
  SentencePiece library itself (used at runtime by our code, not by the
  bundle) is Apache 2.0 separately —
  https://github.com/google/sentencepiece.

## Runtime

The engine drives the 5 ONNX graphs via Microsoft's `onnxruntime-android`
Maven artifact (MIT) — see the `dependencies` block in `app/build.gradle.kts`.

## Voice cloning consent

The Pocket engine supports cloning new voices from user-supplied audio.
The cloning UI displays a mandatory consent reminder before recording or
file selection:

> Only clone voices you have permission to use. Do not clone real
> people's voices without their consent.

Cloned voices are stored on-device only; no audio or embedding leaves
the device.
