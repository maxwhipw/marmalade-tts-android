# Kitten Direct — third-party license notice

Covers **Kitten Direct** (nano, 15M params, `kitten-direct-v0_8`), which
runs the upstream KittenML acoustic model directly on
`com.microsoft.onnxruntime:onnxruntime-android`, with espeak-ng as the
phonemizer. espeak-ng (GPL-3.0-or-later) is compiled from source into
the APK, and the APK also ships the full `espeak-ng-data` dictionaries
(generated at build time from the pinned submodule).

The nano engine is **baked into the APK** as a seed
(`assets/engines-seed/kitten-direct-v0_8/`: model + 8 voices,
Apache-2.0) so the app speaks offline from first launch, with no
download.

## License posture — APK vs bundle

| Layer | Contents | License |
|---|---|---|
| Marmalade APK | Kotlin engine code, ORT bindings, JNI shim, **libespeak-ng.so (compiled from source)**, full espeak-ng-data (build-time generated), baked nano model + voices | Source files MIT; model files Apache-2.0; **APK distributed under GPL-3.0-or-later** because of espeak-ng |

The JNI shim (`app/src/main/cpp/espeak_jni.c`) links no espeak code at
build time — it `dlopen()`s the APK's own `libespeak-ng.so`, built from
the pinned `third_party/espeak-ng` submodule. See
[`../NOTICE.md`](../NOTICE.md).

## 1. Acoustic model — KittenML/kitten-tts-nano-0.8

- **Files:** `kitten.onnx`, `voices/{bella,jasper,luna,bruno,rosie,hugo,kiki,leo}.bin`
- **Upstream:** https://github.com/KittenML/KittenTTS
- **Distribution:** https://huggingface.co/KittenML/kitten-tts-nano-0.8-fp32
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) KittenML contributors. Voices were extracted
  from upstream's `voices.npz` and saved as per-voice little-endian
  float32 binaries.

## 2. Phonemizer — espeak-ng

- **Library:** `libespeak-ng.so` — in the **APK**, compiled from source
  (submodule `third_party/espeak-ng`, commit 96f0dbfb: the 1.52.0 release plus upstream determinism fix espeak-ng#2071) by
  `app/src/main/cpp/espeak-ng/CMakeLists.txt`.
- **Data:** `phonemizer/espeak-ng-data/` (full tree) — in the bundle;
  derives from Debian package `espeak-ng-data 1.51+dfsg-12build1`.
- **Legacy:** bundles ≤v16 also carried `phonemizer/<abi>/libttsespeak.so`
  (lifted from espeak-ng's official 1.52.0 Android APK). The app no
  longer loads it; it remains covered by the same GPL terms.
- **Upstream:** https://github.com/espeak-ng/espeak-ng
- **License:** GNU General Public License v3.0 or later
- **Notice:** Copyright (c) The espeak-ng authors. Used in sentence
  mode (`espeak_TextToPhonemes` with `phonememode = IPA`).
- **Source availability:** Per GPL-3.0 §6, corresponding source for the
  APK's libespeak-ng.so is the pinned submodule in this repository
  (upstream commit 96f0dbfb: 1.52.0 plus determinism fix espeak-ng#2071).

## 3. JNI shim — Marmalade (this repo)

- **File:** `app/src/main/cpp/espeak_jni.c` (~150 lines)
- **License:** MIT (matches the rest of Marmalade)
- **Role:** Resolves espeak's C API at runtime via `dlopen`/`dlsym`.
  Never linked against espeak at build time; contains zero lines of
  espeak code.

## GPL-3.0 implications

The distributed APK compiles in espeak-ng, so the APK as a whole is a
GPL-3.0-or-later combined work; all other APK components are
GPL-compatible (MIT/Apache-2.0/BSD) and Marmalade's own source files
remain MIT. The Kitten bundle adds GPL-licensed espeak-ng-data; the
install screen discloses this before download. See
[`../NOTICE.md`](../NOTICE.md).
