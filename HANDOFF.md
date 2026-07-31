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
(http://marmalade:8095/yeah-fix/, symlink → `~/coding/scratch/yeah/`):
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
      http://marmalade:8095/yeah-fix/, then say "Yeah, sure." on device
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
