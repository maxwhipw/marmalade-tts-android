# Changelog

All notable changes to **marmalade-tts-android** will be documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

Repo scaffolded. README, [SPEC.md](SPEC.md), and [ROADMAP.md](ROADMAP.md)
written. No code yet — first build will be v0.1.0 (see ROADMAP for scope).

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
