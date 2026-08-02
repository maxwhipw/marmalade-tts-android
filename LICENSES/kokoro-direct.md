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
| **espeak-ng data** (`espeak-ng-data`; ≤v18 bundles also carry a now-unused `libttsespeak.so`) | Grapheme→phoneme data for English + European languages | **GPL-3.0-or-later** |
| **Open JTalk dictionary** (NAIST/`open_jtalk_dic`) | Japanese text-analysis dictionary | **Modified BSD** |
| **lexicon-zh** (`lexicon-zh.txt`) | Mandarin Han→IPA lexicon | **CC-BY-SA-4.0** (pinyin data is CC-CEDICT-derived via pypinyin — see note) |

The Japanese **Open JTalk / MeCab** frontend itself is compiled into the
APK from vendored BSD-3 source (`app/src/main/cpp/openjtalk/`) — see
[`open-jtalk.md`](open-jtalk.md) — not downloaded.

## espeak-ng (GPL) and the combined work

This engine depends on **espeak-ng (GPL-3.0-or-later)** for
phonemization. The library (`libespeak-ng.so`) is compiled from source
into the APK from the pinned `third_party/espeak-ng` submodule (tag
1.52.0) and `dlopen()`d at runtime by the JNI shim
(`app/src/main/cpp/espeak_jni.c`); the bundle supplies the
`espeak-ng-data` dictionaries.

Because espeak-ng ships inside it, the distributed APK is a
GPL-3.0-or-later combined work; Marmalade's own source files remain MIT.
See [`../NOTICE.md`](../NOTICE.md) for the full licensing posture and
the espeak corresponding-source pointer.

## Notices

- **Kokoro-82M** — Copyright (c) hexgrad. Apache License, Version 2.0.
  Model card: https://huggingface.co/hexgrad/Kokoro-82M. The shipped
  `model.onnx` is Marmalade's selectively int8-quantized build of the
  ONNX export from https://github.com/thewh1teagle/kokoro-onnx
  (`model-files` release), as packaged for multilingual use by
  k2-fsa/sherpa-onnx (`kokoro-multi-lang-v1_0`).
- **espeak-ng** — Copyright (c) eSpeak NG authors. GPL-3.0-or-later.
  Corresponding source: https://github.com/espeak-ng/espeak-ng (the
  exact version is pinned in [`../NOTICE.md`](../NOTICE.md)).
- **Open JTalk dictionary** — Nagoya Institute of Technology; NAIST
  Japanese Dictionary. Modified BSD. See [`open-jtalk.md`](open-jtalk.md).
- **pypinyin** — MIT. https://github.com/mozillazg/python-pinyin
- **CC-CEDICT** — `lexicon-zh.txt` is sherpa-onnx's pre-baked Mandarin
  Han→IPA table, generated offline from misaki + pypinyin. pypinyin's
  per-character pinyin data derives in part from **CC-CEDICT** (© MDBG,
  https://cc-cedict.org), licensed **CC-BY-SA-4.0**, so the bundled
  `lexicon-zh.txt` is attributed to CC-CEDICT/MDBG and treated as
  CC-BY-SA-4.0. CC-CEDICT is one of pypinyin's several character-pinyin
  sources (alongside Unihan and ZDIC); the values are transformed
  pinyin→IPA.
