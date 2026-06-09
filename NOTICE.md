# NOTICE

This file summarizes the licensing of **marmalade-tts-android** and the
third-party components it builds on. It is informational; the binding
texts are the per-component licenses referenced below.

## Source code: MIT

The Marmalade source code in this repository is licensed under the
**MIT License** — see [`LICENSE`](LICENSE). Every `.kt` file, the JNI
shims, and the build configuration are MIT. This is unchanged by
anything below.

## Distributed binary: GPL-3.0-or-later combined work

The **store build** (the APK published to Google Play and F-Droid)
statically links **espeak-ng**, which is licensed under
**GPL-3.0-or-later**, via the vendored sherpa-onnx AAR. Linking GPL
code into the binary makes the **distributed store APK as a whole a
combined work governed by GPL-3.0-or-later**.

This is permitted and does **not** relicense Marmalade's source:

- The MIT license is one-directionally compatible with the GPL — MIT
  code may be combined into a GPL work.
- The source code itself remains MIT-licensed. Only the *assembled
  store binary* is a GPL-3.0 combined work.
- Anyone may take the MIT source and build a binary without espeak-ng
  (e.g. using only the GPL-free Pocket engine path), which would not be
  a GPL combined work.

### Corresponding source (GPL-3.0 §3 / §6)

The complete corresponding source for the store binary is this
repository:

> **https://github.com/maxwhipw/marmalade-tts-android**

Corresponding source for the espeak-ng component specifically is
available from its upstream project:

> **https://github.com/espeak-ng/espeak-ng**

The shipped espeak-ng binary derives from espeak-ng **1.52.0**. ⚠️ The
`espeak-ng-data` currently bundled derives from a different upstream
revision (Debian `1.51+dfsg`); the planned from-source espeak build will
compile both the library and its data from a single pinned commit, which
this notice will then cite exactly (GPL-3.0 requires the corresponding
source to match the exact version conveyed).

## Full license texts

Verbatim copies of the licenses referenced here are in
[`LICENSES/full-texts/`](LICENSES/full-texts/): **GPL-3.0**, **Apache-2.0**,
**BSD-3-Clause**, and **CC-BY-4.0**. (An in-app "Open-source licenses"
screen is planned so these are reachable from the running app as well.)

## On-demand engine bundles

The default APK bundles **no neural model files**. Engines and their
phonemizer assets are downloaded on opt-in from
**https://github.com/maxwhipw/marmalade-tts-android-engines/releases**
into app-private storage. Some bundles include espeak-ng (`libttsespeak.so`)
and are themselves GPL-3.0 combined works *as assembled on the user's
device*; the install screen discloses this before download. The Pocket
engine bundle contains no GPL components. Per-bundle detail is in the
[`LICENSES/`](LICENSES/) folder.

## Per-component summary

| Component | Role | Where it ships | License |
|---|---|---|---|
| Marmalade app code | The app | APK (source) | **MIT** |
| espeak-ng | Phonemizer (English/multi) | Store APK (linked via sherpa AAR) + some engine bundles | **GPL-3.0-or-later** |
| sherpa-onnx | Legacy inference + packaging | APK (vendored AAR) | Apache-2.0 |
| ONNX Runtime Mobile | Inference runtime (direct engines) | APK | MIT |
| Apache Commons Compress | Engine-bundle extraction | APK | Apache-2.0 |
| Open JTalk + MeCab | Japanese phonemizer frontend | APK (compiled in) | BSD-3-Clause |
| misaki / cutlet (port) | Japanese G2P tables (clean-room Kotlin port) | APK (source) | MIT |
| OpenPhonemizer | GPL-free phonemizer (direct engines) | Engine bundle | BSD-3-Clause Clear |
| Kokoro-82M | Neural voice model | Engine bundle | Apache-2.0 |
| KittenTTS (nano/mini) | Neural voice model | Engine bundle | Apache-2.0 |
| Pocket TTS (Kyutai) — model code | Neural voice model (English) | Engine bundle | **MIT** |
| Pocket TTS predefined voices (6) | Reference voice prompts | Engine bundle | CC0 / CC-BY-4.0 (per voice — see [`LICENSES/pocket-tts.md`](LICENSES/pocket-tts.md)) |
| open_jtalk dictionary | Japanese MeCab dictionary | Engine bundle | Modified BSD |
| AndroidX / Compose / Kotlin / Hilt / Room | App framework | APK | Apache-2.0 |

Full per-component notices, file lists, and upstream URLs are in
[`LICENSES/`](LICENSES/).
