# NOTICE

This file summarizes the licensing of **marmalade-tts-android** and the
third-party components it builds on. It is informational; the binding
texts are the per-component licenses referenced below.

## Source code: MIT

The Marmalade source code in this repository is licensed under the
**MIT License** — see [`LICENSE`](LICENSE). Every `.kt` file, the JNI
shims, and the build configuration are MIT. This is unchanged by
anything below.

## Distributed binary: GPL-3.0-or-later (espeak-ng is compiled in)

The **store build** (the APK published to Google Play and F-Droid)
includes **espeak-ng**, compiled from source into `libespeak-ng.so`.
espeak-ng is **GPL-3.0-or-later**, so the APK as a whole is distributed
under the terms of the **GPL-3.0-or-later**. Every other component in
the APK is MIT-, Apache-2.0-, or BSD-licensed — all GPL-compatible — and
the Marmalade source files themselves remain MIT (see above). Shipping
the lib in the APK is what Google Play requires (executable code must
not be downloaded at runtime) and what F-Droid prefers (built from
source on their buildserver).

### Corresponding source (GPL-3.0 §6)

Complete corresponding source for the APK is this repository, including
the pinned espeak-ng submodule it is built from:

> **https://github.com/maxwhipw/marmalade-tts-android**
> espeak-ng: **https://github.com/espeak-ng/espeak-ng** at tag
> **1.52.0** (pinned in `third_party/espeak-ng`; built by
> `app/src/main/cpp/espeak-ng/CMakeLists.txt`)

The `espeak-ng-data` directory shipped in the engine bundles (v22+) is
built from the same 1.52.0 tag, so the library and its data cite one
upstream commit. (Bundles up to v21 carried data derived from Debian
`1.51+dfsg` plus a legacy `libttsespeak.so` the app never loaded; both
were removed in the v22 re-spin — see the engines repo's release notes.)

## Full license texts

Verbatim copies of the licenses referenced here are in
[`LICENSES/full-texts/`](LICENSES/full-texts/): **GPL-3.0**, **Apache-2.0**,
**BSD-3-Clause**, **CC-BY-4.0**, **CC-BY-SA-4.0**, **CC0-1.0**, and **OFL-1.1**. The running app surfaces a per-component
breakdown under **Settings → About → Open-source licenses**, with the full
license text reachable for each component. License texts that embed the
licensor's copyright (MIT, BSD) ship **per component with the correct holder**
— the verbatim Open JTalk / MeCab `COPYING`, and the standard MIT body with
each project's own copyright line (Marmalade, ONNX Runtime / Microsoft, Pocket
/ Kyutai). The standalone bodies that carry no embedded licensor copyright
(GPL-3.0, Apache-2.0, CC-BY-4.0, CC-BY-SA-4.0, CC0-1.0) are shared, with
attribution shown per component. All in-app texts are bundled in the APK under `assets/licenses/`.

## On-demand engine bundles

The default APK bundles **no neural model files**. Engines and their
phonemizer assets are downloaded on opt-in from
**https://github.com/maxwhipw/marmalade-tts-android-engines/releases**
into app-private storage. Bundles contain model weights and phonemizer
data — no executable code is downloaded. The Kitten and Kokoro bundles
include the GPL-licensed `espeak-ng-data` dictionaries (older bundle
versions also carried a now-unused `libttsespeak.so`); the install
screen discloses each bundle's licenses before download. The Pocket
bundle contains no GPL components. Per-bundle detail is in the
[`LICENSES/`](LICENSES/) folder.

## Per-component summary

| Component | Role | Where it ships | License |
|---|---|---|---|
| Marmalade app code | The app | APK (source) | **MIT** |
| espeak-ng | Phonemizer (English/multi) | APK (compiled from source); data in engine bundles | **GPL-3.0-or-later** |
| ONNX Runtime Mobile | Inference runtime | APK | MIT |
| Apache Commons Compress | Engine-bundle extraction | APK | Apache-2.0 |
| Open JTalk + MeCab | Japanese phonemizer frontend | APK (compiled in) | BSD-3-Clause |
| misaki / cutlet (port) | Japanese G2P tables (clean-room Kotlin port) | APK (source) | MIT |
| Kokoro-82M | Neural voice model | Engine bundle | Apache-2.0 |
| KittenTTS (nano/mini) | Neural voice model | Engine bundle | Apache-2.0 |
| Pocket TTS (Kyutai) — model code | Neural voice model (English) | Engine bundle | **MIT** |
| Pocket TTS predefined voices (6) | Reference voice prompts | Engine bundle | CC0 / CC-BY-4.0 (per voice — see [`LICENSES/pocket-tts.md`](LICENSES/pocket-tts.md)) |
| open_jtalk dictionary | Japanese MeCab dictionary | Engine bundle | Modified BSD |
| lexicon-zh (Mandarin G2P) | Han→IPA Mandarin lexicon | Engine bundle | **CC-BY-SA-4.0** (CC-CEDICT-derived via pypinyin — see [`LICENSES/kokoro-direct.md`](LICENSES/kokoro-direct.md)) |
| AndroidX / Compose / Kotlin / Hilt / Room | App framework | APK | Apache-2.0 |
| Manrope + Momo Trust Display + Fredoka fonts | Brand typography | APK | OFL-1.1 (see [`LICENSES/fonts.md`](LICENSES/fonts.md)) |

Full per-component notices, file lists, and upstream URLs are in
[`LICENSES/`](LICENSES/).
