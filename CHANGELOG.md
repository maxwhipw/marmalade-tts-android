# Changelog

All notable changes to **marmalade-tts-android** will be documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

Repo scaffolded. README, [SPEC.md](SPEC.md), and [ROADMAP.md](ROADMAP.md)
written. No code yet — first build will be v0.1.0 (see ROADMAP for scope).

### Added — Kitten TTS engine + system-TTS wiring

- `KittenEngine` (`app/src/main/java/app/marmalade/tts/engine/KittenEngine.kt`)
  wrapping Sherpa-ONNX's `OfflineTts` in Kitten mode. 24 kHz mono PCM
  output, 8 speakers, lazy load, idempotent `ensureModelLoaded()`,
  release-able. Verified against the vendored AAR's
  `OfflineTtsKittenModelConfig` / `OfflineTtsModelConfig` API surface.
- `MarmaladeTtsService` now feeds real PCM through the
  `SynthesisCallback` instead of returning silence. `@AndroidEntryPoint`,
  Hilt-injected `KittenEngine` + `VoiceMetaDao`. Voice negotiation,
  `LANG_COUNTRY_AVAILABLE` reporting for en-US, chunked
  `audioAvailable` writes capped at `callback.maxBufferSize`.
- `VoiceMeta` Room entity expanded: `id`, `engine`, `displayName`,
  `languageCode`, `sampleRate`, `gender`, `isInstalled`. DB bumped to
  v2 with `fallbackToDestructiveMigration()` (v1 had no real data).
- `VoiceMetaDao` with Flow-returning `getAll()` / `getByEngine()` and
  suspend `findById()` / `upsert()` / `upsertAll()`.
- `KittenVoiceCatalog` seeds the 8 Kitten voices (Bella, Jasper, Luna,
  Bruno, Rosie, Hugo, Kiki, Leo, all en-US, 24 kHz) into the DB on
  first launch via a `RoomDatabase.Callback.onCreate` hook.
- `AppModule` updated to provide `VoiceMetaDao` and wire the seed
  callback through a `Provider<VoiceMetaDao>` to break the cyclic dep.
- Unit tests for `KittenVoiceCatalog` (8 voices, IDs, default,
  install-state) and PCM16 little-endian encoding (endianness bugs
  here = screech instead of speech).
- Removed `engine/SherpaOnnxStub.kt` — `KittenEngine` is now the real
  compile-time proof that the AAR is wired correctly.

### Architecture — engine-as-plugin (engines install on user opt-in)

Engine model files are **not bundled in the APK**. They're downloaded
at runtime by an `EngineInstaller` (separate component, scaffolded
next) into `${filesDir}/engines/<engine>/` when the user opts in via
onboarding or Settings → Engines. The default install ships only the
CLI wrapper code + UI + Sherpa-ONNX AAR — no neural models, no
phonemizer data. This matches the CLI's `marmalade-tts install
<engine>` pattern.

Reasons for the pivot:
- **APK size.** Bundling Kitten alone would push the APK from ~115 MB
  to ~140 MB. The CLI's full engine stack would not fit at all.
- **License hygiene.** The Sherpa-ONNX AAR statically links espeak-ng
  (GPL-3.0). Shipping it by default forces a GPL-licensed APK. With
  opt-in install, the GPL'd component only lands on devices whose
  users have explicitly accepted it, and the default install posture
  stays MIT-clean. Trade-off: users must accept a one-line disclosure
  during engine install that the engine includes GPL components.
- **User choice.** Mobile users have widely varying tolerance for app
  sizes and network use. Letting them choose which engines to install
  is friendlier than forcing a 140 MB+ download for everyone.

`KittenEngine.ensureModelLoaded()` throws `EngineNotInstalledException`
(a typed subclass of `UnsupportedOperationException`) when the user
hasn't installed the engine. The UI catches this and routes to the
install flow.

The Kitten model bundle (`kitten-nano-en-v0_1-fp16` from
[sherpa-onnx tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2),
Apache 2.0, NOTICE at `LICENSES/kitten-tts.md`) is the first engine to
be wired through the installer.

### Added — Initial Android project scaffold
- Gradle 8.11.1 wrapper + AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01
- Single `app` module, namespace `app.marmalade.tts`, minSdk 28, targetSdk 35
- `MarmaladeTtsApplication` with `@HiltAndroidApp`; `MainActivity` with `@AndroidEntryPoint`
- Placeholder Compose screen (mascot + app name + version)
- Marmalade-orange Material 3 theme with Material You dynamic colors (matches marmalade-android)
- Hilt DI wired; `AppModule` provides Room DB and DataStore
- `MarmaladeDb` Room database v1 (no entities — to be added with first migration)
- `marmalade_settings` Preferences DataStore
- `MarmaladeSynthService` foreground service skeleton (`foregroundServiceType="mediaPlayback"`)
- `MarmaladeTtsService` system TTS engine skeleton (registered, produces silence)
- `xml/tts_engine.xml` TTS engine descriptor
- Sherpa-ONNX AAR vendored in `app/libs/`, `OfflineTtsConfig` import verified
- 9 mascot vector drawables installed in `res/drawable/`
- Adaptive launcher icon (foreground: `mascot_happy`, background: marmalade orange)
- Manifest permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `POST_NOTIFICATIONS`, `RECORD_AUDIO` (declared; no INTERNET — on-device v0.1)
- Unit test scaffold (`ApplicationTest` passes; `androidTest/` directory present)
