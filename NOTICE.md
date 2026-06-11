# NOTICE

This file summarizes the licensing of **marmalade-tts-android** and the
third-party components it builds on. It is informational; the binding
texts are the per-component licenses referenced below.

## Source code: MIT

The Marmalade source code in this repository is licensed under the
**MIT License** — see [`LICENSE`](LICENSE). Every `.kt` file, the JNI
shims, and the build configuration are MIT. This is unchanged by
anything below.

## Distributed binary: MIT (no GPL in the APK)

The **store build** (the APK published to Google Play and F-Droid)
contains only MIT-, Apache-2.0-, and BSD-licensed code. **No GPL code is
linked into or shipped inside the APK.** Source for the binary is this
repository:

> **https://github.com/maxwhipw/marmalade-tts-android**

**espeak-ng (GPL-3.0-or-later)** reaches the device only inside optional
engine bundles the user chooses to download (see "On-demand engine
bundles" below). The APK's espeak JNI shim contains zero espeak code —
it resolves espeak's C API at runtime via `dlopen()`, so the GPL
combination is assembled in app-private storage on the user's device
when they accept the engine install.

### espeak-ng corresponding source (GPL-3.0 §6, engine bundles)

Corresponding source for the espeak-ng binary shipped in the engine
bundles:

> **https://github.com/espeak-ng/espeak-ng**

The bundled espeak-ng binary derives from espeak-ng **1.52.0**. ⚠️ The
`espeak-ng-data` currently bundled derives from a different upstream
revision (Debian `1.51+dfsg`); a future from-source espeak build will
compile both the library and its data from a single pinned commit, which
this notice will then cite exactly (GPL-3.0 requires the corresponding
source to match the exact version conveyed).

## Full license texts

Verbatim copies of the licenses referenced here are in
[`LICENSES/full-texts/`](LICENSES/full-texts/): **GPL-3.0**, **Apache-2.0**,
**BSD-3-Clause**, **CC-BY-4.0**, **CC-BY-SA-4.0**, and **CC0-1.0**. The running app surfaces a per-component
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
into app-private storage. Some bundles include espeak-ng (`libttsespeak.so`)
and are themselves GPL-3.0-or-later combined works *as assembled on the
user's device*; the install screen discloses this before download. The Pocket
engine bundle contains no GPL components. Per-bundle detail is in the
[`LICENSES/`](LICENSES/) folder.

## Per-component summary

| Component | Role | Where it ships | License |
|---|---|---|---|
| Marmalade app code | The app | APK (source) | **MIT** |
| espeak-ng | Phonemizer (English/multi) | Engine bundles (opt-in download) | **GPL-3.0-or-later** |
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

Full per-component notices, file lists, and upstream URLs are in
[`LICENSES/`](LICENSES/).
