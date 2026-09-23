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
under the terms of the **GPL-3.0-or-later**. Every other piece of code
and data in the APK is under a GPL-3.0-compatible license (MIT, BSD,
Apache-2.0, MPL-2.0, or the Unicode license). The brand fonts are
OFL-1.1 and ride along as separate font files under their own terms. The
Marmalade source files themselves remain MIT (see above). Shipping
the lib in the APK is what Google Play requires (executable code must
not be downloaded at runtime) and what F-Droid prefers (built from
source on their buildserver).

### Corresponding source (GPL-3.0 §6)

Complete corresponding source for the APK is this repository, including
the pinned espeak-ng submodule it is built from:

> **https://github.com/maxwhipw/marmalade-tts-android**
> espeak-ng: **https://github.com/espeak-ng/espeak-ng** at commit
> **96f0dbfb** (the 1.52.0 release plus upstream's determinism fix for
> dictionary compilation, espeak-ng#2071; pinned in
> `third_party/espeak-ng`; built by
> `app/src/main/cpp/espeak-ng/CMakeLists.txt`)

The `espeak-ng-data` directory shipped in the engine bundles (v22+) is
built from the same pinned commit, so the library and its data cite one
upstream commit. (Bundles up to v21 carried data derived from Debian
`1.51+dfsg` plus a legacy `libttsespeak.so` the app never loaded; both
were removed in the v22 re-spin — see the engines repo's release notes.)

### Eigen source (MPL-2.0)

ONNX Runtime, which ships in the APK as `libonnxruntime.so`, compiles in
the **Eigen** C++ library, which is **MPL-2.0**. Eigen's source is
available from **https://gitlab.com/libeigen/eigen**. The exact revision
ONNX Runtime 1.26.0 builds is commit `1d8b82b0` on Eigen's 3.4 branch,
pinned in ONNX Runtime's `cmake/deps.txt` at tag `v1.26.0`.

## Full license texts

Verbatim copies of the licenses referenced here are in
[`LICENSES/full-texts/`](LICENSES/full-texts/): **GPL-3.0**, **Apache-2.0**,
**BSD-3-Clause**, **CC-BY-4.0**, **CC-BY-SA-4.0**, **CC0-1.0**, and **OFL-1.1**. The running app surfaces a per-component
breakdown under **Settings → About → Open-source licenses**, with the full
license text reachable for each component. License texts that embed the
licensor's copyright (MIT, BSD, the Unicode license) ship **per component
with the correct holder**: the verbatim Open JTalk / MeCab `COPYING` and
open_jtalk dictionary notice, espeak-ng's verbatim `COPYING.UCD` (Unicode,
Inc.), and the MIT text with each project's own copyright line (Marmalade,
ONNX Runtime / Microsoft, Pocket / Kyutai, jsoup / Jonathan Hedley, SLF4J /
QOS.ch, cutlet / Paul O'Leary McCann). ONNX Runtime 1.26.0's
`ThirdPartyNotices.txt` is reproduced verbatim for the libraries linked
into `libonnxruntime.so`; each entry names its own license and holder,
including Eigen under MPL-2.0. The Jenny (Dioco) voice pack's two custom
attribution licenses are quoted verbatim in a text of their own. The
standalone bodies that carry no embedded licensor copyright (GPL-3.0,
Apache-2.0, CC-BY-4.0, CC-BY-SA-4.0, CC0-1.0, OFL-1.1) are shared, with
attribution shown per component. All in-app texts are bundled in the APK
under `assets/licenses/`.

## Baked-in engine and on-demand engine bundles

The APK ships one engine out of the box: **Kitten Nano** (Apache-2.0
model weights + 8 voices, baked in as `assets/engines-seed/`), so the
app speaks offline from first launch. It also ships **one full
`espeak-ng-data` set** (GPL-3.0-or-later), generated at build time from
the pinned `third_party/espeak-ng` submodule and shared by all
espeak-phonemized engines. All other engines download on opt-in from
**https://github.com/maxwhipw/marmalade-tts-android-engines/releases**
into app-private storage. Bundles contain model weights and phonemizer
data — no executable code is downloaded (older bundle versions carried
a now-unused `libttsespeak.so`); the install screen discloses each
bundle's licenses before download. The Pocket bundle contains no GPL
components. Per-bundle detail is in the [`LICENSES/`](LICENSES/)
folder.

