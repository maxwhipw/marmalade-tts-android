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

Unit tests run per flavor: `testFdroidDebugUnitTest` and
`testPlayDebugUnitTest` must both pass. Test JVMs get
`java.io.tmpdir = app/build/tmp/<task>/` (#47): Robolectric's ~200
`robolectric-*` dirs per run, which a killed or starved JVM leaves
behind, stay out of marmalade's shared `/tmp` tmpfs, and each run sweeps
the previous run's leftovers. A fresh worktree has no
`local.properties` and no espeak-ng checkout: build with
`ANDROID_HOME=$HOME/Android/Sdk` and run
`git -c protocol.file.allow=always submodule update --init third_party/espeak-ng`
first (the shared submodule URL points at the main checkout's copy).

**Release smoke gate (#28), before every release:**
`scripts/release-smoke/release-smoke.py --apk <-PsmokeRelease APK>` makes
every engine × language the build advertises speak one sentence on the
Pixel 6a, from the R8 build, and exits non-zero on any failure (no audio,
implausible length, silence, or an error/exception logged by the app's
process). It drives the app through the public `TextToSpeech` API from a
test-only probe APK (`scripts/release-smoke/probe/`, built by the SDK's
build tools outside Gradle), so R8's renaming doesn't matter and no
debug-only hook is needed. The rc package's engine bundles are installed
once through its own UI (not debuggable, so nothing can be copied in).
Unit tests can't catch what it catches: v1.1.0's German died on ICU regex
(#22) while every JVM test passed. Kokoro's accented de/bg rows send that
request language with a Kokoro voice. Kokoro phonemizes in the language
`LangDetector` finds and honours the request only when it abstains, so those
sentences are checked to detect as that language (German, since #59) or to
make it abstain (Cyrillic). Not
covered: developer-only engines (system TTS never offers them). Gotcha: a
client that sets no rate gets the phone's system speech rate
(`settings get secure tts_default_rate`; 1.82× on the 6a) in
`SynthesisRequest.speechRate`, so the smoke stage, which sets none, scales its
length check by it; `setSpeechRate(x)` is honoured as given (measured
2026-10-10). **Speed stage (#42), run by default:** one voice per engine ×
1.0/1.25/1.5/2.0×, silently: the probe timestamps each chunk
(`onAudioAvailable`) and `speed.py` replays the arrivals against a real-time
clock for first sound and stalls, screen off, waiting before each engine for
the phone to cool (skin ≤ 33 °C: a warm 6a renders a third slower). A stall where `SpeedPerfWarning` (the
reference phone's prediction, constants read from the Kotlin) shows no warning
fails, as does a regression against the committed `speed-baseline.json`
(refresh on purpose: `--write-baseline`, per engine with `--engines`).
Since #54 each gap is a **wait** (playback ran dry exactly at a piece's first
byte, from the service's Info line `pieces: starts=… samples=…`) or a
**stall** (inside a piece); a stall inside a piece when the engine's
`RenderMode` line says chunk mode (or `path=whole`) fails the row.
Before measuring it restarts the app and reads the `EngineBundle` lines its
TTS service logs on start (`install/InstalledBundle.kt`: per engine/pack on
disk, the download its install meta records vs the catalog's) and refuses
an engine that isn't on the catalog's download (#51: #42 timed a pre-v26
Kokoro while its table read the catalog); every speed take waits until the
previous take's audio would have finished playing, so the playback horizon
is empty and each take is the cold case. It
writes a listening lab (`speed-lab.html`, “as heard” clips with the stalls
put in). System-TTS path only; the in-app reader's segment scheduling isn't
measured. **Every row checks which voice spoke it** (#50): the TTS service
logs `onSynthesizeText: asked=… voice=… engine=… speed=… effectBlocks=…` at
Info (R8 keeps it), and a row fails unless the voice it asked for spoke it at
its rate with no effect (`routing.py`). A request for Kitten Bella, the
advertised default, reads as an auto-filled echo and goes to the primary
alias: on the rc that is Pocket, so until #50 every "Kitten" row was Pocket
(the "Kitten 0.45 vs predicted 0.25" gap). No row asks for Bella now (smoke
takes Bruno, speed Leo). **Which cores it measures:** Android's TTS manager
(system_server) binds the engine for every client with
`BIND_SCHEDULE_LIKE_TOP_APP` (`dumpsys activity services`: client `system`,
flags `0x80001`, "SLTA"), so system TTS runs on the `top-app` cpuset, X1 cores
included, whoever asks and screen on or off. The in-app reader with the
screen off runs in its own foreground service, in the `foreground` cpuset
(6a: CPUs 0–5, no X1), about half as fast, until #52: it now holds a TTS
session to its own engine while it plays (`TopAppHold`), so it runs top-app
too, and `REFERENCE_COST` predicts that path. README next to the script.

**Headless emulator on the build machine (#34):** `scripts/emulator/emulator.sh`
(`provision` / `start` / `stop`, piped over ssh:
`ssh "$BUILD_HOST" 'bash -s' -- start < scripts/emulator/emulator.sh`) boots
an API 35 x86_64 AVD with no window or audio as `emulator-5580`. Only the
debug build carries x86_64, so it runs debug builds, not the R8 rc. Bundles
are sideloaded by extracting a tarball into `files/engines/` with `run-as`
(no install meta = treated as current), and the release gate's probe speaks
through the public TTS API to WAVs. Use it for wide checks a phone would
tie up (every language, two bundles A/B); it checks the app's path, not
phone-exact audio (x86 ORT rounds differently) and not speed. Always `stop`
it when done. README next to the script.

## Module structure

Single Gradle module `:app`. No multi-module split (yet).

```
app/src/main/
  java/app/marmalade/tts/
    audio/            Synthesis pipeline + audio effects
    data/             Room DB + DataStore Prefs + voice catalogs
    di/               Hilt DI graph (single AppModule)
    engine/           SherpaEngine base + Kitten/Kokoro subclasses
    install/          EngineCatalog + EngineInstaller (HTTP + tar.gz/tar.bz2)
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
- **Data**: Room (v13 schema, 4 entities) for voice/alias/per-app/effect
  mappings; DataStore Preferences for user settings (theme, primary
  alias, per-engine preprocessing rule toggles).
- **Engines**: Vendored Sherpa-ONNX AAR (`libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar`).
  Models download separately per engine (setup recommends one per
  device: see "Engine speed + recommendation"). Since v0.1.19 Kokoro is **multi-language** — 53 voices
  (52 offered since Sky's retirement, issue #2) across American + British English, Spanish, French, Hindi, Italian,
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
- `service/MarmaladeSynthService.kt` — the Narrator's foreground host
  (step C1): a shell that turns intents into Narrator commands and projects
  the Narrator's state onto the notification and media session
  (`ServiceUi.render`). Only `ACTION_SPEAK` and `ACTION_HOST` (both sent via
  `startForegroundService`) promote it; once nothing is on air it leaves the
  foreground and drops the notification at once (no stale "Speaking…" with a
  live Pause after Stop; a read arriving within the grace promotes it again)
  and stops itself 1.5 s later (`stopSelfResult(lastStartId)`, so a newer
  start in flight still reaches its `startForeground`). `ACTION_SPEAK` → `SpeakShim` → one Narrator one-shot and
  one `PreviewCompletions` post. A new speak queues behind other spoken text,
  reads over a playing article (which resumes after it) and **replaces an
  article the user paused** (the Narrator decides — plan §2.8)
- Order of the canonical chain: emoji-detect → preprocess →
  strip-emoji → engine synth → ProsodyApplier → EffectChain (for the
  Narrator's audio the EffectChain step runs on the playback side — see
  "effect chain at PLAYBACK" under Known quirks)

### Engines
- `engine/EngineRegistry.kt` — **the one `engineName → TtsEngine` table**
  (2026-09-29). Name narrowing (`knownEngineOrDefault`, `engineNameFor`,
  unknown → Kokoro Direct), system TTS's narrower set
  (`SYSTEM_TTS_ENGINE_NAMES`: developer-only engines never offered to other
  apps), `onDevice` (all but cloud: residency, native-handle release,
  "release all") and `warmable` (on-device minus developer-only). A new
  engine is added HERE; never write another `when (engineName)` dispatch.
- **Debug-only bench (`app/src/debug/.../devbench/`, issue #1 "B1", 2026-10-04)**:
  `Kokoro7mDevEngine` (Kokoro-7M-Distill as a `KokoroDirectEngine` subclass,
  one style row, en-us, bundle **sideloaded** into
  `filesDir/engines/kokoro-7m-dev/`) and `EngineBenchService`, an adb-only
  foreground service (exported, guarded by `android.permission.DUMP`) that
  renders a sentence file through any engine to WAVs **without playing
  anything** and logs `B1Bench` lines (TTFA, RTF, load ms, PSS delta, thermal).
  Neither is in `EngineRegistry`/`EngineCatalog`: the debug source set keeps
  them out of every release build, developer mode included. Usage in the
  service's file comment; tests in `app/src/testDebug/`.
  The 7M only works on misaki's phoneme spellings (issue #4: plain espeak
  gave 31–34% WER, upstream's own pipeline 3–4%), so it overrides
  `KokoroDirectEngine.englishPhonemes` (a no-op seam for Kokoro-82M) with
  tied espeak → `EspeakToMisaki`, a port of misaki's own EspeakFallback.
  **A small student is only as good as the front end it was distilled on**:
  check that before blaming the model.
  Streaming was prototyped here as dev engines: Kitten (issue #3 "D1a",
  `~/coding/scratch/kitten-stream-d1a/`) and Kokoro (#5 "D1c",
  `~/coding/scratch/kokoro-stream-d1c/`). Both are gone: since #10 and #13
  "E" that is how **KittenDirect and KokoroDirect themselves render** (see
  "Kitten streams" and "Kokoro streams" below). On the phone each generator
  call carries ~120 ms of fixed overhead, which is why slices double up to
  4 s instead of staying at 1 s (#5, #9). **Intra-op spinning helps the split
  path only** (#5 clean phone bench, 2026-10-07, Heart, B1 set; first audio
  median/worst, RTF): unsplit Kokoro 1.81 s / 5.6 s, RTF 0.70; streamed
  1.36 s / 2.3 s, RTF 0.85; streamed + spin 0.94 s / 1.38 s, RTF 0.79; the
  unsplit model with spin RTF 0.78 vs 0.75 without. The planner and every
  slice are many short ORT calls, and sleeping pool threads pay a wake-up on
  each. #13 measured spinning's cost in process CPU time over a 6-minute
  screen-off read: +2.5% (within run-to-run noise), so it stays on.
- **Debug-only model timing (`devbench/OnnxBenchService`, issue #12 "X1")**:
  times ANY `.onnx`/`.ort` file with no engine around it, silently (outputs
  are dropped). One adb start = one line of `files/x1/specs.txt` = one
  session, then **the process kills itself**, so every run's load time and
  PSS start from a fresh process (it releases any engine that app start
  warmed first). The spec grammar, input recipes (seeded random, constants,
  `.npy` sliced or wrapped along an axis, dims linear in `T`) and log lines
  (`X1Bench`: load ms, PSS total/native/other, VmHWM, first call, per-size
  median, fixed + per-audio-second fit) are in `OnnxBenchSpec`, pinned by
  `OnnxBenchSpecTest`. Session knobs: `ep`, `threads`, `opt`, `arena`,
  `mempattern`, `prepack`, `spin`, `mmap` (`.ort` with initializers in the
  mapping), `shrink`. Driver, models and results:
  `~/coding/scratch/x1-phone-timing/` (`x1.py`). Gotchas: ORT 1.26's
  "Runtime"-style `.ort` of Kokoro's QDQ graph won't load (selector replay
  error), so CPU runs use "Fixed" style with `--target_platform arm`; the
  8a flags LIGHT throttling at ~39 °C skin (`VIRTUAL-SKIN`), so the driver
  gates on that and requeues a run that ended throttled.
  `BenchDevice` holds the thermal line and foreground notice both bench
  services share. **Findings to know before touching an engine's
  session setup** (X1, Pixel 8a, measurement only; results lab
  `tts-training-research/x1-results-lab.html`):
  - XNNPACK keeps a separate weight copy per reuse of a shared layer:
    ALBERT on XNNPACK is +280 MB against +35 MB on the plain CPU EP, which
    is most of Kitten's 364 MB. XNNPACK was also slower than the CPU EP for
    all 21 models tried. **Applied in #13 "A"**: Kitten's planner left
    XNNPACK.
  - With prepacking off, the XNNPACK planner SIGSEGVs in ORT 1.26.
  - 4 intra-op threads ran Kitten's generator 1.8× faster than the 5 that
    `CpuClusterDetector` picked. **Why (#13 "C")**: a screen-off reader is a
    foreground service, and the 8a's `foreground` cpuset is CPUs 0–7 (the
    prime core, CPU 8, is only in `top-app`), so the 5th thread ran on a
    little core. The autodetect now leaves out a lone prime core when at
    least two other fast cores remain (`CpuClusterDetector.threadsFor`; a
    1 + 1 + 6 Snapdragon 765G keeps it, or it would drop to one thread);
    apps can't read `/dev/cpuset` (SELinux), so the rule is general, not a
    lookup.
  - A fresh or idle process's first ~1 s of calls costs +40–85 ms each.
  - An arena shrink after each run halves the generator's and Kokoro's
    peak RAM. **Applied in #13 "B"** (`OrtRunAbort(shrinkArena)`, Kitten's
    generator and every Kokoro run).
  - **A/B in one APK (#13)**: debug builds read session knobs from
    `files/dev/engine-knobs.properties` at engine load (`EngineDevKnobs`;
    the release twin returns null): `threads`, `shrink=false`,
    `kitten.plannerXnnpack=true`, `kokoro.stream=false`, `kokoro.spin`.
    `EngineBenchService --ez exit true` gives each run a fresh process and
    logs peak PSS, VmHWM, process CPU time and battery charge per run;
    `--es dumpTokens` writes phone-exact Kokoro token ids. Driver:
    `~/coding/scratch/lean-engines/bench.py`.
- **Kitten's planner runs a trained small text encoder, not ALBERT**
  (issue #14 "X2" trained it, #17 put it in the app): a 4-layer width-256
  encoder (3.4M weights, X1's `te-d256` shape; ALBERT + `bert_encoder` was
  5.8M) maps the same token ids to the same `d_en` that Kitten's duration
  and F0/N predictors consume; everything else in `kitten.onnx` stays.
  `split.py`'s `with_student` swaps it in by graph surgery (its output
  replaces `/bert_encoder/Add_output_0`, names prefixed `x2/`, opset 17 →
  20), so the planner is deterministic from `kitten.onnx` + `student.onnx`
  (both sha-pinned). Desktop parity with #14's PyTorch path is exact
  (durations identical on all 136 B1 cases, F0 within 3e-4 Hz); vs ALBERT
  0.108 frames/token (90% identical), F0 5.3 Hz, streamed Whisper WER
  4.46% vs 4.33% (all number/spelling formatting). `genstats/` was
  recomputed with the new planner. The student must see exactly the app's
  Kitten token ids (it was trained on them), and its position table caps
  inputs at 512 tokens like ALBERT's (`x2//pos/Gather` errors past it).
  A dynamic-int8 student passes the same lines (4 MB vs 13.5 MB) but is
  not shipped (#17 report). Weights, training, ONNX export:
  `~/coding/scratch/x2-encoder/`; #17 parity/WER/bench scripts:
  `~/coding/scratch/kitten-small-planner/`.
- **Kokoro's planner is about half ALBERT on a slower phone** (issue #16;
  in the app since #25 as "the v26 planner", see "Kokoro streams"). On the Pixel 6a (Tensor G1, the app's CPU EP,
  4 threads, spin) the planner takes 209/435/830/1248 ms at 32/80/160/240
  tokens and ALBERT + `bert_encoder` (fp32; the rest of the planner is
  already int8 QDQ) is 44–52% of it; x86 desktop 27–32%; the 8a is
  unmeasured. Two drop-ins for d_en (`/bert_encoder/Add_output_0`,
  [1,T,512]), both verified on desktop only:
  - **int8 ALBERT** needs ALBERT's Identity nodes folded before
    `quantize_dynamic` (the shared layer's weights reach its MatMuls through
    them: a plain call quantizes 8 of 74), and the int8 planner must be
    re-joined from parts (quantizing the whole planner aborts ORT 1.26/1.30
    at load). All-int8 fails Chinese (1.6 frames/token, 31 Hz); keeping the
    FFN expansion MatMul fp32 ("int8f") passes all 8 languages tested and saves
    ~half of ALBERT's time.
  - **An English-only student** (#14's 4-layer width-256 encoder with a
    512-wide output, distilled from Kokoro's ALBERT on half #14's corpus):
    within the X2 lines on English (0.14 frames/token, 3.6 Hz, WER equal;
    a width-192, 2.0M version passes too, 0.16 / 3.9 Hz, but saves only
    ~14 ms more), NOT on ja/zh, so other languages keep ALBERT. On the 6a ALBERT alone is
    645 ms at 240 tokens, the student 40 ms; the planner with it 245/645 ms
    at 80/240 tokens (today's 433/1244).
  - Every lighter planner (int8 or student ALBERT) on the 6a sometimes
    starts a fresh bench process ~1.8× slow and flips fast mid-run; today's
    planner never did (18 runs), nor did today's planner re-joined from its
    parts the same way (so it's the graph's weight, not the surgery).
    Pinning ORT's pool threads to the big cores ("6;7;8") cures the int8
    planners, not the student's.
  - **In the app's own process (#25, 6a, a 157-token English / ~92-token
    Spanish sentence ×8, fresh process per run, screen off)** it is not a
    start-up effect and not special to the lighter graphs: single pieces
    take 1.4–2× at any point, in some runs (v26 English 4 of 42 pieces,
    normal 0.51 s; Spanish 4 of 60, 0.44 s), and today's whole planner
    flips more (0.87 ↔ 1.2 s English, 0.52 ↔ 0.86 s Spanish). Likely
    cause: the 6a's `foreground` cpuset is CPUs 0–5 (`/proc/<pid>/status`
    `Cpus_allowed_list`), so its two X1 cores (6–7) are top-app only and a
    screen-off reader has two fast cores (A76, 4–5) for 4 threads. ORT's
    `session.intra_op_thread_affinities` ids are **1-based** (#16's "6;7;8"
    = CPUs 5–7, two of them outside that cpuset). Pinning the pool to the
    A76s ("5;6", 3 threads) or 2–3 threads unpinned is slower overall
    (English 0.61 s normal; Spanish 40% slow pieces), so the thread rule
    stays 4 unpinned; the `kokoro.affinity` knob remains for A/Bs.
  Scripts, parts and results: `~/coding/scratch/kokoro-planner-k16/`
  (README.md; corpus, teacher store and checkpoints on the build machine, same path;
  lab `tts-training-research/kokoro-planner-lab.html`).
- **Debug-only pickable engines (`BuildTypeEngines`, issue #9)**: a
  prototype engine can be made selectable like any other voice in the DEBUG
  build (aliases, the picker, the primary alias that system TTS uses). None
  is listed today: "Kitten Nano (streaming)" retired in #10 and "Kokoro
  (streaming)" (`kokoro-stream-dev`) in #13 "E". The seam is
  `engine/BuildTypeEngines.kt`, one file per build type: `src/debug/` lists
  the engines, their voice rows, labels and an on-disk check, and binds the
  instances into `EngineRegistry`'s `Set<TtsEngine>` (Hilt `@ElementsIntoSet`);
  `src/release/` is an empty twin with the same signatures, so release builds
  list, route to and load nothing extra. `retiredEngines` (retired engine →
  replacement with the same voice keys; debug: `kokoro-stream-dev` →
  KokoroDirect) is applied at every start by `data/RetiredEngines.kt`
  (`migrateRetiredEngines`): aliases and the default voice move, the retired
  voice rows go; idempotent, a no-op in release. Main code consults the seam
  in `EngineRegistry` (`ENGINE_NAMES`, never `warmable`, `displayName`),
  `MarmaladeTtsApplication` (retirements, then rows upserted on every start,
  outside CATALOG_VERSION), `EngineInstaller.verify` +
  `probeInstalledVoiceAssets` (sideloaded = installed), `VoicePath` and the
  alias editor's engine list. Bundles stay sideloaded (no catalog entry); a
  debug-only engine goes through the seam, never into `EngineCatalog`.
- **Retired voices** (issue #19): a voice dropped from a catalog goes in
  that catalog's `retiredVoices` (retired id → replacement id, same
  engine), and `migrateRetiredVoices` (`data/RetiredEngines.kt`) applies it
  on every start, release too, after the reseed: aliases keep their ids and
  move with `VoiceAliasDao.repointVoice`, the default voice follows, the
  row is deleted. Today: Pocket's `marius` → `alba` (its WAV stays in the
  bundle, unread), mirrored for the clean twin by
  `PocketDevVoiceCatalog.retiredVoices`, and Kokoro's `af_sky` → `af_heart`
  (issue #2). `allRetiredVoices` (`RetiredEngines.kt`) is the union start-up
  applies; system TTS maps a request or `onLoadVoice` naming a retired id
  through it too, so a client that saved the old name keeps speaking. Kokoro
  addresses `voices.bin` by row, so a retired Kokoro voice stays in the
  catalog's private `speakerVoices` (rows don't shift) and only leaves
  `voices`; `KokoroDirectEngine` renders it as its replacement
  (`liveVoiceKey`), style row and genstats alike. `PocketVoiceCatalog.OFFER_VCTK_VOICES`
  is the one-line switch that would retire `azelma`/`eponine`/`fantine` the
  same way. The installer's Pocket layout check requires a WAV per offered
  voice only (per engine: the clean twin checks its own list).
- **Bundle containers** (#65): publish bundles as tar.gz. The installer
  reads tar.gz and tar.bz2 (by magic bytes), but bzip2 goes through
  commons-compress's pure-Java decoder: Pocket's 225 MB unpacked in 15 s as
  tar.bz2 and 1.8 s as tar.gz on the build machine's emulator, the rest of the
  install (sha256, swap, layout check) under 0.3 s. Every install logs one
  line, `EngineInstaller: Installed <engine> from <file>: fetch+sha256 … ms,
  unpack … ms, swap … ms, check … ms`. To repack a bundle in another
  container without changing its files, list the old archive's sha256 in
  `EngineArchive.sameContentAs`: an install from it stays current (no
  update prompt, `current` in the EngineBundle line). `InstallMetaTest`
  covers the meta check under Robolectric (plain JVM tests stub org.json).
- **Pocket voices missing from an older download** (#44): a v21 install
  has five of v27's ten voices and stays usable (Outdated) until updated.
  `engine/pocket/PocketVoiceFiles.kt` decides per voice (`voices/<k>.wav`
  or `voice_cache/<k>.emb`); `PocketEngine.voiceToRender` speaks a request
  for a missing voice with `alba`, logging once per voice, and system TTS
  (`MarmaladeTtsService.advertisableVoices`, shared with CHECK_TTS_DATA)
  lists only `PocketEngine.installedVoiceKeys()`. The in-app lists do the
  same (#45): `probeInstalledVoiceAssets` fills
  `InstalledVoiceAssets.pocketVoices` from `EngineInstaller.pocketVoiceKeys()`,
  and both surfaces share `PocketVoiceFiles.isOffered` (a clone passes).
- **Pocket voice sources** (#30 research, #38 shipped): v27 adds mary,
  rita, stephen, ramone (CC0 Kyutai Unmute donors `3973`,
  `spanish-limaperu`, `thepolishdane`, `floyd2026`; the published
  `voice-donations/<donor>.wav`, unchanged) and jenny (Jenny (Dioco)
  dataset, custom licence: the name must keep "Jenny", and "Jenny (Dioco)"
  where practical; shown as "Jenny (Dioco)", the VITS pack's as "Jenny
  (Dioco, VITS)" (#64); a 17 s reference:
  three whole recipe clips, `~/coding/scratch/pocket-v27-38/src/mkref-jenny.sh`;
  #30's 26 s `refs/jenny.wav` OOM-killed the emulator on first use, see
  the encoder-memory note below). Adding a voice = WAV in the bundle's
  `voices/` + `predefined_voices` in bundle.json + `PocketVoiceCatalog.voices`
  and `creditFor` + CATALOG_VERSION bump + LICENSES/pocket-tts.md, CREDITS,
  NOTICE, LicenseCatalog + the voice count in `engine_pocket_desc` + rerun
  `scripts/pocket-bundle/make_voice_cache.py <bundle-dir>` before packing.
  Re-check each Kyutai donor file is still on HF before a release (donors
  can ask for deletion, ToU §8). The clean twin
  (`PocketDevVoiceCatalog.BUNDLE_VOICE_KEYS`) keeps its v10 archive's five.
  **Precomputed embeddings** (#38, from v27): the bundle ships
  `voice_cache/<voice>.emb` for every predefined voice, made by
  `scripts/pocket-bundle/make_voice_cache.py` from the bundle's own WAVs and
  `mimi_encoder` (the app's preprocessing ported exactly, one ORT thread,
  checked bit-identical over two runs; against the app's own encode on the
  x86 emulator the max abs diff is ≤ 7.5e-7). PocketEngine reads the `.emb`
  before it would encode, so a phone never runs the encoder for a built-in
  voice; a missing or wrong-size `.emb` falls back to the WAV. Pixel 6a,
  first use with the shipped file: alba +0.42 GB / first sound 0.34 s,
  mary +0.43 GB / 0.42 s, jenny +0.47 GB / 0.73 s (encoding on the phone:
  +1.09 / +1.14 / +2.65 GB and 0.66 / 1.75 / 3.5 s). The cache is
  tied to one weights version, and an update replaces the whole engine dir
  (`EngineInstallerTest.anUpdateLeavesNoFileFromTheOldInstallBehind`), so
  an old cache never meets new weights. A ~1e-7 embedding difference still
  changes a seeded take (the sampler amplifies it), so a shipped `.emb` and
  an on-phone encode give different takes of the same voice.
  **Reference length costs memory when the encoder does run** (a missing
  `.emb`, a bundle before v27, user cloning): `mimi_encoder` peaks
  roughly with the square of the WAV's length (desktop ORT, 3 threads:
  10 s 0.9 GB, 15 s 1.7 GB, 20 s 3.0 GB, 26 s 4.4 GB; the app caps at 30 s).
  Pixel 6a, first use (fresh `voice_cache`), peak PSS over the idle app:
  alba 10.0 s +1.09 GB, mary 10.0 s +1.14 GB, jenny 16.8 s +2.65 GB
  (VmHWM 2.83 GB); with the embedding cached, +0.43 GB. Read a WAV's length
  with `soxi`, not from its size: alba is 48 kHz and the VCTK voices 32 kHz.
  Upstream's references run 7.9–12.9 s (azelma the longest) and Jenny's
  16.8 s is the outlier; with the shipped `.emb` that no longer matters.
  It does for user cloning, which always encodes:
  `PocketEngine.cloneVoice` (no caller yet) only applies the 30 s cap, ~6 GB
  at peak, more than a phone can give (#30's 26 s Jenny reference got the
  app OOM-killed on a 4 GB emulator). Whoever wires up a cloning UI must
  cap the clip near the built-in voices' ~10–13 s first (its kdoc says so).
  Deferred to a later release, names already chosen by Max (don't ship
  yet): Kyutai donors Greggy = Greg, Roscoe = Roscoe, P0LFR = Jason,
  Andrea_(Spanish) = Andrea (no "Spanish" label), LC = Bogdan,
  Ineedthisnow = Chike, Yesid = Yesid, Jeremy_Q = Jeremy; and the
  CC-BY-SA picks G, H, J from #30's lab (OpenSLR 83 Scottish female,
  Irish male; OpenSLR 70 Nigerian male), CC0-only for now.
- **Kitten streams** (issue #10, `engine/kitten/KittenDirectEngine.kt`):
  the baked bundle is `planner.onnx` + `gen.onnx` + `genstats/<voice>.bin`
  + `voices/<voice>.bin`, cut from upstream's `kitten.onnx` by
  `scripts/kitten-stream/split.py` (the text encoder is the X2 student
  since #17, Kitten's other weights unchanged; `verify.py` checks the split
  is exact, slices join at >120 dB SNR, the fixed norm statistics sit at
  Kitten's own run-to-run noise, ~0.6 dB mel, and prints the student's
  timing/pitch distance from ALBERT). The seed's `SHA256SUMS` lives in the
  seed dir and is its **content fingerprint**: `seedFromAssets` (and so
  `refreshSeed` at app start) re-copies an installed Kitten whose copy of
  that file differs from the APK's, once; `EngineInstallerSeedTest` fails
  if it doesn't match the seed's files.
  Each clause chunk is planned ONCE (style row, durations, pitch, decoder
  front, harmonic source), then the generator renders its speech frames in
  slices of 0.5 s, 1 s, doubling to 4 s with 4 frames of context
  (`KittenStreamSlicing`), each sent as its own `SynthAudio`. Every planner
  and slice run goes through `AbortableInference`; `synthLock` is held per
  ORT call, never across a send. No non-streaming mode (one slice costs what
  the unsplit model did; each extra slice ~120 ms on an 8a). Clause chunking
  (F rules) stays: it is prosody, not latency. The layout check rejects the
  pre-1.2 `kitten.onnx` layout, so `MarmaladeTtsApplication` replaces an
  installed old copy (old layout, or old/missing `SHA256SUMS`) from the
  seed at start (`EngineInstaller.refreshSeed`; an uninstalled Kitten stays
  uninstalled) and `EngineInstaller.install`
  copies a baked engine from the APK (`SeedAssets`) instead of downloading
  — the v22 download still has the old layout. Only a piece's first slice
  is a `SynthAudio.resumePoint` (see the Narrator's resume points).
- **Kokoro streams** (issue #13 "E", `engine/kokoro/KokoroDirectEngine.kt`,
  prototyped in #5): a bundle with `planner.onnx` + `gen.onnx`
  + `genstats/<voice>.bin` (a non-empty file for every one of the engine's
  `genStatsVoiceKeys`, all 53 catalog voices or German's `thorsten`, or it
  isn't the streaming layout: `hasAllGenStats`; an unknown voice key renders
  with speaker 0's stats, `genStatsKeyFor`; the int8 v23 model cut in two,
  weights unchanged; scripts `~/coding/scratch/kokoro-stream-d1c/`) plans
  each piece ONCE and renders it in Kitten's slices (0.5 s, 1 s, doubling to
  4 s, 4 frames of context; `KokoroStreamSlicing`, whose slices never cut
  inside the trailing pause, so TailTrim + SilenceCompressor run on the last
  slice alone). The generator's 48 norms can't use one fixed table per voice
  (~2.9 dB off): `genstats/<voice>.bin` is a small per-voice linear model and
  `KokoroGenStats` turns it into each piece's table from its pause share and
  length. Sentences stay whole up to the 500-token cap (budget target 360);
  over-cap chunks still re-split (`tokenPieces`). The planner reads a whole
  piece before its first slice (~5 ms a token on the 8a), so a first
  sentence over 160 tokens is cut at a clause into an ~80-token head when
  its plan would be heard: nothing playing covers it, or a pre-roll is due
  (`kokoroStreamCutFirst`; #13 review, the long-article set waited up to
  5.5 s without it). **Later chunks are cut while rendering** (#49):
  before each chunk after the first, `nextChunk` predicts its wait for a
  first sound and, when that outruns the audio still to play (`horizon` +
  audio held for a pre-roll) by more than 150 ms, cuts it at a clause
  (`TextChunker.clauseHead`, never a word gap) into a head sized to fit
  (`kokoroLaterHead`; 15 tokens up to the budget's max, topped up to
  1.35× by the packer: `kokoroLaterCutBudget`); the rest goes back on the
  queue and is checked in turn. The wait is a `PieceCostModel` line fitted
  through the latest short (< 60 tokens) and long measured pieces (Kokoro's
  costs are mostly fixed: 6a v26 plan ~100 ms at 19 tokens, ~250 ms at 153;
  German v28 ~235 / ~345 ms at 18 / 164), plus the first slice on the
  streaming path; the whole path uses its render time. **Measured cold on
  the 6a** ("Good morning." + a 150-token sentence): streaming (v26 Kokoro,
  v28 German) never stalled there at 1× even before; the cut matters on the
  whole path (German v25, any Kokoro still on a pre-v26 bundle), where
  rendering the second sentence whole stalled 1.2–2.6 s. A sentence with
  no clause mark after its head still renders its rest whole there. A
  `StreamPerf` `kokoro cut` line logs each cut. The gate's speed stage
  renders takes back to back, so the horizon carries the last take's
  audio into the next (#51). Pre-roll above 1× banks SLICES, not chunks
  (`PrerollGate.streamBankMs`): played audio enough to cover the request's
  estimated deficit, from the last measured streamed RTF (0.85 before any),
  with the hold's render time capped at 2 s. Holding whole chunks made a
  sped-up listener wait for a full sentence plus the next one's plan. No
  bank in chunk mode (#57): the gate already holds each piece, and the bank
  only held a finished first sentence back until the next was released
  (German at 2× on the 6a: 2–2.5 s of silence after a tap).
  Both graphs: CPU EP (QDQ),
  intra-op spinning ON (#5 clean phone bench, Heart: first sound median /
  worst 1.81 s / 5.6 s unsplit → 0.94 s / 1.38 s streamed + spinning). A
  bundle without the layout (v23 and older, the German engine's v25, the debug 7M)
  renders exactly as before
  through `model.onnx` (the whole path: short first
  piece, chunk-grain `PrerollGate`, spinning off). `isInstalled` and the
  installer's layout check accept either; `modelFile()` prefers a `.ort` of
  any graph. Debug knobs (`EngineDevKnobs`): `kokoro.stream=false` forces the
  whole path when model.onnx is present, `kokoro.spin=false|true`. The
  `render path:` log line at load names the path, threads, spin and shrink;
  `StreamPerf` has one plan line per request (`cutFirst`, `bank`), then per
  piece `kokoro plan=…`, per slice `kokoro slice=i/n`, and `kokoro piece …
  rtf=`. `lexicon-zh.txt` (68k entries, 0.6–0.8 s) loads on the first text
  with a CJK run (`mandarinLexicon()`), not at load (#13 "D2").
  The v26 download (#25) is the first to ship the layout: `planner-rest.ort`
  + `textenc.ort` + `textenc-en.ort` + `gen.ort` ("Fixed" style, ARM; the
  ORT converter isn't byte-deterministic, so the files are frozen in
  `~/coding/scratch/kokoro-v26/ort/` with their sums) + #13's genstats,
  unchanged (refitting all 53 voices under the v26 planners moved nothing
  beyond 0.07 dB), and no `phonemizer/` (the unread espeak-ng-data;
  `model-format.txt` stays, `isQuantizedBundle` reads it). Built by `~/coding/scratch/kokoro-v26/bundle/make_bundle.sh`.
  On the 6a (screen off, B1 set) first sound 2.1 s → 0.8 s (Heart),
  3.7 s → 1.5 s (Spanish); load 3.1 s → 1.0 s; peak −110 to −230 MB; RTF
  0.89 → 0.94 and processor time +35–70% per audio minute (streaming's).
  **The v26 planner** (issue #25): `planner-rest` (the planner minus ALBERT
  + `bert_encoder`; extra input `/bert_encoder/Add_output_0`, [1,T,512]) fed
  per piece by one of two text encoders (tokens → that tensor):
  `textenc-en` (#16's d256 English student) when the piece's ids are English
  (an `en*` language and no Han run, `textEncoderFor`; the student reads only
  token ids, so the voice doesn't matter), else `textenc` (#16's "int8f"
  ALBERT, every language). `hasSplitPlanner` = planner-rest + textenc
  (textenc-en optional; without it English uses textenc) and it wins over a
  whole `planner` when both are present. Encoder sessions open on first use
  (`textEncoderSession`, ~50–90 ms / ~230 ms on the 6a) and stay open; the
  warmup opens textenc too when the default voice is non-English. Two whole
  planners were rejected: each carries the same 89 MB "rest", so the
  download would grow ~80 MB and a language switch would reload 100 MB.
  Debug knobs: `kokoro.planner=whole` (use a whole `planner` beside the
  v26 files, for an A/B), `kokoro.affinity` (ORT
  `session.intra_op_thread_affinities` for the streaming graphs). The plan
  line names the encoder (`enc=`), the load line both files.
  **Kokoro German streams the same way** (issue #35): `KokoroGermanEngine`
  only overrides `genStatsVoiceKeys` (`thorsten`), and the installer's
  `verifyKokoroDeLayout` accepts the whole model or planner-rest + textenc
  + gen + `genstats/thorsten.bin` (no textenc-en: `lang` is always `de`).
  Its v28 bundle is cut from v25's model.onnx exactly like v26
  (`~/coding/scratch/kokoro-de-k35/`, `graphs/README.md`). Thorsten's
  ALBERT differs from base Kokoro's (fine-tuned, ~0.01 per weight), so its
  `textenc` is int8f of its OWN ALBERT; v26's can't be reused. Thorsten's
  graph names match base's except the duration output (`onnx::Shape_3392`).
  Its genstats are fitted on German only (250 lines, CV sentence-collector
  CC0 + PD Kafka/Hesse; tokens from the app-exact G2P spec
  `kokoro-german-lab/fixtures/gen_fixtures_de.py`): 0.84 dB held out.
  The catalog points at engines release v28 (`kokoro-de-v1_0.tar.gz`,
  153,045,045 B, installed 170,513,746; `bundle/make_bundle.sh`). On the
  6a's reader cores (#52's top-app hold, screen off) it starts speaking in
  0.4 s instead of 1.2 s at the same render speed (RTF 0.43 vs v25 0.41):
  `REFERENCE_COST[kokoro-de]` 2.12. On the `foreground` cpuset (Android 11
  and older) 0.8 s instead of 2.4 s but RTF 0.97 vs 0.85 (Thorsten's
  planner keeps more fp32 than base Kokoro's): `FOREGROUND_COST` 4.62.
- **Chunk mode: whole pieces when the engine can't keep up** (#54,
  `engine/ChunkMode.kt`). Per request each streaming engine (Kokoro's
  streaming path, Kitten, Pocket) picks a `RenderMode`: chunk mode exactly
  where the speed-up warning shows (`SpeedPerfWarning.behind`: RTF × playback
  rate > 0.6, else the static speed > 1.35 when nothing is known), else
  stream (slices as they render, as before). The RTF is the warning's:
  `perf/RenderPace.of` (the app's measured RTF, else `EngineRecommender`'s
  prediction); `ChunkModeTest` pins that the two agree. The engines kept
  their own in-process RTF in round 1; it flipped modes between requests
  (the gate's warm-up read Kokoro at 0.61) and the UI couldn't see it, so it
  went. The mode is decided per piece (`RequestPace.pieceMode`), at the speed
  the piece will be heard at: the Narrator's render job carries the session's
  live tempo (`LivePlaybackRate`, a coroutine-context element the engines'
  flows inherit), so a speed change mid-segment reaches the next piece without
  a re-render; system TTS has none and keeps the request's rate. The piece
  already streaming when the speed rises can still stall once inside: its
  first slices are already in the writer's queue (`Narrator.pump` writes
  every rendered chunk at once), so only a hold inside the writer could stop
  it (6a reader, 1× → 2× mid-paragraph: 16–22 stalls inside pieces per take
  before; after, 0–1 per take, always in that piece, none in any later one;
  on main after 1.3.2's WSOLA, 2026-10-23: 0 in 12 takes, Kokoro and Pocket,
  1× → 1.5× and 2×, #55 closed). In chunk mode
  a `PieceGate` holds each piece's slices for `holdMs`: until every slice
  still to render arrives before playback reaches it (estimates from the
  piece's slice grid and a `SliceCost` fit, Kokoro's and Kitten's scaled by
  how far it has been running behind, `cautiousMs`: the second-worst
  actual ÷ fit ratio of similar-size slices, at most 1.25, so a slowdown
  counts and one outlier doesn't; each × 1.1, plus one slice per piece at
  up to 2× its estimate, `PieceGate.SLOW_SLICE`, #57: once released nothing
  calls a piece back, and on the 6a the first 2 s slice of a Kokoro piece is
  the noisy one, up to 1.35× the fit where later ones stay within 1.11×; one
  at ~1.8× stalled a released piece 468 ms, and a margin on every slice big
  enough for that holds whole sentences near real time; Pocket's gate adds
  none, its `chunkRest` charges its own slow decode), in audio ms turned
  into played time at the current rate, against this request's own audio
  still ahead, `PlaybackHorizon.requestRemainingMs`: an earlier request's
  audio may have been flushed by a Stop the engine never hears of, e.g. a
  client flushing an utterance whose render had finished), else until the
  piece is fully rendered: a wait falls before a sentence or clause, never
  inside one. Kokoro and Kitten use `TimedPieceGate` (a timer re-checks at
  the computed moment, `PieceGate.recheck`, and releases unless the slice in
  flight is already late, in which case its arrival decides) and cut 2 s
  slices in chunk mode. Those cost one extra generator call per 4 s (~120 ms,
  ~+0.03 RTF): the engines report it as `SynthAudio.extraRenderMs`, which
  `SegmentRenderer` leaves out of the measured RTF, so the mode can't feed
  its own decision. Why 2 s (`KittenStreamSlicing.CHUNK_MODE_MAX_SLICE_FRAMES`):
  a 4 s last slice needs most of the sentence banked. First try (1.3 margin, release only at a
  slice, 4 s slices) made Kokoro at 2× on the 6a wait 2.3 s before the long
  gate sentence, where streaming had stuttered ~1.1 s in all; #54's rule
  waited ~0.5 s once but stuttered in about 1 take in 6, and with the slow
  slice allowed for (#57) it waits 1.0–1.3 s once, no stutter in 6 takes.
  Pocket stays slice-granular (its deadline-cut
  slices are small). Its hold (`PocketStreamPlan.chunkRest`) assumes one
  decode per piece takes `DECODE_SAFETY` (3×) a full slice's, as its cut
  rule does (about one slice in 50 is parked on a little core): charging
  every slice 3× read as a decoder that never keeps up and held whole
  chunks (2× first sound 1.2 s instead of 0.3 s). Kokoro's whole path
  (no streaming layout, e.g. German v25) sends whole pieces anyway and logs
  `path=whole`. Logs: Info `RenderMode: <engine> mode=chunk rtf=0.43
  (predicted) speed=2.00 played=0.86 path=sliced` per request, again when a
  piece's mode or speed changes (R8 keeps it;
  the release gate reads it), `StreamPerf` `<engine> chunk-mode release
  held=…ms slices=… rest=…` per held piece. Debug knob `chunkMode=on|off`
  forces a mode. The 0.6 threshold: on the 6a Kokoro v26 (RTF ~0.43)
  streamed clean at 1.25× (played 0.54) and stalled 60–120 ms at 1.5× (0.65).
- **Pocket streams** (issue #15, `engine/PocketEngine.kt` + `engine/pocket/`;
  both flavors since #29). Five graphs: text_conditioner, mimi_encoder (voice prompt),
  flow_lm_main (the AR transformer, int8, 6 KV caches of 1000 rows), flow_lm_flow
  (one LSD step), mimi_decoder (56 state slots). Per mini-chunk: fresh flow_lm
  state from the voice prompt, AR frames at 12.5 Hz, each latent frame decoded
  into 1920 samples. What #15 changed, and why (results lab
  `tts-training-research/pocket-speed-lab.html`, Pixel 6a):
  - **State is carried, never copied** (`PocketStateCarry`): a run's output
    tensors are the next run's inputs, and the `Result` owning them closes once
    nothing references it. ORT 1.26's `OnnxTensor.getFloatBuffer()` allocates a
    heap array and copies; the old path did that for the 49 MB of KV caches twice
    per AR frame (1.5 s per frame on the 6a). Init states (`flowInitStates`,
    `mimiState`) are only read, never refilled: refilling the decoder's slots one
    value at a time cost ~0.95 s per sentence. ORT never writes a graph input.
  - **The voice prompt runs once per voice** (`voicePrompt`, "C"): phase 1 has
    no randomness, so later chunks start from its cached state frame. It holds
    ~49 MB native while the engine is loaded (the 1000-row cache; ~22 MB at
    448 rows with "F"), freed by `release()` (residency evicts idle engines)
    and on deleting that cloned voice. Dropping it after every request was
    measured on the 6a and rejected: first sound +190 ms, CPU +10%, and idle
    PSS no lower (799 vs 774 MB, mean of 2: the native heap keeps the freed
    memory and re-running phase 1 grows it).
  - **Decode batches of at most 46 frames are exact** (`PocketMimiBatch`, "D"):
    the export bakes mimi's attention ring at 1000 slots, and the decoder writes
    16 steps per latent frame with a 250-step lookback, so a batch B is exact
    iff `16·B + 249 ≤ capacity`. Larger batches overwrite keys still needed and
    corrupt early frames SILENTLY (the old P-AL 64-frame re-decode garbled
    frames 8–32 of long sentences; probably the old "distorted word" reports).
  - **Every AR frame needs a flow_lm cache row** (#23): the voice prompt
    (BOS + up to 375 rows for a 30 s clone), the chunk's tokens and one row
    per frame must fit in the cache (1000 rows shipped); past it
    flow_lm_main throws a Reshape error and the request fails. So a word
    over the chunk's token limit (URL, hash) is cut into pieces
    (`PocketTokenSplit`; it and the chunk packing count tokens after
    `preprocessForPocket`, whose trailing `.` is a token), and the
    frame cap is cut to the rows left (`PocketCacheShapes.frameBudget`,
    logs `Frame cap … cut to …`).
  - **Streamed render** (`streamChunks`, "E"): frames go to the decoder in
    slices while the AR loop runs; by default the decoder runs on its own
    coroutine (`pocket.stream=inline` decodes on the AR thread). The decoder's
    job queue holds 2 slices (`DECODE_QUEUE`), so when the Narrator's
    read-ahead permit closes the AR loop stops too: past it the engine renders
    at most `ENGINE_AHEAD_CHUNKS` (8, now counting slices) + 4 slices, about
    30 s or 6 sentences of long text on the 6a (an unlimited queue rendered
    the whole segment). Slices are
    cut by deadline (`PocketSliceClock`): the first at 6 frames, each later
    one when waiting one more frame would let playback run dry (min 4 frames,
    max the exact batch). Fixed doubling slices (6/12/24/46) stalled in 13
    of 14 sentences at RTF 0.63, and a length-estimated hold only halved
    that (sentence lengths are only known to ±15%). A first-sound hold
    (`PocketStreamPlan.holdMs`, capped 3 s) is only set when the render
    can't keep up (slow phone, high speed); the decoder's cost per slice is
    fitted live (`engine/SliceCost`, ~60 ms + 24 ms/frame on the 6a; no line
    until sizes span 8 frames, or a 6- and a 7-frame slice tilt it to ~0 ms).
  - **Decode safety factor** ("G", `PocketSliceClock.DECODE_SAFETY` = 3): the
    deadline assumes a slice may take 3× its usual decode. A screen-off app
    (even a foreground service) is in the `foreground` cpuset, cores 0–5 on
    the 6a: no big cores. The AR loop and the decoder each keep a mid core
    busy, and now and then the scheduler parks the decoder on a little core
    for seconds (2.4–5.5× slower slices, ~1 in 50). With the bare estimate
    70 of 91 slices arrived with < 0.5 s of audio left; with 3×: pauses
    1.3 s → 0 per 3-paragraph run, 0.44 s → 0.01 s per 14 sentences, same
    RTF, +3% CPU, audio within 1 LSB. Per-thread cores: `ps -T -p <pid> -o
    TID,PSR,TIME+` (`~/coding/scratch/pocket-speed/psr.sh`).
  - **Chunk mode** (#54, `engine/ChunkMode.kt`): when the mini-chunk's
    `RenderMode` (decided per mini-chunk by the request's `RequestPace`) says the
    render can't keep up, a `PieceGate` in front of the bank holds its slices
    until `PocketStreamPlan.chunkRest` (length guessed from tokens ×1.2, AR at
    `arMs`, decoder at `decodeCost`, one decode charged `DECODE_SAFETY`) says the
    rest can't run dry against this request's audio still ahead
    (`horizon.requestRemainingMs`), else until the chunk is decoded. A chunk past
    its length guess has no length to go by: it is held to its end. The cut rule
    still cuts small slices then; it costs nothing measurable, since the decoder
    runs beside the AR loop (6a: 4-frame slices decode at ~21 ms/frame, the AR
    loop takes ~29; render RTF in chunk mode at 2× 0.307 per 1×, streaming at
    1.5× 0.303).
  - **Threads** ("B", `PocketSessionPlan`): the flow head and the decoder get
    half the threads (at least 2): with the decoder alongside the AR loop, two
    full pools oversubscribed the 6a (2 decoder threads: same RTF, −11% CPU,
    audio within 1 LSB). XNNPACK stays on everywhere (off: −4% RTF, +10% CPU);
    intra-op spinning stays off (on: RTF 0.61 → 0.84, CPU ×2.4, because
    spinning threads starve the decoder running alongside).
  - **Symbolic cache axes** ("F", `PocketCacheShapes`): if a bundle ships
    `*_dyncache` graphs (cache axis −1 in the declared shape), flow_lm's caches
    are sized per voice (`roundUp64(promptRows + tokens + maxFrames)`, ≤ 1000)
    and mimi's ring at 320 (batch ≤ 4). v21 doesn't have them; the v27 bundle
    (#31, 2026-09 weights) declares them as its default graphs in bundle.json.
  - **Weights versions** (#31): Kyutai's `english_2026-09` only retrained
    the flow_lm backbone (transformer, text conditioner, speaker projection,
    EOS head, BOS embeddings). The flow head and the whole Mimi codec are
    bit-identical to 2026-04, so `flow_lm_flow` and `mimi_decoder` (and the
    ≤ 46-frame exact batch) carry over unchanged. `mimi_encoder.onnx` bakes
    the speaker projection in, so a voice embedding (`voice_cache/*.emb`,
    a cloned voice's `.bin`) belongs to one weights version: never reuse
    one across versions. Kyutai's gated repo needs an accepted prohibited-use
    prompt; the ungated `kyutai/pocket-tts-without-voice-cloning` has the
    same weights with the Mimi encoder zeroed, so an export from it takes
    the encoder graph from the previous bundle and swaps in the new
    speaker projection (initializer `val_419`, `[32,1024]` = W^T). Export
    tooling, configs and checks: `~/coding/scratch/pocket-2026-09/` on
    the build machine (`tools/`, `README` in `export/` on marmalade); the exporter
    run on 2026-04 reproduces v21's graphs exactly.
  - Debug knobs (`EngineDevKnobs`): `pocket.xnnpack`, `pocket.spin`,
    `pocket.<text|enc|dec|main|flow>.threads` (`PocketSessionPlan`),
    `pocket.stream=inline`, `pocket.decode=<frames>`, `pocket.decodeSafety`, `pocket.seed` (bit-exact
    A/B), `pocket.ortProfile=<requests>`, `pocket.file.<graph>` (sideloaded
    graph), `pocket.cacheLen`, `pocket.mimiCap`. Logcat `StreamPerf`: per chunk
    `pocket chunk=i prof …` (AR split main/state/flow) and `… decode=…`, per
    slice `pocket slice …`, per request `pocket hold=…`, per held chunk `pocket chunk-mode release …`. Bench driver and
    analysis scripts: `~/coding/scratch/pocket-speed/`.
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
  word level (spaces; Japanese kana→kanji/katakana steps), then hard. The
  Devanagari danda (`।`, `॥`, and "।।" as one mark; with or without spaces
  around it) is a sentence end in every `TextChunker` split, the reader's
  `splitText` and `ArticleCleanup.endsSentence` (#62). (The
  streaming path uses target 360 and never the short first piece below; see
  "Kokoro streams".) Every piece of a cut sentence uses the WHOLE sentence's style row
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

### Narrator (`playback/`) — the owner of all in-app speech
The reader-session redesign (local plan `docs/release/READER-SESSION-PLAN.md`,
steps A → C1 → C2 → C3 → E → G). Since C1 every in-app read goes through the
one Narrator: the reader drives it directly (`ReaderPlaybackController`), the
Speak screen / previews / shares / Tasker reach it through the service's
`ACTION_SPEAK` shim (`SpeakShim`; step E moves the in-app callers in-process),
and `MarmaladeSynthService` is its foreground host. System TTS
(`MarmaladeTtsService`) does not use it. The package itself is pure Kotlin.
- `playback/NarratorApi.kt` — `NarratorControl` (commands, one
  `StateFlow<NarratorState>`), `NarratorShell` (focus/host signals) and the
  ports the service shell will implement (`SessionResolver`,
  `SegmentPreparer`, `FocusPort`, `HostPort`, `ResidencyPort`, …).
- `playback/StartPolicy.kt` — pure start/hold: today's PrerollGate formula
  minus the audio already banked, fed the session's RTF EWMA.
- `playback/RenderPlanner.kt` — pure "what to render next": ~60 s ahead for
  on-device voices (also while paused), cloud only this segment + the next.
  `NarratorConfig(todaysDepthOnly = true)` depth-limits every voice — how
  C1–C3 must run until G (Pocket holds its lock for a whole utterance). The
  60 s cap applies to depth-limited voices too, so one huge segment (a long
  share with no blank lines) never renders whole into the heap.
- `playback/AudioOutput.kt` — the writer port: stream items (Begin/Write/End)
  queue at their frame, controls (Play/Pause/Flush/SetTempo) apply at the
  next slice; every chunk tagged `(sessionId, epoch, segment, chunk)`.
- `playback/Narrator.kt` — the one owner: a command loop on its own
  single thread (`Narrator.newLoopDispatcher()`) whose handlers never
  suspend; resolution and rendering are child jobs reporting tagged events.
  After every message `settle()` plans the render (RenderPlanner), runs the
  StartPolicy, writes chunks to the output, syncs track play/pause, focus
  and residency, and publishes the state. Audio is keyed by per-session
  epochs (a jump/stop/resume bumps it and flushes the writer); child jobs
  by their own token. Encodes Max's §6 answers: a share while the article
  plays interrupts on the `Interrupt` track and the article resumes from the
  start of the interrupted chunk's sentence; ~60 s ahead on-device, cloud this block +
  next; preparing continues while paused except after `PauseReason.Navigation`;
  every speed change (cloud too) is the writer's live Tempo, engines render
  at 1.0 (speed range: the article 0.5–3, the reader's slider 0.5–2; a one-shot
  0.25–4, system TTS's range, for Tasker's `EXTRA_SPEED`); permanent focus loss → `Paused(FocusLoss)`; 30 min paused → stopped
  (Idle, the shell stops); Previous/Next act on the article only (Next past
  the last block finishes it; from a stopped article both start playing, as
  the reader always did). `play`/`pause`/`toggle` act on the one-shot on air,
  else the article (notification, media keys); `playArticle`/`pauseArticle`
  are the reader's article-only buttons.
  A stop drops the article's audio and restarts its block on Play — a
  re-render need not cut the same chunks (Kokoro's short first piece).
  **Resume points** (issue #11): every rewind to "the heard chunk" (resume
  after an interrupting share, rewriting the other sessions on a flushed
  track) goes through `Session.listenerChunk()`, which steps back to the
  last chunk flagged `SynthAudio.resumePoint`. Engines that render whole
  pieces leave it true; streaming ones flag only a piece's first slice
  (KittenDirect, KokoroDirect streaming), so on-device voices resume
  at the sentence (or clause) start, never mid-word. The cloud engine flags
  a request's first network buffer: a cloud resume restarts the interrupted
  REQUEST (≤ 1000 chars, usually the paragraph; the MP3 path is one buffer
  per request anyway). `trimHeard` never drops the listener's sentence. The
  render budget counts ahead from the heard chunk, not the resume point (a
  whole cloud paragraph would otherwise count as "ahead").
- `playback/SegmentRenderer.kt` — one render job: prepare, stream the
  engine at 1.0, wait for the budget permit between chunks, one TTFA sample
  and one RTF sample per job (chunks after a permit wait are skewed: the
  engines keep rendering into their own buffer meanwhile — cut from the
  channelFlow's 64 to `ENGINE_AHEAD_CHUNKS = 8` by a fused `.buffer(8)`).
- Device side (step C1): `di/NarratorModule.kt` builds the one `Narrator`
  (`NarratorConfig(todaysDepthOnly = true)` until G, loop on
  `Narrator.newLoopDispatcher()`, logcat tag `Narrator`) over
  `service/NarratorResolver.kt` (voice once per session: alias / installed /
  explicit / language-aware share, the no-alias fallback, rules, cloud flag;
  `NarratorPreparer` = rules → emoji strip → per-segment phonemizer language),
  `service/NarratorPorts.kt` (`ServiceHost` = "service is foreground now",
  `AudioFocusPort` = one `AudioFocusRequest` per hold, `NarratorStats` = TTFA
  into `VoiceLatencyTracker` + RTF into settings, `NarratorWakeLock`/`WakeLockArm`
  = held while buffering/playing, re-armed per heard chunk), `TopAppHold` (#52:
  while buffering/playing, plus a 5 s grace, holds a `TextToSpeech` session to
  the app's own engine so the process runs in `top-app`, see Known quirks),
  `EngineResidency`
  (the `ResidencyPort`), `KeepaliveCoordinator` (session start) and
  `service/AudioTrackOutput.kt` — the writer (step C2): ONE thread,
  `narrator-writer`, is the only caller of the in-app AudioTracks (at most two:
  `Track.Main`, `Track.Interrupt`). It runs `playback/TrackWriter.kt` — pure
  Kotlin, the same code the harness runs — per track: a flat state machine
  that applies commands between ≤ 20 ms input slices and writes non-blocking.
  **Playback is continuous**: one track for every block of a session and across
  sessions at the same sample rate (no drain, rebuild or chain restart at a
  paragraph); a Begin at another rate waits until the audio ahead is heard,
  then rebuilds at that boundary frame. One `StreamingEffectChain` (live Tempo
  + alias effects) per session. Flush = pause → `AudioTrack.flush()` (ignored
  while playing!) → head and frame marks reset → older epochs dropped. **The
  mixer plays whole periods**, so a track's last few tens of ms stay unplayed
  until more data comes (the old per-block "head stalled … abandoning drain"):
  when the writer has nothing to write and real audio is stuck, it pads
  silence just past it (at a session end at once, mid-session after 40 ms).
  The pad doubles while the head stays stuck (AudioTrack starts a new or
  flushed track only near a full ~250 ms buffer) and shrinks back to 60 ms
  once the real audio is heard. On Pause the queued padding is flushed — if
  the head is still short (AudioTrack pauses asynchronously), re-checked for
  200 ms. The live Tempo stage holds back up to a frame plus its search reach (~53 ms): about to run
  dry mid-session, the writer pushes it out (`drainLiveTempo`, later effects
  keep their state, one ring-out at the session end).
  Logcat `D/AudioTrackOutput`: `track opened|released|rebuild`, and per block
  boundary `seg A→B session=S pad=Xms underrun=Yms` (0/0 = gapless); a chunk
  that came after the session's real audio ran out (the render fell behind,
  #52) logs `stall before seg S chunk C session=X silent=Yms piece=start|mid`,
  from when the audio ran dry to the late chunk's write; `piece=mid` is a
  stutter inside a sentence (#54: `OutputCommand.Write.pieceStart` carries
  the chunk's resume-point flag). A refused
  format, failed write or parked track (head frozen 2.5 s while playing real
  audio) → `OutputEvent.TrackFailed` for EVERY session with audio on or queued
  for that track (a short one-shot can sit on it whole, End applied) → each
  fails. A track idle 30 s is released; a paused or idle writer sleeps on its
  inbox. The Narrator rewrites a flushed track's other sessions under a new
  epoch, so a report sent before the Flush applied is ignored. The harness's
  `VirtualAudioOutput` can model the device's start threshold
  (`startThresholdMs` + a 250 ms `bufferMs`) and asynchronous pause
  (`pauseLatencyMs`); its 30 ms default buffer hides both.
  `service/ServiceUi.kt` is the pure state → notification/media-session table.
  `service/SpeakShim.kt` holds the `ACTION_SPEAK` shim and `PlaybackHost`
  (`ServicePlaybackHost` sends `ACTION_HOST` so the service is foreground
  before the reader plays; the Narrator fails a session whose host never
  comes up within 5 s with `FailureKind.HOST_REFUSED`). **Android refuses
  `startForegroundService` from the background** (targetSdk 36): a link shared
  then left before it loads would autoplay into that wall. So
  `service/ArticleLoadHold.kt` starts the host from `ShareIntentActivity`
  (still in front) and the service shows "Loading article…" until the reader's
  load is over (`ReaderViewModel` ends the hold after play / failure / rebind /
  back-out; 90 s timeout); `ServicePlaybackHost.start()` counts an
  already-foreground host as success. A refused start from the background shows
  `reader_error_background_start` ("Couldn't start reading in the background.
  Tap Play."), not "Synthesis failed". **Every notification the service
  posts (playback and "Loading article…") has a delete intent of
  `ACTION_STOP`** (#94): Android 14+ lets the user swipe away a foreground
  service's notification despite `setOngoing`. A stop while a share is
  loading cancels the load (`ArticleLoadHold.cancel`), and the reader then
  opens that article without autoplaying it. **Swiping away the shade's media
  player is not a dismissal** (Android 13+): SystemUI only marks the player
  timed out (logcat `MediaDataFilter: Media carousel swiped away`) and sends
  the app nothing, playing or paused; the notification stays posted, the read
  carries on, and the player returns on the next pause→play (verified on the
  Pixel 6a, Android 14, 2026-10-20). After a permanent focus loss the service leaves the
  foreground (`STOP_FOREGROUND_DETACH`) with a swipe-away-able notification
  whose delete intent stops the read; it reports a lost host to the Narrator
  only when destroyed while still foreground. Destroyed while detached (the
  system stops background services), it leaves that notification up, re-posted
  without the released media session, timing out with the Narrator's 30 min
  paused timeout.
- Tests: `test/.../playback/` — `NarratorHarness` (virtual clock, fake
  ports, invariants), `FakeEngine` (lock, non-abortable mode, over-cap
  re-split, internal buffer), `VirtualAudioOutput` (the device's own
  `TrackWriter` over a virtual AudioTrack that plays whole mixer periods,
  ignores a flush while playing and can be parked; side-band frame tags, so
  stale audio is caught frame-exact), and the §5.2 scenarios in
  `Narrator{Scenarios,Budget,Sessions,Output,ResumePoint}Test` (Output = C2:
  no gap at a paragraph, one track per session, stale epochs, flush
  mid-write, rate change, interrupt, parked track). The writer against a REAL
  AudioTrack: `androidTest/.../AudioTrackOutputInstrumentedTest` — **emulator
  only**, run with `am instrument`, never `connectedAndroidTest` (recipe in
  its KDoc). The reader's stories
  (`ReaderPlaybackControllerTest`, `ReaderViewModelTest`), the shim
  (`SpeakShimTest`) and the shell's duties (`NarratorShellTest`) run against
  the same harness.

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

### Engine speed + recommendation (issues #18, #19)
- `perf/DeviceCapability.kt` — the probe: the baked Kitten renders one
  fixed sentence in the app (onboarding, reader and alias screens ask),
  stored once per install (`SettingsRepository.kittenRtfMeasurement`;
  one older than `MEASURED_SINCE_MILLIS` is measured again), plus a Tier-1
  CPU score from sysfs for when it can't run.
- `perf/EngineRecommender.kt` — every engine's cost is a fixed multiple of
  that probe RTF (`REFERENCE_COST`), measured once on the reference phone
  (Pixel 6a, 2026-10-09, `~/coding/scratch/engine-speed-18/`, lab
  `tts-training-research/engine-speed-lab.html`); borrowed from Marmalade
  STT's `SpeedBudget`. `recommend(probe, offered)`: the first of
  `candidates` (Pocket where the build offers it, else Kokoro; then Kitten)
  predicted at RTF ≤ 0.6 (= `SpeedPerfWarning`'s line, so a recommended
  engine never warns or plays whole pieces at 1.0×; 0.8 before #54) takes the one
  Recommended pill; others are FINE up to 0.9, then MAY_BE_SLOW; Kitten is
  never warned. Flavor-aware only through `EngineCatalog.visibleTo`; since
  Pocket reached Play (#29) both builds offer the same engines, so a phone
  gets the same pill on either (the Pixel 6a: Pocket, predicted 0.35).
  Pocket's cost (2.39) is the v27 bundle's render, from #38's ABBA against
  v21 scaled by that session's v21 re-measure (see the KDoc).
  `predictedRtf` feeds the speed-up warning before measured RTFs accrue.
- `perf/SpeedPerfWarning.kt` — the speed-up warning, and chunk mode's
  decision (#54, `behind`): measured RTF × effective speed > 0.6, else the
  probe prediction, else the static 1.35 (0.8 before #54, where streaming
  already stuttered). The measured RTF is an EMA per engine
  (`SettingsRepository.engineRtf`, fed by `SegmentRenderer` via
  `SynthStatsPort.recordRtf`, warm segments only); it restarts when the
  phonemizer language changes (`engine_rtf_language_<engine>`; no detected
  language counts as the voice's own, `UtteranceLanguage.renderLanguage`, so
  headings don't restart it), so a blend of two languages can't flip the
  warning between reads. The alias editor's warning reads the alias speed
  alone, as the Speak screen and shared text play it (the reader sheet has
  its own, on its chip); on system TTS the speed is alias × the calling
  app's rate (TalkBack sends its own; only apps that set none get
  `tts_default_rate`), which the editor can't know, so it doesn't guess.
  The engine decides chunk mode from the request's real rate there, so
  playback still never stutters inside a sentence; only the warning can be
  missing. It is dropped
  when stale (#40): every value when the app's versionCode differs from
  the one stamped with them (`measured_rtf_version_code`; read as absent,
  wiped by the next `recordEngineRtf`), and one engine's when an install
  puts new files under it (bundle, voice pack or re-seed:
  `EngineInstaller`'s `EngineFilesChanged` → `clearEngineRtf`, bound in
  `AppModule`). The warning then runs on the prediction until re-measured.
  **The costs model the `top-app` cores (#52):** the in-app reader holds a
  TTS session to its own engine while it plays (`TopAppHold`, Known
  quirks), so it renders where system TTS does. #52 re-based every cost by
  its bench ratio with/without that session (Kitten 0.62, Jenny 0.37,
  Pocket 1.70, Kokoro 2.25, Kokoro German 2.06; table in the KDoc): on the
  6a Kokoro predicts 0.46 (system TTS measured 0.40–0.43 in #50), so it is
  "Fine" in onboarding and warns from about 1.75×. Before #52 they
  modelled the screen-off `foreground` cpuset (Kokoro 4.69, predicting
  0.97, warning from 1.0×). Android 11 and older keep the slow placement
  (no TTS manager service, so no hold) and keep those pre-#52 costs:
  `FOREGROUND_COST`, picked by `DeviceProbe.readerOnFastCores` (=
  `TopAppHold.supported`, the hold's own SDK gate).
- Re-bench before changing a cost or a `SpeedTier`: costs are bench
  service RTF ÷ in-app probe RTF, and the bench must run on the reader's
  cores: `EngineBenchService --ez topApp true` holds the reader's TTS
  session for the run. **Where an engine runs decides its speed on the 6a
  (#50):** the bench service without that session (and, before #52, the
  in-app reader with the screen off) sits in the `foreground` cpuset (CPUs
  0–5: no X1); system TTS is bound by system_server with
  `BIND_SCHEDULE_LIKE_TOP_APP` and gets `top-app` (CPUs 0–7) for every
  client, TalkBack included; so does the app in front. Same text, same
  build: Kokoro v26 RTF 0.97–1.16 vs 0.40–0.43, Jenny 0.16 vs 0.08, Kitten
  0.24 vs 0.12–0.18, Kokoro German 0.84 vs 0.43. Fewer threads don't help
  in `foreground` (#25). Check a process's cpuset with
  `adb shell cat /proc/<pid>/cpuset`. Debug
  `devbench/DevStateService` replays onboarding and forces a probe value
  (`--ez onboarded false --ef kittenRtf <x>`, <= 0 clears it).
- `install/EngineCatalog.kt` `SpeedTier` — the card's 4-segment meter,
  from the same bench: Kitten/Jenny 4, Pocket 3 (v27 0.49 RTF, still FAST), Kokoro/Kokoro German 2
  (MODERATE), HEAVY only the Pocket clean-reference build.
- `install/QualityGrade.kt` — the card's Quality meter (`EngineDescriptor.quality`)
  and the voice-pack meter share this ranked scale (#78): Pocket and Kokoro 4,
  Kitten 3, VITS 2. Max's listening grades, pinned by EngineCatalogTest and
  QualityGradeTest; agents never change them.

### Preprocessing
- `preprocessing/Preprocessor.kt` — applies the rule set
- `preprocessing/PreprocessingRules.kt` — 15 rules ported from CLI
  (numbers, abbreviations, symbols, etc.)
- `preprocessing/EngineProfiles.kt` — default rule sets per engine
- `preprocessing/EmojiProsody.kt` — detects emoji → emotion mapping
- `preprocessing/ProsodyApplier.kt` — applies emotion to engine
  params (speed, energy)
- Per-language text fixups that every engine needs live at the espeak
  choke point, `phonemizer/EspeakPhonemizer.phonemize`, not in the rule
  set: `EsTextFixups` (Spanish voices, before espeak: old spelling's
  standalone á/é/ó/ú → a/e/o/u, else espeak says "a acentuada", #26) and
  `EnPhonemeFixups` (English voices, after espeak: IPA repairs like
  "yeah", per acoustic model).
- Dashes (#43): espeak ends a clause at a spaced en or em dash, and the JNI
  shim re-injects the glyph into the IPA. The shared Kokoro/Kitten vocab
  (`engine/kitten/IpaTokenVocab.kt`) has only the em dash (9), so the en
  dash is aliased to it; VITS packs have neither, so
  `VitsPhonemeIds.splitClauses` turns a spaced dash into a `;` clause (what
  Piper trained on). espeak-de 1.52 phonemizes "aneinander" with a literal
  `1` (its de_list entry). Since #48 `EspeakPhonemizer.stripDigits` drops
  every ASCII digit from raw espeak IPA for all engines (espeak spells
  numbers out, so a digit there is dictionary notation): "aneinander", the
  hyphenated "aneinander-geklebt" and a lone Cyrillic letter on an English
  voice ("л" → `ˈɛl1`) lose theirs. The `GermanOverrides` entry #46 added for
  "aneinander" gave the same IPA and went in #62.
  `stripInWordDots` (#62) drops a `.` written against a phoneme: espeak's
  Hindi retroflex flap (ड़, ढ़) is the phoneme `r.`, which has no IPA symbol,
  so its name passes through (पढ़ें → `pˈʌr.hẽː`; 552 of 3,000 Common Voice
  Hindi sentences, no other app language has any). The shim puts every
  re-injected mark after a space, so no clause end is touched. Measured on
  the emulator (Kokoro v26, 4 Hindi voices × 30 flap sentences + 2 × 8
  paragraphs, Whisper large-v3-turbo; `~/coding/scratch/mtts-62`): with the
  dot Kokoro splits the word ("फार ने", "जमावर आ"); without it the word stays
  whole and WER drops 33.0 → 28.3% (paragraphs 30.4 → 29.0%), but the flap
  leans toward र (Whisper spells the ड़/ढ़ word exactly 26/132 times vs 42).
  Mapping `r.` to `ɽ` instead (vocab 129) was worse on both (31.4%, 8/132):
  Kokoro's Hindi evidently never learned ɽ. The danda chunker (above) took
  the paragraphs from 32.5 to 30.4%.
- Clause punctuation (#46): `espeak_jni.c` re-injects the LAST mark of
  each clause that only spaces/closing quotes follow, so marks espeak
  reads through ("3.5", "10–20") don't replace the terminator. espeak's
  cursor runs one character past each clause (its lookahead, carried
  into the next call), which the shim drops before scanning. A Hindi danda
  (`।` U+0964, `॥` U+0965) has no vocab token, so the shim injects `.` for
  it (#48).
  `tools/espeak-shim-check/run.sh` compiles the real shim on the host
  against `app/build/espeak-hostgen` and prints its IPA over a fixed
  9-language sentence set; diff two runs to see what a shim change does.

### Persistence
- `data/db/MarmaladeDb.kt` — RoomDatabase, schema v13 (history in its
  kdoc). Every step has a migration except 1→2 (v1 held no user data) and 9→10
  (alias re-key): the armed `fallbackToDestructiveMigration()` resets
  those on purpose.
  The fallback would also hide a broken new migration by wiping aliases,
  so test each one without it: `Migration12To13Test` builds a real v12
  file from `app/schemas/.../12.json` and opens it with the migration
  alone (Robolectric has no instrumentation assets for
  `MigrationTestHelper`). An older step's test (`Migration11To12Test`)
  opens with the chain on to the current version, since Room opens at the
  latest.
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
  voiceId + speed + effectId + phonemizationLanguage + expressiveness).
  `fallbackAliasId` holds another alias's **id** (not a FK; the editor
  writes ids since v1.1.0). `expressiveness` (v12, #36) is Pocket's
  sampling temperature (`data/Expressiveness.kt`: 0.2–0.7, null = the
  engine default `POCKET_DEFAULT` = 0.3, Kyutai's recommendation, by
  Max's call; 1.1.0 always sampled at 0.7). It rides
  to the engine as `TtsEngine.synthesize/synthesizeStream(…,
  expressiveness)` on every path an alias reaches: the Narrator
  (`ResolvedVoice.expressiveness` → `SegmentRenderer`), the Speak screen
  (`SpeechPlayer.speak` → `EXTRA_EXPRESSIVENESS` →
  `OneShotVoice.Explicit`) and system TTS (`SynthParams`). Pocket and its
  dev twin turn it into the flow head's noise std (`pocketNoiseStd`) and
  log `request temperature=…` per request; every other engine ignores it.
  `sentencePauseMs` (v13, #96, `NOT NULL DEFAULT 0`) is the pause between
  sentences ([Pause between sentences](#pause-between-sentences-96) below);
  it rides the same paths as `expressiveness` (`ResolvedVoice` /
  `OneShotVoice.Explicit.sentencePauseMs`, `EXTRA_SENTENCE_PAUSE_MS`,
  `SynthParams.sentencePauseMs`).
- `data/db/AppAliasMapping.kt` + DAO — per-app routing (packageName
  → aliasId)
- `data/SettingsRepository.kt` — DataStore Prefs (theme preset, theme
  mode, primary alias name, keep-engine-loaded, per-engine preprocessing
  enables, the last "What's new" notes seen — see Navigation). `keepEngineLoaded` is **stored but unused** as of v0.1.16
  — UI toggle was removed; the engines don't honour it yet.

### Pause between sentences (#96)
An alias's `sentencePauseMs` ([`data/SentencePause.kt`](app/src/main/java/app/marmalade/tts/data/SentencePause.kt):
Natural 0 / Short 250 / Medium 500 / Long 1000; a paragraph break gets
`paragraphMs` = 1.5×) is silence added on top of the voice's own pause,
only BETWEEN sentences. Never for cloud voices (`SentencePause.appliesTo`;
the editor hides the slider and saves 0). **At Natural every engine takes
its pre-#96 path**: each check is `sentencePauseMs > 0`, pinned by
`PocketSentencePauseTest` (chunk plan identical to the old call),
`KittenSentenceGapTest`, `KokoroSentencePauseTest` and the
`SentenceGapsTest` identity cases.
- **Who adds it.** `TtsEngine.synthesize/synthesizeStream(…, sentencePauseMs)`
  carries it; each engine puts it where its audio has a sentence end
  (`engine/SentenceGaps.kt` has the shared bits):
  - Kitten: on its existing 260 ms sentence gap (`kittenGapMs`); it already
    renders a chunk per clause, so nothing else changes.
  - Pocket, its dev twin and VITS: a silence chunk (not a resume point)
    after each chunk that ends a sentence (`SentenceGaps.endsSentence`: not
    a clause mark), never the last. With a pause they stop merging tiny
    sentences (`SentenceGaps.mergeFloor`), or "Yes. No." would get none.
  - Kokoro keeps its grouping: re-cutting grouped sentences moved their
    pitch by up to 1.3 semitones (#96 lab) and costs a planner call each.
    `engine/kokoro/KokoroSentencePause.kt` splices zeros into the middle of
    each `. ! ?` pause-token run (ids ≤ 16) that has speech on both sides,
    placed from the planner's `duration` output (600 samples a frame),
    snapped to the quietest cut within 10 ms; a piece ending a sentence
    gets the pause after it unless it is the request's last. Same on the
    whole path from the unsplit model's second output (`pred_dur`); a
    model without it logs `whole model gave … durations` and pauses only
    between pieces. Logcat `StreamPerf`: `kokoro pause=…ms splices=[…]
    trailing=…` per piece.
- **Paragraphs** are the caller's: the Narrator's segments are paragraphs,
  so `SegmentRenderer.render(…, pauseAfterMs)` ends every segment but the
  session's last with a silence chunk (`Narrator.paragraphPauseMs`; not a
  resume point, not an RTF sample). System TTS splits an utterance at
  blank lines (`SentenceGaps.paragraphs` / `paragraphsWhole`) only when a
  pause is set; at Natural it is the one call it always was. So at Natural
  a system-TTS paragraph break carries the engine's sentence gap (Kitten's
  260 ms), and with a pause the paragraph's edge silence plus 1.5× the
  pause, as in the reader (measured on the 6a, #96).
- Pauses are audio like any other, so the writer's live Tempo scales them
  with speed. The RTF the engines and the renderer measure includes the
  silence an engine adds inside its chunks (more audio per render ms).
- Device checks (debug only): `EngineBenchService --ei pauseMs <ms>`;
  `DevStateService --es readFile <f> --es alias <name>` reads with an
  alias; `service/NarratorCapture` (debug twin; release returns the track
  unchanged) appends what the Narrator writes to its tracks to
  `files/dev/narrator-capture-<rate>.pcm` while `files/dev/narrator-capture`
  exists — the reader's real output, for measuring gaps silently.

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
- `service/MarmaladeSynthService.kt` — the Narrator's foreground host,
  with media-session/lock-screen transport (see Pipeline above)
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
  text as-is"); marks it `EXTRA_SHARED`, so the Narrator's resolver
  (`NarratorResolver`, `OneShotVoice.LanguageAware`) detects the whole
  text's language once and, if the
  primary alias's voice doesn't speak it, uses the same choice as the
  reader (`LanguageVoiceSelector`: other on-device alias → installed on-device voice
  → primary), logged as `D/ShareVoice`. Explicit-voice callers (Speak
  screen, previews) and the reader are untouched; system TTS
  (`MarmaladeTtsService`) keeps its own per-utterance rerouting, only for
  an auto-detect Kitten/Pocket alias and only to an installed voice
  (`resolveUtteranceLanguage` → `VoiceForLanguage.installedVoiceFor`, the
  reader's step: Kokoro first, then e.g. Kokoro German, never cloud; #60).
  `request.voiceName` doesn't block it: Android fills it in from
  `onGetDefaultVoiceNameFor` for any client that calls `setLanguage`
  (emulator-verified), and a deliberate pick never carries "auto"

### Navigation
- `ui/AppRoot.kt` — Scaffold + NavigationBar (5 tabs) + NavHost.
  The selected tab's label is bold, one line, ellipsized: the look of the
  shared library's `MarmaladeNavigationBarLabel` (marmalade-ui 99ca502, used
  by STT), copied locally because TTS doesn't depend on marmalade-ui (#95).
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
  (`nav`/`aside`/`footer`/`dialog` landmarks and their ARIA roles,
  infoboxes, navboxes, sidebars, hatnotes, edit links, footnote-marker
  superscripts like `[1]`/`[citation needed]`) is removed by tag, role and
  class name in `ArticleExtractor.removeNoise` *before* Readability runs —
  its cleaned HTML has no class names left (Readability sometimes keeps a
  wrapper around the whole page, menus included). Its output keeps `<a>`
  tags, so `reader/LinkFurniture` then drops link-shaped furniture while
  walking it: `ul`/`ol` with ≥70% of letters in links (unless a paragraph
  ending in a colon introduces it), link-only rows and runs of short
  link-only blocks, and the headings standing over nothing but those.
  `ArticleCleanup` then filters the block list. Entered from ShareIntentActivity (or the
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
  a ToC pick scrolls. The contents (`ui/reader/ReaderToc.kt`, #66) are a
  Material modal drawer from the END edge, wrapping the reader's Scaffold:
  `ModalNavigationDrawer` only opens from the start, so it runs in the mirrored
  `LocalLayoutDirection` and the drawer and page get the real one back. Swipe
  opening is off (`gesturesEnabled = isOpen`); scrim tap, swipe, a pick or Back
  close it. **Voice per article** (`reader/ReaderVoicePicker.kt`
  over the shared `lang/VoiceForLanguage.kt` + `lang/LanguageVoiceSelector.kt`,
  2026-09-28): once per article, before the first block, the reader
  detects the article's language (`LangDetector` over the first ~2000
  chars: en es fr it pt de by char trigrams, ja zh hi by script; German
  joined in #59, the table's provenance is `LICENSES/langdetect.md` and it is
  trained by the CLI repo's `tools/langdetect-train/`, so the two ship the
  same table) and, if the primary alias's voice doesn't speak it
  (`VoiceMeta.languageCode`, language subtag only; a multilingual cloud
  voice — OpenAI-style, stored as `en-US` as a placeholder — counts as
  speaking everything, `CloudApiVoiceCatalog.hasKnownLanguage`), reads it in another
  on-device alias whose voice does (`VoiceChoice.Alias` →
  `TtsRouter.resolveAlias(aliasId=…)` in `NarratorResolver`), else an installed on-device,
  pickable voice of that language (`VoiceChoice.Installed`, dry, 1.0x), else the
  primary as before. **Never a cloud voice or cloud alias** (Max's privacy
  rule): text reaches a provider only through a cloud primary. Undetected language keeps the
  primary — never an English default, so a German primary keeps reading text
  the detector can't place (#59). A rebind keeps the article's voice; logged as `D/ReaderVoice`.
  **The voice follows the primary alias (#66):** the Narrator resolves an
  article's voice once per session, so before #66 a held article (re-shared,
  reopened from the notification) kept reading in the old primary forever.
  `ReaderPlaybackController` now watches the primary alias row (rename and
  fallback edits ignored) and on a change re-runs the pick and calls
  `NarratorControl.setArticleVoice`, which drops every rendered chunk, re-resolves
  and restarts the listener's block in the new voice (playing keeps playing,
  paused stays paused; logged `D/Narrator: voice change`). An alias the user
  picks in the reader (`pickAlias`, `ReaderPlaybackState.voicePicked`) wins for
  the rest of that article.
  **The alias dial (#67, Max's design; an icon since #81):** voice and speed
  are ONE control, the transport bar's dial (`AliasDial` in ReaderScreen): a
  speaking-person icon (`ic_reader_voice`, Material Symbols, credited in
  NOTICE) with the speed on a `Badge` whenever it isn't 1× (`speedBadgeShown`).
  It is a bare `clickable` (unbounded ripple, 48 dp), not an `IconButton`,
  which clips content to its 40 dp state layer and would cut the badge off.
  TalkBack reads one description, who reads
  (`ReaderViewModel.readingVoice`: the alias, or an installed voice's own name;
  "Reading speed" with neither) then the speed, and the badge is cleared from
  semantics. It opens `ReaderDialSheet`: the alias rows (`PersonaRow`
  from `ui/components/PersonaSheet.kt`, the Speak screen's) then the alias
  editor's speed slider (`ui/components/SpeedSlider.kt`, shared since #71:
  0.5–2.0× in 0.1 steps); it stays open on a pick so the speed it brings
  shows. The slider applies on release (one `setSpeed` per drag), and always
  has a stop at the reading alias's own speed: off the 0.1 grid it drops its
  detents and snaps by hand (`snapSpeed`). A pick goes to
  `ReaderPlaybackController.pickAlias`. The top bar is just "Reader". Both
  flanks take equal weight, so the transport is centred (#67's name-and-speed
  dial had pushed it right of centre).
  **Speed is an override:** an alias switch or primary change brings the new
  alias's speed, unless the user set a speed in the reader. That override
  (`Narrator` `Session.speedOverride`: set when the user's speed differs from
  the voice's own, cleared when they pick the voice's own speed again)
  outlasts every switch, even one to an alias whose speed it happens to equal.
  `ReaderPlaybackController.setSpeed` rounds with `roundedSpeed`, as the
  alias's own speed is, so a slider's float noise can't keep an override.
  The reading speed is
  session-only and an **absolute override** of the reading alias's speed,
  not a factor on it: each new article starts at that alias's own speed
  (`ReaderViewModel.startingSpeed` → `ReaderPlaybackController.open`),
  and it is the Narrator's session speed, applied at playback *instead of*
  the alias's speed (voice, effect and language still come from the
  alias). A mid-article speed change is applied
  live (the writer's Tempo; no restart, no re-render — cloud voices too).
  **The reader is a Narrator client** (`reader/ReaderPlaybackController`,
  step C1): it holds the extracted article for rebinds, forwards commands
  (`playArticle`/`pauseArticle`, `jumpTo`, `next`/`previous`, `setSpeed`,
  `pause(Navigation)` on back) after `PlaybackHost.start()` for any command
  that may start audio, and projects `NarratorState` onto
  `ReaderPlaybackState` (a share reading over the article shows as Playing).
  Forward or a tap onto the prepared next block plays its audio at once; the
  Narrator keeps heard audio for an instant Back.
- `ui/AppRootViewModel.kt` — collects theme preset + mode + onboarded
  flag from `SettingsRepository`; drives `MainActivity` decisions.
- `ui/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` —
  Welcome → EnginePick → Installing → ChooseVoice → BackgroundUnrestricted
  → NotificationPermission → SystemDefault, one path for everyone (#87:
  the old "Use defaults" shortcut skipped the battery step).
  **ChooseVoice** (`ui/onboarding/VoiceStep.kt`, Max's concept A of the
  walkthrough-87 lab) lists the installed, pickable voices grouped by engine,
  each with #86's speaker sample (`VoicePreviewer` in the VM, so it plays
  through `MarmaladeSynthService`); the lead section shows 6, the others 3,
  then "Show N more". One voice is picked on arrival and "Use <voice>"
  (`useVoice`) saves it as the first, primary alias, named after the voice
  (`aliasNameFor`: "Jenny (Dioco)" → "Jenny Dioco"), 1.0×, no effect. The
  order is `ui/onboarding/FirstVoice.kt` (`firstVoiceGroups`, unit-tested):
  the engine with a voice in the phone's language leads, taking the phone
  check's engine first; within an engine, phone language (and its region)
  first, then `sortOrder` (Kitten's best-first: Rosie), then name. Pocket and
  Kokoro have no best-first order yet (Max's ear). The defaults no longer
  come from the catalogs' `DEFAULT_VOICE_ID`s. EnginePick lists the card wearing the Recommended pill
  first (`EngineCardState.showsRecommended`: the probe's fit, else the
  catalog flag, i.e. Kitten until the probe answers), the rest in catalog
  order (#63). The verdict also ticks the recommended engine to install,
  unless the user already toggled a card; the built-in Kitten always stays
  in the selection (its install step waits for the offline seed).
  The step shows only working dots ("Checking your phone…") and disables
  Install until `probeDone` (#76): set when the check gives a verdict, no
  verdict (`recommend` returned null) or throws, or after `PROBE_WAIT_MS`
  (20 s from onboarding's start, seed wait included; the real check is a
  few seconds, see its KDoc). Past the cap the static list shows; a late
  verdict still re-sorts and relabels it but ticks nothing, and the list
  scrolls back to the top whenever its order changes.
  `finish()` flips the onboarded flag. A failed install
  row keeps the installer's own `Failed` (localized via
  `installFailureText`) and offers Retry + "Remove download".
- `MainActivity.kt` — decides between `OnboardingScreen` and `AppRoot`
  based on `onboarded` flag; sets theme via `MarmaladeTtsTheme`.
- `ui/SettingsLinks.kt` + `ui/SystemSettings.kt` (#53, ported from
  marmalade-stt-android #79) — onboarding's and Settings' jumps into system
  Settings (Text-to-speech output; the battery exemption). `SettingsLinks`
  holds the pure per-SDK target lists and the resolve rule (unit-tested);
  `SystemSettings.open` resolves each target (one exported match, no
  permission we lack; only the final `ACTION_SETTINGS` may show a chooser),
  starts it with NEW_TASK|CLEAR_TASK, and if our screen comes back within
  1.5 s from a full-screen page while the job isn't done, tries the next
  target. The battery dialog isn't watched: a quick Deny is an answer. On AOSP 9–17 the
  first target always wins (`com.android.settings.TTS_SETTINGS`; the battery
  dialog); the rest are for phone makers' Settings. Debug probe:
  `devbench/SettingsLinkProbeActivity` (usage in its KDoc).
- **"What's new" pop-up** (#41, 1.2): `ui/whatsnew/WhatsNewDialog.kt`
  (an M3 `AlertDialog`; title is a heading, notes a TalkBack list) shown by
  `AppRoot` over the Speak route only, while `AppRootViewModel.showWhatsNew`.
  The rule is `ui/whatsnew/WhatsNew.decide`, run once per process when
  `AppRootViewModel` is created: stored
  `SettingsRepository.whatsNewSeenVersion` (DataStore
  `whats_new_seen_version`) ≥ `WhatsNew.NOTES_VERSION` → nothing; else not
  onboarded at launch → fresh install, record the version unshown; else
  (a 1.1.0 user has no key but is onboarded) → show, and any dismissal
  records it. Settings → About → "What's new" opens the same dialog and
  records nothing. **New notes for a release:** replace
  `res/values*/strings_whats_new.xml` (8 locales; public text) and raise
  `NOTES_VERSION` to that release's versionCode without the ABI digit.

### DI
- `di/AppModule.kt` — single Hilt module. Provides:
  - `MarmaladeDb` + 3 DAOs
  - `SettingsRepository`
  - `EngineFilesDir` (typealias `() -> File`)
  - `KittenEngine`, `KokoroEngine` (both `@Singleton open`)
  - `NativeEngineHandle` — `release(engineName)` drops only that
    engine's native handle (`NativeEngineHandle.routing` over
    `EngineRegistry.onDevice`; an unknown name releases all), so
    uninstalling one engine can't abort another's read
  - `EngineRegistry` is `@Inject`-constructed (not provided here); so are
    `EngineResidency` and `EngineWarmup`, which take their engine lists from it
  - `EngineInstaller`
  - `TtsRouter`

### Theme
- `ui/theme/Theme.kt` — `MarmaladeTtsTheme(darkTheme, themePreset, content)`
  + `resolveThemeIsDark(mode, isSystemDark)` helper; provides the depth
  tokens below
- `ui/theme/Color.kt` — palette + `ThemePreset` enum
  (`SYSTEM / MARMALADE / MIDNIGHT / FOREST / BERRY`); MARMALADE is
  the default since v0.1.10. `MarmaladeToast` / `MarmaladeToastInk` are the
  v0 toast pair (the dark-mode stand-in for orange). Every surfaceContainer
  role plus surfaceBright/Dim is set in both schemes: an unset one falls back
  to Material's lavender (`DepthTest` checks). Dark is the agent-cards lab's
  stone ramp (#83): page/bars #1C1917, sheets #221E1C, nav bar #24201E, cards
  #292524 (surfaceContainerHigh), menus #2F2A28 (Highest).
  `onSurfaceVariant` is ink at 64% and `outlineVariant` ink at 13% (light) /
  11% (dark), so quiet text and dividers read the same on any surface.
  `ThemeContrastTest` (#89) holds WCAG AA where we promise it: dim/faint on
  every surface in both modes and every preset, and in dark mode the outline
  (3:1, menus included), primary as text and every filled-button label
  (`labelOn` picks white or ink by contrast). Light mode's orange and Stone
  400 outline are knowingly below AA (Max, 2026-10-17); light presets keep
  their luminance-cut `onPrimary`.
- **Depth and motion layer (#83)**, ported from the design-lab agent-cards
  lab and the webui's #259 tokens. Spend these; don't hand-pick colours,
  shadows or durations on a screen:
  - `ui/theme/Depth.kt` — `MaterialTheme.depth`: the text ramp (`dim` is the
    quietest text, ≥ 4.5:1; `faint` glyphs/icons only; `ghost` decoration),
    hairlines (`line`, `line2`), `hover`, `selected` (toast wash), surfaces
    (`page`, `card`, `raised`, `sheet`, `scrim`), shadows (`cardShadow`,
    `menuShadow`, `barShadow`) and `isDark`. `Modifier.softShadow(shadow,
    shape)` draws a CSS-style shadow (offset, blur, spread) with a framework
    shadow layer; Compose 1.7 has none. `LIGHT_DEPTH` is the light-mode pick
    (FLAT / SOFT / LIFTED; Max chooses in the #83 lab).
  - `ui/theme/Motion.kt` — one easing (0.2, 0.8, 0.2, 1) and 150/200/320 ms
    (`MarmaladeMotion.fast/base/slow`), `expand`/`collapse`/`fadeEnter`/
    `fadeExit`, `riseIn()` (8 dp up + fade). Compose already scales finite
    animations by the system animator scale ("Remove animations" = 0), so
    only endless loops check `rememberReducedMotion()`.
  - `ui/components/Surfaces.kt` — the parts screens use: `MarmaladeCard`
    (card fill, hairline, soft shadow, animated toast wash when `selected`;
    never an accent border), `MarmaladeSheet` (use for every bottom sheet; a
    `ListItem` inside needs a transparent container or it paints the page
    colour over the sheet), `MarmaladeMenu` (an `ExposedDropdownMenu` can't
    use it: dress it the same by hand), `chevronAngle`, `WorkingDots` (Max
    rejected spinning rings; it carries the old indicator's "in progress"
    semantics) and `LiveDot`.
  - Max's #83 picks (round 1, 2026-10-16) that shape what screens spend:
    section titles stay the orange accent in both modes (Settings, Advanced,
    Licences, privacy `##`); dark mode keeps its orange accents (O was
    skipped); the Effects and Engines screens are as before #83 (I and J
    skipped). Light-mode alias cards stay the plain Material `Card`, dark
    ones are `MarmaladeCard` (`AliasCard` branches on `depth.isDark`).
  - Round-2 picks (Max, 2026-10-16): a ticked engine card in light mode
    takes a faint toast wash (`LIGHT_TICK_WASH` in
    `ui/onboarding/OnboardingScreen.kt`); stepped sliders show no marks.
- `ui/components/SliderMarks.kt` (#84) — `SteppedSliderTrack`, the track of
  every stepped slider (`SpeedSlider`, the alias editor's expressiveness):
  Material's dot per detent read as a dotted line, so it draws no marks
  (Max's pick over one mark at 1× or faint dots); each new stop gives a
  `TextHandleMove` haptic tick. Onboarding's speed slider is
  the shared `SpeedSlider`.

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
spaCy/pyopenjtalk/espeak dependency). Audited GREEN 2026-05-23 for
code and weights; but (2026-10-12) its model was trained on
`mrfakename/ipa-phonemes-word-pairs`, espeak-generated and labelled
CC-BY-SA-4.0, so treat anything derived from it as share-alike. The repo is archived.

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

- **The fdroid release APK carries no ART baseline profile (#90).** F-Droid
  rebuilds the app and byte-compares it with our GitHub APK. AGP's compiled
  `assets/dexopt/baseline.prof`/`.profm` change with the build machine's core
  count, which failed 1.3.0, so `app/build.gradle.kts` disables every
  `*FdroidRelease*ArtProfile*` task. Play keeps its profile. The same file
  drops `META-INF/version-control-info` (`vcsInfo { include = false }`) for
  the same reason. Check a repro fix with two clean `assembleFdroidRelease`
  builds on different core counts (`taskset -c 0-3 … --max-workers=4`) and
  `diff -r` of the unzipped APKs.

- **Android 17 Settings crashes on an explicit component without its action
  (#53, from STT #79).** Language & region's `onViewCreated` compares the
  intent's action, so `setClassName(...)` alone NPEs Settings, and with
  Settings in our task that took our activity down too. Every explicit
  Settings component in `SettingsLinks` carries its action, Settings always
  opens in its own task, and a bounce (back within 1.5 s) falls through to
  the next target. Related traps: `LANGUAGE_SETTINGS` matches two activities
  on 14/15 (a chooser); the highlight extra `:settings:fragment_args_key` is
  read only inside the `:settings:show_fragment_args` bundle up to 16; the
  battery dialog closes at once when we're already exempt; on 15
  `Settings$LanguageAndInputSettingsActivity` still resolves but crashes
  Settings ("Invalid fragment for this activity"), so it isn't a 14+ target.

- **In-app speech holds a TTS session to its own app (#52, `service/TopAppHold.kt`).**
  A screen-off foreground service sits in the `foreground` cpuset (6a: CPUs
  0–5, no X1), where Kokoro rendered at RTF ~0.95 and stalled at 1.0×. Android's
  TTS manager (system_server) binds an engine for every `TextToSpeech` client
  with `BIND_SCHEDULE_LIKE_TOP_APP`, and that puts the engine's whole process in
  `top-app`. So while the Narrator is buffering or playing, `TopAppHold`
  holds a silent `TextToSpeech(context, …, packageName)` session; the app's
  threads then run on the X1s screen on or off (check: `adb shell cat
  /proc/<pid>/cpuset`; `dumpsys activity services <pkg>/…MarmaladeTtsService`
  shows client `system`, flags `0x80001` "SLTA"). Apps have no public API
  for the cpuset itself (`BIND_SCHEDULE_LIKE_TOP_APP` is a system API).
  Android 12+ only (the TTS manager service; before it, `TextToSpeech` bound
  from the client's own process, so the hold is skipped there). The
  session's bind creates `MarmaladeTtsService`, whose framework `onCreate`
  loads the default language: `onLoadLanguage` would warm the system-TTS
  voice's model next to the reader's, so a service created while
  `TopAppHold.connecting` skips the model warm-up (the rules cache still
  seeds). Debug builds' A/B switch: `topAppHold=off` in
  `files/dev/engine-knobs.properties`. Cost (#52, 6a, 24-min screen-off
  Kokoro read, A-B-B-A, energy from the power rails via perfetto
  `android.power`): with the hold 103 J per audio minute vs 123 without
  (CPU rails 77 vs 89): the X1s finish sooner and idle, where the A55/A76s
  ran flat out and stalled ~180 times (~3.4 min of silence). Skin peaks
  ~1 °C higher with the hold, but the read ends cooler. Scripts:
  `~/coding/scratch/mtts-52/` (`inapp52.py`, `energy.py`).

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
  closing the `Result` inside the block. Pocket doesn't use it: its AR
  steps are short with an `ensureActive` between them, but a cancelled
  render still waits for a decoder slice in flight (up to ~1 s for a
  46-frame slice on a 6a) before releasing `synthLock`.
- **User `speed` is a time-stretch, not a model parameter**: Pocket's
  ONNX graphs are autoregressive with no speed input, and Kokoro's and
  Kitten's `speed` tensors saturate (Kokoro ~2.2x for a requested 3.0x;
  Kitten byte-identical ~1.85x for both 2.5x and 3.0x) while slurring
  articulation — so all three set
  `TtsEngine.supportsNativeSpeed = false`. Kitten's per-voice priors are
  NOT user speed and stay in its tensor.
  System TTS routes the request through `service/SpeedFallback.kt`
  first, which hands the engine 1.0 and prepends an
  `EffectBlock.Tempo(speed)` time-stretch to the effect chain (issue #7;
  Kokoro and Kitten joined 2026-09-12, mirroring the CLI's move to
  `sox tempo`). The Tempo stage is **WSOLA** (#91): each 1024-sample frame
  may start up to 240 samples (10 ms at 24 kHz) off its nominal position,
  wherever it best matches what followed the previous frame (coarse search
  every 4th start, then full resolution). The plain OLA it replaced summed
  frames at arbitrary phase and warbled at the 47 Hz frame rate at every
  speed but 1.0, system TTS and the reader alike, from 1.1.0 to 1.3.1
  ("vibratey and robotic", GitHub #21/#24). Pinned by
  `StreamingEffectChainTest` "tempo keeps a voiced tone's level steady".
  A new engine that can't (or shouldn't) honour `speed` only has to
  override the flag. The Narrator renders every engine at 1.0 and applies
  every speed at playback (next quirk).
- **The Narrator's effect chain runs at PLAYBACK, not in the render**
  (2026-09-28, carried into the Narrator in C1): render jobs hand the
  writer raw engine PCM rendered at 1.0 (so RTF is measured pre-stretch);
  the writer (`playback/TrackWriter` in `service/AudioTrackOutput`, one
  `StreamingEffectChain` per session with a live Tempo stage in front of the
  alias's effects) shapes it in 20 ms slices right before the AudioTrack. A speed change is an
  `OutputCommand.SetTempo`, audible within the AudioTrack's ~250 ms buffer,
  logged `D/Narrator: speed A→B` — no restart, no re-render, cloud voices
  included. Engine pre-roll (`playbackRate`) is sized at stream start only
  (untouched until C3), so a big mid-block speed increase can briefly
  underrun on a slow device. `MarmaladeTtsService` (system TTS) is
  unaffected — its chain still runs inline.
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
  its own pack, a built-in Pocket voice its own files) and, outside developer mode, not a developer-only engine
  or unreleased pack. The Play flavor has no developer mode at all: its
  toggle is hidden, and `EngineCatalog.effectiveDeveloperMode` (applied by
  `SettingsRepository.showDeveloperEngines` and `pickableVoices`) turns a
  stored `true` — F-Droid data carried over — into false there. It also
  hides unreleased packs (`VoicePackCatalog.showsUnreleased`, also used by
  the Engines/EngineDetail pack lists). Shared by `VoicePickerViewModel`, the alias
  editor, onboarding's alias step and the reader/share voice choice
  (`LanguageVoiceSelector`). "On disk" means `isUsableOnDisk` (Installed
  or Outdated) for engines and packs alike (#45): an Outdated download
  still speaks, and 1.2 re-publishes Kokoro, Pocket and Kokoro German, so
  an `is Installed` test there empties the pickers for most users after an
  app update. Keep `is InstallState.Installed` for install actions only
  (onboarding's skip-download guard, the Engines card's buttons).
  Tests that need a pack listed must use the released pack (Jenny,
  `VoicePackCatalog.EN_JENNY_DIOCO_MEDIUM`) or expect
  `showsUnreleased(...)`: Play hides staged packs even with developer mode
  on, and a `flow.first { … }` waiting for one hangs into an
  `UncompletedCoroutinesError` after a minute instead of failing fast.
- **Voice credit lines** (#37): both pickers (`VoicePickerScreen`'s and
  `VoicePickerSheet`'s `VoiceRow`) show `VoiceCreditLine` under a voice,
  from `data/VoiceCredit.kt` (`VoiceCredits.forVoice`). The data sits next
  to each catalog: `PocketVoiceCatalog.creditFor(key)` (dev twin shares
  it), `VoicePack.credit` per pack, `KokoroGermanVoiceCatalog.CREDIT`.
  Kokoro/Kitten stock voices, cloud voices and Pocket clones have none on
  purpose (no documented source to credit). Keep it in step with
  CREDITS.md / LICENSES/ when a voice or pack is added: `VoiceCreditsTest`
  fails for an offered voice or pack without one. Only "Voice donor" and
  "model by" are translated; names and licence names are shown as written.
- **Voice previews** (#86): both pickers audition a voice through
  `ui/screen/VoicePreview.kt` (`VoicePreviewer` + `PreviewState`), owned by
  `VoicePickerViewModel` and `AliasViewModel`; it plays via `SpeechPlayer`,
  so the audio comes from `MarmaladeSynthService`. One sample at a time, and
  a generation token stops a superseded sample's completion from clobbering
  the new state. The alias editor's sheet makes it a play/stop toggle that
  stops when the sheet or the editor closes, or the screen leaves (a
  `DisposableEffect` in `AliasScreen`), and offers none on cloud voices
  (`AliasViewModel.canPreview`). `stop()` calls `SpeechPlayer.cancel()` only
  while a sample plays: `cancel()` stops the player's one in-flight request,
  which otherwise belongs to someone else (a Speak-screen read).
- **`kokoro-int8-multi-lang-v1_0` is an unblessed power-user export**
  — added to sherpa-onnx releases by PR #2137 but never included in
  the team's own APK build script (`scripts/apk/generate-tts-apk-script.py`).
  Naive dynamic int8 quantization on a vocoder produces the
  "tinny / staticy" output the v0.1.19 multi-lang Kokoro shipped with.
  If we ever want multi-lang Kokoro via sherpa-onnx, use either the
  **fp32 `kokoro-multi-lang-v1_0`** (~349 MB) or the team-validated
  **`kokoro-int8-multi-lang-v1_1`** (~92 MB). Never int8-v1.0.
- **Our Kokoro-family ONNX files carry a broken inverse STFT** (issue #6,
  2026-10-05). Kokoro's export-friendly `CustomSTFT` (`disable_complex=True`)
  never doubles the one-sided middle bins and never divides by the window
  overlap. So DC and Nyquist come out 2× too strong: +6 dB rumble below
  100 Hz, −3–4 dB presence across 1–10 kHz, +3 dB fizz near 12 kHz. This is
  in the shipped 82M int8 v23 and the fp32 it came from (Kitten and the
  German Thorsten files contain the same weights). It costs the 82M UTMOS 4.48 → 4.36 (Heart) and
  4.31 → 3.97 (Michael). The fix is data, not code: scale the two fp32
  initializers `kmodel.decoder.generator.stft.weight_backward_{real,imag}`
  (rows 1..9 ×2, all ÷1.5;
  `~/coding/scratch/kokoro-upstream-ab/patch_istft.py`). **Check per model:**
  one trained through this STFT compensates for it. The 7M student was, and
  the patch makes it worse. Same issue: the style row (`kokoroStyleRow`'s
  count vs upstream's count−1) is audibly and measurably irrelevant.
  **Not shipped (Max, blind listen 2026-10-06):** he could not hear the
  patched 82M from today's on any clip, so the v23 bundle stays as is.
- **Kitten nano 0.8 is trainable** (issue #7 "K0", 2026-10-06). Its baked
  `kitten.onnx` (since #10 shipped cut in two, see "Kitten streams") is an fp32 Kokoro `KModel` (hidden 128, 2 layers, a 256-wide
  decoder, the same ALBERT and 178-symbol vocab) and was trained through this
  same `CustomSTFT`. A PyTorch rebuild loading the ONNX weights matches it to
  fp32 rounding (identical durations), and fine-tunes on the build machine. Rebuild,
  weight map, Kokoro→Kitten teacher pipeline and measured training cost:
  `~/coding/scratch/kitten-trainable-k0/README.md`.
  **Kitten nano is band-limited to ~8 kHz** (nothing above 7.8 kHz; Kokoro
  has a full 8–12 kHz octave). Fine-tuning it on a full-band teacher with
  only StyleTTS2's spectral-convergence loss fills that band with hiss and
  steady tones at the iSTFT bin centres (9.6/10.8 kHz), pauses included (#8).
  Either low-pass the teacher to ~7.8 kHz or add log-mel L1; train on crops
  with real context each side, or every sentence ends in a tick. Details:
  `~/coding/scratch/kitten-trainable-k0/k0-hiss/README.md`.

## Where the docs live

- `CHANGELOG.md` — per-version changes (kept current; check before
  assuming a "recent change" is on main)
- `SPEC.md` — original v0.1 spec
- `ROADMAP.md` — planned v0.2+ work
- `STUBS.md` — known deferred items (some closed in v0.1.16's "Keep
  engine loaded" toggle removal)
- `PRIVACY.md`, `SECURITY.md` — what the app does and doesn't access
- `LICENSES/kitten-direct.md`, `LICENSES/kokoro-direct.md`,
  `LICENSES/pocket-tts.md`, `LICENSES/runtime-libraries.md`,
  `LICENSES/langdetect.md` (the language-detection table's training books) —
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
