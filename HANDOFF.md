# HANDOFF — Pocket QDQ parity: desktop half DONE, device bench next, 2026-08-02 (branch `main`)

## State (this session, head `f3f97b1`, UNPUSHED)

Desktop spike complete — full report:
`~/coding/scratch/pocket-qdq/REPORT.md` (read it first; venv `./venv`).

1. **Format question ANSWERED**: bundle v21's flow_lm_main_int8 is DYNAMIC
   int8 (26 DynamicQuantizeLinear+MatMulInteger, from
   KevinAHM/pocket-tts-onnx-export `quantize_dynamic`). fp32 source (302 MB)
   downloaded from HF `KevinAHM/pocket-tts-onnx` — same export family.
2. **Candidate built + desktop-gated**: `~/coding/scratch/pocket-qdq/
   flow_lm_main_qdq_X1.onnx` (static QDQ per-channel MinMax over 213 real
   captured feeds; 24 linears int8, `input_linear` + EOS head fp32).
   Deterministic temp-0 gate: mel 3.71 dB vs shipping dynamic's 2.83,
   duration bias +1.75% vs +6.9% — quality ≈ shipping. Key finding (mirrors
   Kokoro): quantizing the single 32→1024 `input_linear` alone = +12–20%
   duration drift (EOS fires late). Percentile calibration strictly worse
   than MinMax. Eval noise floor is huge (fp32 reseeded 4.65 dB mel, ±8%
   dur) — always compare temp-0 or same-seed.
3. **x86 speed: QDQ ties dynamic** (21.4 vs 22.5 ms/AR-step; fp32 27.6).
   No ConvInteger slow path in this model — the Kokoro-sized win may not
   exist. **Pixel 8a decides.**
4. **Device decider SHIPPED** (`f3f97b1`): PocketQuantBench on the debug
   Benchmark screen — isolates flow_lm_main AR steps (manifest state loop,
   80 steps, cond prefill), variants: int8dyn±XNNPACK, qdq-x1 (CPU EP only
   — XNNPACK+QDQ segfault guardrail), fp32±XNNPACK optional. Side-load:
   `adb push` bundle.json + variant onnx → `files/pocket-quant/` (int8 +
   bundle.json fall back to the installed engine dir). Results persist to
   `files/pocket-quant/results.json`; logcat tag PocketQuantBench. Strings
   ×7 locales. Unit tests green, APK built
   (`app/build/outputs/apk/fdroid/debug/`).
5. **Ear lab UP** (Max verdict pending):
   http://<labs-server>/pocket-qdq/pocket-qdq-lab.html — fp32 vs int8_dyn
   vs qdq_X1, same seed, 8 texts, voice marius.

## Next steps (in order)

1. Max listens to the lab (only penalise artifacts — muffle/buzz/garble;
   pacing differences are EOS timing).
