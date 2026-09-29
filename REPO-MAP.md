# marmalade-tts-android — repo map

> **Read this first when investigating or fixing this codebase.** This
> file is the orientation pass that lets you skip 10+ Grep/Glob calls.
> Last updated against v0.1.17 (commit e60ab4d). Specific file:line
> refs may drift; the *shape* of the map is stable.
>
> - Entries touched by the v1.1.0 review pass (2026-09-22, commits
>   `fc46ffa`..) are current: reader routing, synth service, install
>   failures, chunking, voice filtering, alias fallback.
> - [ ] **TODO (2026-08-02): stale refresh needed** — still documents the
>   sherpa-onnx engines (`SherpaEngine`, `KittenEngine`, `KokoroEngine`,
>   `KittenVoiceCatalog`) removed in June, and Kitten Mini removed in
>   `ec79437`. Trust the code over this map until refreshed.

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
    service/          TTS service, foreground synth, helpers
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
  optional). Since v0.1.19 Kokoro is **multi-language** — 53 voices
  across American + British English, Spanish, French, Hindi, Italian,
  Japanese, Brazilian Portuguese, and Mandarin. Voice/language
  orthogonality holds at the synthesizer (non-English voice speaks
  English with the voice's accent).

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
  service for long-form playback + transport controls. Only
  `ACTION_SPEAK` (the one action sent via `startForegroundService`)
  promotes it to foreground; stops use `stopSelfResult(lastStartId)` so a
  newer SPEAK in flight still reaches its `startForeground`; a partial
  wake lock is held while a request plays or waits and isn't paused.
  A new SPEAK queues behind playing work but **replaces work the user
  paused** (`replacesPausedWork`; an audio-focus pause — a call, a
  notification duck — is not the user's, so it queues); the reader marks its follow-on blocks
  `EXTRA_CONTINUATION` so they never do, and reads the `stopped` flag on
  `PreviewCompletions.Completion` to fall back to Idle when replaced
- Order of the canonical chain: emoji-detect → preprocess →
  strip-emoji → engine synth → ProsodyApplier → EffectChain (in
  MarmaladeSynthService's streaming path the EffectChain step runs on the
  playback side — see "effect chain at PLAYBACK" under Known quirks)

### Engines
- `engine/SherpaEngine.kt` — abstract base (loadLock, ensureModelLoaded,
  synthesize, release, floatToPcm16, sampleRate)
- `engine/KittenEngine.kt`, `engine/KokoroEngine.kt` — subclasses,
  ~100 lines each; only override `buildModelConfig`, `speakerIdFor`,
  `engineName`, `modelFileName`, `defaultSampleRate`
- `data/KittenVoiceCatalog.kt`, `data/KokoroVoiceCatalog.kt` — static
  voice metadata (seeded into Room at app startup).
  `KokoroVoiceCatalog.languageFor(voiceKey)` derives the natural BCP-47
  language code from the upstream voice-key prefix (a=en-US, b=en-GB,
  e=es-ES, f=fr-FR, h=hi-IN, i=it-IT, j=ja-JP, p=pt-BR, z=zh-CN).

- **Kokoro chunk sizing is in model tokens** (`TextChunker.planByTokens`,
  budget `TOKEN_BUDGET` in `KokoroDirectEngine`, inherited by the German
  engine): sentences split at `.!?;:` + space, newlines and 。！？；：; tiny
  sentences merge up to 90 tokens (never past 200, never the request's
  first); a sentence over 270 is cut at `,;:`/—/、，；： to ≤ 200, then
  word level (spaces; Japanese kana→kanji/katakana steps), then hard.
  Every piece of a cut sentence uses the WHOLE sentence's style row
  (`TokenChunk.rowTokens` → `kokoroStyleRow`) — Kokoro picks its row by
  token count, and a short piece's own row sounds like a sentence end.
  **Short first piece** (Max's blind A/B, `docs/release/first-piece-lab.html`):
  when the audio Kokoro already emitted (`engine/PlaybackHorizon`, an
  in-engine estimate — the service passes no "cold" flag) can't cover
  rendering the first sentence, that sentence alone is cut at a clause
  mark (never a word gap) into a 15–55-token first piece sized for
  ~1.1 s of render from the last measured ms/token, then pieces growing
  by `0.9 / (RTF × playbackRate)` (`kokoroStreamBudget`). No seam trim.
  One `D/StreamPerf: kokoro plan …` line per request (cutFirst, aheadMs,
  firstPiece, growth, msPerToken). Other engines still
  chunk by characters (`TextChunker.chunk` / `clauseChunks`).
- **Over-cap chunks**: `audio/TextChunker.splitToFit(text, fits)`
  re-splits a chunk that still overflows an engine's cap (clause
  punctuation → whitespace → hard cut, nothing dropped). Kokoro and
  Kitten call it with a "phonemizes to ≤ cap tokens" predicate
  (`tokenPieces` / `ipaPieces`) and render + emit each piece as its own
  stream chunk, never joined first;
  `TtsEngine.maxInputChars` is the per-engine character cap.

### Cloud API engine (hosted voices)
- `engine/api/CloudApiEngine.kt` — OpenAI-compatible `/audio/speech`
  synthesis with true streaming (WAV header parse + chunked PCM emit).
  Text over `maxInputChars` (1000) is sent as several requests:
  sentence-packed chunks, `splitToFit` for a single oversize sentence.
  One engine for all providers; the voice id carries provider + model:
  `cloud-api-v1:<provider>:<model>:<voice>`.
- `data/cloud/CloudProviders.kt` — provider descriptors as data
  (parse of `cloud-providers.json` + of live `/models?type=tts`).
- `data/cloud/CloudProviderStore.kt` — merges bundled asset, remotely
  fetched descriptor overrides (engines repo), and per-provider live
  voice discovery; owns the engine's `voice_meta` rows via
  `VoiceMetaDao.replaceEngine` (no static catalog / CATALOG_VERSION
  involvement). Caches under `filesDir/cloud/`. The remote copy may add
  providers and change models, but may only move a **built-in**
  provider's `baseUrl` (where saved API keys go) within its own site —
  `CloudProviders.pinBuiltInSites`; an off-site move keeps the bundled
  URL and sets `CloudProvider.movedOffSite`, which the Cloud screen shows
  as "update the app" for keyed providers. A provider only in the remote
  list is held the same way once the user saves a key for it:
  `setCloudApiKey` records the `baseUrl` the key was saved for
  (`SettingsRepository.cloudApiKeyBaseUrls`) and `CloudProviders.pinKeyedSites`
  refuses an off-site move from it on every load. Keys saved before that
  (≤ 1.1.0) get their provider's current URL on first load (trust on first
  use, `CloudProviderStore.pinKeyed`). The site rule is a PSL-free
  approximation (limits documented on `registrableDomain`). Any change to
  the engines repo's `cloud-providers.json` needs Max's manual review;
  agents never merge it.
- "Installed" = any provider key in `SettingsRepository.cloudApiKeys`
  (`cloud_api_key_<provider>` prefs; legacy `cloud_api_key` reads as
  Venice). Configure UI: Engines tab → Cloud voices card →
  `ui/screen/CloudApiScreen.kt`.

### Install / download
- `install/EngineCatalog.kt` — descriptors for installable engines
  (URL, sha256, archiveRoot, label, isRecommended). Single tarball
  per engine (`.tar.bz2`).
- `install/EngineInstaller.kt` — HTTP download, sha256 verify,
  tar.bz2 extract via Apache `commons-compress`, atomic rename. Per-
  engine `StateFlow<InstallState>` (Idle / Downloading / Extracting /
  Installed / Failed).
  - Refuses up front (`InstallFailure.NO_SPACE`) when free space can't
    hold the remaining download + unpacked size + margin; HTTP 404/410
    maps to `InstallFailure.NOT_AVAILABLE`. `InstallState.Failed.failure`
    carries the kind; `ui/screen/EnginesScreen.installFailureText(state)`
    is the one localized rendering (engine cards, pack rows, onboarding).
  - A failed download keeps its partial archive for resume
    (`Failed.partialDownloadBytes`); "Remove download" →
    `discardDownload` / `discardPackDownload` deletes it. Stale `*.tmp`
    scratch dirs/archives are swept when the installer is constructed.

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
- **Catalog versioning**: `MarmaladeTtsApplication.CATALOG_VERSION` (int,
  bumped on every catalog content change) gates a one-shot `upsertAll`
  re-seed on cold start; the last-applied version lives in DataStore via
  `SettingsRepository.catalogVersion`. Pre-v0.1.19 the seed used a
  "rows-absent" gate which left existing installs stranded when a
  catalog *expanded* (the bug that hid 52 of the 53 multi-lang Kokoro
  voices from upgraders). Bump the constant when you change any
  `*VoiceCatalog`.
- `data/db/VoiceMeta.kt` + DAO — installed voices (engine, voice id,
  display name, gender, language, isInstalled flag)
- `data/db/VoiceAlias.kt` + DAO — user "personas" (name + engine +
  voiceId + speed + effectPreset). `fallbackAliasId` holds another
  alias's **id** (not a FK; the editor writes ids since v1.1.0)
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
  enumerate installed voices (BCP-47 → ISO-639-3 conversion). Filters
  through `MarmaladeTtsService.advertisableVoices`, the same rule the
  service's voice list uses (VITS per pack + released only)
- `service/GetSampleTextActivity.kt` — returns "Hello, this is
  Marmalade speaking." for the system picker's Play button
- `service/TtsRouter.kt` — `@Singleton` that resolves
  `(callerPackage) → VoiceAlias?` via: per-app mapping → primary
  alias → engine default. `fallbackVoiceIdFor(alias)` resolves a cloud
  alias's offline fallback by id, then by name (legacy rows stored a name)
- `AndroidManifest.xml` — `TTS_SERVICE` intent-filter declares
  `DEFAULT` category, `CHECK_TTS_DATA` + `GET_SAMPLE_TEXT` + `CONFIGURE_ENGINE`
  filters are also wired. `xml/tts_engine.xml` declares
  `settingsActivity` pointing at `MainActivity`.

### Other entry points
- `service/MarmaladeSynthService.kt` — foreground service for long-
  form Speak with media-session/lock-screen transport
- `ui/intent/ShareIntentActivity.kt` — share-sheet target +
  `PROCESS_TEXT` selection action; dispatches to MarmaladeSynthService,
  or hands a shared link to reader mode
- `ui/intent/ShareRouting.kt` — the pure "reader or speak?" decision the
  share trampoline makes (ACTION_SEND that is essentially just a link —
  `reader/SharedUrlDetector.findLinkShare`: a URL plus at most a short
  one-line title — → reader; everything else, prose containing a link and
  PROCESS_TEXT included → speak). Unit-tested on the JVM.
- `service/SpeakDispatcher.kt` — wraps the foreground-service start
  intent for shared text (share sheet, PROCESS_TEXT, the reader's "read
  text as-is"); marks it `EXTRA_SHARED`, so MarmaladeSynthService's
  `resolveRequest` detects the whole text's language once and, if the
  primary alias's voice doesn't speak it, uses the same choice as the
  reader (`LanguageVoiceSelector`: other on-device alias → installed on-device voice
  → primary), logged as `D/ShareVoice`. Explicit-voice callers (Speak
  screen, previews) and the reader are untouched; system TTS
  (`MarmaladeTtsService`) keeps its own per-utterance rerouting

### Navigation
- `ui/AppRoot.kt` — Scaffold + NavigationBar (5 tabs) + NavHost.
  Tabs: **Speak / Aliases / Effects / Engines / Settings** (Aliases
  promoted to a tab in v0.1.18; Voices left the bottom bar and Engines
  rejoined it in v0.3.0-alpha.12 — Voices is now a detail route
  `voices?engine={e}`, reached from Speak or engine-scoped from
  EngineDetailScreen). Other detail routes: EngineDetail/{name},
  CloudApi, Licenses, EffectEditor, Reader. Bottom bar hides on detail
  routes (`showBottomBar` predicate at the top of AppRoot).
- **Reader mode** (`Routes.ReaderPattern` = `reader?url=…&text=…` →
  `ui/reader/ReaderScreen.kt` + `ReaderViewModel.kt`): share a link →
  fetch + extract (`reader/ArticleFetcher`, `reader/ArticleExtractor`,
  parsed on the `@ReaderParseDispatcher` from
  `reader/ReaderParseDispatcher.kt`, off Main) → article rendered as
  native Compose text blocks. Page furniture that Readability keeps
  (infoboxes, navboxes, sidebars, hatnotes, edit links, footnote-marker
  superscripts like `[1]`/`[citation needed]`) is removed by class name
  in `ArticleExtractor.removeNoise` *before* Readability runs — its
  cleaned HTML has no class names left; `ArticleCleanup` then filters the
  block list. Entered from ShareIntentActivity (or the
  playback notification), which starts MainActivity with
  `EXTRA_READER_URL`. MainActivity takes a request only from a fresh
  launch or `onNewIntent` — not a recreation or a Recents replay (a
  request still pending when onboarding was up survives via saved
  state). AppRoot navigates with `popUpTo(ReaderPattern) { inclusive }`
  and no `launchSingleTop`, so each new link gets a fresh entry and
  ViewModel; the same URL already on top is left alone. The article is
  in-memory only — never persisted. The list follows the spoken block
  (`ReaderAutoScroll`) except after a user drag, and never while
  TalkBack's touch exploration is on (watched live via
  `AccessibilityManager`) — it would steal accessibility focus; then only
  a ToC pick scrolls. **Voice per article** (`reader/ReaderVoicePicker.kt`
  over the shared `lang/VoiceForLanguage.kt` + `lang/LanguageVoiceSelector.kt`,
  2026-09-28): once per article, before the first block, the reader
  detects the article's language (`LangDetector` over the first ~2000
  chars) and, if the primary alias's voice doesn't speak it
  (`VoiceMeta.languageCode`, language subtag only; a multilingual cloud
  voice — OpenAI-style, stored as `en-US` as a placeholder — counts as
  speaking everything, `CloudApiVoiceCatalog.hasKnownLanguage`), reads it in another
  on-device alias whose voice does (sent as `EXTRA_ALIAS_ID` →
  `TtsRouter.resolveAlias(aliasId=…)`), else an installed on-device,
  pickable voice of that language (`EXTRA_VOICE`, dry, 1.0x), else the
  primary as before. **Never a cloud voice or cloud alias** (Max's privacy
  rule): text reaches a provider only through a cloud primary. Undetected language keeps the
  primary. A rebind keeps the article's voice; logged as `D/ReaderVoice`.
  The reader UI shows no voice name. The reading speed (`ReaderSpeedSheet`) is
  session-only and an **absolute override** of the reading alias's speed,
  not a factor on it: each new article starts at that alias's own speed
  (`ReaderViewModel.startingSpeed` → `ReaderPlaybackController.open`),
  and the reader sends it as `EXTRA_SESSION_SPEED`, which
  MarmaladeSynthService applies *after* alias routing (voice, effect and
  language still come from the alias). A non-chip alias speed gets its
  own chip for the whole article (`readerSpeedChoices` over
  `ReaderPlaybackState.startingSpeed`), so picking another speed never
  removes the way back to it. A mid-article speed change is applied
  live to the blocks already queued (no restart; see the
  "effect chain at PLAYBACK" quirk below) — only a fixed-speed (cloud)
  voice falls back to re-enqueueing from the current block.
  **Jumps keep what is already queued** (`ReaderPlaybackController.jumpLocked`):
  Forward, a tap or a contents pick onto a block already queued behind the
  playing one stops only the requests in front of it, so the service plays
  the target's prefetched audio at once instead of re-synthesising it
  (logged `D/ReaderPlayback: jump to N kept queued request <id>` vs
  `jump: restart from N`). Restarting the playing block, a non-queued target
  and any seek while Paused still cancel everything — the service starts its
  next queued request as soon as the paused one stops.
