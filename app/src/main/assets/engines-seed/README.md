# Baked default engine — seed assets

These files are baked into the APK so the default engine (Kitten Nano) works
instantly and offline on first run (see `EngineInstaller.seedFromAssets`).

**Only permissively-licensed files live here.** `kitten-direct-v0_8/kitten.onnx`
and `voices/*.bin` are **Apache-2.0**.

**Never commit `phonemizer/espeak-ng-data/` here.** That data is
**GPL-3.0-or-later** and is generated at build time from the pinned
`third_party/espeak-ng` submodule by `tools/espeak-hostgen` (see the
`generateEspeakData` task in `app/build.gradle.kts`). It lands in
`build/generated/espeakAssets/` (gitignored) and is merged into the APK
assets — exactly the way `libespeak-ng.so` is compiled-in but never committed.
Keeping espeak out of the repo is what keeps the source tree espeak-free and
MIT. See `NOTICE.md`.
