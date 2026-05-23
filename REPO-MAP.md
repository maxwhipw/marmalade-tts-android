# marmalade-tts-android — repo map

> **Read this first when investigating or fixing this codebase.** This
> file is the orientation pass that lets you skip 10+ Grep/Glob calls.
> Last updated against v0.1.17 (commit e60ab4d). Specific file:line
> refs may drift; the *shape* of the map is stable.

## In one sentence

Offline TTS engine for Android. Compose UI on top of vendored
Sherpa-ONNX inference, ships engine models as downloadable plugins,
registers itself as a system TTS service so any app can route through
it. Sister project to `/home/max/coding/marmalade-tts-cli` (the
desktop CLI ancestor) and `/home/max/coding/marmalade-android` (the
chat app whose visual identity was ported here).

## Build & run

```bash
cd /home/max/coding/marmalade-tts-android
./gradlew assembleDebug       # build only
./gradlew installDebug        # build + install on connected device
./gradlew test                # unit tests (Robolectric + JVM)
./gradlew :app:lint           # lint
```

`applicationIdSuffix = ".debug"` so the installed package is
`app.marmalade.tts.debug`. Debug-signed releases only through v0.1.x.

## Module structure

Single Gradle module `:app`. No multi-module split (yet).

```
app/src/main/
  java/app/marmalade/tts/
    audio/            Synthesis pipeline + audio effects
    data/             Room DB + DataStore Prefs + voice catalogs
    di/               Hilt DI graph (single AppModule)
    engine/           SherpaEngine base + Kitten/Kokoro subclasses
    install/          EngineCatalog + EngineInstaller (HTTP + tar.bz2)
    preprocessing/    Text rules + emoji prosody + ProsodyApplier
    service/          TTS service, foreground synth, quick tile, helpers
    ui/               Compose screens, ViewModels, navigation, theme
  res/
    drawable/         9 mascot vectors (3 used: happy, speaking, focused)
    values/           strings.xml, themes.xml, colors.xml
    xml/              tts_engine.xml (TTS engine descriptor)
  assets/             (currently empty)
  AndroidManifest.xml
```

## Architecture — one liner per layer

- **UI**: Jetpack Compose + Material 3, bottom nav, single
  `MainActivity` host, `AppRoot` runs the nav graph.
- **State**: ViewModels expose `StateFlow`s; UI collects via
  `collectAsStateWithLifecycle`.
- **DI**: Hilt. Single `AppModule` provides DAOs, settings, engines,
  installer, router.
- **Data**: Room (v4 schema, 3 entities) for voice/alias/per-app
  mappings; DataStore Preferences for user settings (theme, primary
  alias, per-engine preprocessing rule toggles).
- **Engines**: Vendored Sherpa-ONNX AAR (`libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar`).
  Models download separately per engine (Kokoro recommended, Kitten
  optional).

## Key files by concern

When investigating **{concern}**, start at **{files}**:

### Synthesis pipeline (input → audio out)
- `audio/SynthesisPipeline.kt` — *canonical* pipeline since v0.1.17;
  shared by all three call sites
- `audio/Synthesizer.kt` — in-app Speak path
- `service/MarmaladeTtsService.kt` — system TTS service (external
  apps call here); has `runBlocking` hot-path caches (v0.1.16) for
  voice→engine and rule lookup
- `service/MarmaladeSynthService.kt` — foreground media-playback
  service for long-form playback + transport controls
- Order of the canonical chain: emoji-detect → preprocess →
  strip-emoji → engine synth → ProsodyApplier → EffectChain

### Engines
- `engine/SherpaEngine.kt` — abstract base (loadLock, ensureModelLoaded,
  synthesize, release, floatToPcm16, sampleRate)
- `engine/KittenEngine.kt`, `engine/KokoroEngine.kt` — subclasses,
  ~100 lines each; only override `buildModelConfig`, `speakerIdFor`,
  `engineName`, `modelFileName`, `defaultSampleRate`
- `data/KittenVoiceCatalog.kt`, `data/KokoroVoiceCatalog.kt` — static
  voice metadata (seeded into Room at app startup)

### Install / download
- `install/EngineCatalog.kt` — descriptors for installable engines
  (URL, sha256, archiveRoot, label, isRecommended). Single tarball
  per engine (`.tar.bz2`).
- `install/EngineInstaller.kt` — HTTP download, sha256 verify,
  tar.bz2 extract via Apache `commons-compress`, atomic rename. Per-
  engine `StateFlow<InstallState>` (Idle / Downloading / Extracting /
  Installed / Failed).

