# Credits

Marmalade TTS stands on a lot of open work. This file acknowledges the
projects, datasets, and people whose work the app builds on. Binding
license texts live in [`LICENSES/`](LICENSES/) and [`NOTICE.md`](NOTICE.md);
this file is the human-readable thank-you and the home for the
attribution that some licenses (notably CC-BY-4.0) require.

## Voice data — attribution required

The Pocket engine ships 6 predefined reference voices. Two carry
**CC-BY-4.0**, which requires attribution:

- **VCTK Corpus** — `azelma`, `eponine`, `fantine` are derived from the
  **CSTR VCTK Corpus** (speakers p303, p262, p244), Centre for Speech
  Technology Research, University of Edinburgh. Licensed **CC-BY-4.0**.
  https://doi.org/10.7488/ds/2645
- **Alba Mackenna** — the `alba` voice, released by Kyutai under
  **CC-BY-4.0** (`kyutai/tts-voices`, `alba-mackenna/`).

Two more (`javert`, `marius`) come from the **Unmute Voice Donation
Project** and are **CC0** — no attribution required, but the volunteers
who donated their voices have our thanks.

The upstream `cosette` (Expresso) and `jean` (EARS) voices are
**CC-BY-NC-4.0 (non-commercial)** and are **not shipped**. Full mapping:
[`LICENSES/pocket-tts.md`](LICENSES/pocket-tts.md).

## Neural voice models

- **Kokoro-82M** — Kokoro TTS model. Apache-2.0.
- **KittenTTS** (nano / mini) — KittenML. Apache-2.0.
- **Pocket TTS** — Kyutai. Model/inference code MIT; voices per-license
  (above). https://github.com/kyutai-labs/pocket-tts

## Phonemization

- **espeak-ng** — multilingual G2P, used by the direct engines (GPL-3.0;
  the store binary is a GPL-3.0 combined work as a result — see NOTICE).
- **Open JTalk** + **MeCab** + **NAIST Japanese Dictionary** — Japanese
  text-analysis frontend, vendored from source and compiled in (BSD-3 /
  Modified BSD). The Kotlin G2P that drives it is informed by **r9y9**'s
  `pyopenjtalk` and the **misaki** Japanese frontend.
- **misaki** / **cutlet** — Japanese G2P; our `CutletJaG2P` is a
  clean-room Kotlin port of the cutlet/misaki approach (upstream MIT).
- **OpenPhonemizer** — BSD-3-Clause-Clear English phonemizer; the basis
  for our clean-room Kotlin port used by the GPL-free path.
- **pypinyin** — Mandarin pinyin conventions informing the `lexicon-zh`
  path (MIT).

## Reference implementations & research

- **NekoSpeak** — independent reverse-engineering of the Pocket TTS
  inference pipeline. Their notes on the PKVS voice format, the
  NaN-latent quirk, and per-device threading informed our Pocket
  implementation (which is an independent Kotlin codebase).
- **Maise** (Dane Madsen) — MIT Android TTS app read as a reference for
  the direct-ONNX Kokoro / Kitten integration patterns.
- **KevinAHM/pocket-tts-onnx** — the ONNX export tooling whose 5-graph
  layout + `bundle.json` state-manifest format our Pocket bundle uses
  (Apache-2.0).

## Runtime & frameworks

- **ONNX Runtime (Mobile)** — Microsoft. MIT. Inference for the direct
  engines.
- **sherpa-onnx** — k2-fsa. Apache-2.0. Legacy (developer-only) engines.
- **Apache Commons Compress** — Apache-2.0. Engine-bundle extraction.
- **AndroidX, Jetpack Compose, Kotlin, Hilt, Room** — Apache-2.0.

## Concept lineage

- **marmalade-tts** (the Linux CLI) — the sister project this app
  borrows its concept vocabulary from: voice aliases / personas,
  per-voice preprocessing, the composable audio-effects chain, and the
  **emojivoice** emoji-driven emotional-prosody idea.

---

If you believe your work should be credited here and isn't, please open
an issue — it's an oversight, not an omission.
