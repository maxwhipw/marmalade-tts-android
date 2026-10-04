# Changelog

All notable changes to **marmalade-tts-android** will be documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.1.0] - 2026-10-03

### Added
- **Reader mode**: share a web page to Marmalade and it extracts the
  article and reads it aloud — paragraph-by-paragraph playback with the
  current block highlighted and auto-scrolled, a player notification
  with transport controls that reopens the article at the position you
  left, a table of contents, a typography sheet (font size stepper,
  line width), a reading-speed sheet that starts at your alias's speed
  and changes speed live without restarting the paragraph, tap-a-paragraph
  to play from it, and junk-block filtering (site menus, related-article
  lists, infoboxes and citation markers are skipped) with an honest
  "short extraction" banner when a page resists extraction. The next
  block synthesizes while the current one plays, so paragraph gaps are
  short. Article extraction runs entirely on-device (Readability4J); the
  page is fetched once, and nothing about your reading is sent anywhere.
- **German, for real**: a native German voice engine (**Kokoro German
  v1.0**, downloadable). It speaks with Thorsten Müller's voice — the
  Thorsten-Voice dataset was recorded and donated by him specifically
  for open TTS — fine-tuned on the Kokoro architecture, quantized to
  int8 with the pitch/prosody predictors deliberately kept at full
  precision after they proved intolerant to quantization. Word-error
  rate matches the full-precision reference exactly, and two native
  speakers rated the candidate 4.5/5 and daily-usable (issue #1 — thank
  you!). Comes with an on-device German pronunciation pipeline: number,
  date and unit normalization, an override lexicon, and a German G2P
  that matches the reference phonemization.
- **German and Bulgarian partial support** on the existing Kokoro
  voices: pick the language explicitly and any Kokoro voice will speak
  de or bg — with a noticeable accent, which community reviewers called
  charming and tolerable. Honest tiering: automatic language detection
  and the system-TTS language claims still only advertise the languages
  with native support, so nothing routes to the accented tier unless
  you ask for it.
- **A new engine: VITS Marmalade** — Marmalade's own on-device runtime
  for Piper-class VITS voices, organized as per-language downloadable
  voice packs. It debuts with one English voice, **Jenny (Dioco)**: a
  ~58 MB pack of a voice Jenny recorded herself expressly for TTS,
  independently trained from scratch with permissively licensed weights
  and data (her attribution ships in the pack and the in-app licenses
  screen). It's light enough to run well on very slow devices. More
  language packs are already built and will ship as they pass community
  review.
- **Language sample pages** for community review — recorded samples of
  candidate voices (German, Ukrainian, Russian, Icelandic, Swedish,
  Kazakh, Norwegian) are published on the project's GitHub Pages so
  native speakers can grade them before anything ships. Ukrainian is
  currently held after exactly that feedback: the reviewed voices
  aren't daily-usable yet, so they stay out until they are.
- **Language-aware voices**: when your default voice doesn't speak an
  article's or a shared text's language, Marmalade reads it with an
  installed on-device voice that does. Cloud voices are never picked
  automatically.
- The **Sponsors** row in Settings now ships in both flavors.

### Changed
- **Speed is now a true time-stretch on every on-device engine.** Kokoro,
  Kitten and Pocket used to feed your speed into the model (Pocket dropped
  it entirely — issue #7); model-side speed audibly degraded articulation
  and silently saturated around 1.85–2.2× no matter what you asked for.
  Every on-device engine now renders at its natural pace and the requested
  speed is applied as a tempo stage afterwards, so 2.0× actually plays in
  half the time — on the Speak screen, the reader, and system TTS
  (screen readers included). Sentence pauses scale with the speed too.
  Cloud voices still pass your speed to the provider, which renders it.
- **Speed-aware buffering**: at high speeds the app now pre-buffers
  enough synthesized audio to avoid mid-text stalls where playback
  outruns synthesis. The alias editor and the reader's speed sheet warn
  you when a speed is likely to stutter, based on how fast the chosen
  engine actually runs on your device.
- **Kokoro starts speaking sooner.** Text is chunked by how much work
  each piece is for the model rather than by character count; long
  Chinese and Japanese sentences are cut at clause marks and played as
  each piece is ready; and when nothing is playing, a long first sentence
  starts with a short opening piece, cut at its first comma or similar
  break. A long Chinese run-on sentence that took about 30 seconds to
  start on a Pixel 8a now starts in about two.
- The themed (single-colour) app icon on Android 13+ is now a plain jar
  silhouette with the sound waves, without the cut-out face.
- The Google Play build no longer shows developer options, or voices of
  engines that are only offered on F-Droid.

### Fixed
- **Offline / hardened-Android crashes** (issue #12): opening the Cloud
  voices screen with the Network permission revoked (GrapheneOS's
  per-app toggle) crashed the app — network errors now surface
  gracefully. Sharing a URL while offline no longer crashes the reader,
  and a share speak request that the system refuses to start now
  shows an error toast instead of failing silently.
- **Word endings are no longer swallowed at high speeds** (issue #8):
  Kokoro and Kitten trimmed a fixed slice off each chunk's tail, which
  ate final consonants at 2×. The trim is now amplitude-aware — it only
  removes actual silence.
- **Text preprocessing** got a sweep of pronunciation fixes:
  thousands-separated numbers and currency amounts verbalize correctly
  ("$1,234.56" no longer reads digit-by-digit), "¥" amounts say "yen"
  (not "yens"), over-long decimals no longer half-match, scene-break
  symbol lines (asterisks, dinkuses, ■) are treated as breaks instead
  of being read out, superscript footnote markers are dropped, and
  hard-wrapped text (emails, plain-text notes) is re-joined so
  line breaks mid-sentence don't become sentence breaks. Parenthetical
  asides get a natural spoken pause, ~30 words espeak mispronounces
  ("yeah", "gauge", …) are respelled, and common heteronyms ("lead",
  "tear", …) pick the right reading from context — ported from the
  Marmalade TTS CLI so both stay in lockstep.
- Kokoro's espeak-backed languages no longer read stray language-switch
  markers aloud when a foreign word appears mid-sentence.
- The reader recognizes sentence endings before CJK closing brackets.
- The email preprocessing rule was accidentally quadratic — very long
  texts with many @-signs preprocessed slowly. Now linear.
- A cloud voice's offline fallback now actually takes over when the
  provider can't be reached. Before, the fallback was never used and you
  got an error instead of speech.
- Very long sentences with little or no punctuation are no longer cut
  off partway. They're split at a natural break instead.
- **Stop, Next and tapping a paragraph respond quickly**: cancelled
  speech stops rendering at once instead of finishing in the background,
  and Next plays the paragraph that's already prepared.
- A paused read is no longer resumed by another app's notification
  sound, and media-button Stop/Pause work even before the first audio.
  Android 13+ media controls now show a Stop button.
- Speeds above 2× no longer crash the speed stage, and the speed stage
  no longer clips the last few milliseconds of speech.
- **Cloud provider safety**: an updated provider list can no longer move
  a built-in provider's address to a different site. If that ever
  happens the app shows "Update the app to keep using it" instead of
  sending your API key to the new address. A key saved for a provider
  that comes only from the downloaded list stays tied to the address it
  was saved for.

### Removed
- The Quick Settings "Speak clipboard" tile, for now. Share text to
  Marmalade or use the system "read selection" action instead.

## [1.0.0] - 2026-08-10

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