### Preprocessing
- `preprocessing/Preprocessor.kt` — applies the rule set
- `preprocessing/PreprocessingRules.kt` — 15 rules ported from CLI
  (numbers, abbreviations, symbols, etc.)
- `preprocessing/EngineProfiles.kt` — default rule sets per engine
- `preprocessing/EmojiProsody.kt` — detects emoji → emotion mapping
- `preprocessing/ProsodyApplier.kt` — applies emotion to engine
  params (speed, energy)

### Persistence
- `data/db/MarmaladeDb.kt` — RoomDatabase, schema v4. Migrations
  v1→v4 are CREATE TABLE-only (no data loss paths).
- `data/db/VoiceMeta.kt` + DAO — installed voices (engine, voice id,
  display name, gender, language, isInstalled flag)
- `data/db/VoiceAlias.kt` + DAO — user "personas" (name + engine +
  voiceId + speed + effectPreset)
- `data/db/AppAliasMapping.kt` + DAO — per-app routing (packageName
  → aliasName)
- `data/SettingsRepository.kt` — DataStore Prefs (theme preset, theme
  mode, primary alias name, keep-engine-loaded, per-engine preprocessing
  enables). `keepEngineLoaded` is **stored but unused** as of v0.1.16
  — UI toggle was removed; the engines don't honour it yet.

### TTS service surface (external apps)
- `service/MarmaladeTtsService.kt` — `TextToSpeechService` subclass;
  handles `onSynthesizeText`, `onIsLanguageAvailable`, `onLoadLanguage`,
  `onGetLanguage`, `onLoadVoice`
- `service/CheckVoiceDataActivity.kt` — Android invokes this to
  enumerate installed voices (BCP-47 → ISO-639-3 conversion)
- `service/GetSampleTextActivity.kt` — returns "Hello, this is
  Marmalade speaking." for the system picker's Play button
- `service/TtsRouter.kt` — `@Singleton` that resolves
  `(callerPackage) → VoiceAlias?` via: per-app mapping → primary
  alias → engine default
- `AndroidManifest.xml` — `TTS_SERVICE` intent-filter declares
  `DEFAULT` category, `CHECK_TTS_DATA` + `GET_SAMPLE_TEXT` + `CONFIGURE_ENGINE`
  filters are also wired. `xml/tts_engine.xml` declares
  `settingsActivity` pointing at `MainActivity`.

### Other entry points
- `service/MarmaladeSynthService.kt` — foreground service for long-
  form Speak with media-session/lock-screen transport
- `ui/intent/ShareIntentActivity.kt` — share-sheet target +
  `PROCESS_TEXT` selection action; dispatches to MarmaladeSynthService
- `service/SpeakClipboardTileService.kt` — Quick Settings tile that
  speaks the current clipboard
- `service/SpeakDispatcher.kt` — wraps the foreground-service start
  intent for in-app and external callers

### Navigation
- `ui/AppRoot.kt` — Scaffold + NavigationBar (4 tabs) + NavHost.
  Tabs: **Speak / Voices / Engines / Settings**. Detail routes:
  Aliases, EngineDetail/{name}, AppMappings. Bottom bar hides on
  detail routes (`isDetailRoute()` predicate).
- `ui/AppRootViewModel.kt` — collects theme preset + mode + onboarded
  flag from `SettingsRepository`; drives `MainActivity` decisions.
- `ui/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` —
  5-step flow: Welcome → EnginePick → Installing → CreateAlias →
  SystemDefault. `finish()` flips the onboarded flag.
- `MainActivity.kt` — decides between `OnboardingScreen` and `AppRoot`
  based on `onboarded` flag; sets theme via `MarmaladeTtsTheme`.

### DI
- `di/AppModule.kt` — single Hilt module. Provides:
  - `MarmaladeDb` + 3 DAOs
  - `SettingsRepository`
  - `EngineFilesDir` (typealias `() -> File`)
  - `KittenEngine`, `KokoroEngine` (both `@Singleton open`)
  - `NativeEngineHandle` (`() -> Unit` that releases both)
  - `EngineInstaller`
  - `TtsRouter`

### Theme
- `ui/theme/Theme.kt` — `MarmaladeTtsTheme(darkTheme, themePreset, content)`
  + `resolveThemeIsDark(mode, isSystemDark)` helper
- `ui/theme/Color.kt` — palette + `ThemePreset` enum
  (`SYSTEM / MARMALADE / MIDNIGHT / FOREST / BERRY`); MARMALADE is
  the default since v0.1.10

## Data flow (mermaid-free, just text)

