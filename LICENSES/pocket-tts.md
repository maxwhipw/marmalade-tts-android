# Pocket TTS model — third-party license notice

The Pocket TTS model is **not bundled in the APK**. It is downloaded
on-demand from `marmalade-tts-android-engines` (release `v21`) into
`${filesDir}/engines/pocket-tts-en-v2026_04/` when the user opts in via
the onboarding wizard or Settings → Engines.

Three upstream projects contribute to the downloaded bundle. **None of
them are GPL** — this is the only engine in the catalog without GPL
contamination from the phonemizer path.

## 1. Acoustic model — kyutai-labs/pocket-tts

- **Files:** `flow_lm_main_int8.onnx`, `flow_lm_flow.onnx`,
  `mimi_encoder.onnx`, `mimi_decoder.onnx`, `text_conditioner.onnx`,
  `bundle.json`, `bos_before_voice.npy`
- **Upstream:** https://github.com/kyutai-labs/pocket-tts
- **License:** **MIT License** (the model/inference code is MIT — not
  Apache-2.0).
- **Notice:** Copyright (c) Kyutai. Released under the MIT License.

> The model **weights** and the **voice prompts** are distributed
> separately from the code; the per-voice data licenses are below and
> govern the `voices/*.wav` files, not the MIT code license.

## 2. Predefined voices — per-voice data licenses

Pocket addresses voices by a reference WAV in `voices/<name>.wav`. Each
predefined voice's audio comes from a different source dataset, and the
**license is determined by that source** (authoritative mapping:
Kyutai's own `pocket_tts/utils/utils.py`; folder→license per
https://huggingface.co/kyutai/tts-voices).

We ship **only the 6 commercial-safe voices**. The upstream `cosette`
(Expresso) and `jean` (EARS) voices are **CC-BY-NC-4.0 (non-commercial
only)** and are **deliberately excluded** so the store build distributes
no non-commercial voice data.

| Voice | Source (Kyutai `utils.py`) | Dataset | License |
|---|---|---|---|
| `alba` | `alba-mackenna/casual.wav` | Alba Mackenna | **CC-BY-4.0** |
| `azelma` | `vctk/p303_023_enhanced.wav` | VCTK | **CC-BY-4.0** |
| `eponine` | `vctk/p262_023_enhanced.wav` | VCTK | **CC-BY-4.0** |
| `fantine` | `vctk/p244_023_enhanced.wav` | VCTK | **CC-BY-4.0** |
| `javert` | `voice-donations/Butter.wav` | Unmute Voice Donation | **CC0** |
| `marius` | `voice-donations/Selfie.wav` | Unmute Voice Donation | **CC0** |

### Attribution (CC-BY-4.0)

The CC-BY-4.0 voices require attribution. The reference recordings are
derived from:

- **`alba`** — "Alba Mackenna", released by Kyutai under CC-BY-4.0
  (`kyutai/tts-voices`, `alba-mackenna/`).
- **`azelma`, `eponine`, `fantine`** — the **CSTR VCTK Corpus**
  (speakers p303, p262, p244), Centre for Speech Technology Research,
  University of Edinburgh, CC-BY-4.0. https://doi.org/10.7488/ds/2645
- **`javert`, `marius`** — Unmute Voice Donation Project contributors,
  released CC0 (no attribution required; acknowledged with thanks).

This attribution is also recorded in [`../CREDITS.md`](../CREDITS.md).

## 3. Tokenizer — SentencePiece model file

- **File:** `tokenizer.model`
- **Upstream:** ships with the Pocket TTS export; trained alongside the
  model on the same data
- **License:** MIT (inherited from Pocket TTS). Marmalade does **not**
  depend on the SentencePiece library — `PocketTokenizer` is a pure-Kotlin
  reimplementation of the SentencePiece Unigram decode. SentencePiece
  (Apache-2.0, https://github.com/google/sentencepiece) is referenced only
  for the `tokenizer.model` file format.

## ONNX export tooling — KevinAHM/pocket-tts-onnx

- The 5-graph ONNX layout (`flow_lm`, `mimi` encoder/decoder, text
  conditioner) and the `bundle.json` state-manifest format derive from
  the export pipeline at https://github.com/KevinAHM/pocket-tts-onnx
  (Apache-2.0).

## Runtime

The engine drives the 5 ONNX graphs via Microsoft's `onnxruntime-android`
Maven artifact (MIT) — see the `dependencies` block in `app/build.gradle.kts`.

## Voice cloning

Pocket's architecture can clone a voice from a user-supplied reference
WAV via the `mimi_encoder`. **This is not enabled in the current
release** — there is no cloning UI, and the backend path is dormant
pending a review. No user audio or embedding leaves the device.
