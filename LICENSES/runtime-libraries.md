# Runtime & inference libraries — third-party license notice

This file covers the third-party libraries linked into the Marmalade
APK itself (as opposed to the on-demand engine bundles documented in
the other files in this folder). Versions are authoritative in
`app/build.gradle.kts`.

## 1. ONNX Runtime Mobile — com.microsoft.onnxruntime:onnxruntime-android

- **Role:** Direct ONNX inference runtime used by the Pocket TTS engine
  and the "-direct" Kitten / Kokoro engines.
- **Upstream:** https://github.com/microsoft/onnxruntime
- **License:** MIT
- **Notice:** Copyright (c) Microsoft Corporation.

## 2. sherpa-onnx (vendored AAR) — k2-fsa/sherpa-onnx

- **File:** `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar`
- **Role:** Inference + packaging for the legacy sherpa-backed Kitten /
  Kokoro engines.
- **Upstream:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache-2.0
- **Notice:** Copyright (c) k2-fsa / sherpa-onnx contributors.
- **GPL flag:** The vendored AAR statically links espeak-ng
  (GPL-3.0-or-later) for phonemization. Builds that include the sherpa
  engines therefore link GPL-3.0-or-later code. See `kitten-tts.md` /
  `kokoro-tts.md` and the store-build note in `../NOTICE.md`.

## 3. Apache Commons Compress — org.apache.commons:commons-compress

- **Role:** Streams engine-bundle downloads through
  BZip2CompressorInputStream + TarArchiveInputStream during install.
- **Upstream:** https://commons.apache.org/proper/commons-compress/
- **License:** Apache-2.0
- **Notice:** Copyright (c) The Apache Software Foundation.

## 4. espeak-ng (standalone reference)

espeak-ng is **GPL-3.0-or-later**. It reaches the distributed binary by
two paths:

1. **Store build:** statically linked inside the vendored sherpa-onnx
   AAR (path 2 above), making the **distributed store APK a
   GPL-3.0-or-later combined work**. See `../NOTICE.md`.
2. **"-direct" engine bundles:** shipped as `libttsespeak.so` inside
   user-downloaded bundles and `dlopen`'d at runtime by the MIT JNI
   shim (`app/src/main/cpp/espeak_jni.c`), which contains zero espeak
   code. See `kitten-direct.md`.

- **Upstream / corresponding source:** https://github.com/espeak-ng/espeak-ng
- **License:** GPL-3.0-or-later
- **Notice:** Copyright (c) The espeak-ng authors.

## Android / Jetpack / Kotlin dependencies

The app also depends on standard AndroidX / Jetpack Compose, Kotlin
stdlib + coroutines, Dagger Hilt, Room, DataStore, and Navigation
artifacts (see `app/build.gradle.kts`). These are licensed under the
**Apache License, Version 2.0** by their respective authors (the
Android Open Source Project, JetBrains, and Google). Full texts are
available at https://www.apache.org/licenses/LICENSE-2.0.