2. Device bench (needs Max's ADB port; debug app is disposable, never touch
   the release app): install APK, push
   `~/coding/scratch/pocket-qdq/flow_lm_main_qdq_X1.onnx` (+ optionally
   `flow_lm_main_fp32.onnx` and bundle v21's bundle.json + int8 if Pocket
   isn't installed in-app) to `files/pocket-quant/` via run-as, run
   "Pocket quant bench" in Settings → Advanced → Benchmark. Within-run
   comparisons only (thermals).
3. Decide: if qdq-x1 arMedian is NOT meaningfully below int8dyn → **close
   the parity question**: record numbers in
   docs/HARDWARE-ACCELERATION-2026-07.md, keep shipping dynamic int8, done.
   If it IS faster → quality path continues: Max's ear verdict, then bundle
   as engines-repo v24 (catalog sha/size, model-format marker so
   PocketEngine skips XNNPACK for QDQ — engine change needed, see
   KokoroDirectEngine 2669852 pattern), device end-to-end.

## Prior context (previous session)

## State

`main` is pushed to github (65 commits: Kokoro QDQ quant work + a11y + i18n +
icon). Kokoro now ships quantized (v23 bundle, RTF 0.62–0.77 on Pixel 8a) —
**Pocket is now the slowest engine** (shipping ~1.19 whole-pipeline RTF) and
the next optimization target.

Separate track (2026-08-02): F-Droid submission is IN. Tag `v1.0.0-beta.1`
pushed to github at `f5a2397` (vc 33 — the Play AAB must build from this same
tag); MR open at https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44636
(fork maxwhipw/fdroiddata, branch marmalade-tts). GitLab PAT: header file at
`~/secure/gitlab-header`, use `curl -H @file` so the token stays out of
transcripts. BLOCKED on Max's one-time gitlab.com identity verification (MR
CI pipelines fail with zero jobs until then); after that, retry the pipeline
via API. Lab: `docs/release/fdroid-lab.html`
(http://<labs-server>/marmalade-tts-release/fdroid-lab.html).

## 2026-08-02 addendum — synthesis-language audit fixes (5 commits, UNPUSHED)

Head `dd7d06b` (5 commits past `bcac168`, all unpushed). Audit found the alias
"Phonemization language" dropdown was offered for engines that can't use it;
fixes landed as lettered atoms, 401 JVM unit tests green, `assembleFdroidDebug`
clean:

- A `fa57005` — dropdown now Kokoro-only (was gated only on !isCloud); Kitten
  no longer forwards language overrides to espeak (English-only model made
  non-EN picks produce garbage IPA).
- B `8b4c38a` — `cmn` removed from the option list (unproducible: zh goes via
  LexiconZh, espeak-cmn breaks Latin fragments).
- C `e051e75` — stale "Hindi has no espeak entry" comment fixed; D11 status
  updated in docs/LANGUAGE-AUDIT-2026-07.md.
- D `3eb31cc` — MarmaladeSynthService silently routed cloud + PocketDev
  voices through Kokoro (two drifted engine lists); collapsed into
  `knownEngineOrDefault()` + Robolectric test.
- G `dd7d06b` — Synthesizer.kt per-engine language comment corrected.

Follow-ups Max decided after the audit (420 JVM unit tests green):

- E `dd2c9a4` — revises A: the dropdown is *shown disabled at English (US)*
  for the on-device English-only engines (Kitten, Kitten Mini, Pocket, Pocket
  dev) instead of hidden; Kokoro unchanged (enabled, full list); cloud still
  has no field. New string `alias_lang_engine_english_only` ×8 locales.
- F — **the system-TTS locale decision is taken: option (i), implemented.**
  `MarmaladeTtsService` now answers language negotiation from the languages of
  the *installed* voices (English always, plus Kokoro's eight), implements
  `onGetVoices()`, returns a locale-appropriate `onGetDefaultVoiceNameFor`,
  reports the loaded language from `onGetLanguage`, and — when a request names
  no voice but does name a non-English language — speaks it with a voice of
  that language and the matching espeak code (`applyRequestLanguage`). English
  requests, aliases and per-app routing are untouched by design. The BCP-47 ↔
  framework mapping now lives once in `service/TtsLocales.kt`, shared with
  CheckVoiceDataActivity, so the two can't drift apart again.

Still-open non-EN quality defects: D3-D8 in docs/LANGUAGE-AUDIT-2026-07.md
(they bite harder now that external apps can actually reach the non-EN
voices). Device smoke of A/D/E/F pending (adb port — ask Max).

## Task: apply the Kokoro QDQ recipe to Pocket ("Pocket parity")

Goal: static QDQ per-channel int8 (the format that hits ORT's fast ARM
kernels) for Pocket's flow graphs, replacing the current upstream-style int8.

Read first:
- docs/HARDWARE-ACCELERATION-2026-07.md (measured results + constraints)
- ~/coding/scratch/kokoro-quant-experiments/REPORT.md (the recipe: metric,
  sweep, calibration, exclusion anatomy) + its scripts (common.py,
  quant_qdq.py, sweep_e3.py)
- Memory kokoro-quant-bench-results (hard-won constraints)
- Memory project_pocket_chunk_start_bitcrush_bug + ORT footguns in
  PocketEngine.kt (read the in-code docs before touching Pocket)

Facts to build on:
- Pocket graphs (bundle v21, scratch/pocket-bundle-v21/pocket-tts-en/):
  flow_lm_main_int8 (72MB, 52 int8 tensors — upstream-selective, format
  UNKNOWN: first step is checking whether it's dynamic (ConvInteger/
  MatMulInteger = slow path) or already QDQ. If dynamic, re-quantizing
  static-QDQ is exactly the Kokoro win.); flow_lm_flow fp32 (4×/frame);
  mimi_decoder fp32 (KEEP fp32 — T-Mimi + our Kokoro sweep both say
  output-adjacent convs are the quality killers, and it's only ~19ms/frame);
  text_conditioner/encoder fp32 (cold-path, size-only).
- fp32 flow_lm sources for re-quantization: NOT in the bundle — locate
  KevinAHM's fp32 export (HF) or re-export from upstream kyutai-labs/
  pocket-tts (MIT; venv notes in memory executorch-target).
- Upstream's own selective recipe (pocket_tts/quantization.py): ONLY FlowLM
  attention QKV/out + FFN linears int8; AdaLN MLP + mimi + norms fp32.
  Static-QDQ the same node set first; sweep only if quality fails.

Guardrails (non-negotiable):
- **XNNPACK EP + QDQ graph = native SIGSEGV on ORT-Android 1.26.** Check
  PocketEngine's session options; QDQ graphs must run CPU EP (see
  KokoroDirectEngine's model-format.txt marker pattern, commit 2669852).
- **Pocket sampling is stochastic** — output-comparison gates need a fixed
  seed/greedy mode or statistical mel comparison + Max's ear (the Kokoro
  mel-DTW yardstick trick needs adaptation; fp32-rerun noise floor will be
  much higher than Kokoro's 0.35dB).
- Never quantize blindly past the quality gate: desktop mel check → Max
  listens (lab, serve-locally rule) → device bench → only then bundle.
- Device bench: extend the debug quant-bench pattern or bench Pocket
  end-to-end via the Benchmark screen (PocketEngine logs arMs/decodeMs).
  Within-run comparisons only (thermals). Never connectedAndroidTest vs
  Max's daily app. ADB port rotates — ask Max.
- Bundle changes ship as a new engines-repo release tag (v24), catalog
  sha/size updates, github push only with Max's all-clear.

Success criteria: AR-step ms measurably down on Pixel 8a same-run, audio
quality Max-approved, no crash. If static QDQ shows nothing for Pocket
(possible — M=1 AR is bandwidth-bound, and upstream int8 may already be
near-optimal), record the numbers in docs/HARDWARE-ACCELERATION-2026-07.md
and close the parity question with data.

Build: ./gradlew assembleFdroidDebug · tests: :app:testFdroidDebugUnitTest

---

# HANDOFF — full UI i18n SHIPPED (7 locales), 2026-08-01 late (branch `main`)

## 2026-08-02 addendum — persona→alias + EMULATOR-VERIFIED

Head **`bf68b31`** (4 more commits). `ed32713` persona→alias in EN + all
7 locales (Max's call). `61b84da` debug APKs carry x86_64 (release stays
arm-only) — emulator testing now works. Verified on the `marmalade-test`
AVD (API 35 x86_64): per-app language switching via
`adb shell cmd locale set-app-locales app.marmalade.tts.debug --user 0
--locales ja`; walked ja onboarding→Speak→Aliases→Settings + alias
screens in zh/hi/pt-BR/fr/it/es. Screenshots:
`~/coding/scratch/i18n-emulator/`. Two layout breaks found+fixed
(`bf68b31`): it/pt-BR settings nav labels wrapped → Opzioni/Ajustes.
Still pending: physical-device pass (TalkBack ja, real synthesis —
emulator has no engines installed) and Max's translation-tone review.
KNOWN mixed-language surface: engine catalog descriptions/license notes
(onboarding + engine screens) are English-only by design; theme scheme
names (System/Midnight/Forest/Berry) also stay English.

## 2026-08-02 addendum — licenses row icon + Kokoro export-chain credit

`d089319` Settings → About → Open-source licenses row now has a leading
document glyph (`MarmaladeIcons.LicenseDoc`, drawn in-tree) and renders
via `AboutRow` like its neighbours. `0f30367` (+ engines repo `5ff9b2f`)
credit the quantized Kokoro model's provenance — hexgrad/Kokoro-82M →
thewh1teagle/kokoro-onnx (`model-files`) → k2-fsa/sherpa-onnx
`kokoro-multi-lang-v1_0` → our selective int8 quant — in the in-app
`LicenseCatalog` note, `LICENSES/kokoro-direct.md`, and the engines
README (chain read from model.onnx's embedded metadata). No new string
resources (reused `settings_licenses*`; catalog data is English-only by
design). Compile-verified (`compileFdroidDebugKotlin`); device look
pending with the other checks below. Still UNPUSHED with the rest.

## Branch state

`main`, head **`624af08`** (8 i18n commits `cb9dc1f..624af08` on top of the
QDQ session below). `assembleFdroidDebug` + full unit suite green; aapt2
confirms es/fr/hi/it/ja/pt-rBR/zh-rCN in the APK. **UNPUSHED** (with the
19+ commits already awaiting Max's github all-clear).

## What shipped

- `cb9dc1f` — ~470 hardcoded UI strings (Compose screens, onboarding,
  services/notifications, ALL a11y semantics) extracted into namespaced
  `res/values/strings_*.xml` (speak/voices/alias/effects/settings/engines/
  onboarding/service). ViewModels expose `@StringRes` ids instead of
  formatted text so plain-JVM VM tests stay Context-free.
- `7c14696` — Android 13+ per-app language: `res/xml/locales_config.xml`
  + `android:localeConfig` + `androidResources.localeFilters`.
- `39d2422` — leftovers: voice preview phrase + effect preview sentence are
  resources passed from screens (VM default args keep tests compiling),
  LatencyBucket labels `@StringRes`, onboarding install-failure fallback
  localized at the screen (`reason.ifBlank { … }`).
- `ae09432` — BUGFIX: routing sheet showed alias UUIDs, not names (title +
  "Routed to X" hint); wired the previously-unused `RoutingSheetState.
  aliasName` / `AppRoutingViewModel.aliasNames`.
- `43a6224` — BUGFIX: effects empty-chain hint said the opposite of the
  behavior (empty = dry); 3 translation agents independently caught it.
- `624af08` — ja/es/fr/hi/it/pt-BR/zh-CN translations (471 strings per
  locale). Placeholder parity + XML validity machine-checked per locale.
  Spoken samples/pangrams are natural per-language sentences, not calques.

## Open (needs Max / device)

1. **Device verify**: per-app language switch (Settings → Apps → Marmalade
   TTS → Language), a locale spot-check for layout (zh/hi chips, pt-BR
   "+ todos sem direcionamento" strip chip flagged as long), TalkBack in
   ja. Plus the QDQ + a11y device checks below still pending.
2. **Terminology call for Max**: Speak sheet says "persona" where the
   feature is "alias" — translators unified (zh 别名, it "profilo");
   decide whether English should too.
3. `EngineInstaller` failure reasons stay raw exception text (English);
   full localization = `InstallState.Failed(@StringRes, args)` refactor
   (5 test files subclass the installer). Deliberately not done.
4. Translation tone review is Max's whenever he likes — each locale's
   judgment calls are in the 2026-08-01 journal/session notes.

# HANDOFF (prior same-day) — Kokoro QDQ int8 SHIPPED to v23, 2026-08-01 late (branch `main`)

## State

Head = `docs: on-device result...` on top of `2669852` (engine fix), plus the
earlier quant-bench/catalog commits and a concurrent a11y session's commits
(`0c61516` etc.). Unit tests green (`:app:testFdroidDebugUnitTest`).
**UNPUSHED — awaiting Max's github all-clear.** The engines-repo release
IS live: v23 asset re-uploaded WITH `model-format.txt` marker
(sha `8e8752c9…`, 194 MB).

## The result

Our own selectively-quantized Kokoro (static QDQ per-channel int8, 118-node
mel-loss-sweep exclusion list) is **24–36% FASTER than fp32 on the Pixel 8a**
(same-run mean RTF 0.62–0.77 vs 0.97–1.01), 150 MB model (was 326), Max
ear-verified lossless. Recipe + artifacts:
`~/coding/scratch/kokoro-quant-experiments/REPORT.md`.

Constraints (documented in code + docs/HARDWARE-ACCELERATION-2026-07.md):
- XNNPACK EP + QDQ graph = native SIGSEGV, ORT-Android 1.26, every opt level
  → KokoroDirectEngine skips XNNPACK when `engines/kokoro-direct-v1_0/
  model-format.txt` starts with "qdq"; fp32 bundles keep XNNPACK (~18% win).
- Desktop pre-fused QDQ hurts on ARM — ship the raw QDQ graph.

## Remaining (updated end of session, 2026-08-01)

1. ~~In-app end-to-end~~ **DONE — Max installed v23 in-app and verified:
   "sounded good".**
2. **Github push STILL PENDING Max's explicit all-clear** →
   `git push github main` (includes this session's commits + a concurrent
   a11y session's + an i18n session's — review `git log` before pushing).
3. **DECIDED (Max): Apache-2.0** for standalone component releases;
   **donations NOW** (GitHub Sponsors + FUNDING.yml — Max does the
   Stripe/bank part, agent preps files/copy); monetization direction =
   cloud (app already has the multi-provider api-engine work; future
   option once adoption grows = resell cloud inference for premium
   voices, discoverable in-app). NC/copyleft dual-licensing REJECTED.
4. Next concrete build tasks, in rough priority:
   a. GitHub Sponsors setup + FUNDING.yml across repos (blocked on Max's
      Stripe onboarding; prep everything else).
   b. Standalone quant-recipe repo (Apache-2.0 + NOTICE, proper upstream
      attribution per the copyright-licensing conventions; source =
      ~/coding/scratch/kokoro-quant-experiments/, REPORT.md is the story).
      Max reviews before anything goes public.
   c. Same quant recipe for Kitten nano (54 MB fp32 + 5 LSTMs — same
      sweep tooling applies).
   d. api-engine branch revisit (device-verify pending since 2026-07-24).

## Bench tool (debug screen)

Results persist to `files/kokoro-quant/results.json` and reload on screen
open; known-crash configs are excluded from the variant list; cross-instance
run guard. Side-load dir: `files/kokoro-quant/` via adb + run-as.

---

# HANDOFF — Kokoro quant bench + HW-accel assessment, 2026-08-01 (branch `main`)

## State

Head **`9cf20d8`** (2 new commits on `0ccb79e`): `fec6e16` debug-only
Kokoro quant bench in the Benchmark screen; `9cf20d8` measured-results
addendum to `docs/HARDWARE-ACCELERATION-2026-07.md`. Working tree clean
except pre-existing untracked `app/schemas/.../10.json`. NOT pushed
(github is authoritative; needs Max's all-clear).

## What this session settled

1. **Hardware acceleration** — read `docs/HARDWARE-ACCELERATION-2026-07.md`
   first. Verdict: stay single-runtime ORT-CPU; no GPU/NPU path beats it
   for our models; LiteRT Tensor-NPU SDK is G5-only (8a unreachable);
   ExecuTorch parked, dev-branch-only revisits (Max).
2. **Kokoro quantization, measured on the Pixel 8a** (table in the doc):
   fp32 our-export fastest, mean RTF 0.705; uint8f16 0.831 @ 109 MB
   (2.9× smaller); uniform int8 1.098; q8f16 pathological (~10×);
   onnx-community fp32 export ~1.28× slower than ours. Max approved ALL
   variants on quality (lab: http://<labs-server>/kokoro-quant/
   kokoro-quant-lab.html, includes device-pulled wavs).
   "Smaller = faster from bandwidth" refuted for ORT-CPU int8.
3. Bench tool now in-app (debug): Settings → Advanced → Benchmark →
   "Kokoro quant bench"; side-load models to `files/kokoro-quant/` via
   adb + run-as (commands in KokoroQuantBench.kt header). Gotchas:
   leaving the screen cancels the run; `adb logcat -G 16M` before long
   benches (main buffer evicts fast); thermal variance is huge
   (fp32 measured 1.24 hot vs 0.71 cool) — within-run comparisons only.
4. Phone left clean: all side-loaded models removed. Note Kokoro Direct
   is NOT installed on Max's Pixel (only kitten-direct-v0_8).

## Decisions

- **uint8f16: NOTED for a potential future optional download (Max,
  2026-08-01). Not adopted now — no bundle work.** fp32 ships as-is.
- Still-open speed idea: our OWN static QDQ int8 quantization (per-channel,
  arm64, selective exclusions) might hit ORT's fast ARM QGEMM kernels that
  the pre-made ConvInteger exports miss. Unproven; bench tool exists.

## Build/test

- `./gradlew assembleFdroidDebug` (bench device-verified 2026-08-01);
  unit tests `./gradlew :app:testFdroidDebugUnitTest` (untouched by this
  session's debug-only code).
- NEVER `connectedAndroidTest` against Max's daily debug app (wipes data).
- Device: Pixel 8a wireless ADB, port rotates — ask Max, or scan
  30000–49999 on <phone-ip> (worked 2026-08-01).

---

# HANDOFF — R16 mirror: per-sentence rows + trims + gaps, 2026-07-31 (branch `main`)

## State

Head **`0ccb79e`** (3 new commits on top of `bf90bcc`). **399 unit
tests green** (`./gradlew :app:testFdroidDebugUnitTest`). Installed on
Max's Pixel and **DEVICE-VERIFIED (Max, 2026-07-31): "sounds really
good and is really fast."** StreamPerf logcat from his session
confirms the mechanics: 5 per-sentence chunks (textLen 24/32/39/56/34),
warm TTFA 477ms, rtf 0.19–0.29, zero underruns, and every non-final
consumer chunk exactly +150ms over its producer audio (the run gap),
final chunk bare.

## What landed (CLI R16/seam-chain mirror, Max's ask 2026-07-31)

KittenDirect now mirrors the CLI's decided streaming sound
(marmalade-tts-cli `704d6da` + seam chain), adapted to this engine's
whole-sentence architecture:

- `653fe49` TextChunker `terminalMarksOnly` — `.!?` + newlines split;
  `:` `;` stay in-sentence; quote-attached marks keep dialogue with
  its attribution (CLI run-splitter behaviour).
- `2596f66` `KittenTrim` — duration-exact lead/tail trim, faithful
  port of the CLI daemon's `_trim_run` (lead keeps 2 frames ~50ms,
  trailing non-speech token group keeps 3 frames ~75ms; null on a
  broken contract → caller falls back to the legacy blind 5000-sample
  trim). Contract verified against the shipped nano bundle's ONNX on
  desktop: outputs = waveform f32 + duration int64, len(wav) ==
  sum(dur)×600.
- `0ccb79e` engine wiring: one SENTENCE per chunk (tiny-sentence
  merging removed — the brisk short-utterance register is the point,
  per Max's R16-1 verdict), per-sentence style rows via the existing
  text-length lookup, KittenTrim in runInference, 150ms inter-sentence
  gap on each non-final chunk (CLI RUN_GAP_MS).

NOT ported (needs the CLI's phoneme-space planner): sub-sentence
chunking + context/lookahead conditioning, colon 150ms pad top-up,
RTF-banded sizing, marginal-RTF perfstats (remember: exclude
engine-load from any solo sample — cold-start pollution). Sentences
render whole here, so no conditioning is needed at these seams.

Emulator note: the app CANNOT run on the x86_64 emulator — ORT's
x86_64 build SIGSEGVs in createSession loading kitten.onnx (tried
path and byte-array loads; crash is pre-existing, unrelated to these
changes). APK stays ARM-only; runtime verification = unit tests +
desktop ONNX contract check + Max's ear on the Pixel.

Max's verdict closes the R16 half of the mirror backlog. The phoneme-
plan port (sub-sentence conditioning, colon pad, RTF bands) remains
the big remaining piece.

# HANDOFF (superseded) — UI rework, alias bugs, language audit, 2026-07-27 (branch `main`)

## State

Branch **`main`**, head **`c9e6684`**, ahead of `github/main` —
last pushed state was `9cf5ee6`. **390 unit tests green.**
Debug APK builds. **Nothing in this batch has been seen on device.**
Untracked (not ours, left alone): `app/schemas/.../MarmaladeDb/10.json`.

Build: `./gradlew :app:compileFdroidDebugKotlin` (fast check),
`:app:testFdroidDebugUnitTest` (suite), `:app:assembleFdroidDebug` (APK),
`adb install -r`.

Two labs, served locally (ports may need restarting):

- `docs/design/speak-screen-lab.html` — the Speak-screen study
- `docs/release/play-listing-lab.html` — Play listing mock + asset audit
  (its assets live in `docs/release/play-assets/`)
- `docs/release/play-design-lab.html` — **NEW (956f58b, 2026-07-27,
  separate session)**: variations for icon (A1–A6), feature graphic
  (B1–B3), screenshot framing (C1–C3 + caption copy for all 5 shots),
  and listing copy (3 names / 5 shorts / 3 fulls, live counters). Every
  SVG has a download button; export pipeline in section F (needs
  Manrope + Fredoka installed for inkscape). Numbers sourced from
  `EngineCatalog.kt` / `BuiltinEffects.kt` — re-count before submission
  if engines change. Venice raster concepts (section E) are pending:
  the Venice API key returned 401; four prompts are staged in the page.
  Next step: Max picks variants, then export per section F and write
  winning copy back to `fastlane/metadata/android/en-US/`.

## What landed this session

### Speak screen rewritten — `63fc106`

Direction **B** from the design lab, which Max picked. The voice chip and
the wrapping alias-chip row are **gone**, replaced by one fixed-height
56dp `CurrentVoiceRow` (avatar, persona name, `model · voice`, cloud
glyph, chevron) that opens a `PersonaSheet`. The sheet lists every
persona with a Primary badge and a tick on the active one, plus "Pick a
voice directly" and "New persona".

Also: the top bar's trailing list `IconButton` is gone (a second
unlabelled route to the picker that read as a menu), and **idle renders
no status text** — `PlaybackState.Idle -> ""`. The `Box` keeps its height
so nothing shifts when a real message arrives.

New view state in `SpeakViewModel`: `Persona` data class, `personas`, and
`currentPersona`. The constructor gained `voicePaths: VoicePathResolver`
— **test factories must pass it**.

Nav icons: Speak and Effects now use `ui/MarmaladeIcons.kt`, drawn
in-tree from the lab's own SVG path data (a play triangle read as "play a
file", a star read as "favourites"). Not `material-icons-extended` —
that would pull the whole Material catalogue in for three glyphs.
**Engines and Settings deliberately unchanged**, per Max.

`CloudMark` is one composable shared by the Speak row and the Aliases
card's Cloud chip, so those two cannot drift apart.

### Alias promotion bug — `a19df2d`

Promoting an alias to primary now drops the per-app mappings naming it
(`AppAliasMappingDao.releaseAppsRoutedTo`). This was a regression from
earlier the same day: making the primary's routing strip inert (Max's
request) meant any surviving per-app row became unreachable and its apps
were pinned to that alias forever.

**Renaming the current primary deliberately does NOT release** — that is
a retarget, not a promotion, and dropping routing the user never touched
would be a second data-loss bug. A test pins each direction.

### Phonemizer — `d0c92bc`, `40c30e4`

Three IPA characters were silently dropped, all confirmed against the
shipped bundle's own `tokens.txt`:

- `'ᴻ' to 177, // ᵻ` — the key was U+1D3B at the id belonging to U+1D7B.
  The comment named the right character; only the key was wrong. espeak
  emits `ᵻ` in ~5% of American English IPA.
- `ɯ` and `ʔ` absent outright — 5.24% of Japanese; measured mean CER
  **0.059 → 0.013** once they resolve.

`encodePhonemes` maps unknowns to `PAD` rather than failing, so these
produced no crash and no log. Kokoro and Kitten share the table, so each
hit both engines. `IpaTokenVocabTest` now guards it.

Also corrected two comments claiming the JA path emits "IPA + pitch
markers". It emits IPA; the accent data is carried across JNI and
dropped, which is **correct** — misaki's `ja.JAG2P` defaults to the
segmental cutlet path and Kokoro v1.0 has no token ids for `_`/`-`/`^`.

### "Yeah" mispronunciation fixed — `d78b188` + `178b789` (P0 launch-lab item)

Root cause was espeak, not the token map: espeak-ng (1.51, the pinned
1.52 submodule, AND current upstream master — worth filing upstream) has
**no dictionary entry for "yeah"**, so letter-to-sound emits `jˈɛh` — a
literal aspirated [h], audibly "yeh-h". No text respelling produces the
right phonemes, so the fix is phoneme-level:
`phonemizer/EnPhonemeFixups.kt` rewrites word-initial `j[ˈˌ]?ɛh` on
espeak output for `en*` voices (no right-hand boundary — "yeah's" is
`jˈɛhz`).

**Diagnosis confirmed by Max (2026-07-27): Kokoro renders every "yeah"
candidate nearly identically — the model is robust; Kitten is the weak
renderer.** Per-model targets, ear-picked by Max in the A/B lab
(http://<labs-server>/yeah-fix/, symlink → `~/coding/scratch/yeah/`):
Kitten renders misaki's `jˈɛə` poorly at every size (mini glitched
outright; Max: mini is not better than nano), so **Kitten → flat `jæ`**
("ya", won bar none); **Kokoro → `jˈɛə`** (misaki gold — its own
training transcription). Each engine passes its
`EnPhonemeFixups.Model` when constructing its EspeakPhonemizer.
`EnPhonemeFixupsTest` (8 tests) pins both targets.

The CLI shared the bug (kittentts phonemizes with espeak internally);
`marmalade-tts-cli` `0b00468` patches the daemon's phonemizer backend
with the same `jæ`. Deploy on marmalade needs `./install.sh` + kitten
daemon restart. CLI piper/matcha/emojivoice still carry the espeak bug;
CLI kokoro (misaki) is fine.

### Report a bug in Settings — `2d3dcae`

Settings → About gains a "Report a bug" row (`util/BugReportUrl.kt`)
that opens a GitHub new-issue page with a friendly prefilled markdown
body: what happened / what did you expect / exact text + voice if a
word sounds wrong, plus an auto-filled environment line (version,
flavor, Android, device). Deliberately NOT a GitHub issue form — low
reporting bar, per Max. Pronunciation reports are the feed for the
per-model patch table in `EnPhonemeFixups.kt`; the diagnosis recipe
(espeak IPA vs reference, A/B per model) is in that file's header and
in memory note `espeak-yeah-lts-bug`.

### Device-feedback batch, round 1 — `ee73c19` + `c9e6684` + `fde80f8` + `f93461b`

From Max's first on-device pass of the whole batch:

- **Startup crash fixed** (`fde80f8`): Room forbade
  fallbackToDestructiveMigrationFrom(9) alongside MIGRATION_8_9; scoped
  call removed, plain destructive fallback covers the intended v10 reset.
- **Speak row showed a stale non-alias voice** (`ee73c19`): the v10
  UUID re-key never reached SpeakViewModel — applyAlias resolved
  findByName(uuid) and no-oped. Everything keys on alias UUID now.
  Person-avatar → speaker glyph, no colored circle.
- **Settings restructure** (`c9e6684`): Advanced leaf screen (threads /
  developer engines / benchmark), About-row icons (info, in-tree bug
  vector, orange emoji, primary-tinted heart on Support development),
  license-row copy fix, solid bottom-nav tabs.
- Onboarding notification copy reworded without em-dashes (`f93461b`).

## Open — needs Max

- [ ] **Device verification of everything above.** Nothing in this batch
      has run on hardware. Highest value: the new Speak row + sheet, and
      an English/Japanese listen for the `ᵻ`/`ɯ`/`ʔ` fix, which should be
      audible. Plus the "yeah" fix: pre-listen at
      http://<labs-server>/yeah-fix/, then say "Yeah, sure." on device
      with Kitten and Kokoro voices.
- [ ] **Four language defects deliberately NOT fixed** — see
      `docs/LANGUAGE-AUDIT-2026-07.md`. All are prosody changes with
      strong evidence the *input* is wrong and **zero** evidence about how
      much worse it *sounds*; STT cannot adjudicate the last one.
      Suggested order: **D6** digraph→single-token rewrite (largest
      measured effect, mechanical), **D3** JA spacing ignoring the
      `chainFlag` it already parses, then **D4/D5** Mandarin (no space
      tokens at all; 。，？、 and Hindi । dropped) — Mandarin last, since
      Max can't judge it by ear and it wants a native check.
- [ ] **Store listing copy is factually wrong.** Short description says
      "No cloud"; the full description says text "never leaves your
      phone". False since 2026-07-24, on the most public surface there is,
      and squarely within Play's Metadata policy. A corrected full
      description is loaded in the listing lab as the editable default —
      **not** written back to `fastlane/metadata/android/en-US/`, because
      the copy is Max's voice and the 80-char short description needs his
      call.
- [ ] **Keystore still has no durable backup.** `~/secure/marmalade-upload.jks`
      plus a transit copy at `/sdcard/Download/marmalade-upload.jks` on
      the Pixel that should be deleted. It is the *upload* key, which
      Google will reset on request — an outage of days, not a lost app.
      Wanted: KeePassXC + a non-git path under `~/.nexus`. **Not
      agent-wiki**, which is a git repo pushed to Forgejo.
- [ ] **`keystore.properties`** is Max's to write (typing passwords into
      a transcript is the thing to avoid). Then
      `./gradlew :app:bundlePlayRelease`.
- [ ] **marmalade-tts-cli unpushed** (head `8ccfff7`) — Megaphone 0.8 /
      Intercom 0.9 and the `next_room` removal, mirroring what is already
      public on Android. Needs its own all-clear.

## The next substantial task: alias UUIDs

Max's diagnosis, and it is correct. `VoiceAlias` is keyed by its display
name, so a rename is a `delete` + `insert` and every
`app_alias_mapping.aliasName` still points at the old name — hence "no
apps shown" *and* "routed by an alias of an old name" simultaneously.
A third symptom nobody has hit yet: `VoiceAlias.fallbackAliasName` is
also name-keyed and also unpatched, so renaming an alias that is another
alias's cloud fallback silently kills the fallback.

**Effects are already correct** — `Effect` has `@PrimaryKey val id` plus a
separate mutable `name`. That is the pattern to copy; it also means only
aliases need the change, not effects.

Scope: Room **v9 → v10** with a table rebuild (PK change), plus
`app_alias_mapping.aliasName` → `aliasId`, `fallbackAliasName` →
`fallbackAliasId`, and the DataStore key `primary_alias_name` →
`primary_alias_id`. Roughly 66 reference sites across three identifier
names. **It migrates live data on Max's daily driver — test the migration,
don't bolt it onto another change.**

## Guardrails

- **Never retune Trailer** — signed off 2026-07-26.
- Max's daily driver is the **release** app (`app.marmalade.tts`); never
  uninstall it and never run `connectedAndroidTest` (wipes engines +
  config). The debug app is disposable.
- Restore any `settings put secure tts_default_synth/tts_default_rate`
  after testing (original: `app.marmalade.tts` / rate **307**).
- Any built-in effect chain change needs a `CATALOG_VERSION` bump.
- Effect presets are mirrored in the CLI and must change together.
- **github is public.** Max gives an explicit all-clear per push, every
  time. Never push `origin`/Forgejo by hand.
- Big scratch under `~/coding/scratch/`, never `/tmp`.
