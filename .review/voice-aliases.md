# Voice aliases review

**Verdict:** PASS with notes

## Strengths

- **Schema is well-shaped for the feature.** `name` as PK gives the
  editor's "upsert = rename collision = REPLACE" idiom for free, and the
  collision-vs-rename distinction is handled at the ViewModel layer
  (`save()` deletes the old row first when the user renamed an alias),
  so the DB never carries a duplicate. Schema JSON
  (`app/schemas/.../3.json`) matches the entity declaration exactly —
  `voice_alias` with `name TEXT NOT NULL PRIMARY KEY`, REAL speed, TEXT
  effectPreset, INTEGER createdAt.
- **Effect round-tripping is the right shape for forward compatibility.**
  Storing `EffectPreset.name` as a string (rather than ordinal or FK)
  means adding a new preset doesn't require a schema migration, and the
  lookup-at-read `decodeEffect` pattern is used identically in
  `AliasViewModel` and `SpeakViewModel` — no drift.
- **Manual-voice-change clearing rule is implemented carefully.** The
  `expectedAliasVoiceId` field is set *before* `setDefaultVoiceId` fires
  so the resulting `defaultVoiceId` emission is recognised as the
  alias's own write and doesn't trigger a self-clear. The
  null-`expected` short-circuit avoids the obvious "clears on launch"
  bug.
- **Data-flow comments are present** at the top of every new file
  (VoiceAlias.kt, VoiceAliasDao.kt, AliasScreen.kt, AliasViewModel.kt)
  and updated in SpeakScreen.kt / SpeakViewModel.kt to cover the new
  alias paths. The level of detail is consistent with the existing
  codebase.
- **DI plumbing is correct and avoids the v2 cyclic-dep trap.** The
  `provideVoiceAliasDao` provider is `@Singleton`-scoped, takes the DB
  directly (no `Provider<>` indirection needed because no seed callback
  consumes it), and follows the same pattern as `provideVoiceMetaDao`.