- `ui/AppRootViewModel.kt` — collects theme preset + mode + onboarded
  flag from `SettingsRepository`; drives `MainActivity` decisions.
- `ui/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` —
  5-step flow: Welcome → EnginePick → Installing → CreateAlias →
  SystemDefault. `finish()` flips the onboarded flag. A failed install
  row keeps the installer's own `Failed` (localized via
  `installFailureText`) and offers Retry + "Remove download".
- `MainActivity.kt` — decides between `OnboardingScreen` and `AppRoot`
  based on `onboarded` flag; sets theme via `MarmaladeTtsTheme`.

### DI
- `di/AppModule.kt` — single Hilt module. Provides:
  - `MarmaladeDb` + 3 DAOs
  - `SettingsRepository`
  - `EngineFilesDir` (typealias `() -> File`)
  - `KittenEngine`, `KokoroEngine` (both `@Singleton open`)
  - `NativeEngineHandle` — `release(engineName)` drops only that
    engine's native handle (`NativeEngineHandle.routing`; an unknown name
    releases all), so uninstalling one engine can't abort another's read
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

## Open architectural decision — sherpa-onnx vs onnxruntime-direct

**Current state:** marmalade-tts-android bundles
`libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar` (Apache-2.0). That AAR
wraps Microsoft's ONNX Runtime with TTS-specific helpers
(`OfflineTts`, `OfflineTtsKokoroModelConfig`, voices.bin parsing, lexicon
+ espeak phonemiser, multi-lang routing).