**External app calls TTS** → Android binds `MarmaladeTtsService` →
`onSynthesizeText` resolves voice via cache (or `runBlocking` DAO
miss) → `TtsRouter.resolveAlias(callerPackage)` picks the alias →
`runSynthesisPipeline(text, engine, voiceId, speed, rules, preset, synthLambda)` →
engine synth callback hits `KittenEngine.synthesize()` or
`KokoroEngine.synthesize()` → PCM16 written back to the framework
callback.

**In-app Speak (SpeakScreen)** → `SpeakViewModel.speak(text)` →
`Synthesizer.speak()` → `runSynthesisPipeline(...)` → result handed to
`MarmaladeSynthService` for foreground playback.

**Engine install** → user taps card → `EnginesViewModel.install(name)` →
`EngineInstaller.install(name, onProgress)` → HTTP GET → sha256
verify → tar.bz2 extract → atomic rename `scratch/ → engines/<name>/` →
StateFlow emits `Installed` → `VoiceMetaDao` rows for that engine
flip their `isInstalled` flag.

## Conventions

- **Package naming**: lowercase, separated by concern (`audio.`,
  `engine.`, `service.`). Never use `util.` as a bucket.
- **Compose**: every screen has its own `*Screen.kt` + `*ViewModel.kt`.
  Top-level `@Composable fun XScreen(onNavigate..., viewModel: VM = hiltViewModel())`.
- **Nested Scaffold**: `AppRoot`'s outer Scaffold owns status-bar
  insets. **Every per-screen Scaffold must use
  `contentWindowInsets = WindowInsets(0)` AND pass
  `windowInsets = WindowInsets(0)` to its TopAppBar.** Otherwise the
  bar double-pads. Imported bug from sister chat app, fixed in v0.1.14.
- **Hilt + Activities**: Hilt rejects bare `Activity`. Use
  `ComponentActivity` for activities that need `@AndroidEntryPoint`.
  See `CheckVoiceDataActivity.kt`.
- **Engine name constants**: literal `"kitten"` / `"kokoro"` are
  duplicated across the codebase (catalogs, profiles, when-blocks).
  TODO: consolidate into an enum. Code reviewer flagged this as a
  MEDIUM finding.
- **`@string/app_name` is "Marmalade TTS"** (proper case since
  v0.1.17). Package names and applicationId stay lowercase
  (`app.marmalade.tts`).

## Known quirks / recent gotchas

- **TTS engine registration requires `DEFAULT` category** on the
  `TTS_SERVICE` intent-filter AND `CHECK_TTS_DATA` activity AND a
  populated `tts_engine.xml` with `settingsActivity`. All three are
  needed for Android to list the engine in Settings. Fixed in v0.1.15.
- **runBlocking on TTS worker thread**: `onSynthesizeText` runs on
  the framework worker, watchdog ~10s. v0.1.16 added
  `ConcurrentHashMap` caches at `onCreate`/`onLoadLanguage` to keep
  the hot path lock-free; a single defensive runBlocking fallback
  remains for cache misses.
- **Hilt + saved-state-registry-owner**: composables outside
  NavBackStackEntry can't use `hiltViewModel()`. Use `viewModel()` at
  the AppRoot + OnboardingScreen entry points.
- **Effect framework status**: `EffectChain.kt` ported from CLI; the
  Android port's runtime behaviour was flagged in v0.1.18
  investigation — verify it's not a no-op before assuming it works.
- **Voice list filtering**: `VoiceMetaDao.getAll()` returns every
  seeded voice regardless of whether the engine is installed.
  Filtering by installed state is the screen's job. v0.1.18 fix
  pending.

## Where the docs live

- `CHANGELOG.md` — per-version changes (kept current; check before
  assuming a "recent change" is on main)
- `SPEC.md` — original v0.1 spec
- `ROADMAP.md` — planned v0.2+ work
- `STUBS.md` — known deferred items (some closed in v0.1.16's "Keep
  engine loaded" toggle removal)
- `PRIVACY.md`, `SECURITY.md` — what the app does and doesn't access
- `LICENSES/kitten-tts.md`, `LICENSES/kokoro-tts.md` — third-party
  model licenses (Apache-2.0 KittenTTS, Apache-2.0 Kokoro; espeak-ng
  data inside engine archives is GPL-3.0 — engine-as-plugin keeps the
  default APK MIT-clean)
- `.review/*.md` — historical review artifacts from multi-agent
  audits. Useful for context on past decisions; don't treat as
  current truth.

## When investigating something not in this map

- `Grep` for the symbol/class first
- `Glob` for filenames second
- If you spawn a subagent, **point it at this file in the briefing**
  so it doesn't repeat the orientation work
- Update this file when you discover something a future agent should
  know (new architectural choices, new gotchas, new conventions)
