# Changelog

All notable changes to **marmalade-tts-android** will be documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Removed
- **Kitten Mini (v0.8)** is gone. Upstream KittenML only ever published
  the mini and micro 0.8 models as dynamic-int8 ONNX exports, and on
  listening they are audibly *worse* than the fp32 Kitten Nano they were
  advertised as a step up from — and slower to synthesize as well. Kitten
  Nano is now the only Kitten engine. If you had Mini installed, the app
  moves your aliases (and per-app routes) onto the matching Nano voice —
  the same eight speakers, same names — and reclaims the ~100 MB bundle
  on next launch.

### Added
- **Cloud voices (hosted TTS)**: a new bundle-less engine that
  synthesizes over any OpenAI-compatible `/audio/speech` provider with
  true streaming (first audio ≈ one network round trip). Providers are
  described as *data* (`cloud-providers.json`, bundled + remotely
  updatable from the engines repo) and Venice's model/voice lineup is
  discovered live from its `/models?type=tts` endpoint — so new
  providers, models, or voices arrive without an app update. Configure
  per-provider API keys from the Engines tab's "Cloud voices" card
  (Configure where local engines have Install); voices appear in the
  picker/aliases only once a key is set. Ships with Venice (Kokoro) and
  OpenAI (GPT-4o mini TTS, TTS-1) descriptors.
- Per-engine voice browsing: each engine's detail page has a "Browse
  voices" entry opening the picker scoped to that engine.