## Per-component summary

| Component | Role | Where it ships | License |
|---|---|---|---|
| Marmalade app code | The app | APK (source) | **MIT** |
| espeak-ng | Phonemizer (English/multi) | APK (library and full `espeak-ng-data`, both built from source); Kitten and Kokoro engine bundles also carry a copy of `espeak-ng-data` | **GPL-3.0-or-later** |
| Unicode Character Database (via espeak-ng's ucd-tools) | Unicode character tables compiled into espeak-ng | APK (compiled in) | Unicode-DFS-2016 |
| ONNX Runtime Mobile | Inference runtime | APK | MIT |
| ONNX Runtime third-party notices | Libraries statically linked into ONNX Runtime (XNNPACK, protobuf, Abseil, FlatBuffers, Eigen, …) | APK (inside `libonnxruntime.so`) | Per entry, as published with ONNX Runtime 1.26.0 (MIT, BSD, Apache-2.0, **MPL-2.0** for Eigen, …) |
| Apache Commons Compress | Engine-bundle extraction | APK | Apache-2.0 |
| Readability4J | Reader-mode article extraction | APK | Apache-2.0 |
| SLF4J API | Logging facade (dependency of Readability4J) | APK | MIT |
| jsoup | HTML parser (reader mode) | APK | MIT |
| Open JTalk + MeCab | Japanese phonemizer frontend | APK (compiled in) | BSD-3-Clause |
| misaki / cutlet (Kotlin port) | Japanese G2P tables (clean-room Kotlin port of misaki's `cutlet.py`) | APK (source) | Apache-2.0 |
| cutlet (polm/cutlet) | Origin of misaki's `cutlet.py`, credited through the port | APK (source) | MIT |
| misaki (German G2P port) | German text normalizer + pronunciation-override lexicon (clean-room Kotlin port) | APK (source) | Apache-2.0 (see [`LICENSES/kokoro-de.md`](LICENSES/kokoro-de.md)) |
| Kokoro-82M | Neural voice model | Engine bundle | Apache-2.0 (see [`LICENSES/kokoro-direct.md`](LICENSES/kokoro-direct.md)) |
| Thorsten-Voice/Kokoro (German fine-tune) | Neural voice model (German) | Engine bundle | Apache-2.0 (see [`LICENSES/kokoro-de.md`](LICENSES/kokoro-de.md)) |
| KittenTTS (nano) | Neural voice model | APK (baked-in) and engine bundle | Apache-2.0 |
| Jenny (Dioco) | English VITS voice pack | Voice pack (downloaded) | Custom attribution licenses for the weights (Bryce Beattie) and the training data (Jenny, dioco-group); the voice must be credited "Jenny (Dioco)" (see [`LICENSES/vits-marmalade.md`](LICENSES/vits-marmalade.md)) |
| Other VITS voice packs (developer mode only) | Ukrainian, Icelandic, Swedish, Kazakh and Norwegian voices | Voice pack (downloaded) | MIT weights; training data license per pack (see [`LICENSES/vits-marmalade.md`](LICENSES/vits-marmalade.md)) |
| Pocket TTS (Kyutai) — model code | Neural voice model (English) | Engine bundle | **MIT** |
| Pocket TTS predefined voices (6) | Reference voice prompts | Engine bundle | CC0 / CC-BY-4.0 (per voice — see [`LICENSES/pocket-tts.md`](LICENSES/pocket-tts.md)) |
| open_jtalk dictionary | Japanese MeCab dictionary | Engine bundle | Modified BSD |
| lexicon-zh (Mandarin G2P) | Han→IPA Mandarin lexicon | Engine bundle | **CC-BY-SA-4.0** (CC-CEDICT-derived via pypinyin — see [`LICENSES/kokoro-direct.md`](LICENSES/kokoro-direct.md)) |
| AndroidX / Compose / Kotlin / Hilt / Room | App framework | APK | Apache-2.0 |
| Manrope + Momo Trust Display + Fredoka fonts | Brand typography | APK | OFL-1.1 (see [`LICENSES/fonts.md`](LICENSES/fonts.md)) |

Full per-component notices, file lists, and upstream URLs are in
[`LICENSES/`](LICENSES/).
