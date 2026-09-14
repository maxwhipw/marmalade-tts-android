# VITS Marmalade — third-party license notice

Covers **VITS Marmalade** (`vits-marmalade-v1`), Marmalade's own direct
ONNX Runtime path for Piper-class single-speaker VITS checkpoints, and
the per-language **voice packs** it downloads on opt-in into
`${filesDir}/engines/vits-marmalade-v1/packs/<pack>/`. The default APK
bundles no voice pack.

## No Piper runtime code

The inference in this engine is **Marmalade's own**. The maintained Piper
runtime (`OHF-Voice/piper1-gpl`) is GPL-3.0; **none of its code is used,
copied, linked or distributed here**. The phoneme→id mapping semantics
(NFD normalisation, codepoint iteration, language-switch-flag stripping,
clause-terminator appending, `^ _ (p _)* $` pad interspersal) were
reimplemented in Kotlin from the behaviour of the MIT-era
`rhasspy/piper-phonemize` and verified against the checkpoint's own
`model.onnx.json`, which is the only contract the app relies on.

What the app *does* consume from the Piper ecosystem is **voice
checkpoints and their config files** — data, not code — published under
their own permissive licences and listed below.

## License posture — APK vs pack

| Layer | Contents | License |
|---|---|---|
| Marmalade APK | Kotlin VITS engine (`engine/vits/`), ORT bindings, JNI shim, **libespeak-ng.so (compiled from source)**, full espeak-ng-data | Source files MIT; **APK distributed under GPL-3.0-or-later** because of espeak-ng |
| Voice pack (downloaded) | `model.onnx`, `model.onnx.json`, `MODEL_CARD`, `PROVENANCE.md` | Weights MIT; training data Apache-2.0 (per pack — see below) |

## 1. Voice pack — `uk-lada-x_low` (Ukrainian, "Lada")

- **Files:** `model.onnx`, `model.onnx.json`
- **Upstream:** https://huggingface.co/rhasspy/piper-voices — path
  `uk/uk_UA/lada/x_low/`
- **License:** MIT (repository licence of `rhasspy/piper-voices`)
- **Notice:** Copyright (c) Michael Hansen (Rhasspy).
- **Training:** trained from scratch per the upstream `MODEL_CARD`
  shipped inside the pack — no inherited base checkpoint.

MIT License

Copyright (c) Michael Hansen (Rhasspy)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 2. Training data — `egorsmkv/ukrainian-tts-datasets` ("lada")

- **Role:** the ~10.6 h single-speaker Ukrainian corpus the
  `uk-lada-x_low` checkpoint was trained on. The corpus itself is **not**
  shipped in the app or the pack; it is recorded here because the weights
  are derived from it.
- **Upstream:** https://github.com/egorsmkv/ukrainian-tts-datasets/tree/main/lada
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) Yehor Smoliakov.
- **Full text:** [`full-texts/Apache-2.0.txt`](full-texts/Apache-2.0.txt)
  (also shipped in the APK at `assets/licenses/Apache-2.0.txt` and shown
  by Settings → About → Open-source licenses). Apache-2.0 carries no
  embedded licensor copyright, so the single shared body is correct here.

## 3. Phonemizer — espeak-ng

VITS Marmalade phonemizes through the same app-level espeak-ng
integration as the Kitten and Kokoro engines: `libespeak-ng.so` is
compiled from source into the APK from the pinned
`third_party/espeak-ng` submodule and `dlopen()`d at runtime by the MIT
JNI shim (`app/src/main/cpp/espeak_jni.c`), with the build-generated
full `espeak-ng-data` tree in APK assets.

- **Upstream:** https://github.com/espeak-ng/espeak-ng
- **License:** GNU General Public License v3.0 or later
- **Notice:** Copyright (c) The espeak-ng authors. Used in sentence mode
  (`espeak_TextToPhonemes` with `phonememode = IPA`).
- **Source availability:** per GPL-3.0 §6, the corresponding source for
  the APK's `libespeak-ng.so` is the pinned submodule in this
  repository.

See [`kitten-direct.md`](kitten-direct.md) for the full espeak-ng
posture, and [`../NOTICE.md`](../NOTICE.md) for the repository-wide
notice.

## GPL-3.0 implications

Voice packs themselves contain **no GPL material**: weights are MIT and
the training data is Apache-2.0, which is why this engine can ship on
Play as well as F-Droid. The distributed APK remains a
GPL-3.0-or-later combined work because espeak-ng is compiled into it;
Marmalade's own source files stay MIT.