**Alternative:** depend on `com.microsoft.onnxruntime:onnxruntime-android`
(MIT, v1.26.0+ as of May 2026) directly and write the TTS layer in-app.

**When to consider switching:** every Kokoro audio-quality regression
we've hit (v0.1.19's `kokoro-int8-multi-lang-v1_0` tinny output is the
latest) traces back to sherpa-onnx config quirks — lexicon paths,
`lang="en"` vs `""`, the int8 vs fp32 model pairing, `ruleFsts` on the
outer config vs inner. Direct onnxruntime would let us feed phoneme IDs
straight into Kokoro and skip the whole lexicon/espeak class of bugs.
**Maise** (Mobile-Artificial-Intelligence/maise, MIT) is a working
reference for this path — ~120 LoC for the Kokoro ONNX session and
~200 LoC for **OpenPhonemizer** (BSD-3 English G2P, ONNX-based, no
spaCy/pyopenjtalk/espeak dependency). Audited GREEN 2026-05-23.

**Trade-off:** sherpa-onnx gives multi-language G2P routing for free
(via lexicon + jieba + espeak fallback); onnxruntime-direct + a single
phonemiser is English-only. For the "accented English via non-English
Kokoro voices" use case (the actual goal of v0.1.19), English-only
G2P is sufficient — the voice embedding alone produces the accent.
For real multi-language *text* synthesis (Japanese text → Japanese
speech), you'd need a Misaki-equivalent G2P per language.