- **Editor UX details are thoughtful.** Engine change clears voice
  selection (no stale-voice-ID-from-other-engine smuggling); voice
  dropdown falls back to the full catalog if no voices are flagged
  installed yet (matches the CLI's "let the user try; fail at synth
  time" behaviour); error-clearing is scoped per field via the
  `NameField` / `VoiceField` sentinel tags so fixing one of two
  simultaneous problems doesn't flicker the other away.
- **Two new SpeakViewModel tests genuinely exercise alias paths**, not
  just the widened signature. `speak_passesEffectAndSpeedFromActiveAlias`
  verifies the speed/effect threading end-to-end; the manual-voice-change
  test verifies the clearing rule on the only realistic competing path
  (the picker).

## Issues

- **Severity:** minor
  **File:** `app/src/main/java/app/marmalade/tts/ui/screen/SpeakViewModel.kt:236-247`
  **Issue:** `applyAlias` sets `_activeAlias.value = alias.name` and
  `_currentEffect` / `_currentSpeed` even when `voiceDao.findById(alias.voiceId)`
  returns null. In that branch `expectedAliasVoiceId` stays null, so a
  subsequent manual voice change in the picker will **not** clear the
  alias chip — the chip stays "selected" pointing at an alias whose
  voice was never actually applied. Cosmetic in v0.1 (the static catalog
  makes this branch unreachable in practice), but it'll start mattering
  once dynamic engines can remove voices.
  **Suggested fix:** in the `voice == null` branch, either skip setting
  `_activeAlias` (treat as full no-op) or set `expectedAliasVoiceId =
  alias.voiceId` anyway so the clearing rule still fires when the user
  picks a different voice.

- **Severity:** minor
  **File:** `app/src/main/java/app/marmalade/tts/ui/screen/AliasViewModel.kt`
  (whole file)
  **Issue:** No unit tests. The non-trivial logic here — the regex
  validator, the collision check with `originalName` carve-out, the
  rename-deletes-old-row path in `save()`, the scoped error-clearing in
  `clearIfRelatedTo` — is exactly the kind of thing that breaks
  silently. By the project's own testing rubric this is testable
  business logic, not glue.
  **Suggested fix:** add an `AliasViewModelTest` that at minimum covers:
  (a) `isValidName` boundary cases (blank, leading digit, uppercase,
  space, dash, underscore, single char); (b) `save()` rejects collision
  on create but allows the no-op self-collision on edit; (c) renaming
  an alias deletes the old row and inserts the new one; (d) error
  clears only when the offending field is edited.

- **Severity:** minor
  **File:** `app/src/main/java/app/marmalade/tts/ui/screen/AliasScreen.kt:222`
  **Issue:** The per-alias speed chip in the list shows `"${alias.speed}x"`
  raw — for a saved alias at 0.7 this renders as `0.7x` but for an alias
  at exactly 1.0 the toString gives `1.0x`, and after a slider drag it
  can be `0.7000001x`. The editor itself uses `"%.2f".format(...)` for
  the speed label (line 297); the row should do the same for
  consistency.
  **Suggested fix:** `Text("${"%.2f".format(alias.speed)}x")` on line 222.

- **Severity:** minor
  **File:** `app/src/main/java/app/marmalade/tts/ui/screen/AliasScreen.kt:226, 431`
  **Issue:** Effect preset is rendered to the user as `NONE` / `CAVE` /
  `ROBOT` / `TELEPHONE` (the raw enum names). In the row chip the code
  at least `.lowercase()`s it; in the editor dropdown (line 431, 448)
  it's the bare enum name. The project's overall style is sentence-case
  user copy; this is the only place where SCREAMING_SNAKE leaks into
  the UI.
  **Suggested fix:** add a tiny presentational helper
  (`EffectPreset.userLabel()` → e.g. "None", "Cave", "Robot",
  "Telephone") and use it in both places.

- **Severity:** minor
  **File:** `app/src/main/java/app/marmalade/tts/ui/screen/AliasScreen.kt:153-168`
  **Issue:** The delete-confirm dialog's `Button` for "Delete" uses the
  default primary tone, not an error tone, even though the action is
  destructive. The list row's delete icon correctly uses
  `colorScheme.error` — the dialog should match.
  **Suggested fix:** use `Button(colors =
  ButtonDefaults.buttonColors(containerColor =
  MaterialTheme.colorScheme.error))` or swap to a `TextButton` with the
  error color.

- **Severity:** minor
  **File:** `app/src/main/java/app/marmalade/tts/ui/screen/AliasViewModel.kt:189-195`
  **Issue:** `onEditorNameChange` accepts whatever the user types
  verbatim. The editor's only feedback for an invalid character is the
  red error text *after* tapping Save. For a name like `"My Narrator"`
  the user gets no hint while typing that the space is the problem.
  Not a correctness bug — the regex rejects it — but the UX could be
  warmer.
  **Suggested fix (optional):** show the `InvalidName` error as soon as
  `name.isNotEmpty() && !VoiceAlias.isValidName(name.trim())`, instead
  of waiting for Save. Or filter the input to the allowed character set
  on type. Either is fine.

## Test coverage notes

- The two new tests in `SpeakViewModelTest` are real, not adapter
  tests: they go through `applyAlias → SettingsRepository →
  defaultVoiceId → currentVoice` for one and through the manual-clear
  side effect for the other. The widened `Call` shape (text + voiceId
  + speed + effect) is what made these assertions possible; without it
  the speed/effect-threading test would have been useless. Good call.
- **Gap:** `AliasViewModel` has zero tests. That class owns all the
  user-input validation for the feature — the regex, the
  rename-vs-create collision logic, the per-field error clearing.
  Worth a dedicated test file before this feature is treated as
  hardened.
- **Gap (acceptable):** no Compose UI tests for the chip row or editor
  dialog. The chip row's "always show Create alias" requirement is
  visible to a maintainer reading `AliasChipRow` (the `item(key =
  "__create_alias__")` is structurally always emitted, outside the
  `items(...)` loop), so a UI test for it would mostly re-state the
  code. The editor dialog is fancier but is glue between the ViewModel
  and Material 3 components; if `AliasViewModel` gets unit tests, the
  remaining risk is small.
- **Migration not tested.** v2 → v3 uses
  `fallbackToDestructiveMigration` which wipes `voice_meta` data and
  relies on the `onCreate` callback to reseed. The comment claims it's
  safe because "existing installs have no alias rows yet" — true — but
  the user-facing consequence is that any voice the user has flipped
  `isInstalled = true` on (i.e., downloaded the Kitten engine for) gets
  reset to the catalog's defaults (all `isInstalled = false`) on
  upgrade. For v0.1 with one engine and a simple flag that's fine and
  the reinstall path exists, but it isn't strictly "no user data lost"
  as the comment claims. Worth either a real `Migration(2, 3)` that
  just `CREATE TABLE`s `voice_alias`, or an honest update to the
  comment + a STUBS.md note. Not blocking but worth knowing.

## Recommendation

Ship with notes. The schema is sound, the DI plumbing is right, the
editor and chip-row UX work as designed, and the cross-screen
manual-voice-change clearing rule is implemented correctly for every
path that exists in v0.1 (i.e. through `VoicePickerViewModel.selectVoice`,
which is the only other writer of `defaultVoiceId`). The two stretch
tests genuinely exercise the alias surface, not just the signature
change. The issues above are all minor — a missing test file for
`AliasViewModel`, two cosmetic UX nits, an edge case in `applyAlias`
when the alias's voice has been removed from the catalog, and an
overly-confident migration comment. None of them block shipping, but
the `AliasViewModel` test gap is the one I'd close before treating the
feature as "done" — that file owns the validation logic for the entire
user-facing input surface and is currently unverified.
