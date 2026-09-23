# Runtime & inference libraries — third-party license notice

This file covers the third-party libraries linked into the Marmalade
APK itself (as opposed to the on-demand engine bundles documented in
the other files in this folder). Versions are authoritative in
`app/build.gradle.kts`.

## 1. ONNX Runtime Mobile — com.microsoft.onnxruntime:onnxruntime-android

- **Role:** Direct ONNX inference runtime used by every on-device engine
  (Kitten, Kokoro, Kokoro German, Pocket TTS and VITS Marmalade).
- **Upstream:** https://github.com/microsoft/onnxruntime
- **License:** MIT
- **Notice:** Copyright (c) Microsoft Corporation.
- **Third-party notices:** `libonnxruntime.so` statically links other
  open-source libraries (XNNPACK, protobuf, Abseil, FlatBuffers, Eigen and
  more). Microsoft's `ThirdPartyNotices.txt` for v1.26.0
  (https://github.com/microsoft/onnxruntime/blob/v1.26.0/ThirdPartyNotices.txt)
  is reproduced verbatim in the APK at
  `assets/licenses/onnxruntime-third-party-notices.txt`; each entry names
  its own license and holder.
- **Eigen (MPL-2.0):** one of those libraries, Eigen, is under the Mozilla
  Public License 2.0. Its source is available from
  https://gitlab.com/libeigen/eigen; ONNX Runtime 1.26.0 builds commit
  `1d8b82b0` on Eigen's 3.4 branch, pinned in its `cmake/deps.txt`.

## 2. Apache Commons Compress — org.apache.commons:commons-compress

- **Role:** Streams engine-bundle downloads through
  BZip2CompressorInputStream + TarArchiveInputStream during install.
- **Upstream:** https://commons.apache.org/proper/commons-compress/
- **License:** Apache-2.0
- **Notice:** Copyright (c) The Apache Software Foundation.

## 3. Readability4J — com.github.dankito:Readability4J

- **Role:** Reader mode's article extractor. Given the HTML of a page the
  user shared, it returns the article content with navigation, ads, and
  other clutter stripped.
- **Upstream:** https://github.com/dankito/Readability4J
- **License:** Apache-2.0 (`LICENSE` at the upstream repository)
- **Notice:** Copyright 2017 dankito.
- **Upstream attribution preserved:** Readability4J is a Kotlin port of
  **Mozilla's Readability.js** (https://github.com/mozilla/readability),
  also Apache-2.0, whose `NOTICE` file reads:

  > Readability
  > Copyright (c) 2010 Arc90 Inc
  > Copyright (c) 2010-2026 Mozilla and Contributors

  Readability4J's own LICENSE does not carry that notice, so we reproduce
  it here and in the in-app license entry (Apache-2.0 §4(d)).

The full Apache-2.0 body is in
[`full-texts/Apache-2.0.txt`](full-texts/Apache-2.0.txt) and ships in the
APK at `assets/licenses/Apache-2.0.txt`.

### SLF4J API — org.slf4j:slf4j-api (dependency of Readability4J)

- **Role:** Logging facade Readability4J calls into. Version 1.7.25; no
  logging backend ships, so it falls back to its no-op logger.
- **Upstream:** https://github.com/qos-ch/slf4j
- **License:** MIT
- **Notice:** Copyright (c) 2004-2017 QOS.ch

The exact MIT text with QOS.ch's copyright ships in the APK at
`assets/licenses/slf4j.txt`.

## 4. jsoup — org.jsoup:jsoup

- **Role:** HTML parser. Readability4J parses with jsoup, and reader mode
  uses it directly to walk the extracted article into paragraphs.
- **Upstream:** https://github.com/jhy/jsoup
- **License:** MIT
- **Notice:** Copyright (c) 2009-2026 Jonathan Hedley <https://jsoup.org/>
- **Why it is a direct dependency:** Readability4J (unmaintained since
  2021) pulls in jsoup 1.11.2, which carries **CVE-2021-37714** — a
  denial-of-service on crafted HTML, directly relevant to a feature that
  parses arbitrary pages from the web. Declaring `org.jsoup:jsoup`
  explicitly upgrades the whole graph to the current release.

The exact MIT text with Jonathan Hedley's copyright ships in the APK at
`assets/licenses/jsoup.txt`.

## 5. espeak-ng

espeak-ng is **GPL-3.0-or-later** and ships **in the APK** as
`libespeak-ng.so`, compiled from source out of the pinned
`third_party/espeak-ng` submodule (commit 96f0dbfb: 1.52.0 plus determinism fix espeak-ng#2071) by
`app/src/main/cpp/espeak-ng/CMakeLists.txt`. The MIT JNI shim
(`app/src/main/cpp/espeak_jni.c`) `dlopen`s it at runtime and contains
zero espeak code. Because of this component the distributed APK is a
GPL-3.0-or-later combined work — see `../NOTICE.md`.

The full `espeak-ng-data` tree is also generated from that same pinned
source at build time and ships in the APK. The Kitten and Kokoro engine
bundles still carry a copy of `espeak-ng-data` too, built from the same
commit since the v22 bundles; the app reads the APK's copy.

- **Upstream / corresponding source:** https://github.com/espeak-ng/espeak-ng
  (exact source: the submodule pin in this repository)
- **License:** GPL-3.0-or-later
- **Notice:** Copyright (C) 2005-2015 Jonathan Duddington;
  Copyright (C) 2012-2021 Reece H. Dunn; Copyright (C) 2018-2022 Juho
  Hiltunen; speechPlayer: Copyright 2014 NV Access Limited; and the other
  espeak-ng contributors (see the source file headers).

### Unicode Character Database (via espeak-ng's ucd-tools)

espeak-ng's `ucd-tools` library (itself GPL-3.0-or-later, part of
espeak-ng) carries tables generated from the **Unicode Character
Database**, so those tables are compiled into `libespeak-ng.so` too.

- **License:** Unicode-DFS-2016 (the Unicode, Inc. License Agreement for
  Data Files and Software)
- **Notice:** Copyright © 1991-2018 Unicode, Inc.
- **Text:** espeak-ng's `COPYING.UCD`, reproduced verbatim in the APK at
  `assets/licenses/unicode.txt`.

## Android / Jetpack / Kotlin dependencies

The app also depends on standard AndroidX / Jetpack Compose, Kotlin
stdlib + coroutines, Dagger Hilt, Room, DataStore, and Navigation
artifacts (see `app/build.gradle.kts`). These are licensed under the
**Apache License, Version 2.0** by their respective authors (the
Android Open Source Project, JetBrains, and Google). Full texts are
available at https://www.apache.org/licenses/LICENSE-2.0.
