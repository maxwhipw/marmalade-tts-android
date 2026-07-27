# HANDOFF — UI rework, alias bugs, language audit, 2026-07-27 (branch `main`)

## State

Branch **`main`**, head **`40c30e4`**, **6 commits ahead of `github/main`** —
last pushed state was `9cf5ee6`. Clean tree. **377 unit tests green.**
Debug APK builds. **Nothing in this batch has been seen on device.**

Build: `./gradlew :app:compileFdroidDebugKotlin` (fast check),
`:app:testFdroidDebugUnitTest` (suite), `:app:assembleFdroidDebug` (APK),
`adb install -r`.

Two labs, served locally (ports may need restarting):

- `docs/design/speak-screen-lab.html` — the Speak-screen study
- `docs/release/play-listing-lab.html` — Play listing mock + asset audit
  (its assets live in `docs/release/play-assets/`)

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

## Open — needs Max

- [ ] **Device verification of everything above.** Nothing in this batch
      has run on hardware. Highest value: the new Speak row + sheet, and
      an English/Japanese listen for the `ᵻ`/`ɯ`/`ʔ` fix, which should be
      audible.
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
