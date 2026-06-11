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

## 2. Apache Commons Compress — org.apache.commons:commons-compress

- **Role:** Streams engine-bundle downloads through
  BZip2CompressorInputStream + TarArchiveInputStream during install.
- **Upstream:** https://commons.apache.org/proper/commons-compress/
- **License:** Apache-2.0
- **Notice:** Copyright (c) The Apache Software Foundation.

## 3. espeak-ng (standalone reference)

espeak-ng is **GPL-3.0-or-later**. It does **not** ship in the APK. It
reaches the device only as `libttsespeak.so` inside user-downloaded
engine bundles, `dlopen`'d at runtime by the MIT JNI shim
(`app/src/main/cpp/espeak_jni.c`), which contains zero espeak code.
See `kitten-direct.md`.

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