**GPL boundary note:** `woheller69/ttsengine` (SherpaTTS, the engine
Max uses as fallback on his device) is **GPL-3.0**. Its
`TtsEngine.kt` Kokoro setup confirms the team uses fp32 `model.onnx`,
not the int8 variant we shipped — independent corroboration of the
v0.1.19 bug. But we **cannot port any code** from it; only observe
patterns. Don't read SherpaTTS source files into a context that will
write Marmalade code.

## Known quirks / recent gotchas

- **No Quick Settings tile (removed 2026-09-27, before 1.1.0 shipped)**:
  the "Speak clipboard" tile was pulled rather than debugged. If it comes
  back: a `TileService` can't read the clipboard on Android 10+ (only the
  focused app or default IME can), so it needs a focus-holding trampoline
  activity. The last (still buggy) attempt is in commit `2c6ffb7`
  (`SpeakClipboardTileService` + `SpeakClipboardActivity`).
- **Cancelling a stream aborts the ONNX run in flight** (Kokoro incl.
  German, Kitten, VITS): every `session.run` goes through
  `engine/AbortableInference.kt`, which gives the run its own
  `RunOptions` and calls `setTerminate(true)` when the collecting
  coroutine is cancelled; the resulting OrtException comes back as a
  CancellationException (logcat: `StreamPerf: <engine> inference aborted
  (cancelled)`). Without it the abandoned chunk ran to completion under
  synthLock and the service's synth mutex, and a reader tap paid for it.
  A new ORT engine should route its runs through it too, consuming and
  closing the `Result` inside the block. Pocket needs none: its runs are
  short autoregressive steps with an `ensureActive` between them.