### Fixed
- **Primary alias routing**: TTS clients auto-fill the request's voice
  from the engine's advertised default, and that echo outranked the
  primary alias — a Kitten primary could never fire ("keeps speaking
  Bella Kokoro"). The advertised default now follows the primary alias,
  and an auto-filled echo of it no longer beats the alias; deliberate
  per-app voice picks still win.
- Editing the alias that's active on the Speak screen now also follows a
  **voice** change immediately (speed/effect/language already re-synced);
  previously the new voice only applied after deselecting and re-tapping
  the chip.

### Changed
- Bottom navigation is now **Speak / Aliases / Effects / Engines /
  Settings**: Engines is a tab again (with its old wrench icon back;
  Effects moved to a star), and Voices left the bottom bar — it's a
  detail screen reached from Speak or from an engine's detail page.
  Settings lost its "Manage engines" row accordingly.
- **Per-app voice routing moved onto the Aliases tab.** It used to be a
  separate screen buried in Settings → "Per-app voices"; now each alias
  card shows which apps speak with it ("Used by 2 apps"), and tapping
  that strip opens an app picker scoped to that alias. The primary
  alias's card finally states the fallback rule out loud — "…and
  everything you haven't routed". Ticking an app already routed
  elsewhere shows its current alias, so re-routing is deliberate.
  Existing routes are untouched (no schema change, no migration).
- **Alias rows no longer carry edit and delete icons.** Tapping the card
  opens the editor, which is now a bottom sheet, and Delete lives inside
  it behind the same confirmation as before.
- Brand typography per marmalade-design-scheme-v0: Manrope across the
  app, and the lowercase "marmalade tts" wordmark (Fredoka 600, orange
  in light / cream in dark) on the Speak screen top bar. Both fonts are
  bundled (OFL-1.1; see `LICENSES/fonts.md` and the in-app licenses
  screen).

## [1.0.0-beta.1] — 2026-06-07

First public beta — feature-complete and production-ready; held in beta
until validated across a range of devices, then promoted to `1.0.0`.
(Continues the `0.3.0-alpha` line; see below for earlier history.)

### Changed
- Engine names dropped the internal "Direct" label — production engines are
  now **Kokoro (v1.0)**, **Kitten Nano (v0.8)**, **Kitten Mini (v0.8)**, and
  **Pocket TTS**.
- **sherpa-onnx removed entirely** (engines, catalogs, and the vendored
  AAR). Every engine now runs directly on ONNX Runtime. DB migration
  v7→v8 cleans up the old engines' voice rows; the sherpa engines live
  on in the `experimental/executorch` branch.
- **espeak-ng is now compiled from source into the APK** (pinned
  submodule, tag 1.52.0) instead of being downloaded inside engine
  bundles — Google Play forbids runtime download of executable code.
  The distributed APK is therefore a GPL-3.0-or-later combined work;
  all Marmalade source files remain MIT. Engine bundles now carry only
  models and pronunciation data.
- In-app **Open-source licenses** screen (Settings → About) with
  per-component license texts bundled in the APK.
- The alias editor only offers engines you've actually installed.

### Added
- Onboarding asks for notification permission (Android 13+) so the
  speaking / keep-warm notices can appear.
- Debug benchmark screen shows a live device-load readout (RAM / zram / CPU
  / thermal) and releases each engine between runs for honest numbers.

### Fixed
- Changing the ONNX thread count now takes effect on the next Speak
  (previously a silent no-op until the app was force-stopped).
- Cleared a stale "Tap to install" banner that lingered after switching to
  an installed engine.
- Pocket TTS: faster autoregressive decode (LSD Euler steps 4 → 1) at no
  quality cost.

## [0.3.0-alpha.12] — 2026-06-04

### Fixed
- **Pocket TTS chunk-start "bitcrush" glitch** — the long-running intermittent
  distorted-word artifact. Root cause (confirmed by a frozen-latent decode sweep):
  the exported `mimi_decoder` ONNX graph is **not window-size-invariant** — every
  batched `run()` corrupts its own leading edge, and the corruption *length* scales
  with batch size + how cold the codec state is. The P-AI graduated decode window
  merely *relocated* the seam (frame 0 → frame 8). Replaced it with **P-AL segmented
  overlap-discard**: walk the chunk in capped 64-frame batches; for each, per-frame-
  decode the first 8 frames (clean + warms the state), snapshot/restore mimi state at
  the batch boundary, and keep only the batch *interior* — so no emitted frame ever
  sits on a corrupt leading edge. Per-frame decode quality at near-batched speed.
  (`PocketEngine.runMimiDecoder`, `PocketStateManager.snapshot/restore`.)

### Added
- **Pocket preprocessing:** newline runs now become sentence breaks (P-AM), so multi-
  line / paragraph input reads with sentence pauses instead of running on. `ex.` added
  to the abbreviation rule (→ "for example"), alongside existing i.e./e.g./etc. (both
  Android + CLI).
- **Dev-only decode-strategy experiment harness** (`DECODE_EXPERIMENT`, off by default):
  dumps latents + re-decodes them under multiple window policies for diagnosing/tuning
  the mimi decoder. Root-caused the glitch above; kept for ongoing optimization work.

## [Unreleased]

### Changed
- Engine install path is now single-archive download + extraction.
  v0.1.0/0.1.1 fetched 358 files individually from Hugging Face;
  v0.1.2 mirrored those 358 to a dedicated GitHub Releases CDN;
  v0.1.3 collapses that to one tar.bz2 download (the upstream Sherpa-
  ONNX tarball, byte-identical). 358 HTTPS round-trips → 1.
  KittenEspeakDataManifest.kt + the per-file sha256 list are gone.
- New dep: org.apache.commons:commons-compress for tar.bz2 extraction.

## [0.1.0] — 2026-05-21

First shipped build (debug-signed APK on GitHub Releases). System TTS
engine provider with the Kitten engine via opt-in install, emoji prosody
layer, share-sheet target, Quick Settings tile, voice aliases, three
effect presets (cave / robot / telephone), and a foreground media
playback service for long-form text.

### Added — Engine installer + onboarding flow

- `EngineCatalog` (`app/src/main/java/app/marmalade/tts/install/EngineCatalog.kt`)
  with the static `EngineDescriptor` / `EngineFile` data model. Kitten is
  the only entry in v0.1; the catalog points each file at its HuggingFace
  mirror URL (`huggingface.co/csukuangfj/...`) and lists per-file SHA-256
  for integrity verification.
- `KittenEspeakDataManifest` — file-by-file manifest of the espeak-ng
  phonemizer data (~355 entries). Auto-generated by
  `scripts/generate-kitten-manifest.py` from a locally extracted bundle.
  v0.1 lands with a seed list of the most-critical entries; the full
  enumeration is tracked in STUBS.md.
- `EngineInstaller` — streams each catalog file via `HttpURLConnection`
  into `${filesDir}/engines/<name>.tmp/`, verifies SHA-256 incrementally,
  atomically renames into `${filesDir}/engines/<name>/`. Exposes
  per-engine `Flow<InstallState>` for the UI to render progress.
  Uninstall calls `KittenEngine.release()` first to drop the JNI handle
  before deleting model files.
- `OnboardingScreen` + `OnboardingViewModel` — three-step first-launch
  wizard (Welcome → Engine pick → Install progress). Engines are
  pre-checked when `EngineDescriptor.isRecommended` is true. Mascot
  animations across steps. "Continue" on the final step flips
  `SettingsRepository.onboarded` to true and routes to the Speak screen.
- `EnginesScreen` + `EnginesViewModel` — Settings → Engines surface
  reachable via a dropdown on the Speak screen's top bar. Per-row
  install/uninstall/retry affordances with confirmation dialogs.
  Install dialog surfaces the GPL-3.0 disclosure summary.
- `AppRoot` now gates on `SettingsRepository.onboarded` before routing
  to the regular screen graph. Adds an `Engines` route alongside
  `Speak` / `Voices`.
- `SettingsRepository` gains an `onboarded` boolean key — the documented
  trigger for the first-launch wizard.
- "Model not installed yet" copy in `SpeakScreen` and `VoicePickerScreen`
  updated to point users at Settings → Engines.
- Manifest permission `INTERNET` added with an inline comment scoping
  it to engine downloads. New `PRIVACY.md` documents the policy;
  `SECURITY.md` cross-references it.
- Unit tests:
  - `EngineCatalogTest` — pins the catalog schema (sizes sum, HTTPS
    URLs, GPL disclosure present, empty-files-list rejected).
  - `EngineInstallerTest` — spins up a loopback HTTP server, exercises
    the happy path, SHA mismatch, mid-stream HTTP error, idempotent
    reinstall, uninstall, and the three verify() outcomes.
  - `OnboardingViewModelTest` — step transitions, selection toggling,
    install-error recovery, the `finish()` → `onboarded=true` write.

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
