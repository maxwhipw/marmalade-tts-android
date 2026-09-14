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
| Voice pack (downloaded) | `model.onnx`, `model.onnx.json`, `MODEL_CARD`, `PROVENANCE.md` | Weights MIT; training data per pack — Apache-2.0, CC BY 4.0 or CC0 (see below) |

Some of the corpora behind these packs are **CC BY 4.0, which requires
attribution**. The required credits are given in full below and repeated
in [`../CREDITS.md`](../CREDITS.md), the repo's home for
attribution-required voice data.

## 1. Voice pack — `uk-lada-x_low` (Ukrainian, "Lada")

- **Files:** `model.onnx`, `model.onnx.json`
- **Upstream:** https://huggingface.co/rhasspy/piper-voices — path
  `uk/uk_UA/lada/x_low/`
- **License:** MIT (repository licence of `rhasspy/piper-voices`; that repo
  ships only the `license: mit` tag, so the holder line below is taken
  verbatim from the sibling `rhasspy/piper` LICENSE.md by the same author)
- **Notice:** Copyright (c) 2022 Michael Hansen.
- **Training:** trained from scratch per the upstream `MODEL_CARD`
  shipped inside the pack — no inherited base checkpoint.

MIT License

Copyright (c) 2022 Michael Hansen

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

## 3. Voice packs — `is-bui-medium`, `is-salka-medium`, `is-steinn-medium`, `is-ugla-medium` (Icelandic, Talrómur)

- **Files (each pack):** `model.onnx`, `model.onnx.json`
- **Upstream:** https://huggingface.co/rhasspy/piper-voices — paths
  `is/is_IS/bui/medium/`, `is/is_IS/salka/medium/`,
  `is/is_IS/steinn/medium/`, `is/is_IS/ugla/medium/`
- **License:** MIT — same repository licence and same holder as section 1;
  the MIT text quoted there covers these weights too.
- **Notice:** Copyright (c) 2022 Michael Hansen.
- **Training:** trained from scratch per each pack's upstream
  `MODEL_CARD` — no inherited base checkpoint.

## 4. Training data — Talrómur (Icelandic) — **ATTRIBUTION REQUIRED**

- **Role:** the single-speaker Icelandic corpora the four `is-*-medium`
  checkpoints were trained on (speakers "Búi", "Salka", "Steinn",
  "Ugla"). The corpus itself is **not** shipped in the app or the packs;
  it is recorded here because the weights are derived from it.
- **Upstream:** Talrómur 1, CLARIN-IS handle
  http://hdl.handle.net/20.500.12537/104
- **License:** Creative Commons Attribution 4.0 International
  (**CC BY 4.0**) — commercial use permitted, **attribution required**.
- **Required attribution** (reproduced verbatim in `CREDITS.md`):

  > Talrómur corpus — Reykjavík University & RÚV, Icelandic Language
  > Technology Programme (Sigurgeirsson et al., NoDaLiDa 2021),
  > CC BY 4.0.

- **Full text:** [`full-texts/CC-BY-4.0.txt`](full-texts/CC-BY-4.0.txt)
  (also shipped in the APK at `assets/licenses/CC-BY-4.0.txt` and shown
  by Settings → About → Open-source licenses).
- **Collection:** the eight speakers applied and were selected by
  audition for an explicitly open TTS corpus, recorded in a soundproof
  radio studio at RÚV (the Icelandic National Broadcasting Service) by
  Reykjavík University. Speaker names are pseudonyms.

## 5. Voice pack — `sv-nst-medium` (Swedish, "NST")

- **Files:** `model.onnx`, `model.onnx.json`
- **Upstream:** https://huggingface.co/rhasspy/piper-voices — path
  `sv/sv_SE/nst/medium/`
- **License:** MIT — same repository licence and holder as section 1.
- **Notice:** Copyright (c) 2022 Michael Hansen.
- **Training:** "Trained from scratch by KBLab at The National Library of
  Sweden" per the upstream `MODEL_CARD`.

## 6. Training data — NST Swedish Speech Synthesis

- **Role:** the single-speaker Swedish corpus behind `sv-nst-medium`.
  Not shipped in the app or the pack.
- **Upstream:** Språkbanken resource **sbr-18**, "NST Swedish Speech
  Synthesis" (the upstream model card links sbr-17, the dictation
  corpus; sbr-18 is the synthesis set — see the pack's
  `PROVENANCE.md`).
- **License:** **CC0 1.0** ("CC Zero (fri bruk)") — public domain
  dedication, no attribution required.
- **Rights holder:** Nasjonalbiblioteket (the National Library of
  Norway), which inherited the NST corpora via the 2011 bankruptcy
  consortium.
- **Full text:** [`full-texts/CC0-1.0.txt`](full-texts/CC0-1.0.txt)
  (also in the APK at `assets/licenses/CC0-1.0.txt`).
- **Collection:** recorded in studio by a professional voice actor
  ("informantene er profesjonelle aktører", NST documentation)
  specifically for commercial TTS development by Nordisk
  Språkteknologi.

## 7. Phonemizer — espeak-ng

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
every training corpus is Apache-2.0, CC BY 4.0 or CC0, all of which
permit commercial use — which is why this engine can ship on Play as
well as F-Droid. The distributed APK remains a
GPL-3.0-or-later combined work because espeak-ng is compiled into it;
Marmalade's own source files stay MIT.