- **User `speed` is a time-stretch, not a model parameter**: Pocket's
  ONNX graphs are autoregressive with no speed input, and Kokoro's and
  Kitten's `speed` tensors saturate (Kokoro ~2.2x for a requested 3.0x;
  Kitten byte-identical ~1.85x for both 2.5x and 3.0x) while slurring
  articulation — so all three set
  `TtsEngine.supportsNativeSpeed = false`. Kitten's per-voice priors are
  NOT user speed and stay in its tensor.
  Both services route the request through `service/SpeedFallback.kt`
  first, which hands the engine 1.0 and prepends an
  `EffectBlock.Tempo(speed)` time-stretch to the effect chain (issue #7;
  Kokoro and Kitten joined 2026-09-12, mirroring the CLI's move to
  `sox tempo`).
  A new engine that can't (or shouldn't) honour `speed` only has to
  override the flag.
- **MarmaladeSynthService runs the effect chain at PLAYBACK, not in the
  producer** (2026-09-28): the producer sends raw engine PCM (so RTF is
  still measured pre-stretch) plus a `Shaping` (the chain spec);
  `playFromChannel` runs `StreamingEffectChain` in ~100 ms slices right
  before the AudioTrack. Reason: the reader's speed must change live — the
  producer runs up to 8 chunks ahead, so a producer-side Tempo couldn't
  follow a change. A reader request (`sessionSpeed`) on a time-stretching
  engine gets a **live Tempo stage** (`StreamingEffectChain(liveTempo=…)`,
  in front of the alias's effects) that re-reads
  `service/LiveSessionSpeed` every slice; audible within the AudioTrack's
  ~250 ms buffer, logged as `Live speed: request N tempo A -> B`. Cloud
  (native speed) and the batched emoji path bake the speed in: they're
  marked fixed, `ReaderSpeechClient.changeSpeed` returns false and the
  reader re-enqueues via `seekTo` as before. Engine pre-roll
  (`playbackRate`) is sized at stream start only, so a big mid-block speed
  increase can briefly underrun on a slow device. `MarmaladeTtsService`
  (system TTS) is unaffected — its chain still runs inline.
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
- **Effect framework status**: `EffectChain.kt` is real pure-Kotlin
  DSP (3-tap comb-filter reverb for CAVE, bit-crush + LPF + vibrato
  for ROBOT, HPF+LPF+softclip for TELEPHONE). Not a stub. Effects
  bind to aliases (`VoiceAlias.effectPreset`), not voices, so they
  only fire when an alias is active. `SpeakViewModel` auto-applies
  the primary alias on init (v0.1.18) so effects fire on first
  Speak without needing the user to tap the alias chip manually.
- **Voice list filtering**: Room holds every catalog voice, so disk
  state decides what's pickable. `data/VoiceAvailability.kt`:
  `probeInstalledVoiceAssets` (engine `verify` + per-pack `verifyPack`)
  → `pickableVoices(assets, showDeveloper)` — on disk (a VITS voice needs
  its own pack) and, outside developer mode, not a developer-only engine
  or unreleased pack. The Play flavor has no developer mode at all: its
  toggle is hidden, and `EngineCatalog.effectiveDeveloperMode` (applied by
  `SettingsRepository.showDeveloperEngines` and `pickableVoices`) turns a
  stored `true` — F-Droid data carried over — into false there. It also
  hides unreleased packs (`VoicePackCatalog.showsUnreleased`, also used by
  the Engines/EngineDetail pack lists). Shared by `VoicePickerViewModel`, the alias
  editor and onboarding's alias step.
- **`kokoro-int8-multi-lang-v1_0` is an unblessed power-user export**
  — added to sherpa-onnx releases by PR #2137 but never included in
  the team's own APK build script (`scripts/apk/generate-tts-apk-script.py`).
  Naive dynamic int8 quantization on a vocoder produces the
  "tinny / staticy" output the v0.1.19 multi-lang Kokoro shipped with.
  If we ever want multi-lang Kokoro via sherpa-onnx, use either the
  **fp32 `kokoro-multi-lang-v1_0`** (~349 MB) or the team-validated
  **`kokoro-int8-multi-lang-v1_1`** (~92 MB). Never int8-v1.0.

## Where the docs live

- `CHANGELOG.md` — per-version changes (kept current; check before
  assuming a "recent change" is on main)
- `SPEC.md` — original v0.1 spec
- `ROADMAP.md` — planned v0.2+ work
- `STUBS.md` — known deferred items (some closed in v0.1.16's "Keep
  engine loaded" toggle removal)
- `PRIVACY.md`, `SECURITY.md` — what the app does and doesn't access
- `LICENSES/kitten-direct.md`, `LICENSES/kokoro-direct.md`,
  `LICENSES/pocket-tts.md`, `LICENSES/runtime-libraries.md` —
  per-component licenses (Apache-2.0 models; espeak-ng GPL-3.0-or-later,
  compiled from source into the APK, which makes the distributed APK a
  GPL combined work while source files stay MIT)
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
