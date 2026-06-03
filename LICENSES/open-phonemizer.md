# OpenPhonemizer — third-party license notice

OpenPhonemizer replaces the GPL-3.0 espeak-ng phonemizer used by the
sherpa-onnx Kokoro/Kitten engines, removing the GPL contamination
that path introduced. It runs as a small ONNX seq2seq model alongside
the TTS acoustic model, fed by the same on-disk bundle.

The Kotlin runtime that drives the model lives in
`app/src/main/java/app/marmalade/tts/phonemizer/` and is original code
written for this project — see provenance note at the top of
`OpenPhonemizer.kt`. The model weights and (optional) dictionary are
**not bundled in the APK**; they're downloaded as part of the
KittenDirect / KokoroDirect engine bundles into
`${filesDir}/engines/<engine>/phonemizer/` on opt-in install.

## 1. ONNX model — openphonemizer/ckpt

- **Files:** `open-phonemizer.onnx` (~61 MB)
- **Upstream:** https://github.com/NeuralVox/OpenPhonemizer
- **Distribution:** https://huggingface.co/openphonemizer/ckpt
- **License:** BSD-3-Clause Clear
- **Notice:** Copyright (c) 2024 mrfakename, NeuralVox, OpenPhonemizer
  Contributors. The Clear BSD License — see upstream `LICENSE`. The
  ONNX export is derived from the upstream PyTorch checkpoint via
  the standard `torch.onnx.export` path; weights are unchanged.

## 2. Algorithm — DeepPhonemizer

- **Role:** seq2seq architecture + char-repeat input scheme + CTC
  decode strategy
- **Upstream:** https://github.com/as-ideas/DeepPhonemizer
- **License:** MIT (see upstream)
- **Notice:** Copyright (c) Axel Springer Ideas Engineering. The model
  architecture and the input-side `<en_us>`/`<end>`/`charRepeats=3`
  convention are DeepPhonemizer's; we re-implement the runtime in
  Kotlin from the algorithm description, not by copying code.

## 3. Dictionary cache (optional, deferred bundle) — CMUDict-derived

- **Files:** `dictionary.json` (~10 MB)
- **Status:** not present in the first KittenDirect bundle; planned for
  a follow-up bundle after the engine itself ships clean.
- **Plan:** generate ourselves by running the BSD-3 OpenPhonemizer
  model over the CMU Pronouncing Dictionary wordlist (public domain),
  producing word→IPA entries. Provenance fully documented in the
  generation script.
- **Why not reuse Maise's:** the dictionary.json in the MIT-licensed
  Maise project has no documented provenance for the source wordlist.
  Generating our own keeps the licence trail clean.

## License compatibility

All three components are MIT- or BSD-compatible. KittenDirect /
KokoroDirect engines using this phonemizer do **not** carry the
GPL-3.0 contamination present in the sherpa-onnx-backed Kitten /
Kokoro engines (those engines still ship the espeak-ng phonemizer
path; see `kitten-tts.md` and `kokoro-tts.md`).
