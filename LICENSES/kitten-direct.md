# Kitten Direct — third-party license notice

Kitten Direct runs the upstream KittenML acoustic model directly on
`com.microsoft.onnxruntime:onnxruntime-android` (not via sherpa-onnx),
with espeak-ng as the phonemizer. The bundle ships under GPL-3.0
because of espeak-ng; the Marmalade APK itself stays MIT-licensed.

The acoustic engine is downloaded from
`marmalade-tts-android-engines` (release `v14`) into
`${filesDir}/engines/kitten-direct-v0_8/` when the user opts in.

## License posture — APK vs bundle

| Layer | Contents | License |
|---|---|---|
| Marmalade APK | Kotlin engine code, ORT bindings, JNI shim (10 KB C, dlopen-only) | MIT |
| Engine bundle | espeak-ng `.so`, espeak-ng-data, acoustic model, voices | Mixed; **GPL-3.0 governs the assembled combination** |

The JNI shim (`app/src/main/cpp/espeak_jni.c`) links no espeak code at
build time — it only uses `dlopen()`/`dlsym()` to look up espeak's C
API at runtime, from the path provided by Kotlin (which points into
the user-downloaded bundle). So *this engine's* espeak combination is
assembled on the user's device when they accept the engine install.
Note, however, that the **distributed APK as a whole is a
GPL-3.0-or-later combined work** — the vendored sherpa-onnx AAR
statically links espeak inside `libsherpa-onnx-jni.so`, which ships in
every release build. The source repo stays MIT. See
[`../NOTICE.md`](../NOTICE.md) for the whole-APK license.

This is the same legal posture sherpa-onnx Kitten / Kokoro already
have, made explicit and isolated to one engine.

## 1. Acoustic model — KittenML/kitten-tts-nano-0.8

- **Files:** `kitten.onnx`, `voices/{bella,jasper,luna,bruno,rosie,hugo,kiki,leo}.bin`
- **Upstream:** https://github.com/KittenML/KittenTTS
- **Distribution:** https://huggingface.co/KittenML/kitten-tts-nano-0.8-fp32
- **License:** Apache License, Version 2.0
- **Notice:** Copyright (c) KittenML contributors. Voices were extracted
  from upstream's `voices.npz` and saved as per-voice little-endian
  float32 binaries.

## 2. Phonemizer — espeak-ng

- **Files (per ABI):**
  - `phonemizer/arm64-v8a/libttsespeak.so`
  - `phonemizer/armeabi-v7a/libttsespeak.so`
- **Data:** `phonemizer/espeak-ng-data/` (full tree)
- **Upstream:** https://github.com/espeak-ng/espeak-ng
- **Source:** Binaries lifted from espeak-ng's official Android APK
  (release 1.52.0, `espeak-1.52.0-signed.apk`); data from upstream
  Debian package `espeak-ng-data 1.51+dfsg-12build1`.
- **License:** GNU General Public License v3.0
- **Notice:** Copyright (c) The espeak-ng authors. Used in sentence
  mode (`espeak_TextToPhonemes` with `phonememode = IPA`).
- **Source availability:** Per GPL-3.0 §6, the corresponding source
  for the espeak-ng binary in this bundle is available at the upstream
  URL above; releases page lists the corresponding tagged source for
  each binary version.

## 3. JNI shim — Marmalade (this repo)

- **File:** `app/src/main/cpp/espeak_jni.c` (~150 lines)
- **License:** MIT (matches the rest of Marmalade)
- **Role:** Resolves espeak's C API at runtime via `dlopen`/`dlsym`.
  Never linked against espeak at build time; contains zero lines of
  espeak code.

## GPL-3.0 implications

The Kitten Direct engine bundle (downloaded into the user's app data
directory) is a GPL-3.0 combination because it contains espeak-ng
binaries. The install screen discloses this before download. Users
who don't want GPL components can stay on the MIT-only Pocket TTS
engine, or refrain from installing Kitten Direct.

The Marmalade **source** is MIT, but the **distributed APK** (Google
Play / F-Droid / direct download) is a GPL-3.0-or-later combined work,
because it links espeak-ng. See [`../NOTICE.md`](../NOTICE.md).
