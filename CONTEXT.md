# Marmalade TTS — vocabulary

The words this project uses for its own concepts, and what each one means.
Use these terms in code, docs, issues and UI text, so a user, a contributor
and an agent all mean the same thing. [REPO-MAP.md](REPO-MAP.md) says
*where* things live in the code; this file says *what they are*.

When a new concept appears, or a word starts meaning two things, add or fix
its entry here in the same change.

## Speech

- **Engine.** A text-to-speech model family the app runs on the phone:
  Kitten, Kokoro, Kokoro German, Pocket and VITS, plus Android's own system
  voices. Code: `EngineDescriptor` in `install/EngineCatalog.kt`. The UI
  names each by its short name (`displayName`: "Pocket TTS", "Kokoro");
  the version or date (`releaseName`) shows only under About on the
  engine's detail screen (#85). Nothing keys on either: code uses the id.
- **Voice.** One speaker an engine can speak as, e.g. Kokoro "Heart" or
  Pocket "Alba".
- **Alias.** A user-named bundle of a voice, a speed and an effect, e.g.
  "Steady narrator". Apps and the reader speak through aliases, not raw
  voices. In code it is `VoiceAlias`; the UI components call it a
  *persona* (`PersonaSheet`, `PersonaRow`).
- **Primary alias.** The alias used when nothing more specific applies. It is
  marked "Primary" in the UI. Setup creates the first one from the voice the
  user picks, named after that voice ("Alba"), #87.
- **Per-app mapping.** An alias assigned to one calling app, so that app
  always speaks in it. Code: `AppAliasMapping`. Resolution order: per-app
  mapping, then the primary alias.
- **Effect.** Audio processing attached to an alias, such as pitch, reverb,
  echo or a telephone filter: built-ins, plus the user's own copies of them.
- **Pause between sentences.** Silence an alias adds after each sentence, on
  top of the voice's own pause: Natural (none), Short, Medium or Long
  (+0.25, +0.5, +1 s); a paragraph break gets 1.5× as much. Only between
  sentences, never before the first or after the last; scales with speed;
  not for cloud voices (#96, GitHub #10). Code: `data/SentencePause.kt`.
  Not the *speed warning*'s pauses, which are the phone falling behind.
- **Expressive voice** *(planned, Forgejo #73)*. One speaker recorded in
  several emotions at several intensities, cloned by Pocket. The app switches
  between the recordings sentence by sentence to match each sentence's
  emotion. To the user it is simply a voice. Not to be confused with a voice
  pack.

## Downloads

- **Engine bundle.** An engine's models and pronunciation data, downloaded
  after the user opts in into `filesDir/engines/<engine>/`. It never contains
  executable code.
- **Engines release.** A numbered GitHub release (v27, v28, v29 …) that hosts
  the bundles. A new one means new download URLs and checksums in the
  catalog.
- **Voice pack.** A downloadable VITS model for one language or speaker,
  e.g. the English "Jenny (Dioco)" pack. Code: `VoicePack` in
  `install/VoicePackCatalog.kt`. Unreleased packs (`released = false`) appear
  only in developer mode, and never in the Play build.
- **Quality grade.** How good an engine or a voice pack sounds, on one ranked
  scale: Excellent, Good, Basic, Rough. Shown as a four-segment meter like
  speed; TalkBack reads the word. Grades are Max's listening judgement and
  agents never change them. Code: `QualityGrade` in `install/QualityGrade.kt`.

## Builds and distribution

- **Flavor.** `play` or `fdroid`: one app with one feature set, differing only
  in catalog visibility (see CLAUDE.md, "Distribution flavors").
- **Developer mode.** A setting that reveals diagnostic engines and unreleased
  voice packs. It is hidden in the Play build, where it would reveal nothing.
- **Test app.** A release-style build installed beside the store app for
  trying a release on a real phone: `app.marmalade.tts.rehearsal`, labelled
  "(test)", made with a local, uncommitted edit to the smoke-build settings.
  Debug builds install as `.debug` and R8 smoke builds as `.rc`. None of these
  is ever distributed.

## Playback

- **Reader.** The screen that reads a shared web article aloud, paragraph by
  paragraph (a *block*).
- **Alias dial.** The reader's control for who reads an article and how fast:
  a speaking-person icon, with the speed on a badge whenever it isn't 1×
  (#81; #67's dial showed the alias name over the speed). It opens one sheet
  with the alias list and the speed slider.
- **Speed override.** A reader speed the user set that differs from the
  reading alias's own speed. It survives alias switches until the user moves
  back to the alias's own speed. It applies to the current article only.
- **One-shot.** A single utterance, e.g. a "Speak" or share-sheet request, as
  opposed to an **article session** in the reader. Both run through the
  `Narrator`, and all audio plays through `MarmaladeSynthService`.
- **Preview.** A voice speaking a short sample ("Hello, I'm Heart.") from a
  picker row, so the user hears it before choosing: a speaker button that
  plays and stops, one voice at a time, in the alias editor's picker, the
  Voices screen and setup. On-device voices only: a cloud sample would cost
  money unasked (#86, #88).
- **RTF (real-time factor).** Seconds of compute per second of audio. Below 1
  keeps up; the app warns above 0.6 at the chosen speed.
- **Chunk mode.** What an engine that can't keep up switches to: it renders
  whole pieces before playing them, so the listener hears short pauses
  between sentences instead of stutter inside them.
- **Speed warning.** The note under a speed control that says this phone may
  pause between sentences at this speed.
- **Top-app hold.** A silent session to our own engine that keeps the reader
  on the fast CPU cores on Android 12+. Code: `service/TopAppHold.kt`.

## Pronunciation

- **Phonemes.** The sound symbols an engine speaks from. Kitten, Kokoro and
  VITS take phonemes; Pocket reads text directly and learned pronunciation
  itself.
- **G2P (grapheme-to-phoneme).** Turning written words into phonemes. Today
  that is espeak-ng (built into the APK) plus our per-language fixups. See
  the agent-wiki long-term plan for the alternatives.
