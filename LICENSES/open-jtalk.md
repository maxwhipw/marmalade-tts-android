# Open JTalk (Japanese phonemizer) — third-party license notice

Unlike espeak-ng (which is `dlopen`'d from a user-downloaded engine
bundle), **Open JTalk is compiled directly into the APK** as native
code. Its vendored C/C++ sources live in
`app/src/main/cpp/openjtalk/` and are statically linked into
`libopenjtalk-jni.so`, which ships in every build. Open JTalk and its
bundled MeCab analyzer are both BSD-3-Clause, so this does **not**
introduce any GPL obligation into the APK.

Open JTalk provides the Japanese text-analysis frontend (kanji→kana
reading, mora segmentation, pitch accent) consumed by the Kokoro
Japanese voice path. The Kotlin driver lives in
`app/src/main/java/app/marmalade/tts/phonemizer/OpenJtalkPhonemizer.kt`.

## 1. Open JTalk

- **Files:** `app/src/main/cpp/openjtalk/` (text2mecab, NJD, JPCommon,
  njd2jpcommon, mecab2njd, and supporting rule headers)
- **Upstream:** http://open-jtalk.sourceforge.net/
- **License:** BSD-3-Clause (HTS Working Group style; see
  `app/src/main/cpp/openjtalk/COPYING`)
- **Notice:** Copyright (c) 2008–2016 Nagoya Institute of Technology,
  Department of Computer Science. All rights reserved. Redistribution
  permitted under the three-clause BSD conditions in `COPYING`.

## 2. MeCab (bundled with Open JTalk)

- **Files:** `app/src/main/cpp/openjtalk/mecab/`
- **Upstream:** https://taku910.github.io/mecab/
- **License:** BSD-3-Clause (see
  `app/src/main/cpp/openjtalk/mecab/COPYING`)
- **Notice:** Copyright (c) 2001–2008 Taku Kudo; Copyright (c)
  2004–2008 Nippon Telegraph and Telephone Corporation. All rights
  reserved.

## 3. Dictionary — open_jtalk_dic_utf_8

- **Files:** the MeCab dictionary (`open_jtalk_dic_utf_8-1.11`) is
  **not bundled in the APK**; it downloads as part of the Kokoro
  engine bundle into `${filesDir}/engines/<engine>/` on opt-in
  install.
- **Upstream:** http://open-jtalk.sourceforge.net/ (dictionary release)
- **License:** Modified BSD (NAIST Japanese Dictionary / IPADIC
  lineage). Redistributed unmodified from the upstream Open JTalk
  dictionary release.

## 4. JNI shim — Marmalade (this repo)

- **File:** `app/src/main/cpp/openjtalk_jni.c`
- **License:** MIT (matches the rest of Marmalade)
- **Role:** Bridges Kotlin to the statically-linked Open JTalk NJD
  frontend.

## Japanese G2P port — misaki / cutlet

The IPA conversion layer
(`app/src/main/java/app/marmalade/tts/phonemizer/CutletJaG2P.kt`) is a
clean-room Kotlin port of misaki's `cutlet.py` conversion tables —
**MIT**. No upstream code is copied; only the algorithm and mapping
tables are reimplemented. See the provenance header in that file.

- **Upstream:** https://github.com/hexgrad/misaki
- **License:** MIT
