# Kokoro Direct engine — third-party license notice

The Kokoro Direct engine (`kokoro-direct-v1_0`, the recommended default)
runs the Kokoro-82M v1.0 model on Microsoft `onnxruntime-android`
directly (no sherpa-onnx). Its model + phonemizer assets are downloaded
on opt-in from `marmalade-tts-android-engines` into
`${filesDir}/engines/kokoro-direct-v1_0/`; the default APK bundles no
model files.

## Components in the downloaded bundle

| Component | Role | License |
|---|---|---|
| **Kokoro-82M v1.0** (`model.onnx`, `voices.bin`) | Neural voice model, 53 voices / 9 languages | **Apache-2.0** (hexgrad/Kokoro-82M) |
| **espeak-ng** (`libttsespeak.so` + `espeak-ng-data`) | Grapheme→phoneme for English + European languages | **GPL-3.0-or-later** |
| **Open JTalk dictionary** (NAIST/`open_jtalk_dic`) | Japanese text-analysis dictionary | **Modified BSD** |
| **lexicon-zh** (`lexicon-zh.txt`, pinyin tables) | Mandarin pinyin lexicon | derived from **pypinyin** (MIT) + CC-CEDICT |

The Japanese **Open JTalk / MeCab** frontend itself is compiled into the
APK from vendored BSD-3 source (`app/src/main/cpp/openjtalk/`) — see
[`open-jtalk.md`](open-jtalk.md) — not downloaded.

## espeak-ng (GPL) and the combined work

This engine depends on **espeak-ng (GPL-3.0-or-later)** for
phonemization. The JNI shim (`app/src/main/cpp/espeak_jni.c`) links no
espeak code at build time — it `dlopen()`s `libttsespeak.so` from the
downloaded bundle, so *this engine's* espeak combination is assembled on
the user's device on install.

Independently, the **distributed APK as a whole is a GPL-3.0-or-later
combined work** (the vendored sherpa-onnx AAR statically links espeak in
every release build). The Marmalade **source** stays MIT. See
[`../NOTICE.md`](../NOTICE.md) for the whole-APK license and the GPL
corresponding-source pointer.

## Notices

- **Kokoro-82M** — Copyright (c) hexgrad. Apache License, Version 2.0.
  Model card: https://huggingface.co/hexgrad/Kokoro-82M
- **espeak-ng** — Copyright (c) eSpeak NG authors. GPL-3.0-or-later.
  Corresponding source: https://github.com/espeak-ng/espeak-ng (the
  exact version is pinned in [`../NOTICE.md`](../NOTICE.md)).
- **Open JTalk dictionary** — Nagoya Institute of Technology; NAIST
  Japanese Dictionary. Modified BSD. See [`open-jtalk.md`](open-jtalk.md).
- **pypinyin** — MIT. https://github.com/mozillazg/python-pinyin
