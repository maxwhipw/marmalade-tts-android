# Kokoro German engine — third-party license notice

Covers the **Kokoro German** engine (`kokoro-de-v1_0`) — the native German
voice, a fine-tune of Kokoro-82M with one trained speaker (Thorsten). It runs
the same direct ONNX Runtime path as `kokoro-direct-v1_0`; its model bundle is
downloaded on opt-in from `marmalade-tts-android-engines` into
`${filesDir}/engines/kokoro-de-v1_0/`. The default APK bundles no model files.

This is a **separate engine, not a voice pack** for `kokoro-direct-v1_0`: the
fine-tune retrained the whole acoustic stack, so it can never share weights or
`voices.bin` with the multilingual base — the two coexist on disk. It is
German-only and always phonemizes German through the app's espeak-ng `de`
path; the bundle therefore ships **no** `lexicon-zh.txt` and **no**
`openjtalk_dic`.

This file is read alongside the bundle's own `PROVENANCE.md`, which records
how each artifact was built and gated; the two are kept consistent.

## Components in the downloaded bundle

| Component | Role | License |
|---|---|---|
| **Thorsten-Voice/Kokoro** (`model.onnx`, `voices.bin`) | German fine-tune of Kokoro-82M, 1 voice | **Apache-2.0** (Thorsten-Voice / Thorsten Müller) |
| **tokens.txt** | Kokoro v1.0 phoneme→id vocabulary (114 entries, unchanged from the base model) | **Apache-2.0** (part of the model) |
| **model-format.txt**, **MODEL_CARD**, **PROVENANCE.md** | Bundle metadata (marks the QDQ int8 build; provenance) | project documentation |

Unlike the multilingual `kokoro-direct-v1_0` bundle, this one carries no
espeak-ng data, no Mandarin lexicon and no Japanese dictionary — German is
fixed and phonemized in the app.

## 1. Model — Thorsten-Voice/Kokoro (German fine-tune)

- **Files:** `model.onnx`, `voices.bin`
- **Upstream:** https://huggingface.co/Thorsten-Voice/Kokoro
- **License:** **Apache License, Version 2.0.** The model card states it is
  *"Released under Apache 2.0, consistent with the base Kokoro-82M model and
  the CC0-licensed Thorsten-Voice dataset used for fine-tuning."*
- **Notice:** Copyright (c) Thorsten Müller (the **Thorsten-Voice** project).
  The repository identifies its author as `thorsten-voice`; Thorsten Müller is
  the person behind the project and the speaker of the dataset it was trained
  on.
- **Base model:** a fine-tune of **hexgrad/Kokoro-82M** v1.0
  (https://huggingface.co/hexgrad/Kokoro-82M), Copyright (c) hexgrad,
  Apache-2.0. The fine-tune touched the whole acoustic stack, so no weights are
  shared with the `kokoro-direct-v1_0` bundle.
- **Fine-tuning method:** the model card credits **kikiri-tts by semidark** for
  the fine-tuning approach.
- **What we ship:** the shipped `model.onnx` is Marmalade's **selectively
  static-QDQ int8** build (per-channel; the 155-node "X7" exclusion list —
  the English bundle's 118-node list plus 37 F0/prosody-predictor,
  projection and text-encoder nodes kept in fp32, which this fine-tune
  needs to pass the quality gate) of the ONNX export produced by the
  **k2-fsa/sherpa-onnx**
  `scripts/kokoro/v1.0/export_onnx.py` recipe (Apache-2.0) run against the
  Thorsten checkpoint. Same graph contract as `kokoro-direct-v1_0`: inputs
  `tokens` int64 [1,N], `style` float32 [1,256], `speed` float32 [1]; outputs
  waveform + per-token durations, 24 kHz.
- **Full text:** [`full-texts/Apache-2.0.txt`](full-texts/Apache-2.0.txt) (also
  in the APK at `assets/licenses/Apache-2.0.txt` and shown by Settings → About
  → Open-source licenses). Apache-2.0 carries no embedded licensor copyright,
  so the single shared body is correct here and the notices above carry the
  attribution.

## 2. Training data — Thorsten-Voice dataset (CC0)

- **Role:** the German single-speaker corpus the fine-tune was trained on. It
  is **not** shipped in the app or the bundle; it is recorded here because the
  weights are derived from it.
- **License:** **CC0 1.0** — public-domain dedication, no attribution required.
- **Consent:** the corpus is the voice of **Thorsten Müller himself**, who
  recorded and released it into the public domain expressly for open TTS work
  — the speaker and the publisher are the same person, so consent for TTS use
  is inherent. No synthetic audio appears anywhere in the lineage.

## 3. ONNX export tooling — k2-fsa/sherpa-onnx

- **Role:** the `export_onnx.py` recipe (opset 14, `KModelForONNX`,
  `disable_complex=True`) that produced the fp32 graph later quantized here.
  Tooling only — none of its runtime code ships in the app.
- **Upstream:** https://github.com/k2-fsa/sherpa-onnx
- **License:** **Apache License, Version 2.0.**
- **Notice:** Copyright (c) 2023 Xiaomi Corporation (Fangjun Kuang et al.),
  per the export script header.

## German phonemization (in the app, not this bundle)

The German G2P runs **in the app**, off the same espeak-ng integration as the
other engines — the bundle ships no phonemizer data. It is espeak-ng
(GPL-3.0-or-later, compiled into the APK from source) with its `de` voice in
tie mode, misaki-parity postprocessing, plus a German text normalizer and
pronunciation-override lexicon. Those German tables are a clean-room Kotlin
port of the DEG2P pipeline from **semidark/misaki** (a fork of
**hexgrad/misaki**; both **Apache-2.0**); the override lexicon originates from
**kikiri-tts** PR #28 (author dida-80b). One out-of-vocabulary repair is
applied (`ʏ`→`y`, the only espeak-de symbol absent from the model's vocab).

## espeak-ng (GPL) and the combined work

Because espeak-ng (GPL-3.0-or-later) is compiled into the APK
(`libespeak-ng.so`, from the pinned `third_party/espeak-ng` submodule,
`dlopen()`d by the MIT JNI shim `app/src/main/cpp/espeak_jni.c`), the
distributed APK is a **GPL-3.0-or-later combined work**; Marmalade's own source
files remain MIT. See [`kitten-direct.md`](kitten-direct.md) for the full
espeak-ng posture and [`../NOTICE.md`](../NOTICE.md) for the repository-wide
notice and the espeak corresponding-source pointer.

## GPL-3.0 implications

The bundle itself contains **no GPL material**: the model is Apache-2.0 and the
training data is CC0, both of which permit commercial use. The distributed APK
remains a GPL-3.0-or-later combined work only because espeak-ng is compiled
into it.
